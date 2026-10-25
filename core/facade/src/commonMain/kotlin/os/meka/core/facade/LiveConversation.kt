package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import os.meka.core.domain.AskCard
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.BargeInRules
import os.meka.core.domain.LiveAudioRules
import os.meka.core.domain.LiveDiagnosisRules
import os.meka.core.domain.LiveEchoGate
import os.meka.core.domain.LiveJitter
import os.meka.core.domain.LiveFactsRules
import os.meka.core.domain.LiveHandOnRules
import os.meka.core.domain.LiveMoney
import os.meka.core.domain.LiveOffer
import os.meka.core.domain.LiveTalkRules
import os.meka.core.domain.LiveToolRules
import os.meka.core.wire.LiveCodec
import os.meka.core.wire.LiveProtocol

/**
 * Obi's Live mode, slice 3 (the Mac): one real-time Gemini Live conversation, run here in the core so the Mac's Swift
 * only moves sound. The Fold's own `LiveTalk` does the same on Android; the rules are the same ones
 * ([LiveTalkRules], [LiveToolRules], [LiveHandOnRules]).
 *
 * The device gives the microphone in 40 ms chunks ([sendAudio], base64 16 kHz PCM with its loudness) and plays what
 * [events] hands back ([LiveEvent.Audio], base64 24 kHz PCM; [LiveEvent.Interrupted]: drop what is queued at once).
 * Here: the session token from Navilife's server (never the Gemini key), Google's WebSocket, the setup, Obi's tools
 * (the web search, his days, Ask's cards through the policy gate, his mail, his money while unlocked), the turn's
 * words and sources for Ask, the seconds reported, a fresh window carrying the conversation on while he is still
 * talking, and normal Obi taking over when the session isn't set up within [LiveTalkRules.FALLBACK_MS].
 *
 * Every call is made on [scope]'s thread (the Mac's main thread); [events] has one collector.
 */
