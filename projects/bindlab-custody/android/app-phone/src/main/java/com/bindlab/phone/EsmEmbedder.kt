package com.bindlab.phone

import android.content.Context
import android.os.SystemClock
import com.bindlab.shared.inference.ExecuTorchModule
import com.bindlab.shared.inference.InferenceEngine
import com.bindlab.shared.inference.ModelLoadException
import com.bindlab.shared.inference.ModelStore
import com.bindlab.shared.inference.TensorInput
import com.bindlab.shared.inference.TensorOutput
import java.io.Closeable

object EsmModels {
    val SIZES = listOf("8M", "35M", "150M", "650M", "3B")
    val BACKENDS = listOf("portable-cpu", "xnnpack", "qnn")
    fun base(size: String, backend: String) = "esm2_${size}_$backend"
    fun modelName(size: String) = "esm2_$size"
}

/**
 * ml/ exports take int64 tokens; QNN graphs sometimes want int32. Try int64 first and fall back to
 * int32 once if the first forward fails, then stick with whatever worked.
 */
class TokenDtypeAdapter(private val inner: InferenceEngine) : InferenceEngine by inner {
    private var useI32: Boolean? = null

    override fun run(inputs: List<TensorInput>): List<TensorOutput> {
        if (useI32 == true) return inner.run(inputs.map { it.toI32() })
        return try {
            inner.run(inputs).also { useI32 = false }
        } catch (e: Exception) {
            if (useI32 != null) throw e
            inner.run(inputs.map { it.toI32() }).also { useI32 = true }
        }
    }

    private fun TensorInput.toI32() =
        if (this is TensorInput.I64) TensorInput.I32(IntArray(data.size) { data[it].toInt() }, shape) else this
}

fun tokenInputs(seq: String) =
    listOf(TensorInput.I64(EsmTokenizer.encode(seq), longArrayOf(1, EsmTokenizer.SEQ_LEN.toLong())))

/** Loaded ESM2 model -> mean-pooled embedding [D] (+ contact map [254, 254] for <=150M exports). */
class EsmEmbedder(private val context: Context, val size: String, val backend: String) : Closeable {
    class Result(val embedding: FloatArray, val contacts: TensorOutput?, val latencyMs: Double)

    private var engine: InferenceEngine? = null
    var loadMs = 0L
        private set

    fun open(): EsmEmbedder {
        val files = ModelStore.find(context, EsmModels.base(size, backend))
            ?: throw ModelLoadException("model missing: ${EsmModels.base(size, backend)}.pte")
        val (e, path) = ModelStore.engineFor(files) { ExecuTorchModule(context) }
        val adapted = if (backend == "qnn") TokenDtypeAdapter(e) else e
        loadMs = adapted.load(path)
        engine = adapted
        return this
    }

    fun embed(seq: String): Result {
        require(EsmTokenizer.clean(seq).length <= EsmTokenizer.MAX_RESIDUES) { "Maximum sequence length is 254 residues" }
        val e = engine ?: throw IllegalStateException("not open")
        val t0 = SystemClock.elapsedRealtimeNanos()
        val outs = e.run(tokenInputs(seq))
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        val dim = when (size) { "8M" -> 320L; "35M" -> 480L; "150M" -> 640L; "650M" -> 1280L; "3B" -> 2560L; else -> 0L }
        val emb = outs.firstOrNull { it.rank == 2 && it.shape.contentEquals(longArrayOf(1, dim)) }
            ?: throw IllegalStateException("Model output is not the expected [1, $dim] sequence embedding")
        val contacts = outs.firstOrNull { it !== emb && it.rank == 3 && it.shape[1] == it.shape[2] }
        return Result(emb.data, contacts, ms)
    }

    override fun close() {
        engine?.close(); engine = null
    }
}
