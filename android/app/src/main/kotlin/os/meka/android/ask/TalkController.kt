package os.meka.android.ask

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import os.meka.core.domain.AskCard
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.SpeechRules
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
import kotlin.coroutines.resume

/**
 * Talk to MEKA on the Fold (build plan V1, voice slice 2): runs the core's [TalkFlow] with the phone's own speech.
 *
 * Listening uses Android's **on-device** recogniser only (`createOnDeviceSpeechRecognizer`): nothing is recorded, kept
 * or sent away as audio, and a phone without one says so ([TalkProblem.NO_ON_DEVICE]) rather than falling back to a
 * cloud recogniser. Speaking uses MEKA's voice (Amazon Polly through MEKA's own server, [MekaCore.speechClip]: only
 * MEKA's own words are sent, never what Meka said), piece by piece, and falls back to `TextToSpeech` with the best
 * installed British voice that speaks on the device ([TalkVoice.best]; never a network voice) when MEKA's voice is off,
 * used up for the month, offline or slow. Every change still goes through `MekaCore.doTalk` exactly as tapping a card
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
    /** Completes with the phone's engine once it's ready, or null when it can't speak. */
    private var ttsReady: CompletableDeferred<TextToSpeech?>? = null
    /** The phone's lines being said, by utterance id, completed when each is over. */
    private val deviceLines = HashMap<String, CompletableDeferred<Unit>>()
    private var deviceLine = 0
    /** Bumped by every line and every stop, so a line that was cut short doesn't move the conversation on. */
    private var utterance = 0
    private var speaking: Job? = null
    private var player: MediaPlayer? = null

    val active: Boolean get() = phase != TalkPhase.ENDED

    /** Starts a conversation (the mic permission is already granted). */
    fun start() {
        if (active) return
        generation++
        problem = null
        heard = ""
        said = ""
        ensureTts()
        scope.launch { core.warmVoice() } // MEKA's common lines in its voice, fetched once
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
        ttsReady = null
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
            TalkEffect.StopSpeaking -> stopSaying()
            TalkEffect.End -> {
                recognizer?.cancel()
                stopSaying()
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

    // ---- Speaking: MEKA's voice from MEKA's own server, else the phone's own ----

    /**
     * Says [text]: piece by piece ([SpeechRules.pieces]) in MEKA's voice (Polly, through MEKA's server; only these
     * words are sent), fetching the next piece while one plays; the phone's own voice says whatever is left when a
     * piece doesn't come in time ([SpeechRules.FIRST_AUDIO_MS] for the first), the server refuses, or a clip won't play.
     */
    private fun say(text: String) {
        stopSaying()
        val id = ++utterance
        speaking = scope.launch {
            val pieces = SpeechRules.pieces(text)
            var i = 0
            var next: Deferred<String?>? = pieces.firstOrNull()?.let { p -> async { core.speechClip(p, first = true) } }
            while (i < pieces.size) {
                val clip = next?.await() ?: break
                next = pieces.getOrNull(i + 1)?.let { p -> async { core.speechClip(p, first = false) } }
                if (!play(clip)) break
                i++
            }
            next?.cancel()
            if (i < pieces.size) sayOnDevice(pieces.drop(i).joinToString(" "))
            finishedSaying(id)
        }
    }

    /** Stops whatever is being said, in either voice. */
    private fun stopSaying() {
        utterance++
        speaking?.cancel()
        speaking = null
        player?.let { releasePlayer(it) }
        tts?.stop()
        deviceLines.values.forEach { it.complete(Unit) }
        deviceLines.clear()
    }

    /** Plays one MP3 clip (base64) to the end. False when it can't be played (the phone's voice takes over). */
    private suspend fun play(base64: String): Boolean = suspendCancellableCoroutine { cont ->
        fun done(ok: Boolean) { if (cont.isActive) cont.resume(ok) }
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: IllegalArgumentException) { null }
        if (bytes == null || bytes.isEmpty()) return@suspendCancellableCoroutine done(false)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(speechAttributes())
            mp.setDataSource(ClipSource(bytes))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { releasePlayer(mp); done(true) }
            mp.setOnErrorListener { _, _, _ -> releasePlayer(mp); done(false); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer(mp)
            done(false)
        }
        cont.invokeOnCancellation { main.post { releasePlayer(mp) } }
    }

    private fun releasePlayer(mp: MediaPlayer) {
        if (player === mp) player = null
        try { mp.release() } catch (_: Exception) {}
    }

    /** Says [text] with the phone's own voice and waits until it has been said (or couldn't be). */
    private suspend fun sayOnDevice(text: String) {
        val engine = ensureTts().await() ?: return // no voice on the phone: the words are on screen; carry on
        val id = "u" + (++deviceLine)
        val done = CompletableDeferred<Unit>()
        deviceLines[id] = done
        try {
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.ERROR) return
            done.await()
        } finally {
            deviceLines.remove(id)
        }
    }

    private fun speechAttributes(): AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** The phone's speech engine, once it is ready (null when it can't start). Started with the conversation. */
    private fun ensureTts(): CompletableDeferred<TextToSpeech?> {
        ttsReady?.let { return it }
        val ready = CompletableDeferred<TextToSpeech?>()
        ttsReady = ready
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                val engine = tts
                if (engine == null || status != TextToSpeech.SUCCESS) {
                    ready.complete(null)
                    return@post
                }
                engine.setAudioAttributes(speechAttributes())
                engine.setLanguage(Locale.UK)
                chooseVoice(engine)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { lineSaid(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { lineSaid(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { lineSaid(utteranceId) }
                })
                ready.complete(engine)
            }
        }
        return ready
    }

    /** The speech engine's callback (on its own thread): the line [id] is over. */
    private fun lineSaid(id: String?) {
        main.post { id?.let { deviceLines.remove(it)?.complete(Unit) } }
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

    /** The line [id] finished (or couldn't be said): listen again, unless it was cut short since. */
    private fun finishedSaying(id: Int) {
        if (id != utterance || phase != TalkPhase.SPEAKING) return
        apply(TalkFlow.spoke(session))
    }
}

/** One clip's bytes for [MediaPlayer], straight from memory (nothing written to storage). */
private class ClipSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()
    override fun close() {}
}
