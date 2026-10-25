package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Obi's Live mode, slice 2: the synced switch and voice, the fallback, the meter lines and the counted seconds. */
class LiveTalkTest {
    @Test
    fun liveIsOffUntilTurnedOnAndFollowsToTheOtherDevice() {
        val world = os.meka.core.testing.SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val voice = MekaVoiceStore(a.replica)
        assertFalse(voice.liveOn())
        assertEquals(LiveModeRules.DEFAULT_VOICE, voice.liveVoice())
        voice.setLive(false)
        assertNull(a.replica.entity(EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID)) // nothing written for no change
        voice.choose("Brian")
        voice.setLive(true)
        assertTrue(voice.chooseLiveVoice("sulafat"))
        assertFalse(voice.chooseLiveVoice("Robot")) // not a voice the picker offers
        assertEquals("Sulafat", voice.liveVoice())
        assertEquals("Brian", voice.chosen()) // normal Obi's voice stays as it was
        a.syncWithRetry(); m.syncWithRetry()
        val mac = MekaVoiceStore(m.replica)
        assertTrue(mac.liveOn())
        assertEquals("Sulafat", mac.liveVoice())
        mac.setLive(false)
        m.syncWithRetry(); a.syncWithRetry()
        assertFalse(voice.liveOn())
    }

    @Test
    fun aSessionNotSetUpWithinTwoSecondsHandsOverToNormalAda() {
        assertFalse(LiveTalkRules.fallBack(1_000, 2_999, setUp = false))
        assertTrue(LiveTalkRules.fallBack(1_000, 3_000, setUp = false))
        assertFalse(LiveTalkRules.fallBack(1_000, 60_000, setUp = true))
        // The session ends 15 s before its token's window closes, never past the window.
        assertEquals(500 + 600_000 - 15_000, LiveTalkRules.endAtElapsed(500, 10_000, 10_000 + 600_000))
        assertEquals(500, LiveTalkRules.endAtElapsed(500, 10_000, 9_000))
        assertEquals(500 + LiveModeRules.TOKEN_MS, LiveTalkRules.endAtElapsed(500, 0, 10 * LiveModeRules.TOKEN_MS))
    }

    @Test
    fun secondsAndTheOrbsLevelComeFromTheAudio() {
        assertEquals(0, LiveTalkRules.seconds(0, 16_000))
        assertEquals(1, LiveTalkRules.seconds(1, 16_000))
        assertEquals(1, LiveTalkRules.seconds(32_000, 16_000))
        assertEquals(2, LiveTalkRules.seconds(48_001, 24_000) + 0) // 1.0000… s at 24 kHz rounds up to 2
        assertEquals(640, LiveTalkRules.CHUNK_SAMPLES)
        assertEquals(0f, LiveTalkRules.level(-90f))
        assertEquals(1f, LiveTalkRules.level(0f))
        assertEquals(0.5f, LiveTalkRules.level(-30f))
        // 48,000 bytes at 24 kHz is one second of Obi; a chunk arriving while the last still plays queues behind it.
        assertEquals(2_000, LiveTalkRules.playUntil(0, 1_000, 48_000))
        assertEquals(3_000, LiveTalkRules.playUntil(2_000, 1_500, 48_000))
        assertTrue(LiveTalkRules.speaking(2_000, 2_200))
        assertFalse(LiveTalkRules.speaking(2_000, 2_300))
        // On the speaker only Meka's voice clearly over Obi's goes up while Obi speaks; with headphones everything.
        assertFalse(LiveTalkRules.sendWhileSpeaking(BargeInOutput.SPEAKER, -40f))
        // Obi's echo from the speaker (often -30 to -15 dBFS) stays muted; Meka speaking right at the phone gets through.
        assertFalse(LiveTalkRules.sendWhileSpeaking(BargeInOutput.SPEAKER, -20f))
        assertTrue(LiveTalkRules.sendWhileSpeaking(BargeInOutput.SPEAKER, -10f))
        assertTrue(LiveTalkRules.sendWhileSpeaking(BargeInOutput.HEADPHONES, -60f))
    }

