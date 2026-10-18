package com.bindlab.phone

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bindlab.shared.bench.BenchRecorder
import com.bindlab.shared.bench.BenchResult
import com.bindlab.shared.feedback.Haptics
import com.bindlab.shared.inference.ExecuTorchModule
import com.bindlab.shared.inference.ModelStore
import com.bindlab.shared.inference.looksLikeOom
import com.bindlab.shared.net.BackendClient
import com.bindlab.shared.net.BindingRequest
import com.bindlab.shared.net.BindingResponse
import com.bindlab.shared.net.ConfigStore
import com.bindlab.shared.net.RunDetail
import com.bindlab.shared.net.RunSummary
import com.bindlab.shared.net.ServerMessage
import com.bindlab.shared.net.WsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class LadderRow(
    val size: String,
    val backend: String,
    val available: Boolean,
    val phase: String, // missing | ready | running | ok | oom | load_failed
    val detail: String = "",
    val result: BenchResult? = null,
)

data class BindingUi(
    val running: Boolean = false,
    val message: String = "",
    val score: BindingHead.Score? = null,
    val targetResidues: List<Int> = emptyList(),
    val binderResidues: List<Int> = emptyList(),
    val latencyMs: Double? = null,
    val size: String = "8M",
    val backend: String = "portable-cpu",
    val request: BindingRequest? = null,
    val runId: String? = null,
    val xrPhase: XrPhase = XrPhase.Idle,
    val labPhase: String = "", // "" | sending | sent | failed:<msg>
    val saved: Boolean = false,
    val localKey: String = "",
)

enum class XrPhase { Idle, Posting, Waiting, Sent, Confirmed, Failed }

/** A scored experiment the phone knows about (local result and/or server run). */
data class Experiment(
    val key: String,
    val runId: String?,
    val state: String?,
    val score: Float?,
    val model: String,
    val backend: String,
    val targetLen: Int,
    val binderLen: Int,
    val latencyMs: Double?,
    val createdMs: Long,
)

/** Stage labels for the One UI cards. */
fun stageOf(state: String?): String = when {
    state == null -> "Designed"
    state.startsWith("STEP_") || state == "LAB_ENTERED" -> "Running"
    state == "SENT_TO_LAB" -> "Ready for lab"
    state in setOf("APPROVAL", "BEAMED", "EVIDENCE", "PATENT_DRAFT") -> "Reviewed"
    else -> "Designed"
}

/** Never claim the NPU unless the backend string says so. */
fun backendLabel(backend: String): String {
    val b = backend.lowercase()
    return if (b.contains("qnn") || b.contains("htp") || b.contains("npu")) "Snapdragon NPU" else "S25 CPU"
}

class PhoneViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    val config = ConfigStore(app)
    val backend = BackendClient({ config.server })
    val ws = WsClient({ config.server }, "phone", viewModelScope)
    private val haptics = Haptics(app)
    private val prefs = app.getSharedPreferences("ladder", Context.MODE_PRIVATE)
    private val expPrefs = app.getSharedPreferences("experiments", Context.MODE_PRIVATE)

    val target = MutableStateFlow(config.getString("target", SequenceParser.DEMO_TARGET))
    val binder = MutableStateFlow(config.getString("binder", SequenceParser.DEMO_BINDER))
    val runId = MutableStateFlow<String?>(null)
    val runState = MutableStateFlow<String?>(null)
    val lastHaptic = MutableStateFlow<String?>(null)

    private val _ladder = MutableStateFlow(emptyList<LadderRow>())
    val ladder: StateFlow<List<LadderRow>> = _ladder
    val ladderRunning = MutableStateFlow(false)
    val sustained = MutableStateFlow(true)
    private var ladderJob: Job? = null

    val binding = MutableStateFlow(BindingUi())

    private val localExperiments = MutableStateFlow(loadLocalExperiments())
    private val serverRuns = MutableStateFlow(emptyList<RunSummary>())
    val experiments: StateFlow<List<Experiment>> = combine(localExperiments, serverRuns) { local, remote ->
        val remoteExps = remote.map { r ->
            Experiment(
                key = "run:${r.id}", runId = r.id, state = r.state, score = r.score, model = r.model, backend = r.backend,
                targetLen = r.targetLen, binderLen = r.binderLen, latencyMs = r.latencyMs, createdMs = (r.created * 1000).toLong(),
            )
        }
        val remoteIds = remoteExps.mapNotNull { it.runId }.toSet()
        (remoteExps + local.filter { it.runId == null || it.runId !in remoteIds }).sortedByDescending { it.createdMs }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Run selected for the Review screen. */
    val reviewRunId = MutableStateFlow<String?>(null)
    val review = MutableStateFlow<ReviewUi>(ReviewUi())

    val modelDir: String get() = ModelStore.primaryDir(ctx).absolutePath

    init {
        refreshLadder()
        reportCrashedRun()
        ws.start()
        viewModelScope.launch {
            ws.messages.collect { m ->
                when (m) {
                    is ServerMessage.Haptic -> { haptics.play(m.pattern); lastHaptic.value = m.pattern }
                    is ServerMessage.State -> {
                        runId.value = m.runId; runState.value = m.state
                        localExperiments.update { list -> list.map { if (it.runId == m.runId) it.copy(state = m.state) else it } }
                        serverRuns.update { list -> list.map { if (it.id == m.runId) it.copy(state = m.state) else it } }
                        if (binding.value.runId == m.runId && binding.value.xrPhase == XrPhase.Waiting) {
                            binding.update { it.copy(xrPhase = XrPhase.Confirmed) }
                        }
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch { ws.connected.collect { if (it) refreshRuns() } }
    }

    override fun onCleared() {
        ws.stop()
    }

    fun tick() = haptics.play("tick")

    fun setSequences(t: String?, b: String?) {
        t?.let { target.value = it; config.putString("target", it) }
        b?.let { binder.value = it; config.putString("binder", it) }
    }

    /** First model that is actually on disk, preferring the verified 8M portable-cpu export. */
    fun defaultModel(): Pair<String, String> {
        val rows = _ladder.value.filter { it.available }
        val preferred = rows.firstOrNull { it.size == "8M" && it.backend == "portable-cpu" } ?: rows.firstOrNull()
        return (preferred?.size ?: "8M") to (preferred?.backend ?: "portable-cpu")
    }

    // ------------------------------------------------------------------ experiments

    fun refreshRuns() {
        viewModelScope.launch {
            runCatching { backend.listRuns() }.onSuccess { serverRuns.value = it }
        }
    }

    private fun loadLocalExperiments(): List<Experiment> = runCatching {
        val arr = JSONArray(expPrefs.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Experiment(
                key = o.getString("key"), runId = o.optString("runId").ifEmpty { null }, state = o.optString("state").ifEmpty { null },
                score = if (o.has("score")) o.getDouble("score").toFloat() else null, model = o.optString("model"),
                backend = o.optString("backend"), targetLen = o.optInt("targetLen"), binderLen = o.optInt("binderLen"),
                latencyMs = if (o.has("latencyMs")) o.getDouble("latencyMs") else null, createdMs = o.optLong("createdMs"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persistLocal(list: List<Experiment>) {
        val arr = JSONArray()
        list.take(50).forEach { e ->
            arr.put(JSONObject().put("key", e.key).put("runId", e.runId ?: "").put("state", e.state ?: "")
                .apply { e.score?.let { put("score", it.toDouble()) }; e.latencyMs?.let { put("latencyMs", it) } }
                .put("model", e.model).put("backend", e.backend).put("targetLen", e.targetLen)
                .put("binderLen", e.binderLen).put("createdMs", e.createdMs))
        }
        expPrefs.edit().putString("list", arr.toString()).apply()
    }

    private fun upsertExperiment(e: Experiment) {
        localExperiments.update { list -> (listOf(e) + list.filter { it.key != e.key }) }
        persistLocal(localExperiments.value)
    }

    private fun currentExperiment(ui: BindingUi): Experiment? {
        val s = ui.score ?: return null
        val req = ui.request
        return Experiment(
            key = ui.localKey.ifEmpty { "local:${System.currentTimeMillis()}" },
            runId = ui.runId, state = if (ui.runId != null) runState.value else null, score = s.value,
            model = EsmModels.modelName(ui.size), backend = ui.backend,
            targetLen = req?.targetSeq?.length ?: 0, binderLen = req?.binderSeq?.length ?: 0,
            latencyMs = ui.latencyMs, createdMs = System.currentTimeMillis(),
        )
    }

    fun saveExperiment() {
        val ui = binding.value
        currentExperiment(ui)?.let { upsertExperiment(it) }
        binding.update { it.copy(saved = true) }
    }

    // ------------------------------------------------------------------ ladder

    fun refreshLadder() {
        if (ladderRunning.value) return
        _ladder.value = EsmModels.SIZES.flatMap { size ->
            EsmModels.BACKENDS.map { be ->
                val found = ModelStore.find(ctx, EsmModels.base(size, be))
                val prev = _ladder.value.firstOrNull { it.size == size && it.backend == be }
                when {
                    found == null -> LadderRow(size, be, false, "missing", "model missing: ${EsmModels.base(size, be)}.pte")
                    prev?.result != null -> prev.copy(available = true)
                    else -> LadderRow(size, be, true, "ready", found.joinToString { it.name })
                }
            }
        }
    }

    private fun updateRow(size: String, be: String, f: (LadderRow) -> LadderRow) =
        _ladder.update { rows -> rows.map { if (it.size == size && it.backend == be) f(it) else it } }

    fun runLadder() {
        if (ladderRunning.value) return
        refreshLadder()
        ladderRunning.value = true
        val seq = target.value
        val recorder = BenchRecorder(ctx)
        ladderJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                for (row in _ladder.value.filter { it.available }) {
                    val files = ModelStore.find(ctx, EsmModels.base(row.size, row.backend)) ?: continue
                    updateRow(row.size, row.backend) { it.copy(phase = "running", detail = "starting") }
                    // If the process dies here (native OOM / LMK), the next launch reports it as "oom".
                    prefs.edit().putString(KEY_INFLIGHT, "${row.size}|${row.backend}").commit()
                    val (engine, path) = ModelStore.engineFor(files) { ExecuTorchModule(ctx) }
                    val result = try {
                        recorder.measure(
                            "s25", EsmModels.modelName(row.size), row.backend, TokenDtypeAdapter(engine), path,
                            tokenInputs(seq), sustained.value,
                        ) { p -> updateRow(row.size, row.backend) { it.copy(detail = p) } }
                    } catch (e: OutOfMemoryError) {
                        engine.close()
                        BenchResult("s25", EsmModels.modelName(row.size), row.backend, status = "oom", error = "Java OOM")
                    }
                    prefs.edit().remove(KEY_INFLIGHT).commit()
                    updateRow(row.size, row.backend) { it.copy(phase = result.status, detail = summary(result), result = result) }
                    post(result, row)
                    System.gc()
                }
            } finally {
                prefs.edit().remove(KEY_INFLIGHT).apply()
                ladderRunning.value = false
            }
        }
    }

    fun cancelLadder() {
        ladderJob?.cancel()
    }

    private suspend fun post(r: BenchResult, row: LadderRow) {
        runCatching { backend.postBench(r) }.onFailure { e ->
            updateRow(row.size, row.backend) { it.copy(detail = it.detail + "\nPOST /bench failed: ${e.message}") }
        }
    }

    private fun reportCrashedRun() {
        val inflight = prefs.getString(KEY_INFLIGHT, null) ?: return
        prefs.edit().remove(KEY_INFLIGHT).apply()
        val (size, be) = inflight.split("|").let { it[0] to it.getOrElse(1) { "qnn" } }
        val r = BenchResult(
            "s25", EsmModels.modelName(size), be, status = "load_failed",
            error = "previous model run was interrupted; cause unknown",
        )
        updateRow(size, be) { it.copy(phase = "load_failed", detail = r.error!!, result = r) }
        viewModelScope.launch { post(r, LadderRow(size, be, true, "load_failed")) }
    }

    private fun summary(r: BenchResult): String = if (r.status == "ok") {
        "load ${fmt(r.loadMs)} ms · p50 ${fmt(r.p50Ms)} · p95 ${fmt(r.p95Ms)} ms · peak ${fmt(r.peakRssMb)} MB\n" +
            "thermal ${fmt(r.thermalHeadroom, 2)} · battery ${fmt(r.batteryDelta, 2)}% · sustained ${fmt(r.sustainedS)} s" +
            (r.error?.let { "\n$it" } ?: "")
    } else "${r.status}: ${r.error}"

    // ------------------------------------------------------------------ binding

    fun runBinding(size: String, be: String) {
        if (binding.value.running) return
        val t = EsmTokenizer.clean(target.value)
        val b = EsmTokenizer.clean(binder.value)
        if (t.isEmpty() || b.isEmpty()) {
            binding.value = BindingUi(message = "scan or enter both sequences first", size = size, backend = be); return
        }
        if (t.length > EsmTokenizer.MAX_RESIDUES || b.length > EsmTokenizer.MAX_RESIDUES) {
            binding.value = BindingUi(message = "This model supports at most 254 residues per sequence. Select an explicit domain or fragment.", size = size, backend = be); return
        }
        binding.value = BindingUi(running = true, message = "loading ${EsmModels.base(size, be)}…", size = size, backend = be)
        viewModelScope.launch(Dispatchers.Default) {
            val ui = try {
                EsmEmbedder(ctx, size, be).open().use { emb ->
                    binding.update { it.copy(message = "loaded in ${emb.loadMs} ms, embedding…") }
                    val et = emb.embed(t)
                    val eb = emb.embed(b)
                    val score = BindingHead.load(ctx, size)?.score(et.embedding, eb.embedding)
                        ?: BindingHead.cosine(et.embedding, eb.embedding)
                    var tr = emptyList<Int>()
                    var br = emptyList<Int>()
                    if (et.contacts != null && InterfaceResidues.fits(t, b)) {
                        val pair = emb.embed(InterfaceResidues.linked(t, b))
                        pair.contacts?.let { c ->
                            val res = InterfaceResidues.fromContacts(c.data, c.shape[2].toInt(), t.length, b.length)
                            tr = res.first; br = res.second
                        }
                    }
                    val latency = et.latencyMs + eb.latencyMs
                    val req = BindingRequest(t, b, EsmModels.modelName(size), be, score.value, tr, br, latency, STRUCTURE_REF)
                    BindingUi(
                        message = "", score = score, targetResidues = tr, binderResidues = br, latencyMs = latency,
                        size = size, backend = be, request = req, localKey = "local:${System.currentTimeMillis()}",
                    )
                }
            } catch (e: OutOfMemoryError) {
                BindingUi(message = "oom: ${EsmModels.base(size, be)} does not fit", size = size, backend = be)
            } catch (e: Exception) {
                BindingUi(message = (if (looksLikeOom(e)) "oom: " else "") + (e.message ?: e.javaClass.simpleName), size = size, backend = be)
            }
            binding.value = ui
        }
    }

    /** Preserve the computed run; every tap sends a fresh explicit headset launch request. */
    fun openInXr() {
        val ui = binding.value
        val req = ui.request ?: return
        if (ui.xrPhase == XrPhase.Posting || ui.xrPhase == XrPhase.Waiting) return
        binding.update { it.copy(xrPhase = XrPhase.Posting, message = "") }
        viewModelScope.launch {
            runCatching {
                val resp = if (ui.runId != null) BindingResponse(ui.runId, runState.value ?: "")
                    else backend.postBinding(req)
                runId.value = resp.runId
                if (resp.state.isNotEmpty()) runState.value = resp.state
                binding.update { it.copy(runId = resp.runId) }
                currentExperiment(binding.value)?.let { upsertExperiment(it) }
                backend.openXr(resp.runId)
            }.onSuccess {
                binding.update { it.copy(xrPhase = XrPhase.Sent, message = "") }
            }.onFailure { error ->
                binding.update { it.copy(xrPhase = XrPhase.Failed, message = "Open in XR failed: ${error.message}") }
            }
        }
    }

    fun sendToLab() {
        val ui = binding.value
        val id = ui.runId ?: run {
            binding.update { it.copy(labPhase = "failed:Open in XR first so the run exists on the hub") }; return
        }
        binding.update { it.copy(labPhase = "sending") }
        viewModelScope.launch {
            val r = runCatching { backend.labHandoff(id) }
            binding.update { it.copy(labPhase = r.fold({ "sent" }, { e -> "failed:${e.message}" })) }
            if (r.isSuccess) { runState.value = "SENT_TO_LAB"; refreshRuns() }
        }
    }

    // ------------------------------------------------------------------ review

    fun openReview(id: String) {
        reviewRunId.value = id
        review.value = ReviewUi(loading = true)
        viewModelScope.launch {
            review.value = runCatching { backend.getRun(id) }
                .fold({ ReviewUi(detail = it) }, { ReviewUi(error = it.message ?: "failed to load run") })
        }
    }

    /** Generates the evidence PDF and returns its absolute URL for a browser intent. */
    fun exportEvidence(onReady: (String) -> Unit) {
        val id = reviewRunId.value ?: return
        review.update { it.copy(exporting = true, error = null) }
        viewModelScope.launch {
            val r = runCatching { backend.postEvidence(id) }
            review.update { it.copy(exporting = false, error = r.exceptionOrNull()?.message) }
            r.getOrNull()?.let { onReady(config.server.trimEnd('/') + it) }
        }
    }

    fun onServerChanged() { ws.restart(); refreshRuns() }

    companion object {
        private const val KEY_INFLIGHT = "inflight"
        private const val XR_WAIT_MS = 6_000L
        /** Demo complex served by the backend; sent as structure_ref with every binding. */
        const val STRUCTURE_REF = "demo_complex"
        fun fmt(v: Double?, digits: Int = 1) = v?.let { "%.${digits}f".format(it) } ?: "–"
    }
}

data class ReviewUi(
    val loading: Boolean = false,
    val detail: RunDetail? = null,
    val error: String? = null,
    val exporting: Boolean = false,
)
