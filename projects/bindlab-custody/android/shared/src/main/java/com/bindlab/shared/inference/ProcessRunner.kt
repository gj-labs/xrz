package com.bindlab.shared.inference

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Fallback engine (stub-grade): execs qnn_executor_runner shipped in jniLibs as
 * `libqnn_executor_runner.so` so the installer extracts it, executable, into nativeLibraryDir.
 * Every run() is one process: write raw inputs, run, read output_0_*.raw. Load cost is paid per run,
 * so this is for "does the NPU work on the headset at all", not for frame-rate numbers.
 */
class ProcessRunner(
    private val context: Context,
    private val runnerName: String = "libqnn_executor_runner.so",
    private val timeoutS: Long = 60,
) : InferenceEngine {
    override val name = "qnn_executor_runner"
    private var modelPath: String? = null
    private val work = File(context.cacheDir, "runner").apply { mkdirs() }

    val runner: File get() = File(QnnEnv.libDir(context), runnerName)

    override fun load(modelPath: String): Long {
        if (!File(modelPath).exists()) throw ModelLoadException("model missing: $modelPath")
        if (!runner.exists()) {
            throw ModelLoadException("runner missing: put qnn_executor_runner in jniLibs/arm64-v8a/$runnerName")
        }
        this.modelPath = modelPath
        return 0 // loaded inside each process; see run()
    }

    override fun run(inputs: List<TensorInput>): List<TensorOutput> {
        val model = modelPath ?: throw IllegalStateException("model not loaded")
        val out = File(work, "out").apply { deleteRecursively(); mkdirs() }
        val files = inputs.mapIndexed { i, t -> File(work, "input_0_$i.raw").also { it.writeBytes(t.toBytes()) } }
        val list = File(work, "input_list.txt").apply { writeText(files.joinToString(" ") { it.absolutePath } + "\n") }

        val pb = ProcessBuilder(
            runner.absolutePath,
            "--model_path", model,
            "--input_list_path", list.absolutePath,
            "--output_folder_path", out.absolutePath,
        ).directory(work).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = QnnEnv.ldPath(context)
        pb.environment()["ADSP_LIBRARY_PATH"] = QnnEnv.adspPath(context)
        val proc = pb.start()
        val log = StringBuilder()
        val reader = Thread { proc.inputStream.bufferedReader().forEachLine { log.appendLine(it) } }.apply { start() }
        if (!proc.waitFor(timeoutS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw RuntimeException("qnn_executor_runner timed out after ${timeoutS}s")
        }
        reader.join(1000)
        if (proc.exitValue() != 0) {
            throw RuntimeException("qnn_executor_runner exit ${proc.exitValue()}: ${log.takeLast(800)}")
        }
        val outputs = out.listFiles { f -> f.name.startsWith("output_0_") && f.name.endsWith(".raw") }
            ?.sortedBy { it.name.removePrefix("output_0_").removeSuffix(".raw").toIntOrNull() ?: 0 }
            .orEmpty()
        if (outputs.isEmpty()) throw RuntimeException("runner produced no outputs: ${log.takeLast(800)}")
        return outputs.map { f ->
            val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            TensorOutput(FloatArray(bb.remaining()).also { bb.get(it) }, LongArray(0))
        }
    }

    override fun close() {
        modelPath = null
    }

    private fun TensorInput.toBytes(): ByteArray = when (this) {
        is TensorInput.F32 -> ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { it.asFloatBuffer().put(data) }.array()
        is TensorInput.I64 -> ByteBuffer.allocate(data.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            .also { it.asLongBuffer().put(data) }.array()
        is TensorInput.I32 -> ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .also { it.asIntBuffer().put(data) }.array()
    }
}