    @Test
    fun theSettingsSayWhatLiveCostsWhyItCantRunAndWhatGoesToGoogle() {
        val off = LiveTalkRules.view(false, null, meterKnown = true, configured = true, level = "ok", spentPence = 340, capPence = 1_500)
        assertEquals(LiveTalkRules.OFF_LINE, off.line)
        assertEquals("£3.40 of £15 this month", off.meterLine)
        assertFalse(off.showVoices)
        assertEquals(LiveModeRules.SHARING, off.sharing)
        val on = LiveTalkRules.view(true, "Leda", meterKnown = true, configured = true, level = "heads_up", spentPence = 1_250, capPence = 1_500)
        assertEquals("£12.50 of £15 this month · nearly used up", on.line)
        assertNull(on.meterLine)
        assertTrue(on.showVoices)
        assertEquals(listOf("Leda"), on.voices.filter { it.selected }.map { it.name })
        assertEquals("Charon", LiveTalkRules.VOICES.first().name) // the default first: Obi, a man's voice
        assertEquals(LiveModeRules.DEFAULT_VOICE, LiveTalkRules.voice("nobody"))
        assertEquals(LiveTalkRules.NO_KEY, LiveTalkRules.view(true, null, true, configured = false, "ok", 0, 1_500).line)
        assertEquals(LiveTalkRules.UNREACHABLE, LiveTalkRules.view(true, null, meterKnown = false, false, "ok", 0, 0).line)
        assertNull(LiveTalkRules.view(false, null, meterKnown = false, false, "ok", 0, 0).meterLine)
        assertEquals("Live is off until the 1st · this month's £15 is used up",
            LiveTalkRules.view(true, null, true, true, "over", 1_500, 1_500).line)
    }

    @Test
    fun activitysLiveLineSaysTheMonthsTalksMinutesAndCost() {
        // Slice 2b.
        val r = LiveTalkRules
        assertNull(r.activityLine(false, "2026-10", 0, 0, "ok", 0, 1_500)) // no key, nothing used: nothing to say
        assertEquals("Obi's Live · no talks yet in October · up to £15", r.activityLine(true, "2026-10", 0, 0, "ok", 0, 1_500))
        assertEquals("Obi's Live · 3 talks · 13 min · £0.42 of £15 in October", r.activityLine(true, "2026-10", 3, 754, "ok", 42, 1_500))
        assertEquals("Obi's Live · 1 talk · £0.19 of £15 this month", r.activityLine(true, null, 1, 0, "ok", 19, 1_500)) // not reported yet
        assertEquals(
            "Obi's Live · 40 talks · 300 min · £15 of £15 in December · off until 1 Jan",
            r.activityLine(true, "2026-12", 40, 18_000, "over", 1_500, 1_500),
        )
        assertEquals("Obi's Live · 2 talks · 1 min · £0.05 of £15 this month · off until the 1st", r.activityLine(false, "bad", 2, 1, "over", 5, 1_500))
    }

    @Test
    fun slice4ALiveSearchNeverCarriesHisLifeAndATurnReadsAsAnAnswerWithSources() {
        assertEquals("rail strike latest", LiveFactsRules.searchable("  rail   strike latest "))
        assertEquals("Barcelona weather Saturday", LiveFactsRules.searchable("Barcelona weather Saturday"))
        assertNull(LiveFactsRules.searchable("my dentist opening hours"))
        assertNull(LiveFactsRules.searchable("Oakfield School inset days AB1 2CD"))
        assertNull(LiveFactsRules.searchable("call 0123456789"))
        assertNull(LiveFactsRules.searchable("alex@example.com"))
        assertNull(LiveFactsRules.searchable("Alex football results", listOf("Alex", "Sam")))
        assertEquals("Alexandria weather", LiveFactsRules.searchable("Alexandria weather", listOf("Alex")))
        assertNull(LiveFactsRules.searchable("ok"))
        // Ask's own filter is unchanged.
        assertNull(LiveFactsRules.query("history of the railways"))
        assertEquals("latest on the rail strike", LiveFactsRules.query("latest on the rail strike"))

        val cards = LiveFactsRules.sources(listOf("Strike off" to "https://www.bbc.co.uk/news/1", "x" to "http://plain"))
        val a = LiveTalkRules.turnAnswer("  The strike's  off,\n says the BBC. ", cards) as AskOutcome.Answered
        assertEquals("The strike's off, says the BBC.", a.answer.text)
        assertEquals("bbc.co.uk", a.answer.sources.single().site)
        assertNull(LiveTalkRules.turnAnswer(" ", cards))
        assertEquals(LiveTalkRules.HEARD_NONE, LiveTalkRules.turnQuestion(""))
        assertEquals("is the strike on", LiveTalkRules.turnQuestion(" is the strike on "))
    }
}
