package os.meka.android.ask

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.core.domain.BargeInOutput
import os.meka.core.domain.BargeInRules
import os.meka.core.domain.LiveTalkRules
import os.meka.core.domain.LiveToolRules
import os.meka.core.facade.MekaCore
import os.meka.core.wire.LiveCodec
import os.meka.core.wire.LiveProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Ada's Live mode on the Fold, slice 2: one real-time Gemini Live conversation.
 *
 * Navilife's server mints a one-session token ([MekaCore.liveSession]; the Gemini key never reaches the phone), and the
 * phone talks to Google's Live WebSocket with it: the microphone goes up in 40 ms chunks (16 kHz, through the voice-call
 * path with the echo canceller and noise suppressor where the phone has them), Ada's voice comes back at 24 kHz and
 * plays as it arrives; when he talks over her Google says so and what is still queued is dropped at once. On the
 * phone's own speaker, while she speaks only his voice clearly over hers goes up ([LiveTalkRules.sendWhileSpeaking]).
 * Nothing is recorded or kept; only the seconds heard and spoken are reported back ([MekaCore.liveUsage]).
 *
 * If the session isn't set up within [LiveTalkRules.FALLBACK_MS] (no key, the month's cap, Google refusing, no
 * signal), [Listener.onEnded] says fell back and Talk carries on as normal Ada. Callbacks arrive on the main thread.
 */
class LiveTalk(private val context: Context, private val core: MekaCore, private val scope: CoroutineScope, private val listener: Listener) {
    interface Listener {
        /** Google set the session up: he can talk. */
        fun onReady()
        /** Ada started or stopped speaking. */
        fun onSpeaking(speaking: Boolean)
        /** The orb's level, 0 … 1: his voice while he talks, hers while she speaks. */
        fun onLevel(level: Float)
        /** The conversation ended; [fellBack] when Live never opened and normal Ada should answer instead. */
        fun onEnded(fellBack: Boolean)
        /** Slice 4: this turn's words so far, his ([heard]) and Ada's ([said]), from Google's transcripts. */
        fun onWords(heard: String, said: String) {}
        /** Slice 4: a turn finished: his words and Ada's answer with the pages she looked up, for Ask to show. */
        fun onTurn(question: String, answer: os.meka.core.domain.AskOutcome) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { HttpClient(OkHttp) { install(WebSockets) } }

    /** Bumped by every start and end, so a late callback from an earlier session changes nothing. */
    @Volatile private var generation = 0
    @Volatile var running = false
        private set
    @Volatile private var setUp = false
    @Volatile private var playUntil = 0L
    @Volatile private var speakingNow = false
    @Volatile private var output = BargeInOutput.SPEAKER
    private var session: LiveCodec.Session? = null
    private val jobs = mutableListOf<Job>()
    private var outgoing: Channel<String>? = null
    @Volatile private var inBytes = 0L
    @Volatile private var outBytes = 0L
    private var record: AudioRecord? = null
    private var effects: List<android.media.audiofx.AudioEffect> = emptyList()
    private var reader: Thread? = null
    private var track: AudioTrack? = null
    private var player: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    // Slice 4: the turn's transcripts and the pages its searches found (main thread only).
    private val heardNow = StringBuilder()
    private val saidNow = StringBuilder()
    private val pagesNow = mutableListOf<Pair<String, String>>()
    /** Slice 5: the cards Ada offered this turn (Ask's own, through the policy gate), shown with her words. */
    private val cardsNow = mutableListOf<os.meka.core.domain.AskCard>()

    /** Starts a Live conversation (the microphone is already allowed). */
    fun start() {
        if (running) return
        val gen = ++generation
        running = true
        setUp = false
        inBytes = 0
        outBytes = 0
        playUntil = 0
        speakingNow = false
        output = TalkBargeIn.output(context)
        val askedAt = SystemClock.elapsedRealtime()
        jobs += scope.launch {
            delay(LiveTalkRules.FALLBACK_MS)
            if (gen == generation && LiveTalkRules.fallBack(askedAt, SystemClock.elapsedRealtime(), setUp)) end(gen, fellBack = true)
        }
        jobs += scope.launch {
            val voice = runCatching { core.liveVoice() }.getOrNull()
            val s = core.liveSession(voice)
            if (gen != generation) return@launch
            if (s == null || s.state != LiveCodec.Session.READY || s.token == null || s.url == null || s.model == null) {
                end(gen, fellBack = true)
                return@launch
            }
            session = s
            val out = Channel<String>(Channel.BUFFERED)
            outgoing = out
            val endAt = LiveTalkRules.endAtElapsed(SystemClock.elapsedRealtime(), System.currentTimeMillis(), s.expiresAtMs ?: 0L)
            jobs += scope.launch(Dispatchers.IO) { connect(gen, s, out) }
            jobs += scope.launch {
                // The token's window: end a little before it closes, never mid-word. Meanwhile, Ada's speaking state.
                while (gen == generation) {
                    val now = SystemClock.elapsedRealtime()
                    if (setUp && now >= endAt) { end(gen, fellBack = false); return@launch }
                    val speaking = LiveTalkRules.speaking(playUntil, now)
                    if (speaking != speakingNow) {
                        speakingNow = speaking
                        listener.onSpeaking(speaking)
                    }
                    delay(TICK_MS)
                }
            }
        }
    }

