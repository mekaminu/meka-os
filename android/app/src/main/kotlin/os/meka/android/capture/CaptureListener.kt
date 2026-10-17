package os.meka.android.capture

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.ask.PhoneListen
import os.meka.core.domain.TalkOrb
import os.meka.core.domain.ListenEngine
import os.meka.core.domain.ListenRules
import os.meka.core.domain.ListenStep
import os.meka.core.domain.TalkEndpoint

/**
 * The capture sheet's 🎙 listening inside the sheet ("the mic should just add it, fast, and understand him", step 3;
 * was Android's own recogniser dialog): [partial] shows his words as he speaks, listening ends 0.8 s after his last
 * word or sound ([TalkEndpoint]), Google's online speech with signal and the phone's own without ([PhoneListen]; a
 * network failure tries the phone's own once). [onHeard] gets the words, the recogniser that heard them and when his
 * words ended (elapsed ms), for Activity's "Speech to Added" line; [onNothing] gets a line to show (null: nothing
 * heard, nothing to say). Nothing is recorded or kept. Main thread only.
 */
class CaptureListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHeard: (words: String, engine: ListenEngine, endedAtMs: Long) -> Unit,
    private val onNothing: (line: String?) -> Unit,
) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var engine by mutableStateOf<ListenEngine?>(null)
        private set
    /** The microphone's level, 0 … 1, for the breathing dot. */
    var level by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L
    private var lastWordsAt = 0L
    private var lastVoiceAt: Long? = null
    private var endedAt: Long? = null
    private var endpoint: Job? = null

    fun start(force: ListenEngine? = null) {
        release()
        val want = force ?: PhoneListen.choose(context) ?: return onNothing(os.meka.core.domain.TalkProblem.NO_ON_DEVICE.line)
        val r = try {
            PhoneListen.create(context, want).also { it.setRecognitionListener(listener) }
        } catch (_: Exception) {
            return onNothing(os.meka.core.domain.TalkProblem.FAILED.line)
        }
        recognizer = r
        engine = want
        partial = ""
        level = 0f
        val now = SystemClock.elapsedRealtime()
        startedAt = now
        lastWordsAt = now
        lastVoiceAt = null
        endedAt = null
        listening = true
        try {
            r.startListening(PhoneListen.intent(want))
        } catch (_: Exception) {
            release()
            return onNothing(os.meka.core.domain.TalkProblem.FAILED.line)
        }
        endpoint = scope.launch {
            while (listening) {
                delay(TalkEndpoint.TICK_MS)
                if (!listening) return@launch
                val t = SystemClock.elapsedRealtime()
                when (TalkEndpoint.step(startedAt, partial.isNotBlank(), ListenRules.lastSound(lastWordsAt, lastVoiceAt), t)) {
                    ListenStep.FINISH -> { finishNow(t); return@launch }
                    ListenStep.SILENCE -> { release(); onNothing(null); return@launch }
                    ListenStep.KEEP -> {}
                }
            }
        }
    }

    /** The 🎙 tapped again while listening: what he has said so far is taken now. */
    fun finishNow(at: Long = SystemClock.elapsedRealtime()) {
        if (!listening) return
        if (endedAt == null) endedAt = at
        endpoint?.cancel()
        runCatching { recognizer?.stopListening() } // the words come in onResults
    }

    /** Stops without taking anything (the sheet left). */
    fun release() {
        listening = false
        endpoint?.cancel()
        endpoint = null
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
        level = 0f
    }

    private fun done(text: String) {
        val by = engine ?: ListenEngine.ON_DEVICE
        val at = endedAt ?: SystemClock.elapsedRealtime()
        val r = recognizer
        recognizer = null
        listening = false
        endpoint?.cancel()
        level = 0f
        // Destroying inside the recogniser's own callback is allowed but noisy on some builds: do it after.
        r?.let { android.os.Handler(context.mainLooper).post { runCatching { it.destroy() } } }
        if (text.isBlank()) onNothing(null) else onHeard(text, by, at)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (!listening) return
            val l = TalkOrb.level(rmsdB)
            if (ListenRules.isVoice(l)) lastVoiceAt = SystemClock.elapsedRealtime()
            level = TalkOrb.smooth(level, l)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (endedAt == null) endedAt = SystemClock.elapsedRealtime()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let {
                    if (it != partial) lastWordsAt = SystemClock.elapsedRealtime()
                    partial = it
                }
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            done(text.ifBlank { partial })
        }

        override fun onError(error: Int) {
            if (!listening) return // a cancel after stopping reports ERROR_CLIENT
            val current = engine
            val heardSoFar = partial
            if (current != null && heardSoFar.isBlank()) {
                ListenRules.fallback(current, PhoneListen.networkFailure(error), PhoneListen.onDeviceAvailable(context))
                    ?.let { start(force = it); return }
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> done(heardSoFar)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { release(); onNothing(os.meka.core.domain.TalkProblem.NO_PERMISSION.line) }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { release(); onNothing(os.meka.core.domain.TalkProblem.BUSY.line) }
                else -> if (heardSoFar.isNotBlank()) done(heardSoFar) else { release(); onNothing(os.meka.core.domain.TalkProblem.FAILED.line) }
            }
        }
    }
}
