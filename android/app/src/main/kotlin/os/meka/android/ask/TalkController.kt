package os.meka.android.ask

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import os.meka.core.domain.AskCard
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.TalkDid
import os.meka.core.domain.TalkEffect
import os.meka.core.domain.TalkFlow
import os.meka.core.domain.TalkOrb
import os.meka.core.domain.TalkPhase
import os.meka.core.domain.TalkProblem
import os.meka.core.domain.TalkSession
import os.meka.core.domain.TalkStep
import os.meka.core.domain.TalkVoice
import os.meka.core.domain.VoiceCandidate
import os.meka.core.facade.MekaCore
import java.util.Locale

/**
 * Talk to MEKA on the Fold (build plan V1, voice slice 2): runs the core's [TalkFlow] with the phone's own speech.
 *
 * Listening uses Android's **on-device** recogniser only (`createOnDeviceSpeechRecognizer`): nothing is recorded, kept
 * or sent away as audio, and a phone without one says so ([TalkProblem.NO_ON_DEVICE]) rather than falling back to a
 * cloud recogniser. Speaking uses `TextToSpeech` with the best installed British voice that speaks on the device
 * ([TalkVoice.best]; never a network voice). Every change still goes through `MekaCore.doTalk` exactly as tapping a card
 * would, and its undo bar rises. Tapping the orb while MEKA speaks stops it and listens (barge-in by touch: the phone's
 * recogniser can't listen over its own speaker without hearing MEKA); tapping it otherwise ends the conversation.
 *
 * Everything here runs on the main thread (the recogniser requires it); the speech engine's callbacks are posted back.
 */