    /** Ends the conversation at once (the orb tapped, the screen left). */
    fun stop() {
        if (running) end(generation, fellBack = false)
    }

    /** Frees everything for good. */
    fun release() {
        stop()
        runCatching { client.close() }
    }

    private suspend fun connect(gen: Int, s: LiveCodec.Session, out: Channel<String>) {
        runCatching {
            client.webSocket(LiveProtocol.connectUrl(s.url!!, s.token!!)) {
                send(Frame.Text(LiveProtocol.setup(s.model!!, s.voice ?: LiveTalkRules.voice(null))))
                val sender = launch {
                    for (msg in out) send(Frame.Text(msg))
                }
                try {
                    for (frame in incoming) {
                        if (gen != generation) break
                        val text = when (frame) {
                            is Frame.Text -> frame.readText()
                            is Frame.Binary -> frame.data.decodeToString()
                            else -> continue
                        }
                        LiveProtocol.decode(text).forEach { handle(gen, it) }
                    }
                } finally {
                    sender.cancel()
                }
            }
        }
        // The socket closed: Google ended the session, the signal went, or it never opened.
        main.post { if (gen == generation) end(gen, fellBack = !setUp) }
    }

    private fun handle(gen: Int, e: LiveProtocol.Event) {
        when (e) {
            LiveProtocol.Event.SetupComplete -> main.post {
                if (gen != generation || setUp) return@post
                setUp = true
                openPlayer(gen)
                openMic(gen)
                listener.onReady()
            }
            is LiveProtocol.Event.Audio -> e.chunks.forEach { b64 ->
                val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return@forEach
                if (gen != generation) return
                outBytes += pcm.size
                playUntil = LiveTalkRules.playUntil(playUntil, SystemClock.elapsedRealtime(), pcm.size)
                queue.offer(pcm)
            }
            LiveProtocol.Event.Interrupted -> {
                // He talked over her: drop what's queued and what the speaker still holds.
                queue.clear()
                playUntil = 0
                track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
            }
            LiveProtocol.Event.TurnComplete -> main.post { if (gen == generation) finishTurn() }
            is LiveProtocol.Event.Heard -> main.post {
                if (gen != generation) return@post
                heardNow.append(e.text)
                listener.onWords(heardNow.toString().trim(), saidNow.toString().trim())
            }
            is LiveProtocol.Event.Said -> main.post {
                if (gen != generation) return@post
                saidNow.append(e.text)
                listener.onWords(heardNow.toString().trim(), saidNow.toString().trim())
            }
            is LiveProtocol.Event.ToolCall -> scope.launch { answerTool(gen, e) }
            is LiveProtocol.Event.GoAway -> main.post { if (gen == generation) end(gen, fellBack = false) }
            is LiveProtocol.Event.Resumable -> Unit // a longer conversation resumes with it in a later slice
        }
    }

    /**
     * Slice 4: Ada asked Navilife to search the web. The core checks the query (never his own life: refused on the
     * phone) and Navilife's server searches it; the results go back to Google as data, and their pages become the
     * turn's sources in Ask. Anything else is answered with a refusal the model can act on.
     */
    private suspend fun answerTool(gen: Int, call: LiveProtocol.Event.ToolCall) {
        val id = session?.sessionId
        val reply = if (call.name == LiveToolRules.LOOK_UP_TOOL) {
            // Slice 5: another day, read here from his own calendar and tasks (nothing of it goes to Navilife's server).
            val lines = withContext(Dispatchers.IO) { core.liveLookUp(call.args[LiveToolRules.WHEN_ARG].orEmpty()) }
            LiveProtocol.toolAnswer(call.id, call.name, lines)
        } else if (call.name == LiveToolRules.PROPOSE_TOOL) {
            // Slice 5: one of Ask's actions, as Ask's card through the policy gate; it waits for his tap.
            if (cardsNow.size >= LiveToolRules.MAX_CARDS) LiveProtocol.toolAnswer(call.id, call.name, null, error = LiveToolRules.PROPOSE_FULL)
            else {
                val offer = withContext(Dispatchers.IO) { core.livePropose(call.args) }
                val card = offer.card
                if (card != null && gen == generation) cardsNow += card
                if (card != null) LiveProtocol.toolAnswer(call.id, call.name, offer.reply)
                else LiveProtocol.toolAnswer(call.id, call.name, null, error = offer.reply)
            }
        } else if (call.name != LiveTalkRules.SEARCH_TOOL || id == null) {
            LiveProtocol.toolResponse(call.id, call.name, emptyList(), error = "Navilife has no such tool.")
        } else {
            val r = withContext(Dispatchers.IO) { core.liveSearch(id, call.query) }
            when (r.state) {
                LiveCodec.SearchResult.FOUND -> {
                    if (gen == generation) pagesNow += r.hits.map { it.title to it.url }
                    LiveProtocol.toolResponse(call.id, call.name, r.hits.map { h ->
                        LiveProtocol.Hit(os.meka.core.domain.LiveFactsRules.site(h.url).orEmpty(), h.title, h.text)
                    })
                }
                LiveCodec.SearchResult.REFUSED -> LiveProtocol.toolResponse(call.id, call.name, emptyList(), error = LiveTalkRules.SEARCH_REFUSED)
                else -> LiveProtocol.toolResponse(call.id, call.name, emptyList(), error = LiveTalkRules.SEARCH_FAILED)
            }
        }
        if (gen == generation) outgoing?.trySend(reply)
    }

