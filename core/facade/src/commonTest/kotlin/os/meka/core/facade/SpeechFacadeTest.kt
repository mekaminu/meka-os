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
        // Slower than 4 s to the first audio: the device speaks this time, and the next line asks again.
        server.slowMs = 4_500
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
        assertEquals("Navilife's voice · Amy · 12,400 of 1,000,000 characters in October", c.voiceUsageLine())
        assertTrue(c.chooseMekaVoice("Brian"))
        assertEquals("Navilife's voice · Brian · 12,400 of 1,000,000 characters in October", c.voiceUsageLine())
        // A voice this server doesn't offer is said by its default, so that's the one named.
        assertTrue(c.chooseMekaVoice("Zed"))
        assertEquals("Navilife's voice · Amy · 12,400 of 1,000,000 characters in October", c.voiceUsageLine())
        server.down = true
        assertNull(c.voiceUsageLine())
        // The device's own voice: said without asking the server.
        assertTrue(c.chooseMekaVoice("device"))
        assertEquals("Navilife's voice · the device's own voice, nothing is sent", c.voiceUsageLine())
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
        assertEquals("Navilife's voice · Amy · 12,400 of 1,000,000 characters in October", v.usageLine)
        // Choosing one lights it on the next look (and follows to every device: it's the synced setting).
        assertTrue(c.chooseMekaVoice("Brian"))
        assertEquals("Brian", c.voicePicker(mac = true).choices.single { it.selected }.id)
        // ▶ Sample asks for that voice whatever is chosen, once, then plays from memory; the device's row isn't sent.
        val words = c.speechSampleWords()
        val sample = c.speechSample("Amy")
        assertEquals("bXAz" + words.length, sample)
        assertEquals(sample, c.speechSample("Amy"))
        assertEquals(listOf<Pair<String, String?>>(words to "Amy"), server.said)
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

    @Test
    fun theSampleIsTheStartOfTodaysBriefKeptPerVoiceSoReplaysAreFree() = runTest {
        now = 1_791_786_600_000L // Mon 12 Oct 2026, 07:30 in London: the morning's brief
        val c = core()
        c.setAboutYou(os.meka.core.domain.AboutField.SHOWN_NAME, "Meka") // one name everywhere: the brief says Your name
        c.addTask("Renew passport")
        val brief = os.meka.core.domain.BriefScriptRules.script(c.briefView.value)
        val words = c.speechSampleWords()
        // The brief's first two sentences, not the stock line, and never the whole brief.
        assertTrue(words.startsWith("Good morning, Meka."), words)
        assertTrue(brief.startsWith(words), words)
        assertTrue(words.length <= os.meka.core.domain.VoicePickerRules.SAMPLE_MAX && words.length < brief.length)
        server.voices = SpeechCodec.Voices(
            SpeechCodec.Voices.ON,
            listOf(SpeechCodec.Voice("Aoede", "Female", "chirp3hd", "google"), SpeechCodec.Voice("Amy", "Female", "generative")),
            "Amy", "2026-10", 0, 1_000_000,
        )
        assertEquals(os.meka.core.domain.VoicePickerRules.SAMPLE_FROM_BRIEF, c.voicePicker(mac = false).sampleLine)
        // Each voice is asked once; playing either again sends nothing.
        assertEquals("bXAz" + words.length, c.speechSample("Aoede"))
        assertEquals("bXAz" + words.length, c.speechSample("Amy"))
        repeat(os.meka.core.domain.SpeechRules.CACHE_CLIPS + 1) { c.speechClip("Line $it.", first = false) } // a long Talk
        c.speechSample("Aoede"); c.speechSample("Amy")
        assertEquals(listOf("Aoede", "Amy"), server.said.filter { it.first == words }.map { it.second })
        // Before there's a brief: the stock line, and the picker says so.
        val fresh = core(transport = null)
        if (fresh.briefView.value.dateLabel.isBlank()) {
            assertEquals(os.meka.core.domain.VoicePickerRules.SAMPLE, fresh.speechSampleWords())
        }
    }

    @Test
    fun theBriefWaitsLongerForItsFirstPieceAndSaysWhenTheVoiceIsResting() = runTest {
        assertTrue(core(transport = null).speechResting())
        val c = core()
        assertEquals(false, c.speechResting())
        // A cold server taking 4.5 s: a conversation's first piece goes to the device, the brief's waits for MEKA's voice.
        server.slowMs = 4_500
        assertNull(c.speechClip("Good morning, Meka.", first = true))
        assertEquals("bXAz19", c.speechClip("Good morning, Meka.", first = true, reading = true))
        server.slowMs = 6_000 // even the brief gives up after 5 s
        assertNull(c.speechClip("It's Friday.", first = true, reading = true))
        server.slowMs = 0
        assertEquals(false, c.speechResting()) // slow isn't a refusal
        // Offline: resting for a minute, so the brief's device voice says the rest in one go.
        server.down = true
        assertNull(c.speechClip("Offline.", first = false, reading = true))
        assertTrue(c.speechResting())
        server.down = false
        now += 61_000
        assertEquals(false, c.speechResting())
        // The device's own voice chosen: always resting.
        assertTrue(c.chooseMekaVoice("device"))
        assertTrue(c.speechResting())
    }

    @Test
    fun aReadingNeverSwitchesVoiceWhileTheServerIsStillAnswering() = runTest {
        val c = core()
        // The matches bit took Polly 10 s: a conversation's later piece goes to the device after 6 s…
        server.slowMs = 10_000
        assertNull(c.speechClip("Theo's under-sevens play Eastfield at ten o'clock.", first = false, reading = false))
        // …the brief waits for it in MEKA's voice (up to 15 s), and slow still isn't a refusal.
        assertEquals("bXAz" + "Finn's under-tens play Northam.".length, c.speechClip("Finn's under-tens play Northam.", first = false, reading = true))
        assertEquals(false, c.speechResting())
        // Past 15 s only that piece goes to the device.
        server.slowMs = 16_000
        assertNull(c.speechClip("In the news.", first = false, reading = true))
        assertEquals(false, c.speechResting())
    }

    @Test
    fun aHoldingPieceWaitsLongerAndQuietly() = runTest {
        val c = core()
        c.warmVoice()
        // Talking to Ada like a conversation, slice 1: no "One moment…" is made ahead any more.
        assertTrue(server.said.none { it.first.startsWith("One moment") })
        val asked = server.said.size
        // 7 s to answer: too slow without a hold (4 s), in time with one (4 s + 6 s).
        server.slowMs = 7_000
        assertNull(c.speechClip("Your dentist moved to Friday.", first = true, reading = false, hold = false))
        assertEquals("bXAz" + "Your dentist moved to Friday.".length, c.speechClip("Your dentist moved to Friday.", first = true, reading = false, hold = true))
        assertEquals(asked + 2, server.said.size)
    }

    @Test
    fun activitySaysHowQuicklyMekasVoiceAnsweredOnThisDevice() = runTest {
        val c = core()
        assertNull(c.voiceTimingLine())
        server.answer = { now += 1_800; SpeechCodec.Response(SpeechCodec.Response.SPOKEN, audio = "bXAz", format = "mp3") }
        c.speechClip("First.", first = true)
        c.speechClip("First.", first = true) // from memory: not counted
        assertEquals("Time to Navilife's voice · 1.8 s", c.voiceTimingLine())
        server.slowMs = 4_500
        assertNull(c.speechClip("Slow.", first = true))
        assertEquals("Time to Navilife's voice · late · 1.8 s", c.voiceTimingLine())
        server.slowMs = 0
        server.answer = { now += 300; SpeechCodec.Response(SpeechCodec.Response.OFF) }
        assertNull(c.speechClip("Off.", first = true))
        assertTrue(c.voiceTimingLine()!!.startsWith("Time to Navilife's voice · no answer · late · 1.8 s"))
    }

    @Test
    fun activitySaysHowQuicklyWhatHeSaidWasAdded() = runTest {
        val c = core()
        assertNull(c.heardTimingLine())
        c.recordHeardTiming(640, os.meka.core.domain.ListenEngine.ONLINE)
        c.recordHeardTiming(-5, os.meka.core.domain.ListenEngine.ONLINE) // a clock jump: dropped
        c.recordHeardTiming(1_300, os.meka.core.domain.ListenEngine.ON_DEVICE)
        assertEquals("Speech to Added · 1.3 s slow offline · 0.6 s", c.heardTimingLine())
    }

    @Test
    fun offlineTheDeviceVoiceSaysSoOnceUntilNavilifesVoiceIsHeardAgain() = runTest {
        val c = core()
        // Slow isn't offline, and nothing failed yet: no note.
        assertNull(c.speechOfflineNote(mac = false))
        server.down = true
        assertNull(c.speechClip("Hello.", first = true))
        assertEquals("Using the phone's voice while offline.", c.speechOfflineNote(mac = false))
        assertNull(c.speechOfflineNote(mac = false)) // once
        assertNull(c.speechClip("Still offline.", first = true)) // resting: not asked, no new note
        assertNull(c.speechOfflineNote(mac = false))
        // Back: Navilife's voice is heard, then a later spell says it again (the Mac's words on the Mac).
        server.down = false
        now += os.meka.core.domain.SpeechRules.FAILED_QUIET_MS + 1
        assertEquals("bXAz5", c.speechClip("Back.", first = true))
        assertNull(c.speechOfflineNote(mac = true))
        server.down = true
        assertNull(c.speechClip("Gone again.", first = true))
        assertEquals("Using this Mac's voice while Navilife's can't be reached.", c.speechOfflineNote(mac = true))
        // The device's own voice chosen: never said.
        val d = core()
        assertNull(d.speechClip("Hi.", first = true))
        assertTrue(d.chooseMekaVoice("device"))
        assertNull(d.speechOfflineNote(mac = false))
    }

    @Test
    fun clipsHeardOverBluetoothAreMarkedInTheTimingLine() = runTest {
        val c = core()
        c.speechRoute(bluetooth = true)
        c.speechClip("One.", first = true)
        c.speechRoute(bluetooth = false)
        c.speechClip("Two.", first = true)
        val line = c.voiceTimingLine()!!
        assertTrue(line.contains(" + Bluetooth"), line)
        assertTrue(line.endsWith(os.meka.core.domain.SpeechRules.BLUETOOTH_NOTE), line)
    }
}
