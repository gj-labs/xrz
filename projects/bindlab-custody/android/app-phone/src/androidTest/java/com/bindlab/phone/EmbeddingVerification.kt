package com.bindlab.phone

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Executes the installed app's exact embedding path against the export reference. */
class EmbeddingVerification : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val report = Bundle()
        try {
            val dir = File(targetContext.getExternalFilesDir(null), "models")
            val manifest = JSONObject(File(dir, "manifest.json").readText())
            val model = File(dir, "esm2_8M_portable-cpu.pte")
            val hash = MessageDigest.getInstance("SHA-256").digest(model.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(hash == manifest.getString("pte_sha256")) { "Model hash mismatch" }
            val fixture = manifest.getJSONObject("fixture")
            val ids = fixture.getJSONArray("ids")
            check(EsmTokenizer.encode("AGVSE").contentEquals(LongArray(ids.length()) { ids.getLong(it) }))
            EsmEmbedder(targetContext, "8M", "portable-cpu").open().use { embedder ->
                val result = embedder.embed("AGVSE")
                val expected = fixture.getJSONArray("expected_embedding")
                check(result.embedding.size == expected.length())
                var maxError = 0.0
                result.embedding.forEachIndexed { i, value ->
                    check(value.isFinite())
                    val error = abs(value.toDouble() - expected.getDouble(i))
                    maxError = maxOf(maxError, error)
                    check(error <= 0.0001 + 0.001 * abs(expected.getDouble(i))) { "Reference mismatch at $i: $error" }
                }
                val target = embedder.embed(SequenceParser.DEMO_TARGET)
                val binder = embedder.embed(SequenceParser.DEMO_BINDER)
                check(target.embedding.all { it.isFinite() } && binder.embedding.all { it.isFinite() })
                report.putString("result", "PASS: hash, tokenizer, 320 finite outputs, reference tolerance, demo pair")
                report.putDouble("max_abs_error", maxError)
                report.putLong("load_ms", embedder.loadMs)
                report.putDouble("fixture_ms", result.latencyMs)
                report.putDouble("demo_pair_ms", target.latencyMs + binder.latencyMs)
            }
            lateinit var vm: PhoneViewModel
            runOnMainSync {
                vm = PhoneViewModel(targetContext.applicationContext as android.app.Application)
                vm.setSequences(SequenceParser.DEMO_TARGET, SequenceParser.DEMO_BINDER)
                vm.runBinding("8M", "portable-cpu")
            }
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 60000
                while (vm.binding.value.running && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                check(!vm.binding.value.running) { "Binding timed out" }
                check(vm.binding.value.score != null) { vm.binding.value.message }
                check(vm.runId.value != null) { vm.binding.value.message }
                report.putString("binding_flow", vm.binding.value.message)
                report.putString("score_method", vm.binding.value.score!!.method)
            } finally { vm.ws.stop() }
            finish(-1, report)
        } catch (e: Throwable) {
            report.putString("failure", e.stackTraceToString())
            finish(1, report)
        }
    }
}
