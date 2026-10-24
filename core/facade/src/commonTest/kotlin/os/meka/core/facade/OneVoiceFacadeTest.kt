package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
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
import kotlin.test.assertNull

/**
 * Meka 2026-10-24 19:29: Obi spoke in a woman's voice when the orb was tapped and a man's on Ask. Clips kept in memory
 * from the server's old default voice must never play once the server speaks in another.
 */
class OneVoiceFacadeTest {
    private var now = 1_792_872_000_000L

    private class Server(service: SyncService) : SyncTransport, SpeechApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val said = mutableListOf<String>()
        var speaker = "Amy"
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?): SpeechCodec.Response {
            said += text
            return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = speaker + ":" + text.length, format = "mp3", voice = voice ?: speaker)
        }
        override suspend fun speechVoices() = SpeechCodec.Voices(SpeechCodec.Voices.ON, defaultVoice = speaker)
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aClipFromTheOldDefaultVoiceIsMadeAgainOnceTheServerSpeaksInAnother() = runTest {
        val c = core()
        assertNull(c.speechVoiceInUse())
        assertEquals("Amy:14", c.speechClip("Morning, Meka.", first = true))
        assertEquals("Amy:14", c.speechClip("Morning, Meka.", first = true)) // from memory
        assertEquals(1, server.said.size)
        assertEquals("Amy", c.speechVoiceInUse())
        // The server's default becomes Brian (a deploy; the phone keeps running): Ask's next answer comes in his voice…
        server.speaker = "Brian"
        assertEquals("Brian:15", c.speechClip("Here's the gist", first = true))
        assertEquals("Brian", c.speechVoiceInUse())
        // …and the hello kept from Amy is no longer played: it's asked for again, in Brian.
        assertEquals("Brian:14", c.speechClip("Morning, Meka.", first = true))
        assertEquals(3, server.said.size)
        // Brian's clips stay.
        c.speechClip("Here's the gist", first = true)
        assertEquals(3, server.said.size)
    }

    @Test
    fun theHelloWarmChecksTheDefaultVoiceBeforeTheNextTap() = runTest {
        val c = core()
        c.speechClip("Zebra crossing.", first = true)
        server.speaker = "Brian"
        // No answer has come in Brian yet: the 15-minute warm looks at the voice list and drops Amy's clips.
        c.warmHello()
        val before = server.said.size
        assertEquals("Brian:15", c.speechClip("Zebra crossing.", first = true))
        assertEquals(before + 1, server.said.size)
    }

    @Test
    fun aChosenVoiceIsLeftAloneByTheDefaultCheck() = runTest {
        val c = core()
        c.chooseMekaVoice("Arthur")
        c.speechClip("Morning, Meka.", first = true)
        server.speaker = "Brian"
        c.warmHello()
        val before = server.said.size
        assertEquals("Amy:14", c.speechClip("Morning, Meka.", first = true))
        assertEquals(before, server.said.size, "a chosen voice's clips are kept")
    }
}
