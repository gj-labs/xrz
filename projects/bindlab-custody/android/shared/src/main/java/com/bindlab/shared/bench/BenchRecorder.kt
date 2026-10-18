package com.bindlab.shared.bench

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import com.bindlab.shared.inference.InferenceEngine
import com.bindlab.shared.inference.ModelLoadException
import com.bindlab.shared.inference.TensorInput
import com.bindlab.shared.inference.looksLikeOom
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/** One POST /bench row. Nullable numbers go out as JSON null. */
data class BenchResult(
    val device: String,
    val model: String,
    val backend: String,
    val loadMs: Double? = null,
    val p50Ms: Double? = null,
    val p95Ms: Double? = null,
    val peakRssMb: Double? = null,
    val thermalHeadroom: Double? = null,
    val batteryDelta: Double? = null,
    val sustainedS: Double? = null,
    val status: String = "ok", // ok | oom | load_failed
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device", device).put("model", model).put("backend", backend)
        .put("load_ms", num(loadMs)).put("p50_ms", num(p50Ms)).put("p95_ms", num(p95Ms))
        .put("peak_rss_mb", num(peakRssMb)).put("thermal_headroom", num(thermalHeadroom))
        .put("battery_delta", num(batteryDelta)).put("sustained_s", num(sustainedS))
        .put("status", status).put("error", error ?: JSONObject.NULL)

    private fun num(v: Double?): Any = if (v == null || v.isNaN() || v.isInfinite()) JSONObject.NULL else v
}

/**
 * load -> [warmup] runs -> [runs] timed runs (p50/p95) -> optional sustained loop.
 * Memory: peak of Debug.getMemoryInfo total PSS and /proc/self/status VmRSS, sampled on a side thread.
 * Thermal: PowerManager.getThermalHeadroom(10s forecast), read at the end (after the sustained loop).
 * Battery: capacity delta in percent (negative = drained), refined with the charge counter when available.
 */
class BenchRecorder(
    private val context: Context,
    val warmup: Int = 10,
    val runs: Int = 50,
    val sustainedSeconds: Int = 120,
) {
    suspend fun measure(
        device: String,
        model: String,
        backend: String,
        engine: InferenceEngine,
        modelPath: String,
        inputs: List<TensorInput>,
        sustained: Boolean = true,
        progress: (String) -> Unit = {},
    ): BenchResult {
        val base = BenchResult(device, model, backend)
        val battery = BatterySnapshot.take(context)
        val sampler = MemorySampler().also { it.start() }
        var loadMs: Double? = null
        try {
            progress("loading")
            loadMs = engine.load(modelPath).toDouble()

            for (i in 0 until warmup) {
                currentCoroutineContext().ensureActive()
                progress("warm-up ${i + 1}/$warmup")
                engine.run(inputs)
            }
            val times = DoubleArray(runs)
            for (i in 0 until runs) {
                currentCoroutineContext().ensureActive()
                if (i % 5 == 0) progress("timed ${i + 1}/$runs")
                val t0 = SystemClock.elapsedRealtimeNanos()
                engine.run(inputs)
                times[i] = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            }
            times.sort()

            var sustainedS: Double? = null
            var sustainedError: String? = null
            if (sustained && sustainedSeconds > 0) {
                val t0 = SystemClock.elapsedRealtime()
                val end = t0 + sustainedSeconds * 1000L
                var n = 0
                try {
                    while (SystemClock.elapsedRealtime() < end) {
                        currentCoroutineContext().ensureActive()
                        engine.run(inputs)
                        if (++n % 20 == 0) progress("sustained ${(SystemClock.elapsedRealtime() - t0) / 1000}s/${sustainedSeconds}s")
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    sustainedError = "sustained loop stopped: ${t.message ?: t.javaClass.simpleName}"
                }
                sustainedS = (SystemClock.elapsedRealtime() - t0) / 1000.0
            }
            return base.copy(
                loadMs = loadMs,
                p50Ms = percentile(times, 0.50),
                p95Ms = percentile(times, 0.95),
                peakRssMb = sampler.stop(),
                thermalHeadroom = thermalHeadroom(),
                batteryDelta = battery.deltaTo(BatterySnapshot.take(context)),
                sustainedS = sustainedS,
                error = sustainedError,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val status = if (looksLikeOom(t)) "oom" else "load_failed"
            val msg = (if (t is ModelLoadException) t.message else "${t.javaClass.simpleName}: ${t.message}") ?: "error"
            return base.copy(
                loadMs = loadMs, status = status, error = msg.take(500),
                peakRssMb = sampler.stop(), thermalHeadroom = thermalHeadroom(),
            )
        } finally {
            sampler.stop()
            runCatching { engine.close() }
        }
    }

    fun thermalHeadroom(): Double? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val h = pm.getThermalHeadroom(10)
        return if (h.isNaN()) null else h.toDouble()
    }

    companion object {
        fun percentile(sorted: DoubleArray, q: Double): Double? {
            if (sorted.isEmpty()) return null
            val idx = (ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
    }
}

private class MemorySampler(private val periodMs: Long = 250) {
    @Volatile private var running = false
    @Volatile private var peakKb = 0L
    private var thread: Thread? = null

    fun start() {
        running = true
        sample()
        thread = Thread {
            while (running) {
                sample()
                try { Thread.sleep(periodMs) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "bench-mem"; start() }
    }

    /** Stops sampling (idempotent) and returns the peak in MB. */
    fun stop(): Double {
        if (running) {
            running = false
            thread?.interrupt()
            sample()
        }
        return peakKb / 1024.0
    }

    private fun sample() {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val pss = mi.totalPss.toLong()
        val rss = vmRssKb()
        peakKb = maxOf(peakKb, pss, rss)
    }

    private fun vmRssKb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLong() ?: 0L
        }
    }.getOrDefault(0L)
}

private class BatterySnapshot(val capacityPct: Int, val chargeUah: Int) {
    fun deltaTo(end: BatterySnapshot): Double? {
        if (capacityPct <= 0 || end.capacityPct <= 0) return null
        if (chargeUah > 0 && end.chargeUah > 0) {
            val fullUah = chargeUah * 100.0 / capacityPct
            return (end.chargeUah - chargeUah) / fullUah * 100.0
        }
        return (end.capacityPct - capacityPct).toDouble()
    }

    companion object {
        fun take(context: Context): BatterySnapshot {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return BatterySnapshot(
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            )
        }
    }
}
