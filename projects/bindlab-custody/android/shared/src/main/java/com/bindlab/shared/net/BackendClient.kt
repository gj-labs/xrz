package com.bindlab.shared.net

import com.bindlab.shared.bench.BenchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
}

data class BindingRequest(
    val targetSeq: String,
    val binderSeq: String,
    val model: String,
    val backend: String,
    val score: Float,
    val targetResidues: List<Int>,
    val binderResidues: List<Int>,
    val latencyMs: Double,
    val structureRef: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("target_seq", targetSeq).put("binder_seq", binderSeq)
        .put("model", model).put("backend", backend).put("score", score.toDouble())
        .put("target_residues", JSONArray(targetResidues)).put("binder_residues", JSONArray(binderResidues))
        .put("latency_ms", latencyMs)
        .also { o -> structureRef?.let { o.put("structure_ref", it) } }
}

data class BindingResponse(val runId: String, val state: String)

data class RunSummary(
    val id: String,
    val state: String,
    val stepIndex: Int,
    val created: Double,
    val score: Float?,
    val model: String,
    val backend: String,
    val targetLen: Int,
    val binderLen: Int,
    val latencyMs: Double?,
) {
    companion object {
        fun fromJson(r: JSONObject): RunSummary {
            val d = r.optJSONObject("data") ?: JSONObject()
            return RunSummary(
                id = r.optString("id"), state = r.optString("state"), stepIndex = r.optInt("step_index", 0),
                created = r.optDouble("created", 0.0),
                score = if (d.has("score") && !d.isNull("score")) d.optDouble("score").toFloat() else null,
                model = d.optString("model"), backend = d.optString("backend"),
                targetLen = d.optString("target_seq").length, binderLen = d.optString("binder_seq").length,
                latencyMs = if (d.has("latency_ms") && !d.isNull("latency_ms")) d.optDouble("latency_ms") else null,
            )
        }
    }
}

data class RunEvent(val kind: String, val ts: Double, val payload: JSONObject)

data class RunDetail(val summary: RunSummary, val chainOk: Boolean, val events: List<RunEvent>)

data class LabStepRequest(
    val runId: String,
    val stepId: String,
    val status: String, // "passed" | "anomaly"
    val detections: List<Detection>,
    val snapshotB64: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("run_id", runId).put("step_id", stepId).put("status", status)
        .put("detections", detections.toJson())
        .put("snapshot_b64", snapshotB64 ?: JSONObject.NULL)
}

class BackendException(message: String) : IOException(message)

/** REST side of the backend contract (see server/main.py). */
class BackendClient(private val baseUrl: () -> String, private val http: OkHttpClient = Http.client) {

    suspend fun postBench(r: BenchResult): JSONObject = post("/bench", r.toJson())

    suspend fun postBinding(r: BindingRequest): BindingResponse =
        post("/binding", r.toJson()).let { BindingResponse(it.optString("run_id"), it.optString("state")) }

    suspend fun postLabStep(r: LabStepRequest): JSONObject = post("/lab/step", r.toJson())

    suspend fun openXr(runId: String): JSONObject = post("/runs/$runId/open-xr", JSONObject())

    suspend fun health(): String = get("/health")

    suspend fun protocol(): List<ProtocolStep> = ServerMessage.parseSteps(JSONObject(get("/protocol")).optJSONArray("steps"))

    suspend fun listRuns(): List<RunSummary> {
        val arr = JSONArray(get("/runs"))
        return (0 until arr.length()).map { RunSummary.fromJson(arr.getJSONObject(it)) }
    }

    suspend fun getRun(runId: String): RunDetail {
        val o = JSONObject(get("/runs/$runId"))
        val ev = o.optJSONArray("events")
        val events = (0 until (ev?.length() ?: 0)).map { i ->
            val e = ev!!.getJSONObject(i)
            RunEvent(e.optString("kind"), e.optDouble("ts", 0.0), e.optJSONObject("payload") ?: JSONObject())
        }
        return RunDetail(RunSummary.fromJson(o), o.optBoolean("chain_ok", false), events)
    }

    suspend fun labHandoff(runId: String): JSONObject = post("/lab/handoff/$runId", JSONObject())

    /** Generates the evidence PDF; returns the server-relative PDF path. */
    suspend fun postEvidence(runId: String): String = post("/evidence/$runId", JSONObject()).optString("pdf", "/evidence/$runId.pdf")

    /** Most recent run as (run_id, state, step_index), or null. */
    suspend fun latestRun(): Triple<String, String, Int>? {
        val arr = JSONArray(get("/runs"))
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        return Triple(r.optString("id"), r.optString("state"), r.optInt("step_index", 0))
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl().trimEnd('/') + path)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw BackendException("POST $path -> ${resp.code}: ${text.take(300)}")
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl().trimEnd('/') + path).get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw BackendException("GET $path -> ${resp.code}: ${text.take(300)}")
            text
        }
    }
}
