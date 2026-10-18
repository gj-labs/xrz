package com.bindlab.phone

import android.content.Context
import com.bindlab.shared.inference.ModelStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Binding score from two embeddings. Weights: models/binding_head_<size>.json, or
 * models/binding_head.json (either one head, or {"heads": {"8M": {...}, ...}}).
 *
 * Head JSON: {"features": "concat" | "concat_absdiff_prod",
 *             "feature_mean": [...]?, "feature_std": [...]?,
 *             "layers": [{"weight": [[out x in]], "bias": [out], "activation": "relu"|"tanh"|"sigmoid"|"none"}],
 *             "threshold": 0.5}
 * weight is PyTorch nn.Linear layout [out][in]. The final scalar goes through a sigmoid unless the
 * last layer already is one. No head (or a dim mismatch) -> cosine similarity mapped to 0..1.
 */
class BindingHead private constructor(
    private val layers: List<Layer>,
    private val features: String,
    private val mean: FloatArray?,
    private val std: FloatArray?,
    val threshold: Float,
) {
    class Layer(val w: Array<FloatArray>, val b: FloatArray, val act: String) {
        val inDim get() = w.firstOrNull()?.size ?: 0
    }

    data class Score(val value: Float, val method: String, val threshold: Float) {
        val bound get() = value >= threshold
    }

    fun score(t: FloatArray, b: FloatArray): Score? {
        var x = featurize(t, b, features)
        if (layers.isEmpty() || x.size != layers[0].inDim) return null
        if (mean != null && std != null && mean.size == x.size && std.size == x.size) {
            x = FloatArray(x.size) { (x[it] - mean[it]) / (if (std[it] == 0f) 1f else std[it]) }
        }
        for (l in layers) {
            x = FloatArray(l.b.size) { o ->
                var s = l.b[o]
                val row = l.w[o]
                for (i in row.indices) s += row[i] * x[i]
                activate(s, l.act)
            }
        }
        val v = if (layers.last().act == "sigmoid") x[0] else sigmoid(x[0])
        return Score(v, "mlp", threshold)
    }

    companion object {
        fun load(context: Context, size: String): BindingHead? {
            val f = ModelStore.file(context, "binding_head_$size.json") ?: ModelStore.file(context, "binding_head.json")
                ?: return null
            return runCatching {
                var o = JSONObject(f.readText())
                o.optJSONObject("heads")?.let { heads -> o = heads.optJSONObject(size) ?: return null }
                parse(o)
            }.getOrNull()
        }

        fun parse(o: JSONObject): BindingHead {
            val arr = o.getJSONArray("layers")
            val layers = (0 until arr.length()).map { i ->
                val l = arr.getJSONObject(i)
                val w = (l.optJSONArray("weight") ?: l.optJSONArray("W") ?: l.getJSONArray("w")).toMatrix()
                val b = (l.optJSONArray("bias") ?: l.getJSONArray("b")).toFloats()
                Layer(w, b, l.optString("activation", l.optString("act", "none")).lowercase())
            }
            return BindingHead(
                layers, o.optString("features", "concat"),
                o.optJSONArray("feature_mean")?.toFloats(), o.optJSONArray("feature_std")?.toFloats(),
                o.optDouble("threshold", 0.5).toFloat(),
            )
        }

        fun cosine(t: FloatArray, b: FloatArray): Score {
            var dot = 0.0; var nt = 0.0; var nb = 0.0
            for (i in 0 until minOf(t.size, b.size)) {
                dot += t[i] * b[i]; nt += t[i] * t[i]; nb += b[i] * b[i]
            }
            val cos = if (nt == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(nt) * sqrt(nb))
            return Score(((cos + 1) / 2).toFloat(), "cosine", 0.5f)
        }

        fun featurize(t: FloatArray, b: FloatArray, kind: String): FloatArray = when (kind) {
            "concat_absdiff_prod", "symmetric" ->
                t + b + FloatArray(t.size) { kotlin.math.abs(t[it] - b[it]) } + FloatArray(t.size) { t[it] * b[it] }
            else -> t + b
        }

        private fun activate(x: Float, act: String) = when (act) {
            "relu" -> if (x > 0) x else 0f
            "tanh" -> kotlin.math.tanh(x)
            "sigmoid" -> sigmoid(x)
            "gelu" -> (0.5 * x * (1 + kotlin.math.tanh(0.7978845608 * (x + 0.044715 * x * x * x)))).toFloat()
            else -> x
        }

        private fun sigmoid(x: Float) = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

        private fun JSONArray.toFloats() = FloatArray(length()) { getDouble(it).toFloat() }
        private fun JSONArray.toMatrix() = Array(length()) { getJSONArray(it).toFloats() }
    }
}

/**
 * Predicted interface residues (1-based): run target + 25xG linker + binder through a contact-capable
 * export and take the top inter-chain contacts. Empty when the model has no contact output or the
 * pair is longer than 254 residues together.
 */
object InterfaceResidues {
    const val LINKER = "GGGGGGGGGGGGGGGGGGGGGGGGG"

    fun fits(target: String, binder: String) =
        target.length + LINKER.length + binder.length <= EsmTokenizer.MAX_RESIDUES

    fun linked(target: String, binder: String) = target + LINKER + binder

    fun fromContacts(map: FloatArray, n: Int, targetLen: Int, binderLen: Int, topK: Int = 12): Pair<List<Int>, List<Int>> {
        val off = targetLen + LINKER.length
        val pairs = ArrayList<Triple<Int, Int, Float>>()
        for (i in 0 until targetLen) for (j in 0 until binderLen) {
            val idx = i * n + off + j
            if (idx < map.size) pairs += Triple(i, j, map[idx])
        }
        val top = pairs.sortedByDescending { it.third }.take(topK)
        return top.map { it.first + 1 }.distinct().sorted() to top.map { it.second + 1 }.distinct().sorted()
    }
}
