package com.bindlab.xr

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bindlab.shared.feedback.Haptics
import com.bindlab.shared.inference.ModelStore
import com.bindlab.shared.net.BackendClient
import com.bindlab.shared.net.ClientMessages
import com.bindlab.shared.net.ConfigStore
import com.bindlab.shared.net.Detection
import com.bindlab.shared.net.LabStepRequest
import com.bindlab.shared.net.ProtocolStep
import com.bindlab.shared.net.ServerMessage
import com.bindlab.shared.net.Troubleshoot
import com.bindlab.shared.net.WsClient
import com.bindlab.xr.feedback.SpatialAudio
import com.bindlab.xr.protocol.StepGate
import com.bindlab.xr.voice.VoiceCommander
import com.bindlab.xr.yolo.YoloDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

sealed interface Warning {
    data object None : Warning
    data class Amber(val text: String) : Warning
    data class Red(val text: String) : Warning
}

sealed interface VoiceBadge {
    data object Idle : VoiceBadge
    data object Listening : VoiceBadge
    data class Heard(val text: String) : VoiceBadge
    data class Command(val text: String) : VoiceBadge
}

class XrViewModel(app: Application) : AndroidViewModel(app) {
    val config = ConfigStore(app)
    private val backend = BackendClient({ config.server })
    val ws = WsClient({ config.server }, "xr", viewModelScope)
    private val haptics = Haptics(app)
    private val audio = SpatialAudio(app)
    val gate = StepGate()

    val engineMode = MutableStateFlow(config.getString("engine", YoloDetector.MODE_AAR))
    @Volatile private var detector: YoloDetector? = null
    @Volatile private var detectorMode: String? = null

    // ---- perception
    val detections = MutableStateFlow(emptyList<Detection>())
    val frameSize = MutableStateFlow(4 to 3)
    val inferMs = MutableStateFlow<Double?>(null)
    val modelStatus = MutableStateFlow("starting camera…")
    private val _seenLabels = MutableStateFlow(emptySet<String>())
    /** Labels at conf > gate threshold in the latest frame. */
    val seenLabels: StateFlow<Set<String>> = _seenLabels.asStateFlow()

    // ---- protocol
    val steps = MutableStateFlow(emptyList<ProtocolStep>())
    val stepIndex = MutableStateFlow(0)
    private val _currentStep = MutableStateFlow<ProtocolStep?>(null)
    val currentStep: StateFlow<ProtocolStep?> = _currentStep.asStateFlow()
    val progress = MutableStateFlow(emptyMap<String, Int>())
    val runId = MutableStateFlow<String?>(null)
    val runState = MutableStateFlow<String?>(null)
    val labNames = MutableStateFlow(emptyMap<String, String>())
    /** True once every step is done or the server reports APPROVAL / BEAMED. */
    val complete = MutableStateFlow(false)
    val awaitingReview = MutableStateFlow(false)
    val log = MutableStateFlow("")

    // ---- guidance state
    private val _warning = MutableStateFlow<Warning>(Warning.None)
    val warning: StateFlow<Warning> = _warning.asStateFlow()
    val holdActive = MutableStateFlow(false)
    private var holdReason: String? = null
    val timerRemaining = MutableStateFlow<Int?>(null)
    val troubleshootOpen = MutableStateFlow(false)
    val deviationCount = MutableStateFlow(0)
    val notes = MutableStateFlow(emptyList<String>())

    // ---- voice
    val voiceBadge = MutableStateFlow<VoiceBadge>(VoiceBadge.Idle)
    val micEnabled = MutableStateFlow(config.getString("mic", "on") == "on")
    val micGranted = MutableStateFlow(false)
    val voiceStatus = MutableStateFlow("")
    private var voice: VoiceCommander? = null
    private var badgeFade: Job? = null

    // ---- settings
    val showDetections = MutableStateFlow(config.getString("show_dets", "on") == "on")
    /** Developer mode: no holds, no review wait, "Next" always advances locally. For walking through steps. */
    val devMode = MutableStateFlow(config.getString("dev_mode", "on") == "on")

    @Volatile private var lastFrame: Bitmap? = null
    private var lastWsSend = 0L
    private var seenRequired = emptySet<String>()
    private var missingSince: Long? = null
    /** Forbidden labels the operator explicitly accepted via "Continue anyway" for the current step. */
    private var acknowledgedForbidden = emptySet<String>()
    private var stepStartedAt = 0L
    private var timerEndAt: Long? = null
    private var timerChimed = false
    private var localAdvance: Job? = null

