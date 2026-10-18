package com.bindlab.shared.inference

import java.io.Closeable

/** One model input. Shapes follow the exported program (e.g. ESM2 tokens: [1, 256]). */
sealed class TensorInput {
    abstract val shape: LongArray

    class F32(val data: FloatArray, override val shape: LongArray) : TensorInput()
    class I64(val data: LongArray, override val shape: LongArray) : TensorInput()
    class I32(val data: IntArray, override val shape: LongArray) : TensorInput()
}

/** A float output; [shape] is empty when the engine cannot report it (ProcessRunner). */
class TensorOutput(val data: FloatArray, val shape: LongArray) {
    val rank get() = shape.size
}

class ModelLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Swappable inference layer. The AAR / Module.load path is the default; ProcessRunner is the
 * fallback for devices where only the CLI qnn_executor_runner is validated (Galaxy XR).
 */
interface InferenceEngine : Closeable {
    val name: String

    /** Loads the program and its "forward" method. Returns wall-clock load time in ms. */
    fun load(modelPath: String): Long

    fun run(inputs: List<TensorInput>): List<TensorOutput>
}

/** True when a failure message looks like memory exhaustion rather than a bad/missing program. */
fun looksLikeOom(t: Throwable): Boolean {
    if (t is OutOfMemoryError) return true
    var c: Throwable? = t
    while (c != null) {
        val m = c.message?.lowercase() ?: ""
        if ("memory" in m || "alloc" in m || "oom" in m || "0x21" in m) return true
        c = c.cause
    }
    return false
}
