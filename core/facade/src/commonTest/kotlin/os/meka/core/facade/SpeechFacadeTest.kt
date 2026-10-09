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
        var voices = SpeechCodec.Voices(SpeechCodec.Voices.ON)
        override suspend fun speechVoices(): SpeechCodec.Voices {
            if (down) throw TransportException("offline")
            return voices
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aPieceIsSaidInMekasVoiceOnceAndThenPlaysFromTheDevice() = runTest {
        val c = core()
        assertEquals("bXAz30", c.speechClip("You've got three things today.", first = true))
        assertEquals("bXAz30", c.speechClip(" You've got three things today. ", first = false))
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

    @Test
    fun activityShowsTheMonthsVoiceCharacters() = runTest {
        assertNull(core(transport = null).voiceUsageLine()) // not connected
        val c = core()
        server.voices = SpeechCodec.Voices(
            SpeechCodec.Voices.ON,
            listOf(SpeechCodec.Voice("Amy", "Female", "generative"), SpeechCodec.Voice("Brian", "Male", "neural")),
            "Amy", "2026-10", 12_400, 1_000_000,
        )
        assertEquals("MEKA's voice · Amy · 12,400 of 1,000,000 characters in October", c.voiceUsageLine())
        assertTrue(c.chooseMekaVoice("Brian"))
        assertEquals("MEKA's voice · Brian · 12,400 of 1,000,000 characters in October", c.voiceUsageLine())
        // A voice this server doesn't offer is said by its default, so that's the one named.
        assertTrue(c.chooseMekaVoice("Zed"))
        assertEquals("MEKA's voice · Amy · 12,400 of 1,000,000 characters in October", c.voiceUsageLine())
        server.down = true
        assertNull(c.voiceUsageLine())
        // The device's own voice: said without asking the server.
        assertTrue(c.chooseMekaVoice("device"))
        assertEquals("MEKA's voice · the device's own voice, nothing is sent", c.voiceUsageLine())
        server.down = false
        assertTrue(c.chooseMekaVoice(null))
        server.voices = SpeechCodec.Voices(SpeechCodec.Voices.OFF)
        assertNull(c.voiceUsageLine())
    }

    @Test
    fun thePickerListsTheServersVoicesAndSamplesAnyOfThem() = runTest {
        // Not connected: only the device's own voice, and why.
        val offline = core(transport = null).voicePicker(mac = false)
        assertEquals(listOf("device"), offline.choices.map { it.id })
        assertTrue(offline.statusLine!!.contains("Until this device is connected"))
        assertNull(core(transport = null).speechSample("Amy"))
        val c = core()
        server.voices = SpeechCodec.Voices(
            SpeechCodec.Voices.ON,
            listOf(SpeechCodec.Voice("Amy", "Female", "generative"), SpeechCodec.Voice("Brian", "Male", "neural")),
            "Amy", "2026-10", 12_400, 1_000_000,
        )
        val v = c.voicePicker(mac = true)
        assertEquals(listOf("Amy", "Brian", "device"), v.choices.map { it.id })
        assertEquals("Amy", v.choices.single { it.selected }.id)
        assertEquals("MEKA's voice · Amy · 12,400 of 1,000,000 characters in October", v.usageLine)
        // Choosing one lights it on the next look (and follows to every device: it's the synced setting).
        assertTrue(c.chooseMekaVoice("Brian"))
        assertEquals("Brian", c.voicePicker(mac = true).choices.single { it.selected }.id)
        // ▶ Sample asks for that voice whatever is chosen, once, then plays from memory; the device's row isn't sent.
        val sample = c.speechSample("Amy")
        assertEquals("bXAz" + os.meka.core.domain.VoicePickerRules.SAMPLE.length, sample)
        assertEquals(sample, c.speechSample("Amy"))
        assertEquals(listOf<Pair<String, String?>>(os.meka.core.domain.VoicePickerRules.SAMPLE to "Amy"), server.said)
        assertNull(c.speechSample("device"))
        assertEquals(1, server.said.size)
        // Refused or unreachable: no clip, the device says it.
        server.answer = { SpeechCodec.Response(SpeechCodec.Response.OVER) }
        assertNull(c.speechSample("Brian"))
        server.down = true
        assertNull(c.speechSample("Emma"))
        val unreachable = c.voicePicker(mac = false)
        assertEquals(listOf("Brian", "device"), unreachable.choices.map { it.id })
        assertTrue(!unreachable.choices.first().sample && unreachable.choices.first().selected)
    }
}
