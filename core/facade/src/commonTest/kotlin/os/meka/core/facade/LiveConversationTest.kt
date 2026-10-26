package os.meka.core.facade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.BargeInOutput
import os.meka.core.domain.LiveAudioStats
import os.meka.core.domain.LiveDiagnosisRules
import os.meka.core.domain.LiveMoney
import os.meka.core.domain.LiveOffer
import os.meka.core.domain.LiveTalkRules
import os.meka.core.domain.LiveToolRules
import os.meka.core.wire.LiveCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Obi's Live mode, slice 3: the conversation the Mac runs in the core, against a fake Google and a fake core. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveConversationTest {
    private class FakeSocket : LiveSocket {
        val sent = mutableListOf<String>()
        val incoming = Channel<String>(Channel.UNLIMITED)
        var url: String? = null
        /** What Google's close said, once [incoming] is closed. */
        var closeWith: String? = null
        override suspend fun run(url: String, outgoing: ReceiveChannel<String>, onText: suspend (String) -> Unit): String? = coroutineScope {
            this@FakeSocket.url = url
            val s = launch { for (m in outgoing) sent += m }
            for (t in incoming) onText(t)
            s.cancel()
            closeWith
        }
    }

    private class FakeCore(var ready: Boolean = true, val refused: String? = null) : LiveConversation.Core {
        val usage = mutableListOf<Triple<String, Long, Long>>()
        val outcomes = mutableListOf<Triple<Boolean, LiveDiagnosisRules.LiveFallback?, String?>>()
        val audio = mutableListOf<LiveAudioStats?>()
        override suspend fun outcome(live: Boolean, fallback: LiveDiagnosisRules.LiveFallback?, detail: String?, audio: LiveAudioStats?) {
            outcomes += Triple(live, fallback, detail)
            this.audio += audio
        }
        val searches = mutableListOf<String>()
        val asked = mutableListOf<String?>()
        override suspend fun voice(): String? = "Kore"
        override suspend fun session(voice: String?, greeted: String?, resume: String?): LiveCodec.Session? {
            asked += greeted
            if (refused != null) return LiveCodec.Session(LiveCodec.Session.FAILED, LiveCodec.Meter(true, "ok", 0, 1_500), reason = refused)
            return if (!ready) LiveCodec.Session(LiveCodec.Session.OVER, LiveCodec.Meter(true, "over", 1_500, 1_500))
            else LiveCodec.Session(LiveCodec.Session.READY, LiveCodec.Meter(true, "ok", 0, 1_500), "ls_1", "tok", "gemini-live", "wss://g", voice, expiresAtMs = 600_000)
        }
        override suspend fun usage(sessionId: String, inSeconds: Long, outSeconds: Long) { usage += Triple(sessionId, inSeconds, outSeconds) }
        override suspend fun search(sessionId: String, query: String): LiveCodec.SearchResult {
            searches += query
            return LiveCodec.SearchResult(LiveCodec.SearchResult.FOUND, listOf(LiveCodec.SearchHit("Strike off", "https://www.bbc.co.uk/news/1", "Talks resume")))
        }
        override suspend fun lookUp(whenText: String) = "Thursday: dentist 09:30"
        override suspend fun propose(args: Map<String, String>) = LiveOffer(null, LiveToolRules.PROPOSE_REFUSED)
        override suspend fun mail(about: String) = "no emails"
        override suspend fun money(question: String) = LiveMoney(LiveToolRules.MONEY_LOCKED, unlockMoney = true)
    }

    private fun TestScope.conversation(core: FakeCore, socket: FakeSocket, wall: Long = 0): Pair<LiveConversation, MutableList<LiveEvent>> {
        val events = mutableListOf<LiveEvent>()
        val c = LiveConversation(core, socket, backgroundScope, { testScheduler.currentTime }, { wall + testScheduler.currentTime })
        backgroundScope.launch { c.events.collect { events += it } }
        return c to events
    }

    private val chunk = "AAAAAA==" // 4 bytes of 24 kHz PCM

    @Test
    fun aTurnWithASearchShowsInAskAndItsSecondsAreReported() = runTest {
        val core = FakeCore()
        val socket = FakeSocket()
        val (c, events) = conversation(core, socket)
        c.start()
        runCurrent()
        assertEquals("wss://g?access_token=tok", socket.url)
        assertTrue(socket.sent.first().contains("\"setup\""), "the setup goes first")
        socket.incoming.trySend("""{"setupComplete":{}}""")
        runCurrent()
        assertTrue(LiveEvent.Ready in events)
        c.sendAudio("AQID", 3200, -20f, headphones = false)
        runCurrent()
        assertTrue(socket.sent.last().contains("AQID"))
        socket.incoming.trySend("""{"toolCall":{"functionCalls":[{"id":"c1","name":"${LiveTalkRules.SEARCH_TOOL}","args":{"query":"rail strike"}}]}}""")
        runCurrent()
        assertEquals(listOf("rail strike"), core.searches)
        assertTrue(socket.sent.last().contains("toolResponse") && socket.sent.last().contains("Strike off"))
        socket.incoming.trySend("""{"serverContent":{"inputTranscription":{"text":"Rail strike?"}}}""")
        socket.incoming.trySend("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$chunk"}}]},"outputTranscription":{"text":"It's off, says the BBC."}}}""")
        socket.incoming.trySend("""{"serverContent":{"turnComplete":true}}""")
        runCurrent()
        assertTrue(events.any { it is LiveEvent.Audio && it.base64 == chunk })
        val turn = events.filterIsInstance<LiveEvent.Turn>().single()
        assertEquals("Rail strike?", turn.question)
        val answered = turn.answer as AskOutcome.Answered
        assertEquals("It's off, says the BBC.", answered.answer.text)
        assertEquals(1, answered.answer.sources.size)
        c.stop()
        runCurrent()
        assertEquals(LiveEvent.Ended(false), events.last())
        assertEquals(Triple("ls_1", 1L, 1L), core.usage.single())
        assertEquals(Triple(true, null, null), core.outcomes.single(), "Health hears this talk ran in Live")
    }

    @Test
    fun aSessionAskedForDuringTheHelloIsTheOneThatOpens() = runTest {
        // Slice 3b (the Mac's hello into Live): one session, asked for with the hello's words, opens when it ends.
        val core = FakeCore()
        val socket = FakeSocket()
        val (c, _) = conversation(core, socket)
        c.prepare("Morning, Alex. Two things need you.")
        runCurrent()
        advanceTimeBy(3_000) // the hello plays
        c.start()
        runCurrent()
        assertEquals(listOf<String?>("Morning, Alex. Two things need you."), core.asked)
        assertEquals("wss://g?access_token=tok", socket.url)
    }

    @Test
    fun aHelloEndedByHandReportsItsSessionAsNothing() = runTest {
        val core = FakeCore()
        val (c, events) = conversation(core, FakeSocket())
        c.prepare("Evening, Alex.")
        runCurrent()
        c.discardPrepared()
        runCurrent()
        assertEquals(Triple("ls_1", 0L, 0L), core.usage.single())
        assertTrue(events.isEmpty(), "no conversation started")
    }

    @Test
    fun noSessionFallsBackToNormalAdaAndSaysWhy() = runTest {
        val core = FakeCore(ready = false)
        val (c, events) = conversation(core, FakeSocket())
        c.start()
        runCurrent()
        assertEquals(listOf<LiveEvent>(LiveEvent.Ended(true, "Normal Obi · Live unavailable: this month's Live allowance is used up")), events)
        assertEquals(Triple(false, LiveDiagnosisRules.LiveFallback.OVER, null), core.outcomes.single())
    }

    @Test
    fun googleRefusingTheTokenIsQuotedOnTheStage() = runTest {
        // 2026-10-24: Live On but normal Obi answering is never silent: the server's reason (Google's own words) shows.
        val core = FakeCore(refused = "Google said 403: PERMISSION_DENIED: Requests are blocked.")
        val (c, events) = conversation(core, FakeSocket())
        c.start()
        runCurrent()
        assertEquals(LiveEvent.Ended(true, "Normal Obi · Live unavailable: Google said 403: PERMISSION_DENIED: Requests are blocked."), events.single())
        assertEquals(LiveDiagnosisRules.LiveFallback.REFUSED, core.outcomes.single().second)
        assertEquals("Normal Obi · Live unavailable: Google said 403: PERMISSION_DENIED: Requests are blocked.", c.fallbackLine)
    }

    @Test
    fun withATokenInHandGoogleGetsSixSecondsToSetUp() = runTest {
        // 2026-10-24: the token came at once; Google's set-up is given up to 6 s from asking, not 2.
        val socket = FakeSocket()
        val core = FakeCore()
        val (c, events) = conversation(core, socket)
        c.start()
        advanceTimeBy(LiveTalkRules.FALLBACK_MS + 1_000)
        runCurrent()
        assertTrue(events.isEmpty(), "still waiting for Google past 2 s")
        advanceTimeBy(LiveDiagnosisRules.CONNECT_MS - LiveTalkRules.FALLBACK_MS)
        runCurrent()
        assertEquals(LiveEvent.Ended(true, "Normal Obi · Live unavailable: Google took over 6 s to open the session"), events.single())
        assertEquals(LiveDiagnosisRules.LiveFallback.SLOW_GOOGLE, core.outcomes.single().second)
    }

    @Test
    fun googleClosingBeforeSetUpIsQuoted() = runTest {
        val socket = FakeSocket().apply { closeWith = "closed 1008: models/gemini-x is not found" }
        val core = FakeCore()
        val (c, events) = conversation(core, socket)
        c.start()
        runCurrent()
        socket.incoming.close()
        runCurrent()
        assertEquals(LiveEvent.Ended(true, "Normal Obi · Live unavailable: closed 1008: models/gemini-x is not found"), events.single())
        assertEquals(Triple(false, LiveDiagnosisRules.LiveFallback.CLOSED, "closed 1008: models/gemini-x is not found"), core.outcomes.single())
    }

    @Test
    fun onTheSpeakerHerEchoGoesUpAsSilenceButHisVoiceOverHersGoesUp() = runTest {
        val socket = FakeSocket()
        val (c, _) = conversation(FakeCore(), socket)
        c.start()
        runCurrent()
        socket.incoming.trySend("""{"setupComplete":{}}""")
        // A second of his voice queued.
        socket.incoming.trySend("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"${"A".repeat(64_000)}"}}]}}}""")
        runCurrent()
        c.sendAudio("ZZZZ", 3, -45f, headphones = false)
        runCurrent()
        assertTrue(!socket.sent.last().contains("ZZZZ") && socket.sent.last().contains("AAAA"), "quiet: silence goes up")
        // 2026-10-25: Meka over Obi goes up once his voice has lasted a quarter second (a clatter doesn't cut Obi off).
        val passed = (0 until 8).map {
            advanceTimeBy(40)
            c.sendAudio("YYYY", 3, -10f, headphones = false)
            runCurrent()
            socket.sent.last().contains("YYYY")
        }
        // Hotfix 2026-10-26: on the speaker nothing goes up while Obi speaks (he is stopped by a tap on the orb).
        assertEquals(List(8) { false }, passed, "on the speaker the mic waits while Obi speaks")
        c.sendAudio("XXXX", 3, -45f, headphones = true)
        runCurrent()
        assertTrue(socket.sent.last().contains("XXXX"), "headphones: everything goes up")
        c.stop()
    }

    @Test
    fun obisVoiceIsHeldAMomentThenPlaysAsOneStreamAndTalkingOverDropsWhatIsHeld() = runTest {
        val socket = FakeSocket()
        val core = FakeCore()
        val (c, events) = conversation(core, socket)
        c.start()
        runCurrent()
        socket.incoming.trySend("""{"setupComplete":{}}""")
        runCurrent()
        // 40 ms of his voice (2,560 base64 characters = 1,920 bytes): held, nothing plays yet.
        val forty = "A".repeat(2_560)
        socket.incoming.trySend("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$forty"}}]}}}""")
        runCurrent()
        assertTrue(events.none { it is LiveEvent.Audio }, "held until a little is queued")
        // Seven chunks (280 ms) queued: all of them go to the speaker at once.
        repeat(6) { socket.incoming.trySend("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$forty"}}]}}}""") }
        runCurrent()
        assertEquals(7, events.count { it is LiveEvent.Audio })
        advanceTimeBy(150)
        runCurrent()
        assertTrue(LiveEvent.Speaking(true) in events)
        // A new chunk after he talked over Obi is held afresh; the turn's end plays what's held.
        socket.incoming.trySend("""{"serverContent":{"interrupted":true}}""")
        socket.incoming.trySend("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$forty"}}]}}}""")
        runCurrent()
        assertEquals(7, events.count { it is LiveEvent.Audio })
        socket.incoming.trySend("""{"serverContent":{"turnComplete":true}}""")
        runCurrent()
        assertEquals(8, events.count { it is LiveEvent.Audio })
        assertEquals(0, c.underruns)
        assertEquals(1, c.cutOffs)
        c.stop()
        runCurrent()
        // 2026-10-25: Health hears how the talk sounded: the speaker, one cut-off (no microphone yet: nothing went up), no waits.
        val a = core.audio.single()!!
        assertEquals(BargeInOutput.SPEAKER, a.output)
        assertEquals(1, a.cutOffs)
        assertEquals(0, a.waits)
    }

    @Test
    fun moneyLockedShowsUnlockAndAnUnknownToolIsRefused() = runTest {
        val socket = FakeSocket()
        val (c, events) = conversation(FakeCore(), socket)
        c.start()
        runCurrent()
        socket.incoming.trySend("""{"setupComplete":{}}""")
        socket.incoming.trySend("""{"toolCall":{"functionCalls":[{"id":"m1","name":"${LiveToolRules.MONEY_TOOL}","args":{"question":"takeaways"}}]}}""")
        runCurrent()
        socket.incoming.trySend("""{"toolCall":{"functionCalls":[{"id":"x1","name":"send_email","args":{}}]}}""")
        runCurrent()
        assertTrue(socket.sent.last().contains(LiveConversation.NO_TOOL))
        socket.incoming.trySend("""{"serverContent":{"outputTranscription":{"text":"Money's locked."}}}""")
        socket.incoming.trySend("""{"serverContent":{"turnComplete":true}}""")
        runCurrent()
        val answered = events.filterIsInstance<LiveEvent.Turn>().single().answer as AskOutcome.Answered
        assertTrue(answered.answer.unlockMoney != null)
        c.stop()
    }

    @Test
    fun base64Lengths() {
        assertEquals(0, LiveConversation.decodedBytes(""))
        assertEquals(4, LiveConversation.decodedBytes("AAAAAA=="))
        assertEquals(3, LiveConversation.decodedBytes("AAAA"))
        assertEquals(5, LiveConversation.decodedBytes("AAAAAAA="))
    }
}