class TalkController(
    private val context: Context,
    private val core: MekaCore,
    private val scope: CoroutineScope,
    /** An answer arrived: Ask shows it (the words and the cards, which can still be tapped). */
    private val onAnswer: (question: String, outcome: AskOutcome) -> Unit,
    /** A spoken yes did [cards]: Ask folds them away and raises one undo bar. */
    private val onDid: (did: TalkDid, cards: List<AskCard>) -> Unit,
) {
    var phase by mutableStateOf(TalkPhase.ENDED)
        private set
    /** Meka's voice level, 0 … 1, smoothed ([TalkOrb.smooth]). */
    var level by mutableFloatStateOf(0f)
        private set
    /** The live transcript while listening, then the question as heard. */
    var heard by mutableStateOf("")
        private set
    /** What MEKA last said aloud. */
    var said by mutableStateOf("")
        private set
    /** Why MEKA couldn't listen, shown under the orb until the next start. */
    var problem by mutableStateOf<TalkProblem?>(null)
        private set

    private var session: TalkSession = TalkSession(TalkPhase.ENDED)
    /** Bumped by every start and stop, so a late answer from an earlier conversation changes nothing. */
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var waitingToSay: String? = null
    private var utterance = 0

    val active: Boolean get() = phase != TalkPhase.ENDED

    /** Starts a conversation (the mic permission is already granted). */
    fun start() {
        if (active) return
        generation++
        problem = null
        heard = ""
        said = ""
        ensureTts()
        apply(TalkFlow.start())
    }

    /** The orb tapped: while MEKA speaks, stop and listen; otherwise end. */
    fun tapOrb() {
        when (phase) {
            TalkPhase.SPEAKING -> apply(TalkFlow.bargeIn(session))
            TalkPhase.ENDED -> start()
            else -> stop()
        }
    }

    /** The screen left, the app went to the background, a call: everything stops at once. */
    fun stop() {
        if (!active) return
        generation++
        apply(TalkFlow.stop(session))
    }

    /** Meka said no to the microphone: say why MEKA can't listen. */
    fun refused() { problem = TalkProblem.NO_PERMISSION }

    /** A card tapped by hand while talking: it is no longer on offer aloud. */
    fun cardTapped(card: AskCard) {
        session = session.copy(pending = session.pending - card)
    }

    /** Ask left the screen for good: free the recogniser and the speech engine. */
    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun apply(step: TalkStep) {
        session = step.session
        phase = session.phase
        step.effects.forEach(::run)
    }

    private fun run(effect: TalkEffect) {
        when (effect) {
            TalkEffect.Listen -> listen()
            is TalkEffect.Ask -> {
                heard = effect.question
                level = 0f
                val gen = generation
                scope.launch {
                    val out = core.talk(effect.question, effect.history)
                    if (gen != generation) return@launch
                    onAnswer(effect.question, out)
                    apply(TalkFlow.answered(session, effect.question, out, core.todayEpochDay()))
                }
            }
            is TalkEffect.Do -> {
                val gen = generation
                scope.launch {
                    val did = core.doTalk(effect.cards)
                    onDid(did, effect.cards) // done is done: the undo bar rises even if the talk was stopped meanwhile
                    if (gen != generation) return@launch
                    apply(TalkFlow.did(session, did.done, did.lines, did.failed, core.todayEpochDay()))
                }
            }
            is TalkEffect.Speak -> {
                said = effect.text
                say(effect.text)
            }
            TalkEffect.StopSpeaking -> {
                waitingToSay = null
                utterance++
                tts?.stop()
            }
            TalkEffect.End -> {
                recognizer?.cancel()
                waitingToSay = null
                utterance++
                tts?.stop()
                level = 0f
            }
        }
    }

    // ---- Listening (on the device only) ----

    private fun fail(p: TalkProblem) {
        problem = p
        generation++
        apply(TalkFlow.stop(session))
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun listen() {
        val r = recognizer ?: run {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return fail(TalkProblem.NO_ON_DEVICE)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        }
        heard = ""
        level = 0f
        try {
            r.startListening(recognitionIntent())
        } catch (e: Exception) {
            fail(TalkProblem.FAILED)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (phase == TalkPhase.LISTENING) level = TalkOrb.smooth(level, TalkOrb.level(rmsdB))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { level = 0f }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (phase != TalkPhase.LISTENING) return
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { heard = it }
        }

        override fun onResults(results: Bundle?) {
            if (phase != TalkPhase.LISTENING) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) heard = text
            apply(TalkFlow.heard(session, text))
        }

        override fun onError(error: Int) {
            if (phase != TalkPhase.LISTENING) return // a cancel after stopping reports ERROR_CLIENT
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> apply(TalkFlow.silence(session))
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(TalkProblem.NO_PERMISSION)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> fail(TalkProblem.BUSY)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // Ask the phone to fetch English for on-device speech (Android 13+), and say so.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try { recognizer?.triggerModelDownload(recognitionIntent()) } catch (_: Exception) {}
                    }
                    fail(TalkProblem.LANGUAGE_MISSING)
                }
                else -> fail(TalkProblem.FAILED)
            }
        }
    }

    // ---- Speaking (on the device only) ----

    private fun ensureTts() {
        if (tts != null || ttsFailed) return
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val engine = tts ?: return@post
                if (status != TextToSpeech.SUCCESS) {
                    ttsFailed = true
                    waitingToSay?.let { waitingToSay = null; finishedSaying(utterance) }
                    return@post
                }
                engine.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                engine.setLanguage(Locale.UK)
                chooseVoice(engine)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { finishedSaying(utteranceId?.toIntOrNull()) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { main.post { finishedSaying(utteranceId?.toIntOrNull()) } }
                    override fun onError(utteranceId: String?, errorCode: Int) { main.post { finishedSaying(utteranceId?.toIntOrNull()) } }
                })
                ttsReady = true
                waitingToSay?.let { waitingToSay = null; say(it) }
            }
        }
    }

    private fun chooseVoice(engine: TextToSpeech) {
        val voices = try { engine.voices.orEmpty() } catch (_: Exception) { emptySet() }
        val best = TalkVoice.best(voices.map { v ->
            VoiceCandidate(
                name = v.name, language = v.locale.toLanguageTag(), quality = v.quality,
                needsNetwork = v.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
            )
        }) ?: return
        voices.firstOrNull { it.name == best.name }?.let { engine.setVoice(it) }
    }

    private fun say(text: String) {
        val id = ++utterance
        val engine = tts
        when {
            ttsFailed || engine == null -> main.post { finishedSaying(id) } // the words are on screen; carry on
            !ttsReady -> waitingToSay = text
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id.toString()) == TextToSpeech.ERROR -> main.post { finishedSaying(id) }
        }
    }

    /** The line [id] finished (or couldn't be said): listen again, unless it was cut short since. */
    private fun finishedSaying(id: Int?) {
        if (id != utterance || phase != TalkPhase.SPEAKING) return
        apply(TalkFlow.spoke(session))
    }
}
