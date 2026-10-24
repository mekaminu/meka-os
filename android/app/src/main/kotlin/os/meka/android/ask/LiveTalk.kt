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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.core.domain.BargeInOutput
import os.meka.core.domain.BargeInRules
import os.meka.core.domain.LiveHandOnRules
import os.meka.core.domain.LiveTalkRules
import os.meka.core.domain.LiveToolRules
import os.meka.core.facade.MekaCore
import os.meka.core.wire.LiveCodec
import os.meka.core.wire.LiveProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Obi's Live mode on the Fold, slice 2: one real-time Gemini Live conversation.
 *
 * Navilife's server mints a one-session token ([MekaCore.liveSession]; the Gemini key never reaches the phone), and the
 * phone talks to Google's Live WebSocket with it: the microphone goes up in 40 ms chunks (16 kHz, through the voice-call
 * path with the echo canceller and noise suppressor where the phone has them), Obi's voice comes back at 24 kHz and
 * plays as it arrives; when Meka talks over him Google says so and what is still queued is dropped at once. On the
 * phone's own speaker, while Obi speaks only Meka's voice clearly over his goes up ([LiveTalkRules.sendWhileSpeaking]).
 * Nothing is recorded or kept; only the seconds heard and spoken are reported back ([MekaCore.liveUsage]).
 *
 * If the session isn't set up within [LiveTalkRules.FALLBACK_MS] (no key, the month's cap, Google refusing, no
 * signal), [Listener.onEnded] says fell back and Talk carries on as normal Obi. Callbacks arrive on the main thread.
 *
 * Slice 2c: a session can be asked for ahead ([prepare], while the hello plays) so Live opens at once when it ends; one
 * never used is reported as 0 seconds ([discardPrepared]). As a session's window closes (or Google says it is going
 * away) and he is still talking, a fresh token carrying Google's resumption handle carries the same conversation on
 * ([LiveHandOnRules]): the microphone and his voice stay open, only the connection to Google is swapped.
 */
class LiveTalk(private val context: Context, private val core: MekaCore, private val scope: CoroutineScope, private val listener: Listener) {
    interface Listener {
        /** Google set the session up: he can talk. */
        fun onReady()
        /** Obi started or stopped speaking. */
        fun onSpeaking(speaking: Boolean)
        /** The orb's level, 0 … 1: his voice while he talks, Obi's while Obi speaks. */
        fun onLevel(level: Float)
        /** The conversation ended; [fellBack] when Live never opened and normal Obi should answer instead. */
        fun onEnded(fellBack: Boolean)
        /** Slice 4: this turn's words so far, his ([heard]) and Obi's ([said]), from Google's transcripts. */
        fun onWords(heard: String, said: String) {}
        /** Slice 4: a turn finished: his words and Obi's answer with the pages he looked up, for Ask to show. */
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
    /** Slice 5: the cards Obi offered this turn (Ask's own, through the policy gate), shown with his words. */
    private val cardsNow = mutableListOf<os.meka.core.domain.AskCard>()
    /** Slice 5b: Money was locked when Obi looked this turn, so Ask shows its Unlock under his words. */
    private var unlockNow = false

    // Slice 2c: the connection to Google now (bumped by each fresh window, so the old one's close ends nothing), the
    // conversation's resumption handle, the fresh windows used, when he last spoke, and the window's end.
    @Volatile private var link = 0
    @Volatile private var heldHandle: String? = null
    private var resumes = 0
    @Volatile private var lastHeardAt: Long? = null
    @Volatile private var endAt = Long.MAX_VALUE
    @Volatile private var resuming = false
    private var resumingFrom = 0
    private var connectJob: Job? = null

    /** Slice 2c: a session asked for ahead (during the hello), and when (elapsed ms). */
    private class Prepared(val session: Deferred<LiveCodec.Session?>, val at: Long)
    private var prepared: Prepared? = null

    /**
     * Slice 2c: asks for a session now (the hello is playing; [greeted] is what it says), so [start] opens at once
     * when the hello ends. Main thread.
     */
    fun prepare(greeted: String?) {
        if (running || prepared != null) return
        prepared = Prepared(scope.async {
            val voice = runCatching { core.liveVoice() }.getOrNull()
            core.liveSession(voice, greeted = greeted)
        }, SystemClock.elapsedRealtime())
    }

