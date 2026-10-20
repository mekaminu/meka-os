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
    fun aTapOnTheOrbPlaysTheHelloFromMemory() = runTest {
        val c = core()
        c.warmHello()
        val asked = server.said.toList()
        assertTrue("Good evening." in asked, asked.toString())
        // The tap: the greeting's opening comes from memory, nothing is fetched (and a clear day's rest too).
        val g = c.hello()
        val pieces = SpeechRules.pieces(g.spoken)
        assertEquals("bXAz" + pieces.first().length, c.speechClip(pieces.first(), first = true))
        assertEquals(asked, server.said.toList())
        if (g.points.isEmpty()) {
            pieces.drop(1).forEach { assertEquals("bXAz" + it.length, c.speechClip(it, first = false)) }
            assertEquals(asked, server.said.toList())
        }
        // Checked again later: already here, nothing asked.
        c.warmHello()
        assertEquals(asked, server.said.toList())
        // The Mac's welcome back is made too.
        c.warmHello(welcome = true)
        assertEquals(listOf("Welcome back."), server.said.drop(asked.size))
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