    /** Slice 4: the turn as Ask shows it (her words and sources), then a clean slate for the next. Main thread. */
    private fun finishTurn() {
        val sources = os.meka.core.domain.LiveFactsRules.sources(pagesNow)
        LiveToolRules.turnAnswer(saidNow.toString(), sources, cardsNow.toList())?.let { listener.onTurn(LiveTalkRules.turnQuestion(heardNow.toString()), it) }
        heardNow.clear()
        saidNow.clear()
        pagesNow.clear()
        cardsNow.clear()
    }

    @SuppressLint("MissingPermission") // a conversation only runs once the microphone is allowed
    private fun openMic(gen: Int) {
        val rate = LiveTalkRules.IN_RATE
        val chunk = LiveTalkRules.CHUNK_SAMPLES
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 2 * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            end(gen, fellBack = true)
            return
        }
        effects = buildList<android.media.audiofx.AudioEffect> {
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
        }
        if (runCatching { rec.startRecording() }.isFailure) {
            rec.release()
            end(gen, fellBack = true)
            return
        }
        record = rec
        val out = outgoing
        reader = Thread({
            val buf = ShortArray(chunk)
            val bytes = ByteArray(chunk * 2)
            val silence = ByteArray(chunk * 2)
            while (gen == generation) {
                val n = try { rec.read(buf, 0, chunk) } catch (e: Exception) { -1 }
                if (n <= 0) break
                val db = BargeInRules.dbfs(buf, n)
                val speaking = LiveTalkRules.speaking(playUntil, SystemClock.elapsedRealtime())
                val send = !speaking || LiveTalkRules.sendWhileSpeaking(output, db)
                val len = n * 2
                if (send) {
                    for (i in 0 until n) {
                        bytes[2 * i] = (buf[i].toInt() and 0xff).toByte()
                        bytes[2 * i + 1] = ((buf[i].toInt() shr 8) and 0xff).toByte()
                    }
                }
                val data = Base64.encodeToString(if (send) bytes else silence, 0, len, Base64.NO_WRAP)
                inBytes += len
                out?.trySend(LiveProtocol.audio(data))
                if (!speaking) main.post { if (gen == generation) listener.onLevel(LiveTalkRules.level(db)) }
            }
        }, "navilife-live-mic").also { it.start() }
    }

    private fun openPlayer(gen: Int) {
        val rate = LiveTalkRules.OUT_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
                .build()
        }.getOrNull() ?: return
        runCatching { t.play() }
        track = t
        player = Thread({
            while (gen == generation) {
                val pcm = try { queue.poll(TICK_MS, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null } ?: continue
                if (gen != generation) break
                // Her level for the orb, from the chunk about to play.
                val samples = ShortArray(pcm.size / 2) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }
                val level = LiveTalkRules.level(BargeInRules.dbfs(samples, samples.size))
                main.post { if (gen == generation) listener.onLevel(level) }
                runCatching { t.write(pcm, 0, pcm.size) }
            }
        }, "navilife-live-voice").also { it.start() }
    }

    private fun end(gen: Int, fellBack: Boolean) {
        if (gen != generation || !running) return
        generation++
        running = false
        val pending = jobs.toList()
        jobs.clear()
        pending.forEach { it.cancel() }
        outgoing?.close()
        outgoing = null
        record?.let { r -> runCatching { r.stop() } }
        runCatching { reader?.join(JOIN_MS) }
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
        record?.let { runCatching { it.release() } }
        record = null
        reader = null
        queue.clear()
        runCatching { player?.join(JOIN_MS) }
        player = null
        track?.let { t -> runCatching { t.pause(); t.flush(); t.release() } }
        track = null
        playUntil = 0
        speakingNow = false
        // Slice 4: a turn cut short by the end still shows what Ada said.
        finishTurn()
        // The seconds heard and spoken (nothing of what was said), so the month's count drops from the reserve.
        session?.sessionId?.let { id ->
            val heard = LiveTalkRules.seconds(inBytes, LiveTalkRules.IN_RATE)
            val spoke = LiveTalkRules.seconds(outBytes, LiveTalkRules.OUT_RATE)
            scope.launch { withContext(Dispatchers.IO) { core.liveUsage(id, heard, spoke) } }
        }
        session = null
        listener.onEnded(fellBack)
    }

    private companion object {
        const val TICK_MS = 100L
        const val JOIN_MS = 150L
    }
}
