package os.meka.android.ask

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.core.domain.DeviceVoice
import os.meka.core.domain.DeviceVoiceRules
import os.meka.core.domain.DeviceVoiceSettings
import os.meka.core.domain.MekaVoiceRules
import os.meka.core.domain.PlayStep
import os.meka.core.domain.SpeechRules
import os.meka.core.domain.VoicePickerRules
import os.meka.core.facade.MekaCore
import java.util.Locale
import kotlin.coroutines.resume

/**
 * MEKA saying something aloud on the Fold (Weather and a voice, slices 6 and 8; Morning brief read aloud): Talk's
 * answers and the spoken morning brief (a long read: see [say]). A line is said piece by piece ([SpeechRules.pieces]) in MEKA's voice (Amazon Polly through MEKA's own
 * server, [MekaCore.speechClip]: only MEKA's own words are sent), the next piece fetched while one plays, each clip
 * played from memory (nothing written to storage); the phone's own voice (`TextToSpeech`, the best installed British
 * voice that speaks on the device, [DeviceVoiceRules.pick]: the one chosen in the voice picker, else MEKA's pick; never a
 * network voice; at this phone's speed and pitch, [DeviceVoiceStore]) says whatever is left when a piece doesn't come in
 * time, the server refuses, or a clip won't play.
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
    /** This phone's own voice, speed and pitch (kept on the phone only). */
    private var settings: DeviceVoiceSettings = DeviceVoiceStore.load(context)
    /** When MEKA's own voice was last heard (wall clock), so a late answer in the same conversation holds. */
    private var lastMekaVoiceMs: Long? = null

    /** Starts the phone's own speech engine early, so a fallback line doesn't wait for it. */
    fun prepare() { ensureTts() }

    /**
     * Says [text], stopping anything already being said; [onDone] runs once it has all been said (or couldn't be),
     * never when it was stopped or replaced. A long [reading] (the morning brief) waits longer for its first piece
     * ([SpeechRules.firstWaitMs]) and, when one piece is late, lets the phone say only that piece before MEKA's voice
     * carries on ([SpeechRules.onMiss]). In a conversation where MEKA's voice has been heard ([SpeechRules.holds]) a
     * late piece never switches voice at once: MEKA says "One moment…" in its own voice (from memory) and waits up to
     * [SpeechRules.HOLD_AUDIO_MS] more before the phone's voice takes over.
     */
    fun say(text: String, reading: Boolean = false, onDone: () -> Unit = {}) {
        stop()
        // The picker may have changed the phone's voice since this speaker started (prefs are read from memory).
        DeviceVoiceStore.load(context).let { if (it != settings) useDeviceVoice(it) }
        val id = ++line
        speaking = true
        job = scope.launch {
            speakNow(text, reading)
            if (id == line) {
                speaking = false
                onDone()
            }
        }
    }

    /**
     * "Play my messages" in Talk (call assistant polish 8c): says and plays the playlist's steps in order — MEKA's
     * lines as [say] would, each caller's recording fetched with [fetch] (from MEKA's server, played from memory and
     * let go), or the step's words when it can't be had or played. [onDone] runs once all of it is over, never when
     * stopped ([stop] cuts the playlist short too).
     */
    fun playlist(steps: List<PlayStep>, fetch: suspend (String) -> ByteArray?, onDone: () -> Unit = {}) {
        stop()
        DeviceVoiceStore.load(context).let { if (it != settings) useDeviceVoice(it) }
        val id = ++line
        speaking = true
        job = scope.launch {
            for (step in steps) {
                when (step) {
                    is PlayStep.Say -> speakNow(step.text, reading = false)
                    is PlayStep.Recording -> {
                        val bytes = try { fetch(step.heldId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                        if (bytes == null || !playBytes(bytes, recording = true)) speakNow(step.otherwise, reading = false)
                    }
                }
            }
            if (id == line) {
                speaking = false
                onDone()
            }
        }
    }

    /**
     * Says [text] piece by piece in MEKA's voice, the phone's own for what's left; returns once it has been said. A long
     * [reading] is cut into short pieces ([SpeechRules.pieces], at most [SpeechRules.READ_PIECE]) and asks for
     * [SpeechRules.ahead] of them at once from the start, so later ones are ready before they are needed; a late one is
     * waited for ([SpeechRules.READ_PIECE_WAIT_MS]) rather than switching voice (Meka, 2026-10-10).
     */
    private suspend fun speakNow(text: String, reading: Boolean): Unit = coroutineScope {
        val pieces = SpeechRules.pieces(text, reading)
        val ahead = SpeechRules.ahead(reading)
        val queue = ArrayDeque<Pending>()
        var asked = 0
        var held = false

        suspend fun fetch(k: Int): Pending {
            val p = pieces[k]
            val hold = SpeechRules.holds(reading, core.speechResting(), lastMekaVoiceMs, System.currentTimeMillis())
            val first = k == 0
            return Pending(async { core.speechClip(p, first, reading, hold) }, SystemClock.elapsedRealtime(), first, hold)
        }

        suspend fun topUp() {
            while (asked < pieces.size && queue.size < ahead) queue.addLast(fetch(asked++))
        }

        // A piece that may hold: its usual wait, then "One moment…" (once a line) and the rest of its budget.
        suspend fun clipOf(p: Pending): String? {
            if (!p.hold) return p.clip.await()
            val left = SpeechRules.waitMs(p.first, reading) - (SystemClock.elapsedRealtime() - p.startedAt)
            val inTime = if (left > 0) withTimeoutOrNull(left) { p.clip.await() } else null
            if (inTime != null || p.clip.isCompleted) return inTime ?: p.clip.await()
            if (!held) {
                held = true
                core.speechHoldClip()?.let { play(it) }
            }
            return p.clip.await()
        }

        var i = 0
        topUp()
        while (i < pieces.size) {
            val clip = queue.removeFirstOrNull()?.let { clipOf(it) }
            if (clip != null) lastMekaVoiceMs = System.currentTimeMillis() // heard in this conversation
            topUp()
            if (clip != null && play(clip)) {
                lastMekaVoiceMs = System.currentTimeMillis()
                i++
                continue
            }
            if (SpeechRules.onMiss(reading, core.speechResting()) == SpeechRules.Miss.REST_ON_DEVICE) break
            sayOnDevice(pieces[i])
            i++
        }
        queue.forEach { it.clip.cancel() }
        if (i < pieces.size) sayOnDevice(pieces.drop(i).joinToString(" "))
    }

    /** One piece's clip on its way: when it was asked for, whether it is the line's first, and whether it may hold. */
    private class Pending(val clip: Deferred<String?>, val startedAt: Long, val first: Boolean, val hold: Boolean)

    /**
     * ▶ Sample in the voice picker: [VoicePickerRules.SAMPLE] in the server's [voice] (a Polly name), or in the phone's
     * own voice for [MekaVoiceRules.DEVICE] or when the clip can't be had or played. Stops anything already being said.
     */
    fun sample(voice: String, onDone: () -> Unit = {}) {
        stop()
        val id = ++line
        speaking = true
        job = scope.launch {
            val clip = if (voice == MekaVoiceRules.DEVICE) null else core.speechSample(voice)
            if (clip == null || !play(clip)) sayOnDevice(VoicePickerRules.SAMPLE)
            if (id == line) {
                speaking = false
                onDone()
            }
        }
    }

    /** The phone's voices as the picker lists them (empty when its engine can't speak). */
    suspend fun deviceVoices(): List<DeviceVoice> {
        val engine = ensureTts().await() ?: return emptyList()
        return voicesOf(engine)
    }

    /** Speaks with [s] from now on (the picker saved it). */
    fun useDeviceVoice(s: DeviceVoiceSettings) {
        settings = s
        tts?.let { if (ttsReady?.isCompleted == true) applyVoice(it, s) }
    }

    /**
     * ▶ Sample on one of the phone's voices: [VoicePickerRules.SAMPLE] in that voice ([DeviceVoiceRules.AUTOMATIC]:
     * MEKA's pick) at this phone's speed and pitch. Stops anything already being said.
     */
    fun sampleOnDevice(voice: String, onDone: () -> Unit = {}) {
        stop()
        val id = ++line
        speaking = true
        job = scope.launch {
            val engine = ensureTts().await()
            if (engine != null) applyVoice(engine, settings.copy(voice = voice.ifEmpty { null }))
            try {
                sayOnDevice(VoicePickerRules.SAMPLE)
            } finally {
                if (engine != null) applyVoice(engine, settings)
            }
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
    private suspend fun play(base64: String): Boolean {
        val bytes = try { Base64.decode(base64, Base64.DEFAULT) } catch (e: IllegalArgumentException) { null }
        if (bytes == null || bytes.isEmpty()) return false
        return playBytes(bytes, recording = false)
    }

    /** Plays MP3 [bytes] from memory to the end (a caller's [recording] as speech on the media stream). */
    private suspend fun playBytes(bytes: ByteArray, recording: Boolean): Boolean = suspendCancellableCoroutine { cont ->
        fun done(ok: Boolean) { if (cont.isActive) cont.resume(ok) }
        if (bytes.isEmpty()) return@suspendCancellableCoroutine done(false)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(if (recording) recordingAttributes() else speechAttributes())
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

    private fun recordingAttributes(): AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

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
                applyVoice(engine, settings)
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

    /** The engine's voices with its label ("Speech Services by Google"). */
    private fun voicesOf(engine: TextToSpeech): List<DeviceVoice> {
        val label = try { engine.engines.firstOrNull { it.name == engine.defaultEngine }?.label.orEmpty() } catch (_: Exception) { "" }
        val voices = try { engine.voices.orEmpty() } catch (_: Exception) { emptySet() }
        return voices.map { v ->
            DeviceVoice(
                name = v.name, displayName = "", language = v.locale.toLanguageTag(), quality = v.quality, engine = label,
                needsNetwork = v.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
            )
        }
    }

    /** The chosen (else MEKA's pick of the) phone's voice, at its speed and pitch. */
    private fun applyVoice(engine: TextToSpeech, s: DeviceVoiceSettings) {
        engine.setSpeechRate(DeviceVoiceRules.rate(s.rate))
        engine.setPitch(DeviceVoiceRules.pitch(s.pitch))
        val best = DeviceVoiceRules.pick(voicesOf(engine), s) ?: return
        val voices = try { engine.voices.orEmpty() } catch (_: Exception) { emptySet() }
        voices.firstOrNull { it.name == best.name }?.let { engine.setVoice(it) }
    }
}

/** This phone's own voice, speed and pitch (Weather and a voice, slice 10): on the phone only, never synced. */
object DeviceVoiceStore {
    private const val PREFS = "meka_voice"
    private const val KEY = "device_voice"

    fun load(context: Context): DeviceVoiceSettings = DeviceVoiceRules.decode(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
    )

    fun save(context: Context, s: DeviceVoiceSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, DeviceVoiceRules.encode(s)).apply()
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