class LiveConversation internal constructor(
    private val core: Core,
    private val socket: LiveSocket,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long,
    private val wallMs: () -> Long,
) {
    /** What the conversation needs of the core (MekaCore's own Live calls). */
    internal interface Core {
        suspend fun voice(): String?
        suspend fun session(voice: String?, greeted: String?, resume: String?): LiveCodec.Session?
        suspend fun usage(sessionId: String, inSeconds: Long, outSeconds: Long)
        suspend fun search(sessionId: String, query: String): LiveCodec.SearchResult
        suspend fun lookUp(whenText: String): String
        suspend fun propose(args: Map<String, String>): LiveOffer
        suspend fun mail(about: String): String
        suspend fun money(question: String): LiveMoney
        /** 2026-10-24: how this talk went (Live, or why normal Obi answered), for Health; reasons only, never words. */
        suspend fun outcome(live: Boolean, fallback: LiveDiagnosisRules.LiveFallback?, detail: String?) {}
    }

    private val out = Channel<LiveEvent>(Channel.UNLIMITED)

    /** What the device shows and plays, in order. */
    val events: Flow<LiveEvent> = out.receiveAsFlow()

    /** Bumped by every start and end, so a late answer from an earlier session changes nothing. */
    private var generation = 0
    var running = false
        private set
    private var setUp = false
    private var playUntil = 0L
    private var speakingNow = false
    private var headphones = false
    /** 2026-10-25: Obi's echo kept out of the microphone on the speakers, and his voice held a moment so it never stutters. */
    private var gate = LiveEchoGate()
    private var jitter = LiveJitter<Held>()
    private class Held(val b64: String, val bytes: Int, val dbfs: Float)
    private var session: LiveCodec.Session? = null
    private val jobs = mutableListOf<Job>()
    private var outgoing: Channel<String>? = null
    private var inBytes = 0L
    private var outBytes = 0L
    private val heardNow = StringBuilder()
    private val saidNow = StringBuilder()
    private val pagesNow = mutableListOf<Pair<String, String>>()
    private val cardsNow = mutableListOf<AskCard>()
    private var unlockNow = false
    private var link = 0
    private var heldHandle: String? = null
    private var resumes = 0
    private var lastHeardAt: Long? = null
    private var endAt = Long.MAX_VALUE
    private var resuming = false
    private var resumingFrom = 0
    private var connectJob: Job? = null
    /** 2026-10-24: the server's ready session has arrived (Google then gets [LiveDiagnosisRules.CONNECT_MS]). */
    private var tokenIn = false
    /** Why this talk fell back, once known (the server's answer, Google's close); the timer's own reason otherwise. */
    private var why: Pair<LiveDiagnosisRules.LiveFallback, String?>? = null
    /** Under the orb for this talk when normal Obi answered instead ([LiveDiagnosisRules.stageLine]); null while Live runs. */
    var fallbackLine: String? = null
        private set

    private class Prepared(val session: Deferred<LiveCodec.Session?>, val at: Long)
    private var prepared: Prepared? = null

    /** Asks for a session now (the hello is playing; [greeted] is what it says), so [start] opens at once after it. */
    fun prepare(greeted: String?) {
        if (running || prepared != null) return
        prepared = Prepared(scope.async { runCatching { core.session(core.voice(), greeted, null) }.getOrNull() }, elapsedMs())
    }

    /** A session asked for ahead won't be used: reported as 0 seconds, so it costs nothing. */
    fun discardPrepared() {
        val p = prepared ?: return
        prepared = null
        scope.launch {
            val s = runCatching { p.session.await() }.getOrNull() ?: return@launch
            s.sessionId?.let { report(it, 0, 0) }
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
        gate = LiveEchoGate()
        jitter = LiveJitter()
        heldHandle = null
        resumes = 0
        lastHeardAt = null
        endAt = Long.MAX_VALUE
        resuming = false
        tokenIn = false
        why = null
        fallbackLine = null
        val askedAt = elapsedMs()
        val ahead = prepared?.takeIf { LiveHandOnRules.preparedUsable(it.at, askedAt) }
        if (ahead == null) discardPrepared()
        prepared = null
        jobs += scope.launch {
            // 2026-10-24: 2 s for Navilife's server to hand over a token, then up to 6 s from asking for Google to set
            // the session up (normal Obi takes about ten seconds a turn, so a slower Live is still the quicker answer).
            delay(LiveTalkRules.FALLBACK_MS)
            while (gen == generation && !setUp) {
                if (LiveDiagnosisRules.fallBack(askedAt, elapsedMs(), setUp, tokenIn)) {
                    if (why == null) why = (if (tokenIn) LiveDiagnosisRules.LiveFallback.SLOW_GOOGLE else LiveDiagnosisRules.LiveFallback.SLOW_SERVER) to null
                    end(gen, fellBack = true)
                    break
                }
                delay(TICK_MS)
            }
        }
        jobs += scope.launch {
            val s = if (ahead != null) runCatching { ahead.session.await() }.getOrNull()
            else runCatching { core.session(core.voice(), null, null) }.getOrNull()
            if (gen != generation) {
                s?.sessionId?.let { report(it, 0, 0) }
                return@launch
            }
            if (!ready(s)) {
                why = (LiveDiagnosisRules.fromSession(s?.state, s?.reason) ?: LiveDiagnosisRules.LiveFallback.NO_ANSWER) to LiveDiagnosisRules.detail(s?.reason)
                end(gen, fellBack = true)
                return@launch
            }
            tokenIn = true
            open(gen, s!!, null)
            jobs += scope.launch {
                while (gen == generation) {
                    val now = elapsedMs()
                    if (setUp && !resuming && now >= endAt) carryOn(gen)
                    play(jitter.tick(now))
                    val speaking = herVoice(now)
                    if (speaking != speakingNow && gen == generation) {
                        speakingNow = speaking
                        out.trySend(LiveEvent.Speaking(speaking))
                    }
                    delay(TICK_MS)
                }
            }
        }
    }

    /**
     * One microphone chunk: [base64] 16 kHz PCM of [bytes] bytes, at [dbfs]. While Obi speaks on the speaker, only his
     * voice clearly over Obi's echo for a quarter second goes up ([LiveEchoGate]); the rest goes as silence of the same length.
     */
    fun sendAudio(base64: String, bytes: Int, dbfs: Float, headphones: Boolean) {
        if (!running || !setUp) return
        this.headphones = headphones
        val speaking = herVoice(elapsedMs())
        val output = if (headphones) os.meka.core.domain.BargeInOutput.HEADPHONES else os.meka.core.domain.BargeInOutput.SPEAKER
        val send = gate.send(output, dbfs, speaking, elapsedMs())
        inBytes += bytes.coerceAtLeast(0)
        outgoing?.trySend(LiveProtocol.audio(if (send) base64 else silence(bytes)))
    }

    /** Ends the conversation at once (the orb clicked, the window closed). */
    fun stop() {
        if (running) end(generation, fellBack = false)
    }

    /** Ends everything for good. */
    fun release() {
        stop()
        discardPrepared()
    }

    /** Obi's voice is still playing at [now] (nothing yet arrived reads as quiet). */
    private fun herVoice(now: Long): Boolean = playUntil > 0 && LiveTalkRules.speaking(playUntil, now)

    private fun ready(s: LiveCodec.Session?): Boolean =
        s != null && s.state == LiveCodec.Session.READY && s.token != null && s.url != null && s.model != null

    private fun open(gen: Int, s: LiveCodec.Session, resumeHandle: String?) {
        session = s
        val chan = Channel<String>(Channel.UNLIMITED)
        outgoing = chan
        val l = ++link
        endAt = LiveTalkRules.endAtElapsed(elapsedMs(), wallMs(), s.expiresAtMs ?: 0L)
        chan.trySend(LiveProtocol.setup(s.model!!, s.voice ?: LiveTalkRules.voice(null), resumeHandle = resumeHandle))
        connectJob = scope.launch {
            var closed: String? = null
            try {
                closed = socket.run(LiveProtocol.connectUrl(s.url!!, s.token!!), chan) { text ->
                    if (gen == generation && l == link) LiveProtocol.decode(text).forEach { handle(gen, l, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Closed below; why, in a word (the handshake refused, no signal), never the token.
                closed = LiveDiagnosisRules.redact(e.message?.let { m -> "${e::class.simpleName}: $m" } ?: e::class.simpleName, s.token)
            }
            if (!setUp && gen == generation && l == link && why == null) why = LiveDiagnosisRules.LiveFallback.CLOSED to LiveDiagnosisRules.redact(closed, s.token)
            // Google ended the session, the signal went, or it never opened. An earlier window's connection closing
            // while a fresh one opens ends nothing.
            if (gen == generation && l == link && !(resuming && l == resumingFrom)) end(gen, fellBack = !setUp)
        }.also { jobs += it }
    }

    private fun carryOn(gen: Int) {
        if (gen != generation || resuming) return
        val h = heldHandle
        if (!LiveHandOnRules.resume(h, resumes, lastHeardAt, elapsedMs())) {
            end(gen, fellBack = false)
            return
        }
        resuming = true
        resumingFrom = link
        resumes++
        jobs += scope.launch {
            val s = runCatching { core.session(core.voice(), null, h) }.getOrNull()
            if (gen != generation) {
                s?.sessionId?.let { report(it, 0, 0) }
                return@launch
            }
            if (!ready(s)) { end(gen, fellBack = false); return@launch }
            reportUsage()
            val old = connectJob
            val oldOut = outgoing
            open(gen, s!!, h)
            oldOut?.close()
            old?.cancel()
        }
    }

    private fun handle(gen: Int, l: Int, e: LiveProtocol.Event) {
        when (e) {
            LiveProtocol.Event.SetupComplete -> {
                if (l == link && resuming) resuming = false
                if (setUp) return
                setUp = true
                out.trySend(LiveEvent.Ready)
            }
            is LiveProtocol.Event.Audio -> e.chunks.forEach { b64 ->
                val bytes = decodedBytes(b64)
                if (bytes <= 0) return@forEach
                outBytes += bytes
                play(jitter.arrive(Held(b64, bytes, levelOf(b64)), bytes, elapsedMs()))
            }
            LiveProtocol.Event.Interrupted -> {
                jitter.interrupted()
                gate.clear()
                playUntil = 0
                out.trySend(LiveEvent.Interrupted)
            }
            LiveProtocol.Event.TurnComplete -> {
                play(jitter.turnComplete(elapsedMs()))
                finishTurn()
            }
            is LiveProtocol.Event.Heard -> {
                heardNow.append(e.text)
                if (e.text.isNotBlank()) lastHeardAt = elapsedMs()
                out.trySend(LiveEvent.Words(heardNow.toString().trim(), saidNow.toString().trim()))
            }
            is LiveProtocol.Event.Said -> {
                saidNow.append(e.text)
                out.trySend(LiveEvent.Words(heardNow.toString().trim(), saidNow.toString().trim()))
            }
            is LiveProtocol.Event.ToolCall -> scope.launch { answerTool(gen, e) }
            is LiveProtocol.Event.GoAway -> if (l == link) carryOn(gen)
            is LiveProtocol.Event.Resumable -> if (l == link) LiveHandOnRules.handle(e.handle)?.let { heldHandle = it }
        }
    }

    /** Obi asked Navilife for something: answered here exactly as the Fold answers it. */
    private suspend fun answerTool(gen: Int, call: LiveProtocol.Event.ToolCall) {
        val id = session?.sessionId
        val reply = when (call.name) {
            LiveToolRules.MAIL_TOOL -> LiveProtocol.toolAnswer(call.id, call.name, core.mail(call.args[LiveToolRules.ABOUT_ARG].orEmpty()))
            LiveToolRules.MONEY_TOOL -> {
                val money = core.money(call.args[LiveToolRules.QUESTION_ARG].orEmpty())
                if (money.unlockMoney && gen == generation) unlockNow = true
                LiveProtocol.toolAnswer(call.id, call.name, money.reply)
            }
            LiveToolRules.LOOK_UP_TOOL -> LiveProtocol.toolAnswer(call.id, call.name, core.lookUp(call.args[LiveToolRules.WHEN_ARG].orEmpty()))
            LiveToolRules.PROPOSE_TOOL ->
                if (cardsNow.size >= LiveToolRules.MAX_CARDS) LiveProtocol.toolAnswer(call.id, call.name, null, error = LiveToolRules.PROPOSE_FULL)
                else {
                    val offer = core.propose(call.args)
                    val card = offer.card
                    if (card != null && gen == generation) cardsNow += card
                    if (card != null) LiveProtocol.toolAnswer(call.id, call.name, offer.reply)
                    else LiveProtocol.toolAnswer(call.id, call.name, null, error = offer.reply)
                }
            LiveTalkRules.SEARCH_TOOL -> if (id == null) LiveProtocol.toolResponse(call.id, call.name, emptyList(), error = LiveTalkRules.SEARCH_FAILED) else {
                val r = core.search(id, call.query)
                when (r.state) {
                    LiveCodec.SearchResult.FOUND -> {
                        if (gen == generation) pagesNow += r.hits.map { it.title to it.url }
                        LiveProtocol.toolResponse(call.id, call.name, r.hits.map { h -> LiveProtocol.Hit(LiveFactsRules.site(h.url).orEmpty(), h.title, h.text) })
                    }
                    LiveCodec.SearchResult.REFUSED -> LiveProtocol.toolResponse(call.id, call.name, emptyList(), error = LiveTalkRules.SEARCH_REFUSED)
                    else -> LiveProtocol.toolResponse(call.id, call.name, emptyList(), error = LiveTalkRules.SEARCH_FAILED)
                }
            }
            else -> LiveProtocol.toolResponse(call.id, call.name, emptyList(), error = NO_TOOL)
        }
        if (gen == generation) outgoing?.trySend(reply)
    }

    /** The turn as Ask shows it (his words, sources, cards, Unlock), then a clean slate for the next. */
    private fun finishTurn() {
        val sources = LiveFactsRules.sources(pagesNow)
        LiveToolRules.turnAnswer(saidNow.toString(), sources, cardsNow.toList(), unlockNow)?.let {
            out.trySend(LiveEvent.Turn(LiveTalkRules.turnQuestion(heardNow.toString()), it))
        }
        heardNow.clear()
        saidNow.clear()
        pagesNow.clear()
        cardsNow.clear()
        unlockNow = false
    }

    private fun reportUsage() {
        val id = session?.sessionId ?: return
        val heard = LiveTalkRules.seconds(inBytes, LiveTalkRules.IN_RATE)
        val spoke = LiveTalkRules.seconds(outBytes, LiveTalkRules.OUT_RATE)
        inBytes = 0
        outBytes = 0
        scope.launch { report(id, heard, spoke) }
    }

    private suspend fun report(id: String, heard: Long, spoke: Long) {
        try { core.usage(id, heard, spoke) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
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
        playUntil = 0
        speakingNow = false
        finishTurn() // a turn cut short still shows what Obi said
        reportUsage()
        session = null
        connectJob = null
        resuming = false
        heldHandle = null
        // 2026-10-24: never quietly: how this talk went, for the stage and for Health.
        val reason = if (fellBack) why ?: (LiveDiagnosisRules.LiveFallback.NO_ANSWER to null) else null
        fallbackLine = reason?.let { LiveDiagnosisRules.stageLine(it.first, it.second) }
        val wasLive = setUp
        if (fellBack || wasLive) scope.launch { runCatching { core.outcome(!fellBack && wasLive, reason?.first, reason?.second) } }
        out.trySend(LiveEvent.Ended(fellBack, fallbackLine))
    }

    /** What the jitter let through: to the speaker in order, each chunk's span and level to the gate, the speaking clock on. */
    private fun play(chunks: List<Held>) {
        if (chunks.isEmpty()) return
        var at = jitter.lastFrom
        for (c in chunks) {
            val to = at + LiveAudioRules.ms(c.bytes)
            gate.played(at, to, c.dbfs)
            at = to
            out.trySend(LiveEvent.Audio(c.b64))
        }
        playUntil = jitter.until
    }

    /** Times the speaker ran dry mid-turn this talk (Obi waited for more rather than stuttering). */
    val underruns: Int get() = jitter.underruns

    private var silenceFor: Pair<Int, String>? = null

    /** Base64 silence of [bytes] bytes, kept for the usual chunk size. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun silence(bytes: Int): String {
        silenceFor?.takeIf { it.first == bytes }?.let { return it.second }
        val s = kotlin.io.encoding.Base64.encode(ByteArray(bytes.coerceIn(0, MAX_CHUNK)))
        silenceFor = bytes to s
        return s
    }

    internal companion object {
        const val TICK_MS = 100L
        const val MAX_CHUNK = 64_000
        const val NO_TOOL = "Navilife has no such tool."

        /** The loudness (dBFS) of base64 16-bit PCM [b64]; -160 when it can't be read. */
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun levelOf(b64: String): Float {
            val pcm = runCatching { kotlin.io.encoding.Base64.decode(b64.trim()) }.getOrNull() ?: return -160f
            val n = pcm.size / 2
            if (n == 0) return -160f
            val samples = ShortArray(n) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }
            return BargeInRules.dbfs(samples, n)
        }

        /** The decoded length of base64 [b64] (what the usage count and the speaking clock go by). */
        fun decodedBytes(b64: String): Int {
            val s = b64.trim()
            if (s.isEmpty()) return 0
            val pad = s.takeLast(2).count { it == '=' }
            return s.length / 4 * 3 - pad + when (s.length % 4) { 2 -> 1; 3 -> 2; else -> 0 }
        }
    }
}

/** What a Live conversation hands the device. */
sealed interface LiveEvent {
    /** Google set the session up: he can talk. */
    data object Ready : LiveEvent
    /** Obi started or stopped speaking. */
    data class Speaking(val speaking: Boolean) : LiveEvent
    /** Obi's voice to play next: base64 PCM, 24 kHz, 16-bit, mono. */
    data class Audio(val base64: String) : LiveEvent
    /** Meka talked over him: drop whatever is still queued to play, at once. */
    data object Interrupted : LiveEvent
    /** This turn's words so far, his and Obi's. */
    data class Words(val heard: String, val said: String) : LiveEvent
    /** A turn finished: his words and Obi's answer (sources, cards, Unlock) for Ask. */
    data class Turn(val question: String, val answer: AskOutcome) : LiveEvent
    /** The conversation ended; [fellBack] when Live never opened and normal Obi should answer instead. */
    data class Ended(val fellBack: Boolean, val fallbackLine: String? = null) : LiveEvent
}

/** The WebSocket to Google, behind an interface so the conversation can be tested without one. */
internal interface LiveSocket {
    /**
     * Opens [url], sends what [outgoing] yields in order, calls [onText] for each message; returns when it closes, with
     * Google's close code and reason when it gave one ("1008 · …", 2026-10-24), else null.
     */
    suspend fun run(url: String, outgoing: ReceiveChannel<String>, onText: suspend (String) -> Unit): String?
}

/** The real one: Ktor's WebSocket client ([client] has the WebSockets plugin). */
internal class KtorLiveSocket(private val client: HttpClient) : LiveSocket {
    override suspend fun run(url: String, outgoing: ReceiveChannel<String>, onText: suspend (String) -> Unit): String? {
        var closed: String? = null
        client.webSocket(url) {
            val sender = launch { for (msg in outgoing) send(Frame.Text(msg)) }
            try {
                for (frame in incoming) {
                    val text = when (frame) {
                        is Frame.Text -> frame.readText()
                        is Frame.Binary -> frame.data.decodeToString()
                        else -> continue
                    }
                    onText(text)
                }
            } finally {
                sender.cancel()
            }
            closed = kotlinx.coroutines.withTimeoutOrNull(500) { runCatching { closeReason.await() }.getOrNull() }
                ?.let { r -> LiveDiagnosisRules.closeLine(r.code.toInt(), r.message) }
        }
        return closed
    }
}

/** MekaCore's own Live calls, as the conversation needs them. */
internal class MekaLiveCore(private val core: MekaCore) : LiveConversation.Core {
    override suspend fun voice(): String? = runCatching { core.liveVoice() }.getOrNull()
    override suspend fun session(voice: String?, greeted: String?, resume: String?) = core.liveSession(voice, greeted, resume)
    override suspend fun usage(sessionId: String, inSeconds: Long, outSeconds: Long) { core.liveUsage(sessionId, inSeconds, outSeconds) }
    override suspend fun search(sessionId: String, query: String) = core.liveSearch(sessionId, query)
    override suspend fun lookUp(whenText: String) = core.liveLookUp(whenText)
    override suspend fun propose(args: Map<String, String>) = core.livePropose(args)
    override suspend fun mail(about: String) = core.liveMail(about)
    override suspend fun money(question: String) = core.liveMoney(question)
    override suspend fun outcome(live: Boolean, fallback: LiveDiagnosisRules.LiveFallback?, detail: String?) { core.noteLiveOutcome(live, fallback, detail) }
}
