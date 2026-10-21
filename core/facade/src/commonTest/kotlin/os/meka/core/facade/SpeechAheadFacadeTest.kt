package os.meka.core.facade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import os.meka.core.domain.SpeechRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.SpeechCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The orb speed pass, slice 3: the hello's openers made ahead in Navilife's voice (a tap plays them from memory), and a
 * clip asked for ahead (the rest of an answer while its first sentence plays) shared with the speaker's own ask.
 */
class SpeechAheadFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 16:15 UTC (17:15 in London)

    private class Server(service: SyncService) : SyncTransport, SpeechApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val said = mutableListOf<String>()
        var slowMs = 0L
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?): SpeechCodec.Response {
            said += text
            if (slowMs > 0) delay(slowMs)
            return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz" + text.length, format = "mp3")
        }
        override suspend fun speechVoices() = SpeechCodec.Voices(SpeechCodec.Voices.ON)
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theHellosOpeningIsMadeAhead() = runTest {
        val c = core()
        c.warmHello()
        assertTrue("Good evening." in server.said)
    }

    @Test
    fun adasAnswersToGoodThanksYouAreMadeAheadToo() = runTest {
        val c = core()
        c.warmHello()
        val line = os.meka.core.domain.HelloReplyRules.answer(os.meka.core.domain.HelloSmallTalk(os.meka.core.domain.HelloMood.GOOD, true), 0)
        SpeechRules.pieces(line).forEach { assertTrue(it in server.said, it) }
    }

    @Test
    fun aTapOnTheOrbFindsItsOpeningInMemory() = runTest {
        val c = core()
        c.warmHello()
        val asked = server.said.size
        val first = SpeechRules.pieces(c.hello().spoken).first()
        assertEquals("Good evening.", first)
        assertEquals("bXAz13", c.speechClip(first, first = true))
        assertEquals(asked, server.said.size)
    }

    @Test
    fun aSecondCheckAsksNothing() = runTest {
        val c = core()
        c.warmHello()
        val asked = server.said.size
        c.warmHello()
        assertEquals(asked, server.said.size)
    }

    @Test
    fun theMacsWelcomeBackIsMadeToo() = runTest {
        val c = core()
        c.warmHello()
        val asked = server.said.size
        c.warmHello(welcome = true)
        assertTrue("Welcome back." in server.said.drop(asked), server.said.drop(asked).toString())
    }

    @Test
    fun nothingIsMadeAheadWithTheDevicesOwnVoice() = runTest {
        val c = core()
        assertTrue(c.chooseMekaVoice("device"))
        c.warmHello()
        c.warmVoice()
        assertTrue(!c.speechPrefetch("The rest of the answer."))
        assertTrue(server.said.isEmpty())
    }

    @Test
    fun aClipAskedForAheadIsSharedNotAskedTwice() = runTest {
        // Real time here: the request ahead and the speaker's ask meet on the core's own thread.
        withContext(Dispatchers.Default) {
            val c = core()
            server.slowMs = 600
            // The rest of the answer is asked for while its first sentence plays; then the speaker reaches it.
            val ahead = async { c.speechPrefetch("The rest of the answer. And a little more.") }
            delay(150)
            assertEquals("bXAz23", c.speechClip("The rest of the answer.", first = false))
            assertTrue(ahead.await())
            assertEquals(listOf("The rest of the answer."), server.said.toList())
            // Once here, asking ahead again costs nothing.
            assertTrue(c.speechPrefetch("The rest of the answer."))
            assertEquals(1, server.said.size)
        }
    }
}

/** The orb speed pass, slice 4: the holding line on a slow answer is played from memory, never asked for then. */
class AnswerHoldFacadeTest {
    private val now = 1_791_476_100_000L

    private class Server(service: SyncService) : SyncTransport, SpeechApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val said = mutableListOf<String>()
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?): SpeechCodec.Response {
            said += text
            return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "clip:$text", format = "mp3")
        }
        override suspend fun speechVoices() = SpeechCodec.Voices(SpeechCodec.Voices.ON)
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun nothingIsSaidBeforeTheLinesAreMade() = runTest {
        val c = core()
        assertEquals(null, c.answerHoldClip())
        assertTrue(server.said.isEmpty())
    }

    @Test
    fun theHoldingLinesAreMadeWithTheHelloAndTakeTurns() = runTest {
        val c = core()
        c.warmHello()
        assertTrue("One sec…" in server.said)
        val asked = server.said.size
        val first = c.answerHoldClip()
        val second = c.answerHoldClip()
        assertEquals("clip:One sec…", first)
        assertEquals("clip:Bear with me…", second)
        assertEquals(asked, server.said.size)
    }

    @Test
    fun nothingWithTheDevicesOwnVoice() = runTest {
        val c = core()
        c.warmHello()
        assertTrue(c.chooseMekaVoice("device"))
        assertEquals(null, c.answerHoldClip())
    }
}
