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
        val asked = mutableListOf<String?>()
        var speaker = "Amy"
        var googleUp = true
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun speak(text: String, voice: String?): SpeechCodec.Response {
            said += text
            asked += voice
            // The server speaks a Google voice by name while Google answers, else its default stands in.
            val google = voice == "Charon" || voice == "Puck"
            val by = if (google && googleUp) voice!! else speaker
            return SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = by + ":" + text.length, format = "mp3", voice = if (google) by else voice ?: speaker)
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

    /** Meka 2026-10-25 14:38: the hello came in Brian, then Live answered in Charon. One conversation, one voice. */
    @Test
    fun withLiveOnTheHelloAndEveryLineAskForLivesVoice() = runTest {
        val c = core()
        server.speaker = "Brian"
        assertEquals("Brian:14", c.speechClip("Morning, Meka.", first = true))
        assertEquals("Brian", c.speechVoiceInUse())
        c.setLiveOn(true)
        // The hello kept in Brian isn't played: it's asked for in Live's voice, and so is normal Obi's line if Live drops.
        assertEquals("Charon:14", c.speechClip("Morning, Meka.", first = true))
        assertEquals("Charon:20", c.speechClip("Here's what I found.", first = false, reading = false, hold = true))
        assertEquals(listOf(null, "Charon", "Charon"), server.asked)
        assertEquals("Charon · the same as Live", c.speechVoiceInUse())
        // A new Live voice: the next lines follow it.
        assertEquals(true, c.setLiveVoice("Puck"))
        assertEquals("Puck:14", c.speechClip("Morning, Meka.", first = true))
        // Live off: back to the server's default, and its clip kept from before is played again.
        c.setLiveOn(false)
        val before = server.said.size
        assertEquals("Brian:14", c.speechClip("Morning, Meka.", first = true))
        assertEquals("Brian", c.speechVoiceInUse())
        assertEquals(before, server.said.size)
    }

    @Test
    fun healthSaysWhenAnotherVoiceStoodInForLive() = runTest {
        val c = core()
        server.speaker = "Brian"
        server.googleUp = false
        c.setLiveOn(true)
        assertEquals("Brian:14", c.speechClip("Morning, Meka.", first = true))
        assertEquals("Charon", server.asked.last())
        assertEquals("Brian · not Live's Charon", c.speechVoiceInUse())
    }

    @Test
    fun theDevicesOwnVoiceStaysTheDevicesWithLiveOn() = runTest {
        val c = core()
        c.chooseMekaVoice(os.meka.core.domain.MekaVoiceRules.DEVICE)
        c.setLiveOn(true)
        assertNull(c.speechClip("Morning, Meka.", first = true))
        assertEquals(0, server.said.size)
    }
}
