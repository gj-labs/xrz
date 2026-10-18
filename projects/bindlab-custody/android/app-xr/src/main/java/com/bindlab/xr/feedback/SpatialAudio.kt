package com.bindlab.xr.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * SoundPool cues with constant-power left/right panning toward a box centre (x in 0..1 of the frame).
 * Tones are synthesised to WAV in cacheDir at start-up, so there are no binary assets.
 */
class SpatialAudio(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
    ).build()

    private val ids: Map<String, Int>

    init {
        val dir = File(context.cacheDir, "cues").apply { mkdirs() }
        ids = mapOf(
            "success" to tone(listOf(880.0 to 0.12, 1320.0 to 0.18), square = false),
            "anomaly" to tone(listOf(220.0 to 0.18, 180.0 to 0.18, 220.0 to 0.18), square = true),
            "tick" to tone(listOf(1600.0 to 0.04), square = false),
            // Timer reached zero: three rising soft tones.
            "chime" to tone(listOf(660.0 to 0.14, 880.0 to 0.14, 1100.0 to 0.22), square = false),
            // Hold / red strip: a single low cue, less harsh than "anomaly".
            "low" to tone(listOf(160.0 to 0.30), square = false),
        ).mapValues { (name, pcm) ->
            val f = File(dir, "$name.wav").apply { writeBytes(wav(pcm)) }
            pool.load(f.absolutePath, 1)
        }
    }

    fun play(cue: String, x: Float = 0.5f) {
        val id = ids[cue] ?: return
        val pan = x.coerceIn(0f, 1f) * (PI / 2)
        pool.play(id, cos(pan).toFloat(), sin(pan).toFloat(), 1, 0, 1f)
    }

    fun release() = pool.release()

    private fun tone(parts: List<Pair<Double, Double>>, square: Boolean): ShortArray {
        val out = ArrayList<Short>()
        for ((freq, dur) in parts) {
            val n = (RATE * dur).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                var s = sin(2 * PI * freq * t)
                if (square) s = if (s >= 0) 0.6 else -0.6
                val env = minOf(1.0, i / (0.005 * RATE)) * exp(-3.0 * t / dur)
                out += (s * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            pcm.forEach { putShort(it) }
        }.array()
    }

    companion object {
        private const val RATE = 44100
    }
}