    /** Slice 2c: a session asked for ahead won't be used (he ended the hello): reported as 0 seconds, so it costs nothing. */
    fun discardPrepared() {
        val p = prepared ?: return
        prepared = null
        scope.launch {
            val s = runCatching { p.session.await() }.getOrNull() ?: return@launch
            s.sessionId?.let { id -> withContext(Dispatchers.IO) { core.liveUsage(id, 0, 0) } }
        }
    }

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
        heldHandle = null
        resumes = 0
        lastHeardAt = null
        endAt = Long.MAX_VALUE
        resuming = false
        output = TalkBargeIn.output(context)
        val askedAt = SystemClock.elapsedRealtime()
        // Slice 2c: the session asked for during the hello, while its token can still open one.
        val ahead = prepared?.takeIf { LiveHandOnRules.preparedUsable(it.at, askedAt) }
        if (ahead == null) discardPrepared()
        prepared = null
        jobs += scope.launch {
            delay(LiveTalkRules.FALLBACK_MS)
            if (gen == generation && LiveTalkRules.fallBack(askedAt, SystemClock.elapsedRealtime(), setUp)) end(gen, fellBack = true)
        }
        jobs += scope.launch {
            val s = if (ahead != null) runCatching { ahead.session.await() }.getOrNull() else {
                val voice = runCatching { core.liveVoice() }.getOrNull()
                core.liveSession(voice)
            }
            if (gen != generation) {
                s?.sessionId?.let { id -> withContext(Dispatchers.IO) { core.liveUsage(id, 0, 0) } }
                return@launch
            }
            if (!ready(s)) {
                end(gen, fellBack = true)
                return@launch
            }
            open(gen, s!!, null)
            jobs += scope.launch {
                // The token's window: a little before it closes, carry on into a fresh one or end, never mid-word.
                // Meanwhile, Obi's speaking state.
                while (gen == generation) {
                    val now = SystemClock.elapsedRealtime()
                    if (setUp && !resuming && now >= endAt) carryOn(gen)
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

    private fun ready(s: LiveCodec.Session?): Boolean =
        s != null && s.state == LiveCodec.Session.READY && s.token != null && s.url != null && s.model != null

    /** Connects to Google with [s] (a fresh window carries the conversation on with [resumeHandle]). Main thread. */
    private fun open(gen: Int, s: LiveCodec.Session, resumeHandle: String?) {
        session = s
        val out = Channel<String>(Channel.BUFFERED)
        outgoing = out
        val l = ++link
        endAt = LiveTalkRules.endAtElapsed(SystemClock.elapsedRealtime(), System.currentTimeMillis(), s.expiresAtMs ?: 0L)
        connectJob = scope.launch(Dispatchers.IO) { connect(gen, l, s, out, resumeHandle) }.also { jobs += it }
    }

    /**
     * Slice 2c: the window is closing or Google is going away. While he is still talking (and under the hour), a fresh
     * token with Google's handle carries the conversation on ([LiveHandOnRules.resume]); otherwise it ends as before.
     * The finished window's seconds are reported; the fresh one is metered and capped like any session. Main thread.
     */
    private fun carryOn(gen: Int) {
        if (gen != generation || resuming) return
        val h = heldHandle
        if (!LiveHandOnRules.resume(h, resumes, lastHeardAt, SystemClock.elapsedRealtime())) {
            end(gen, fellBack = false)
            return
        }
        resuming = true
        resumingFrom = link
        resumes++
        jobs += scope.launch {
            val voice = runCatching { core.liveVoice() }.getOrNull()
            val s = core.liveSession(voice, resume = h)
            if (gen != generation) {
                s?.sessionId?.let { id -> withContext(Dispatchers.IO) { core.liveUsage(id, 0, 0) } }
                return@launch
            }
            if (!ready(s)) { end(gen, fellBack = false); return@launch } // the month's cap, offline: ends as before
            reportUsage()
            val old = connectJob
            open(gen, s!!, h)
            old?.cancel()
        }
    }

    /** The current session's seconds heard and spoken (nothing of what was said), then a fresh count. */
    private fun reportUsage() {
        val id = session?.sessionId ?: return
        val heard = LiveTalkRules.seconds(inBytes, LiveTalkRules.IN_RATE)
        val spoke = LiveTalkRules.seconds(outBytes, LiveTalkRules.OUT_RATE)
        inBytes = 0
        outBytes = 0
        scope.launch { withContext(Dispatchers.IO) { core.liveUsage(id, heard, spoke) } }
    }

    /** Ends the conversation at once (the orb tapped, the screen left). */
    fun stop() {
        if (running) end(generation, fellBack = false)
    }

    /** Frees everything for good. */
    fun release() {
        stop()
        discardPrepared()
        runCatching { client.close() }
    }

    private suspend fun connect(gen: Int, l: Int, s: LiveCodec.Session, out: Channel<String>, resumeHandle: String?) {
        runCatching {
            client.webSocket(LiveProtocol.connectUrl(s.url!!, s.token!!)) {
                send(Frame.Text(LiveProtocol.setup(s.model!!, s.voice ?: LiveTalkRules.voice(null), resumeHandle = resumeHandle)))
                val sender = launch {
                    for (msg in out) send(Frame.Text(msg))
                }
                try {
                    for (frame in incoming) {
                        if (gen != generation || l != link) break
                        val text = when (frame) {
                            is Frame.Text -> frame.readText()
                            is Frame.Binary -> frame.data.decodeToString()
                            else -> continue
                        }
                        LiveProtocol.decode(text).forEach { handle(gen, l, it) }
                    }
                } finally {
                    sender.cancel()
                }
            }
        }
        // The socket closed: Google ended the session, the signal went, or it never opened. Slice 2c: an earlier
        // window's connection closing while a fresh one opens ends nothing.
        main.post {
            if (gen == generation && l == link && !(resuming && l == resumingFrom)) end(gen, fellBack = !setUp)
        }
    }

    private fun handle(gen: Int, l: Int, e: LiveProtocol.Event) {
        when (e) {
            LiveProtocol.Event.SetupComplete -> main.post {
                if (gen != generation) return@post
                // Slice 2c: a fresh window is set up: the conversation carries on, nothing to open.
                if (l == link && resuming) resuming = false
                if (setUp) return@post
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
                // Meka talked over him: drop what's queued and what the speaker still holds.
                queue.clear()
                playUntil = 0
                track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
            }
            LiveProtocol.Event.TurnComplete -> main.post { if (gen == generation) finishTurn() }
            is LiveProtocol.Event.Heard -> main.post {
                if (gen != generation) return@post
                heardNow.append(e.text)
                if (e.text.isNotBlank()) lastHeardAt = SystemClock.elapsedRealtime()
                listener.onWords(heardNow.toString().trim(), saidNow.toString().trim())
            }
            is LiveProtocol.Event.Said -> main.post {
                if (gen != generation) return@post
                saidNow.append(e.text)
                listener.onWords(heardNow.toString().trim(), saidNow.toString().trim())
            }
            is LiveProtocol.Event.ToolCall -> scope.launch { answerTool(gen, e) }
            // Slice 2c: Google is closing this connection: carry the conversation on, or end as before.
            is LiveProtocol.Event.GoAway -> main.post { if (gen == generation && l == link) carryOn(gen) }
            is LiveProtocol.Event.Resumable -> main.post {
                if (gen == generation && l == link) LiveHandOnRules.handle(e.handle)?.let { heldHandle = it }
            }
        }
    }

    /**
     * Slice 4: Obi asked Navilife to search the web. The core checks the query (never his own life: refused on the
     * phone) and Navilife's server searches it; the results go back to Google as data, and their pages become the
     * turn's sources in Ask. Anything else is answered with a refusal the model can act on.
     */
    private suspend fun answerTool(gen: Int, call: LiveProtocol.Event.ToolCall) {
        val id = session?.sessionId
        val reply = if (call.name == LiveToolRules.MAIL_TOOL) {
            // Slice 5b: his mail, read here as Ask reads it (the emails that need him, or from whoever he named).
            val lines = withContext(Dispatchers.IO) { core.liveMail(call.args[LiveToolRules.ABOUT_ARG].orEmpty()) }
            LiveProtocol.toolAnswer(call.id, call.name, lines)
        } else if (call.name == LiveToolRules.MONEY_TOOL) {
            // Slice 5b: the phone's own answer from Money, only while it's unlocked; locked, the turn shows Unlock.
            val money = withContext(Dispatchers.IO) { core.liveMoney(call.args[LiveToolRules.QUESTION_ARG].orEmpty()) }
            if (money.unlockMoney && gen == generation) unlockNow = true
            LiveProtocol.toolAnswer(call.id, call.name, money.reply)
        } else if (call.name == LiveToolRules.LOOK_UP_TOOL) {
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

    /** Slice 4: the turn as Ask shows it (his words and sources), then a clean slate for the next. Main thread. */
    private fun finishTurn() {
        val sources = os.meka.core.domain.LiveFactsRules.sources(pagesNow)
        LiveToolRules.turnAnswer(saidNow.toString(), sources, cardsNow.toList(), unlockNow)?.let { listener.onTurn(LiveTalkRules.turnQuestion(heardNow.toString()), it) }
        heardNow.clear()
        saidNow.clear()
        pagesNow.clear()
        cardsNow.clear()
        unlockNow = false
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
                outgoing?.trySend(LiveProtocol.audio(data)) // slice 2c: the connection of the window now
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
                // Obi's level for the orb, from the chunk about to play.
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
        // Slice 4: a turn cut short by the end still shows what Obi said.
        finishTurn()
        // The seconds heard and spoken (nothing of what was said), so the month's count drops from the reserve.
        reportUsage()
        session = null
        connectJob = null
        resuming = false
        heldHandle = null
        listener.onEnded(fellBack)
    }

    private companion object {
        const val TICK_MS = 100L
        const val JOIN_MS = 150L
    }
}
