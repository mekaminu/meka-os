package os.meka.android.ask

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import os.meka.core.domain.SpeechRules
import os.meka.core.domain.TalkVoice
import os.meka.core.domain.VoiceCandidate
import os.meka.core.facade.MekaCore
import java.util.Locale
import kotlin.coroutines.resume

/**
 * MEKA saying something aloud on the Fold (Weather and a voice, slices 6 and 8): Talk's answers and the spoken
 * morning brief. A line is said piece by piece ([SpeechRules.pieces]) in MEKA's voice (Amazon Polly through MEKA's own
 * server, [MekaCore.speechClip]: only MEKA's own words are sent), the next piece fetched while one plays, each clip
 * played from memory (nothing written to storage); the phone's own voice (`TextToSpeech`, the best installed British
 * voice that speaks on the device, [TalkVoice.best]; never a network voice) says whatever is left when a piece doesn't
 * come in time, the server refuses, or a clip won't play.
 *
 * Main thread only; the speech engine's callbacks are posted back.
 */
class MekaSpeaker(
    private val context: Context,
    private val core: MekaCore,
    private val scope: CoroutineScope,
) {
    /** Something is being said, in either voice. */
    var speaking by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    /** Completes with the phone's engine once it's ready, or null when it can't speak. */
    private var ttsReady: CompletableDeferred<TextToSpeech?>? = null
    /** The phone's lines being said, by utterance id, completed when each is over. */
    private val deviceLines = HashMap<String, CompletableDeferred<Unit>>()
    private var deviceLine = 0
    /** Bumped by every line and every stop, so a line cut short never reports that it finished. */
    private var line = 0
    private var job: Job? = null
    private var player: MediaPlayer? = null

    /** Starts the phone's own speech engine early, so a fallback line doesn't wait for it. */
    fun prepare() { ensureTts() }

    /**
     * Says [text], stopping anything already being said; [onDone] runs once it has all been said (or couldn't be),
     * never when it was stopped or replaced.
     */
    fun say(text: String, onDone: () -> Unit = {}) {
        stop()
        val id = ++line
        speaking = true
        job = scope.launch {
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
            if (id == line) {
                speaking = false
                onDone()
            }
        }
    }

    /** Stops whatever is being said, in either voice. */
    fun stop() {
        line++
        job?.cancel()
        job = null
        player?.let { releasePlayer(it) }
        tts?.stop()
        deviceLines.values.forEach { it.complete(Unit) }
        deviceLines.clear()
        speaking = false
    }

    /** The screen is gone for good: stop and free the speech engine. */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ttsReady = null
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

    /** The phone's speech engine, once it is ready (null when it can't start). */
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
