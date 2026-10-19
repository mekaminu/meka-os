package os.meka.android.ask

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import os.meka.core.domain.HelloWakeRules
import os.meka.core.domain.ListenEngine

/**
 * "Hello, Navilife" slice 3 on the Fold: listens for "Hello Navilife" with Android's **on-device** recogniser only
 * (`createOnDeviceSpeechRecognizer`; the words never leave the phone, nothing is kept), one listen after another, and
 * calls [onWake] when [HelloWakeRules.heard] finds the phrase in a partial or final result. Where it runs and when is the
 * core's [HelloWakeRules.listens] (the stand, charging, outside quiet hours, switched on, the microphone allowed, no
 * conversation running); this only drives the recogniser. Errors back off ([HelloWakeRules.restartDelayMs]) and, after
 * too many in a row, it rests until it is started again. Main thread only.
 */
class HelloWake(private val context: Context, private val onRest: () -> Unit = {}, private val onWake: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var errors = 0

    fun start() {
        if (running) return
        if (!available(context)) return
        running = true
        errors = 0
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
    }

    private fun listen() {
        if (!running) return
        val r = recognizer ?: runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?.also { recognizer = it } ?: return rest()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) { heardIn(partialResults) }
            override fun onResults(results: Bundle?) {
                if (heardIn(results)) return
                errors = 0
                again()
            }
            override fun onError(error: Int) {
                // Silence and "nothing matched" are a quiet room, not a fault.
                val quiet = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH
                errors = if (quiet) 0 else errors + 1
                again()
            }
        })
        runCatching { r.startListening(PhoneListen.intent(ListenEngine.ON_DEVICE)) }.onFailure { errors++; again() }
    }

    /** True when the phrase was heard: listening stops and [onWake] runs. */
    private fun heardIn(bundle: Bundle?): Boolean {
        if (!running) return true
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!HelloWakeRules.heard(text)) return false
        stop()
        onWake()
        return true
    }

    private fun again() {
        if (!running) return
        val delay = HelloWakeRules.restartDelayMs(errors) ?: return rest()
        main.postDelayed({ listen() }, delay)
    }

    /** Too many errors in a row, or no recogniser: stop until started again, and say it isn't listening. */
    private fun rest() {
        stop()
        onRest()
    }

    companion object {
        /** On-device English speech is installed (Android 12+ offers it; the language pack may still be missing). */
        fun available(context: Context): Boolean = runCatching { PhoneListen.onDeviceAvailable(context) }.getOrDefault(false)
    }
}

/**
 * Runs a [HelloWake] while [active] (the caller's [HelloWakeRules.listens]); returns whether it is listening now, for
 * the "Listening for “Hello Navilife”" line beside the orb. Stops when [active] turns false or the screen goes.
 */
@Composable
fun rememberHelloWake(active: Boolean, onWake: () -> Unit): Boolean {
    val context = LocalContext.current
    val wake by rememberUpdatedState(onWake)
    var listening by remember { mutableStateOf(false) }
    DisposableEffect(active, context) {
        val w = HelloWake(context, onRest = { listening = false }) { listening = false; wake() }
        if (active && HelloWake.available(context)) {
            w.start()
            listening = true
        } else {
            listening = false
        }
        onDispose {
            w.stop()
            listening = false
        }
    }
    return listening
}
