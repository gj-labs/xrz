package com.bindlab.shared.net

import org.json.JSONArray
import org.json.JSONObject

/** One detection. box = [x, y, w, h], top-left origin, normalised to 0..1 of the source frame. */
data class Detection(val label: String, val conf: Float, val box: FloatArray) {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("conf", conf.toDouble())
        .put("box", JSONArray().apply { box.forEach { put(it.toDouble()) } })

    val centerX get() = box[0] + box[2] / 2f
}

fun List<Detection>.toJson() = JSONArray().apply { forEach { put(it.toJson()) } }

/** One troubleshooting hint attached to a protocol step. */
data class Troubleshoot(val symptom: String, val cause: String, val fix: String)

data class ProtocolStep(
    val id: String,
    val title: String,
    val required: List<String>,
    val forbidden: List<String>,
    val instruction: String = "",
    val timerS: Int? = null,
    val critical: Boolean = false,
    val troubleshoot: List<Troubleshoot> = emptyList(),
)

sealed interface ServerMessage {
    data class State(val runId: String, val state: String, val stepIndex: Int?) : ServerMessage
    data class Protocol(val runId: String, val steps: List<ProtocolStep>, val labNames: Map<String, String>) :
        ServerMessage
    data class Haptic(val pattern: String) : ServerMessage
    data class Other(val type: String, val json: JSONObject) : ServerMessage

    companion object {
        fun parse(text: String): ServerMessage? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            return when (val type = o.optString("type")) {
                "state" -> State(
                    o.optString("run_id"), o.optString("state"),
                    if (o.has("step_index") && !o.isNull("step_index")) o.optInt("step_index") else null,
                )
                "protocol" -> Protocol(o.optString("run_id"), parseSteps(o.optJSONArray("steps")), o.optJSONObject("coco_to_lab").toStringMap())
                "haptic" -> Haptic(o.optString("pattern", "pulse"))
                else -> Other(type, o)
            }
        }

        fun parseSteps(arr: JSONArray?): List<ProtocolStep> = (0 until (arr?.length() ?: 0)).map { i ->
            val s = arr!!.getJSONObject(i)
            ProtocolStep(
                s.optString("id"), s.optString("title"),
                s.optJSONArray("required").toStringList(), s.optJSONArray("forbidden").toStringList(),
                instruction = s.optString("instruction", ""),
                timerS = if (s.has("timer_s") && !s.isNull("timer_s")) s.optInt("timer_s").takeIf { it > 0 } else null,
                critical = s.optBoolean("critical", false),
                troubleshoot = parseTroubleshoot(s.optJSONArray("troubleshoot")),
            )
        }

        private fun parseTroubleshoot(arr: JSONArray?): List<Troubleshoot> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            arr!!.optJSONObject(i)?.let { Troubleshoot(it.optString("symptom"), it.optString("cause"), it.optString("fix")) }
        }
    }
}

/** Client -> server messages. */
object ClientMessages {
    fun detections(runId: String, stepId: String, dets: List<Detection>): JSONObject = JSONObject()
        .put("type", "detections").put("run_id", runId).put("step_id", stepId).put("detections", dets.toJson())

    fun haptic(pattern: String): JSONObject = JSONObject().put("type", "haptic").put("pattern", pattern)
}

fun JSONArray?.toStringList(): List<String> = (0 until (this?.length() ?: 0)).map { this!!.getString(it) }

fun JSONObject?.toStringMap(): Map<String, String> =
    this?.keys()?.asSequence()?.associateWith { optString(it) } ?: emptyMap()