    val modelDir: String get() = ModelStore.primaryDir(getApplication()).absolutePath

    init {
        ws.start()
        viewModelScope.launch {
            ws.messages.collect { m ->
                when (m) {
                    is ServerMessage.Protocol -> {
                        runId.value = m.runId
                        labNames.value = m.labNames
                        applyProtocol(m.steps, 0)
                    }
                    is ServerMessage.State -> onState(m)
                    is ServerMessage.Haptic -> haptics.play(m.pattern)
                    is ServerMessage.Other -> if (m.type == "open_viewer") {
                        val requestedRun = m.json.optString("run_id")
                        if (requestedRun.isNotBlank()) openMoleculeViewer(requestedRun)
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch { sync() }
        viewModelScope.launch { while (true) { tick(); delay(250) } }
    }

    override fun onCleared() {
        ws.stop()
        audio.release()
        detector?.close()
        voice?.stop()
    }

    // ------------------------------------------------------------------ protocol + state

    private fun applyProtocol(newSteps: List<ProtocolStep>, index: Int) {
        gate.setProtocol(newSteps, index)
        steps.value = newSteps
        complete.value = false
        enterStep(index)
    }

    private fun enterStep(index: Int) {
        localAdvance?.cancel(); localAdvance = null
        stepIndex.value = index
        val s = steps.value.getOrNull(index)
        _currentStep.value = s
        awaitingReview.value = false
        holdActive.value = false; holdReason = null
        troubleshootOpen.value = false
        _warning.value = Warning.None
        missingSince = null
        acknowledgedForbidden = emptySet()
        seenRequired = emptySet()
        stepStartedAt = SystemClock.elapsedRealtime()
        timerChimed = false
        timerEndAt = s?.timerS?.let { stepStartedAt + it * 1000L }
        timerRemaining.value = s?.timerS
        if (s == null && steps.value.isNotEmpty()) complete.value = true
    }

    private fun onState(m: ServerMessage.State) {
        runId.value = m.runId
        runState.value = m.state
        when {
            m.state.startsWith("STEP_") -> {
                val i = m.stepIndex ?: (m.state.removePrefix("STEP_").toIntOrNull()?.minus(1) ?: 0)
                if (i != stepIndex.value || gate.isPassed) {
                    gate.setIndex(i); enterStep(i)
                }
            }
            m.state == "APPROVAL" || m.state == "BEAMED" -> {
                stepIndex.value = steps.value.size
                _currentStep.value = null
                awaitingReview.value = false
                complete.value = true
            }
        }
    }

    private var lastViewerRun: String? = null

    /** Phone "Open in XR" → hub BIND_OK → launch the WebXR molecule viewer in Chrome (one tap "Enter XR" remains). */
    fun openMoleculeViewer(run: String) {
        lastViewerRun = run
        val base = config.getString("viewer", "http://localhost:8080/viewer/")
        val url = Uri.parse(base).buildUpon()
            .appendQueryParameter("run", run)
            .appendQueryParameter("hub", config.server)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val app = getApplication<Application>()
        try {
            app.startActivity(Intent(intent).setPackage("com.android.chrome"))
            log.value = "opened viewer for run $run"
        } catch (e: ActivityNotFoundException) {
            runCatching { app.startActivity(intent) }
                .onFailure { log.value = "viewer launch failed: ${it.message}" }
        }
    }

    /** Pull protocol + latest run over REST (if the app started after the WS broadcast). */
    fun sync() {
        viewModelScope.launch {
            runCatching {
                val p = backend.protocol()
                val run = backend.latestRun()
                if (run != null) { runId.value = run.first; runState.value = run.second }
                val idx = if (run != null && run.second.startsWith("STEP_")) run.third else 0
                if (steps.value.isEmpty() || run?.second?.startsWith("STEP_") == true) applyProtocol(p, idx)
                log.value = "synced: ${p.size} steps, run ${run?.first ?: "-"} ${run?.second ?: ""}"
            }.onFailure { log.value = "sync failed: ${it.message}" }
        }
    }

    fun loadDemoProtocol() { runId.value = null; runState.value = null; applyProtocol(DEMO_STEPS, 0) }

    fun startProtocol() {
        if (steps.value.isEmpty()) {
            viewModelScope.launch {
                sync()
                delay(1200)
                if (steps.value.isEmpty()) loadDemoProtocol()
            }
        } else if (runId.value == null || complete.value) applyProtocol(steps.value, 0)
    }

    fun lab(coco: String) = labNames.value[coco]?.let { "$it ($coco)" } ?: coco

    // ------------------------------------------------------------------ ticker (timer, amber)

    private fun tick() {
        val s = _currentStep.value ?: return
        val now = SystemClock.elapsedRealtime()
        timerEndAt?.let { end ->
            val left = ((end - now + 999) / 1000).toInt().coerceAtLeast(0)
            timerRemaining.value = left
            if (left == 0 && !timerChimed) { timerChimed = true; audio.play("chime"); haptics.play("success") }
        }
        if (holdActive.value) return
        val missing = s.required.filter { it !in _seenLabels.value }
        val forbiddenNow = s.forbidden.filter { it in _seenLabels.value && it !in acknowledgedForbidden }
        when {
            forbiddenNow.isNotEmpty() -> _warning.value = Warning.Red("⚠ ${forbiddenNow.joinToString { lab(it).replaceFirstChar(Char::uppercase) }} on bench — not allowed in this step")
            missing.isNotEmpty() && !awaitingReview.value -> {
                val since = missingSince ?: now.also { missingSince = it }
                if (now - since > 3000) _warning.value = Warning.Amber("Waiting to see: ${missing.joinToString { lab(it) }}")
            }
            else -> { missingSince = null; _warning.value = Warning.None }
        }
    }

    // ------------------------------------------------------------------ frames (analysis thread)

    fun onFrame(bmp: Bitmap) {
        val mode = engineMode.value
        if (detector == null || detectorMode != mode) {
            detector?.close()
            detector = YoloDetector(getApplication(), mode); detectorMode = mode
        }
        val det = detector!!
        lastFrame = bmp
        frameSize.value = bmp.width to bmp.height
        val frame = det.detect(bmp)
        modelStatus.value = det.status
        if (frame == null) { detections.value = emptyList(); inferMs.value = null; return }
        detections.value = frame.detections
        inferMs.value = frame.inferMs
        handleFrame(frame.detections)
    }

    private fun handleFrame(dets: List<Detection>) {
        val step = gate.current
        val ack = acknowledgedForbidden
        val event = gate.onFrame(if (ack.isEmpty()) dets else dets.filter { it.label !in ack })
        progress.value = gate.progress.toMap()
        _seenLabels.value = dets.filter { it.conf > gate.conf }.map { it.label }.toSet()
        step?.let { s ->
            // Cue toward a required object the first frame it comes into view.
            val now = dets.filter { it.conf > gate.conf && it.label in s.required }
            now.firstOrNull { it.label !in seenRequired }?.let { audio.play("tick", it.centerX) }
            seenRequired = now.map { it.label }.toSet()

            val t = SystemClock.elapsedRealtime()
            val rid = runId.value
            if (rid != null && t - lastWsSend > 250) {
                lastWsSend = t
                ws.send(ClientMessages.detections(rid, s.id, dets))
            }
        }
        when (event) {
            is StepGate.Event.Passed -> onPassed(event)
            is StepGate.Event.Anomaly -> onAnomaly(event)
            null -> Unit
        }
    }

    private fun onPassed(e: StepGate.Event.Passed) {
        awaitingReview.value = true
        _warning.value = Warning.None
        audio.play("success")
        haptics.play("success")
        ws.send(ClientMessages.haptic("success"))
        report(e.step, "passed", e.detections) { ok ->
            if (!ok && runId.value != null) {
                awaitingReview.value = false
                hold("Evidence upload failed — retry or sync")
                gate.reset()
            }
        }
        // Demo protocol has no server to review (or dev mode): advance locally after a beat.
        if (runId.value == null || devMode.value) {
            localAdvance = viewModelScope.launch {
                delay(1500)
                val next = stepIndex.value + 1
                gate.setIndex(next); enterStep(next)
            }
        }
    }

    private fun onAnomaly(e: StepGate.Event.Anomaly) {
        val box = e.detections.firstOrNull { it.label == e.label }
        audio.play("anomaly", box?.centerX ?: 0.5f)
        haptics.play("anomaly")
        hold("⚠ ${lab(e.label).replaceFirstChar(Char::uppercase)} on bench — not allowed in this step", cue = false)
        report(e.step, "anomaly", e.detections) { ok ->
            // On success the server already buzzes the phone for anomalies; only mirror it ourselves otherwise.
            if (!ok) ws.send(ClientMessages.haptic("anomaly"))
        }
    }

    private fun report(step: ProtocolStep, status: String, dets: List<Detection>, then: suspend (Boolean) -> Unit = {}) {
        val rid = runId.value
        val snap = lastFrame
        viewModelScope.launch(Dispatchers.IO) {
            val ok = if (rid == null) {
                log.value = "no run_id (demo protocol): /lab/step not sent"; false
            } else runCatching {
                backend.postLabStep(LabStepRequest(rid, step.id, status, dets, snap?.let(::jpegB64)))
            }.fold({ log.value = "POST /lab/step ${step.id} $status → ${it.optString("state")}"; true },
                { log.value = "POST /lab/step failed: ${it.message}"; false })
            then(ok)
        }
    }

    // ------------------------------------------------------------------ operator actions

    fun hold(reason: String, cue: Boolean = true) {
        if (_currentStep.value == null) return
        if (devMode.value) { _warning.value = Warning.Amber("[dev] $reason"); return }
        holdActive.value = true
        holdReason = reason
        _warning.value = Warning.Red(reason)
        if (cue) audio.play("low")
        if (_currentStep.value?.troubleshoot?.isNotEmpty() == true) troubleshootOpen.value = true
    }

    /** "Redo": clear the hold and re-arm the gate for the current step. */
    fun redo() {
        holdActive.value = false; holdReason = null
        troubleshootOpen.value = false
        _warning.value = Warning.None
        missingSince = null
        acknowledgedForbidden = emptySet()
        gate.reset()
        awaitingReview.value = false
    }

    /** "Continue anyway": record the deviation (status anomaly) and release the hold. */
    fun continueAnyway() {
        val s = gate.current ?: return
        deviationCount.value += 1
        holdReason?.let { notes.value = notes.value + "deviation @${s.id}: $it" }
        report(s, "anomaly", detections.value)
        acknowledgedForbidden = acknowledgedForbidden + s.forbidden.filter { it in _seenLabels.value }
        holdActive.value = false; holdReason = null
        troubleshootOpen.value = false
        _warning.value = Warning.None
        gate.clearAnomaly()
    }

    /** Voice "next" / explicit advance: post the step as passed; server advances on review. */
    fun requestAdvance() {
        val s = gate.current ?: return
        if (devMode.value) {
            // Skip gate, hold and review: step straight to the next card.
            val next = stepIndex.value + 1
            gate.setIndex(next); enterStep(next)
            log.value = "[dev] skipped ${s.id}"
            return
        }
        if (holdActive.value || awaitingReview.value) return
        gate.markPassed()
        val dets = detections.value
        onPassed(StepGate.Event.Passed(s, dets.filter { it.label in s.required } + dets.filter { it.label !in s.required }))
    }

    fun openTroubleshoot(open: Boolean = true) { troubleshootOpen.value = open }

    fun applyFix(t: Troubleshoot) {
        notes.value = notes.value + "fix @${gate.current?.id ?: "-"}: ${t.fix}"
        redo()
    }

    fun addNote(text: String) {
        if (text.isBlank()) return
        notes.value = notes.value + "${gate.current?.id ?: "-"}: ${text.trim()}"
    }

    fun addTimer(seconds: Int) {
        val s = _currentStep.value ?: return
        val now = SystemClock.elapsedRealtime()
        timerEndAt = maxOf(timerEndAt ?: now, now) + seconds * 1000L
        timerChimed = false
        if (s.timerS == null) timerRemaining.value = seconds
    }

    // ------------------------------------------------------------------ voice

    fun onMicPermission(granted: Boolean) {
        micGranted.value = granted
        syncVoice()
    }

    fun setMicEnabled(on: Boolean) {
        config.putString("mic", if (on) "on" else "off")
        micEnabled.value = on
        syncVoice()
    }

    private fun syncVoice() {
        val want = micEnabled.value && micGranted.value
        if (want && voice == null) {
            voice = VoiceCommander(
                getApplication(),
                onListening = { if (voiceBadge.value is VoiceBadge.Idle) voiceBadge.value = VoiceBadge.Listening },
                onPartial = { showBadge(VoiceBadge.Heard(it)) },
                onResult = ::onVoiceResult,
                onUnavailable = { voiceStatus.value = it; voiceBadge.value = VoiceBadge.Idle },
            ).also { it.start(); voiceStatus.value = "listening (offline preferred)" }
            voiceBadge.value = VoiceBadge.Listening
        } else if (!want && voice != null) {
            voice?.stop(); voice = null
            voiceBadge.value = VoiceBadge.Idle
            voiceStatus.value = if (!micGranted.value) "mic permission not granted" else "mic off"
        }
    }

    private fun showBadge(b: VoiceBadge, fade: Boolean = true) {
        voiceBadge.value = b
        badgeFade?.cancel()
        if (fade) badgeFade = viewModelScope.launch {
            delay(2500)
            voiceBadge.value = if (voice != null) VoiceBadge.Listening else VoiceBadge.Idle
        }
    }

    fun onVoiceResult(raw: String) {
        val text = raw.trim()
        if (text.length < 3) return
        val t = text.lowercase()
        val step = stepIndex.value + 1
        val note = Regex("""\bnote\b[,:]?\s+(.+)""").find(t)?.groupValues?.get(1)
        val cmd: String = when {
            note != null -> { addNote(note); "📝 note(\"${note.take(40)}\")" }
            "start protocol" in t -> { startProtocol(); "▶ start protocol" }
            "what's wrong" in t || "whats wrong" in t || "what is wrong" in t || t.contains(Regex("""\bhelp\b""")) -> {
                openTroubleshoot(true); "❓ troubleshoot(step $step)"
            }
            "add two minutes" in t || "add 2 minutes" in t -> { addTimer(120); "⏱ timer(+2 min)" }
            "add one minute" in t || "add 1 minute" in t || "add a minute" in t -> { addTimer(60); "⏱ timer(+1 min)" }
            t.contains(Regex("""\bcontinue\b""")) -> { if (holdActive.value) continueAnyway(); "▶ continue(step $step)" }
            t.contains(Regex("""\bredo\b""")) -> { redo(); "↺ redo(step $step)" }
            t.contains(Regex("""\b(hold|stop|pause)\b""")) -> {
                val reason = t.replace(Regex("""^.*?\b(hold|stop|pause)\b\s*"""), "").ifBlank { "operator hold" }
                hold("⏸ Hold — $reason"); "⏸ hold(\"$reason\")"
            }
            t.contains(Regex("""\brepeat\b""")) -> { repeatInstruction(); "🔁 repeat(step $step)" }
            t.contains(Regex("""\b(next|done)\b""")) -> { requestAdvance(); "▶ advance(step ${step + 1})" }
            else -> "✕ no command"
        }
        showBadge(VoiceBadge.Command(cmd))
    }

    /** Re-emphasise the instruction: bump the UI with a tick cue (TTS deliberately skipped). */
    val repeatPulse = MutableStateFlow(0)
    private fun repeatInstruction() { repeatPulse.value += 1; audio.play("tick") }

    // ------------------------------------------------------------------ debug / settings

    fun simulate(pass: Boolean) {
        val s = gate.current ?: return
        if (pass) {
            gate.markPassed()
            onPassed(StepGate.Event.Passed(s, s.required.map { Detection(it, 0.99f, floatArrayOf(0.4f, 0.4f, 0.2f, 0.2f)) }))
        } else {
            val label = s.forbidden.firstOrNull() ?: "scissors"
            onAnomaly(StepGate.Event.Anomaly(s, label, listOf(Detection(label, 0.99f, floatArrayOf(0.8f, 0.4f, 0.15f, 0.2f)))))
        }
    }

    fun setShowDetections(on: Boolean) {
        config.putString("show_dets", if (on) "on" else "off")
        showDetections.value = on
    }

    fun setDevMode(on: Boolean) {
        config.putString("dev_mode", if (on) "on" else "off")
        devMode.value = on
        if (on && holdActive.value) redo()
    }

    fun setEngine(mode: String) {
        config.putString("engine", mode)
        engineMode.value = mode
    }

    fun onServerChanged() {
        ws.restart(); sync()
    }

    private fun jpegB64(bmp: Bitmap): String {
        val scale = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
        val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        /** Same as server/protocol.json, for running with no backend. */
        val DEMO_STEPS = listOf(
            ProtocolStep("s1", "Operator at bench", listOf("person"), emptyList(), instruction = "Stand at the bench so the headset can see you."),
            ProtocolStep(
                "s2", "Pick up reagent", listOf("bottle"), listOf("scissors"),
                instruction = "Take the reagent bottle. Keep sharps off the bench.",
                critical = true,
                troubleshoot = listOf(Troubleshoot("Scissors detected", "Sharps left from previous prep", "Move scissors to the tray, then redo")),
            ),
            ProtocolStep(
                "s3", "Prepare beaker", listOf("cup"), listOf("scissors"),
                instruction = "Dissolve pNPA in acetone; start timer.", timerS = 300,
                troubleshoot = listOf(Troubleshoot("Blank wells turn yellow", "Substrate too old", "Make fresh pNPA")),
            ),
            ProtocolStep("s4", "Record in lab notebook", listOf("book"), emptyList(), instruction = "Log the batch and time in the notebook."),
        )
    }
}
