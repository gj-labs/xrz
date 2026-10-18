package com.bindlab.xr.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wake-word-free, always-on speech capture built on the platform [SpeechRecognizer].
 * Each utterance is one recognition session; we restart after every result / error with a short
 * back-off so the operator never has to tap anything. All calls must come from the main thread.
 */
class VoiceCommander(
    private val context: Context,
    private val onListening: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onUnavailable: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var restart: Runnable? = null

    val isRunning get() = running

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { onUnavailable("speech recognition not available"); return }
        running = true
        recognizer = create().also { it.setRecognitionListener(listener) }
        listen()
    }

    fun stop() {
        running = false
        restart?.let(main::removeCallbacks); restart = null
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun create(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else SpeechRecognizer.createSpeechRecognizer(context)

    private fun listen() {
        val r = recognizer ?: return
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { r.startListening(intent) }.onFailure { scheduleRestart(1500) }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        restart?.let(main::removeCallbacks)
        restart = Runnable { restart = null; listen() }.also { main.postDelayed(it, delayMs) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onListening()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(onResult)
            scheduleRestart(250)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { onUnavailable("microphone permission denied"); stop() }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    // The engine wants a fresh instance after these.
                    recognizer?.let { runCatching { it.destroy() } }
                    recognizer = create().also { it.setRecognitionListener(this) }
                    scheduleRestart(600)
                }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    onUnavailable("speech language unavailable offline")
                else -> scheduleRestart(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) 150 else 800)
            }
        }
    }
}
