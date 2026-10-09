package os.meka.core.facade

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.SpeechCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MEKA's voice through the facade (Weather and a voice, item 3): clips, the device voice as the fallback, the cache. */
class SpeechFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 16:15 UTC

    private class Server(service: SyncService) : SyncTransport, SpeechApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val said = mutableListOf<Pair<String, String?>>()
        var answer: (String) -> SpeechCodec.Response = { SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz" + it.length, format = "mp3") }
        var slowMs = 0L
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?): SpeechCodec.Response {
            said += text to voice
            if (slowMs > 0) delay(slowMs)
            if (down) throw TransportException("offline")
            return answer(text)
        }
        override suspend fun speechVoices() = SpeechCodec.Voices(SpeechCodec.Voices.ON)
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aPieceIsSaidInMekasVoiceOnceAndThenPlaysFromTheDevice() = runTest {
        val c = core()
        assertEquals("bXAz29", c.speechClip("You've got three things today.", first = true))
        assertEquals("bXAz29", c.speechClip(" You've got three things today. ", first = false))
        assertEquals(listOf<Pair<String, String?>>("You've got three things today." to null), server.said) // asked once, then from memory
        // The chosen voice is asked for (and cached apart from the default's).
        assertTrue(c.chooseMekaVoice("Brian"))
        assertEquals("Brian", c.mekaVoice())
        c.speechClip("You've got three things today.", first = true)
        assertEquals("Brian", server.said.last().second)
        // The common lines are fetched once when a conversation starts.
        c.warmVoice(); c.warmVoice()
        assertEquals(1 + 1 + os.meka.core.domain.SpeechRules.COMMON.size, server.said.size)
    }

    @Test
    fun theDevicesOwnVoiceSpeaksWhenChosenNotConnectedSlowOrRefused() = runTest {
        // Not connected: nothing to ask.
        assertNull(core(transport = null).speechClip("Hello.", first = true))
        val c = core()
        // The device voice chosen: nothing is sent.
        assertTrue(c.chooseMekaVoice("device"))
        assertNull(c.speechClip("Hello.", first = true))
        assertTrue(server.said.isEmpty())
        assertTrue(c.chooseMekaVoice(null))
        // Too long for one piece, or blank: the device says it.
        assertNull(c.speechClip("x".repeat(601), first = true))
        assertNull(c.speechClip("  ", first = true))
        // Slower than 1.2 s to the first audio: the device speaks this time, and the next line asks again.
        server.slowMs = 1_500
        assertNull(c.speechClip("Slow one.", first = true))
        assertEquals("bXAz9", c.speechClip("Slow one.", first = false)) // a later piece may take up to 6 s
        server.slowMs = 0
        // Offline: the device speaks and the server is left alone for a minute.
        server.down = true
        assertNull(c.speechClip("Offline.", first = true))
        server.down = false
        val asked = server.said.size
        assertNull(c.speechClip("Back.", first = true))
        assertEquals(asked, server.said.size)
        now += 61_000
        assertEquals("bXAz5", c.speechClip("Back.", first = true))
        // Over the month's characters: the device speaks until the 1st (UTC).
        server.answer = { SpeechCodec.Response(SpeechCodec.Response.OVER) }
        assertNull(c.speechClip("Over.", first = true))
        server.answer = { SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz", format = "mp3") }
        now += 20 * 86_400_000L // 28 Oct
        assertNull(c.speechClip("Still over.", first = true))
        now = 1_793_491_200_000L // 1 Nov 2026 00:00 UTC
        assertEquals("bXAz", c.speechClip("New month.", first = true))
        // A server with no voice: rested for six hours.
        server.answer = { SpeechCodec.Response(SpeechCodec.Response.OFF) }
        assertNull(c.speechClip("Off.", first = true))
        server.answer = { SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz", format = "mp3") }
        now += 5 * 3_600_000L
        assertNull(c.speechClip("Later.", first = true))
        now += 3_600_000L
        assertEquals("bXAz", c.speechClip("Later.", first = true))
    }
}
