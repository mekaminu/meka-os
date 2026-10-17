package os.meka.wear

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import os.meka.core.domain.ListenEngine
import os.meka.core.domain.ListenRules
import os.meka.core.domain.ListenStep
import os.meka.core.domain.TalkEndpoint
import os.meka.core.domain.WatchListenFailure

/**
 * Quick capture by voice on the watch (Galaxy Watch, slice 4a; ADR-006): listens once. Since the mic fix (steps 3–4,
 * 2026-10-17; ADR-006 addendum, Meka's yes): Google's online recogniser when the watch has signal (its own or the
 * phone's), the on-device one without it (and after a network failure online, once); listening ends 0.8 s after his
 * last word ([TalkEndpoint]). Nothing is recorded or kept. Use from the main thread.
 */
class WatchListener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onHeard: (String) -> Unit,
    private val onFail: (WatchListenFailure) -> Unit,
    /** Capture ends 0.8 s after his last word; a voice note keeps to the recogniser's own longer end. */
    private val quickEnd: Boolean = true,
) {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var engine: ListenEngine = ListenEngine.ON_DEVICE
    private val handler = android.os.Handler(context.mainLooper)
    private var startedAt = 0L
    private var lastWordsAt = 0L
    private var heardSoFar = ""

    private fun hasSignal(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun start(force: ListenEngine? = null) {
        stop()
        val want = force ?: ListenRules.engine(
            hasSignal(), SpeechRecognizer.isRecognitionAvailable(context), SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
        ) ?: return onFail(WatchListenFailure.NO_RECOGNISER)
        val r = try {
            if (want == ListenEngine.ONLINE) SpeechRecognizer.createSpeechRecognizer(context)
            else SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (_: Exception) {
            return onFail(WatchListenFailure.NO_RECOGNISER)
        }
        r.setRecognitionListener(listener)
        recognizer = r
        engine = want
        listening = true
        startedAt = SystemClock.elapsedRealtime()
        lastWordsAt = startedAt
        heardSoFar = ""
        try {
            r.startListening(intent())
        } catch (_: Exception) {
            finish()
            return onFail(WatchListenFailure.FAILED)
        }
        if (quickEnd) handler.postDelayed(endpoint, TalkEndpoint.TICK_MS)
    }

    /** Ends listening 0.8 s after his last word, rather than at the recogniser's own end (the mic fix, step 3). */
    private val endpoint: Runnable = object : Runnable {
        override fun run() {
            if (!listening) return
            val step = TalkEndpoint.step(startedAt, heardSoFar.isNotBlank(), lastWordsAt, SystemClock.elapsedRealtime())
            if (step == ListenStep.FINISH) runCatching { recognizer?.stopListening() } // the words come in onResults
            else handler.postDelayed(this, TalkEndpoint.TICK_MS)
        }
    }

    /** Stops listening and lets the recogniser go (leaving the screen, or Undo). Nothing is reported after this. */
    fun stop() {
        listening = false
        handler.removeCallbacks(endpoint)
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
    }

    private fun finish() {
        listening = false
        handler.removeCallbacks(endpoint)
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
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, engine == ListenEngine.ON_DEVICE)
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
                ?.takeIf { it.isNotBlank() }?.let {
                    if (it != heardSoFar) lastWordsAt = SystemClock.elapsedRealtime()
                    heardSoFar = it
                    onPartial(it)
                }
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { heardSoFar }
            finish()
            if (text.isBlank()) onFail(WatchListenFailure.NOT_HEARD) else onHeard(text)
        }

        override fun onError(error: Int) {
            if (!listening) return // a cancel after stopping reports ERROR_CLIENT
            // Online failed on the network before any words: listen again on the watch itself, once.
            val network = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
            if (heardSoFar.isBlank()) {
                ListenRules.fallback(engine, network, SpeechRecognizer.isOnDeviceRecognitionAvailable(context))?.let { start(it); return }
            }
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
