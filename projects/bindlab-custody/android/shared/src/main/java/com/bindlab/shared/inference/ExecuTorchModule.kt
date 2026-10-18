package com.bindlab.shared.inference

import android.content.Context
import android.os.SystemClock
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/** ExecuTorch AAR path: org.pytorch.executorch.Module.load + forward. */
class ExecuTorchModule(private val context: Context) : InferenceEngine {
    override val name = "executorch"
    private var module: Module? = null

    override fun load(modelPath: String): Long {
        close()
        if (!File(modelPath).exists()) throw ModelLoadException("model missing: $modelPath")
        QnnEnv.apply(context)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val m = try {
            Module.load(modelPath)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Throwable) {
            throw ModelLoadException("Module.load failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        // Module.load is lazy; force program + method init so load_ms is real and failures surface here.
        try {
            m.loadMethod("forward")
        } catch (e: OutOfMemoryError) {
            m.destroy(); throw e
        } catch (e: Throwable) {
            m.destroy()
            throw ModelLoadException("loadMethod(forward) threw: ${e.message ?: e.javaClass.simpleName}", e)
        }
        module = m
        return (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
    }

    override fun run(inputs: List<TensorInput>): List<TensorOutput> {
        val m = module ?: throw IllegalStateException("model not loaded")
        val values = inputs.map {
            EValue.from(
                when (it) {
                    is TensorInput.F32 -> Tensor.fromBlob(it.data, it.shape)
                    is TensorInput.I64 -> Tensor.fromBlob(it.data, it.shape)
                    is TensorInput.I32 -> Tensor.fromBlob(it.data, it.shape)
                }
            )
        }
        return m.forward(*values.toTypedArray())
            .filter { it.isTensor }
            .map { ev -> ev.toTensor().let { TensorOutput(it.dataAsFloatArray, it.shape()) } }
    }

    override fun close() {
        module?.destroy()
        module = null
    }
}
