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
import os.meka.core.domain.LiveMoney
import os.meka.core.domain.LiveOffer
import os.meka.core.domain.LiveTalkRules
import os.meka.core.domain.LiveToolRules
import os.meka.core.wire.LiveCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ada's Live mode, slice 3: the conversation the Mac runs in the core, against a fake Google and a fake core. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveConversationTest {
    private class FakeSocket : LiveSocket {
        val sent = mutableListOf<String>()
        val incoming = Channel<String>(Channel.UNLIMITED)
        var url: String? = null
        override suspend fun run(url: String, outgoing: ReceiveChannel<String>, onText: suspend (String) -> Unit) = coroutineScope {
            this@FakeSocket.url = url
            val s = launch { for (m in outgoing) sent += m }
            for (t in incoming) onText(t)
            s.cancel()
        }
    }

    private class FakeCore(var ready: Boolean = true) : LiveConversation.Core {
        val usage = mutableListOf<Triple<String, Long, Long>>()
        val searches = mutableListOf<String>()
        override suspend fun voice(): String? = "Kore"
        override suspend fun session(voice: String?, greeted: String?, resume: String?): LiveCodec.Session? =
            if (!ready) LiveCodec.Session(LiveCodec.Session.OVER, LiveCodec.Meter(true, "over", 1_500, 1_500))
            else LiveCodec.Session(LiveCodec.Session.READY, LiveCodec.Meter(true, "ok", 0, 1_500), "ls_1", "tok", "gemini-live", "wss://g", voice, expiresAtMs = 600_000)
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
    }

    @Test
    fun noSessionFallsBackToNormalAda() = runTest {
        val (c, events) = conversation(FakeCore(ready = false), FakeSocket())
        c.start()
        runCurrent()
        assertEquals(listOf<LiveEvent>(LiveEvent.Ended(true)), events)
    }

    @Test
    fun aSetupThatNeverComesFallsBackAfterTwoSeconds() = runTest {
        val socket = FakeSocket()
        val (c, events) = conversation(FakeCore(), socket)
        c.start()
        advanceTimeBy(LiveTalkRules.FALLBACK_MS - 100)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(LiveEvent.Ended(true), events.single())
    }

    @Test
    fun onTheSpeakerHerEchoGoesUpAsSilenceButHisVoiceOverHersGoesUp() = runTest {
        val socket = FakeSocket()
        val (c, _) = conversation(FakeCore(), socket)
        c.start()
        runCurrent()
        socket.incoming.trySend("""{"setupComplete":{}}""")
        // A second of her voice queued.
        socket.incoming.trySend("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"${"A".repeat(64_000)}"}}]}}}""")
        runCurrent()
        c.sendAudio("ZZZZ", 3, -45f, headphones = false)
        runCurrent()
        assertTrue(!socket.sent.last().contains("ZZZZ") && socket.sent.last().contains("AAAA"), "quiet: silence goes up")
        c.sendAudio("YYYY", 3, -10f, headphones = false)
        runCurrent()
        assertTrue(socket.sent.last().contains("YYYY"), "him over her goes up")
        c.sendAudio("XXXX", 3, -45f, headphones = true)
        runCurrent()
        assertTrue(socket.sent.last().contains("XXXX"), "headphones: everything goes up")
        c.stop()
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
