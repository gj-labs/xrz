package com.bindlab.xr.yolo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.bindlab.shared.inference.ExecuTorchModule
import com.bindlab.shared.inference.InferenceEngine
import com.bindlab.shared.inference.ModelStore
import com.bindlab.shared.inference.ProcessRunner
import com.bindlab.shared.inference.TensorInput
import com.bindlab.shared.net.Detection
import java.io.File

/**
 * Picks the model + engine and runs one frame. Engine modes:
 *  - "aar": ExecuTorch Module (yolo11n_qnn.pte, falling back to yolo11n_xnnpack.pte)
 *  - "runner": exec qnn_executor_runner (yolo11n_qnn.pte only); Galaxy XR fallback
 * Not thread-safe: call from the analyzer thread only.
 */
class YoloDetector(private val context: Context, private val mode: String) {
    var status: String = "not loaded"
        private set
    var backend: String? = null
        private set

    private val pre = YoloPreprocessor(640)
    private var engine: InferenceEngine? = null
    private var lastAttempt = 0L

    private fun candidates(): List<Pair<String, File>> {
        val names = if (mode == MODE_RUNNER) listOf("qnn") else listOf("qnn", "xnnpack")
        return names.mapNotNull { be -> ModelStore.file(context, "yolo11n_$be.pte")?.let { be to it } }
    }

    /** Tries to load at most every 3 s, so a model pushed while the app runs gets picked up. */
    private fun ensureLoaded(): Boolean {
        if (engine != null) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 3000) return false
        lastAttempt = now
        val found = candidates()
        if (found.isEmpty()) {
            status = "model missing: push yolo11n_qnn.pte or yolo11n_xnnpack.pte to ${ModelStore.primaryDir(context)}"
            return false
        }
        val errors = mutableListOf<String>()
        for ((be, f) in found) {
            val e: InferenceEngine = if (mode == MODE_RUNNER) ProcessRunner(context) else ExecuTorchModule(context)
            try {
                val ms = e.load(f.absolutePath)
                engine = e
                backend = be
                status = "${f.name} via ${e.name} (load $ms ms)"
                return true
            } catch (t: Throwable) {
                runCatching { e.close() }
                errors += "${f.name}: ${t.message}"
            }
        }
        status = "load failed: " + errors.joinToString("; ")
        return false
    }

    class Frame(val detections: List<Detection>, val inferMs: Double)

    fun detect(bitmap: Bitmap): Frame? {
        if (!ensureLoaded()) return null
        val e = engine ?: return null
        val t0 = SystemClock.elapsedRealtimeNanos()
        val lb = pre.process(bitmap)
        return try {
            val out = e.run(listOf(TensorInput.F32(pre.input, pre.shape))).first()
            val dets = YoloDecoder.decode(out.data, out.shape, lb)
            Frame(dets, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
        } catch (t: Throwable) {
            status = "inference error: ${t.message}"
            null
        }
    }

    fun close() {
        engine?.close(); engine = null
    }

    companion object {
        const val MODE_AAR = "aar"
        const val MODE_RUNNER = "runner"
    }
}
