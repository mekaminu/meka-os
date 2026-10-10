package os.meka.wear

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import os.meka.core.domain.WatchListenFailure

/**
 * Quick capture by voice on the watch (Galaxy Watch, slice 4a; ADR-006): listens once with Android's **on-device**
 * recogniser only, as the Fold's Talk does. Nothing is recorded or kept; if the watch has no on-device recogniser it
 * says so and doesn't listen (never a recogniser that sends the voice away). Use from the main thread.
 */
class WatchListener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onFail: (WatchListenFailure) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun start() {
        stop()
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return onFail(WatchListenFailure.NO_RECOGNISER)
        val r = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: Exception) {
            return onFail(WatchListenFailure.NO_RECOGNISER)
        }
        r.setRecognitionListener(listener)
        recognizer = r
        listening = true
        try {
            r.startListening(intent())
        } catch (_: Exception) {
            finish()
            onFail(WatchListenFailure.FAILED)
        }
    }

    /** Stops listening and lets the recogniser go (leaving the screen, or Undo). Nothing is reported after this. */
    fun stop() {
        listening = false
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
    }

    private fun finish() {
        listening = false
        val r = recognizer
        recognizer = null
        // Destroying inside the recogniser's own callback is allowed but noisy on some builds: do it after.
        r?.let { android.os.Handler(context.mainLooper).post { runCatching { it.destroy() } } }
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            finish()
            if (text.isBlank()) onFail(WatchListenFailure.NOT_HEARD) else onHeard(text)
        }

        override fun onError(error: Int) {
            if (!listening) return // a cancel after stopping reports ERROR_CLIENT
            val r = recognizer
            val failure = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> WatchListenFailure.NOT_HEARD
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> WatchListenFailure.NO_MIC
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // Ask the watch to fetch English for on-device speech (Android 13+), as the Fold does.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try { r?.triggerModelDownload(intent()) } catch (_: Exception) {}
                    }
                    WatchListenFailure.NO_LANGUAGE
                }
                else -> WatchListenFailure.FAILED
            }
            finish()
            onFail(failure)
        }
    }
}
