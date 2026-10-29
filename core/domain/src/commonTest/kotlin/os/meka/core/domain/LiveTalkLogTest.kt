package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-10-26 (Meka: Obi says "the same sentence 20 times" in Live): the talk's log and the repeat guard. */
class LiveTalkLogTest {
    @Test
    fun theSameOpeningWithinHalfAMinuteIsARepeat() {
        val log = LiveTalkLog(1_000)
        assertFalse(log.said("Right, so", 1_000))
        assertFalse(log.said("Right, so tomorrow you've got the dentist", 1_200))
        assertFalse(log.turnComplete("Right, so tomorrow you've got the dentist at half nine.", 4_000))
        log.interrupted(letThrough = false, nowMs = 5_000)
        assertTrue(log.said("Right — so, tomorrow you’ve got the", 6_000), "punctuation and curly quotes don't hide it")
        assertEquals("right so tomorrow you've got the", log.repeated)
        assertEquals(
            listOf(
                "0:00 Obi: \"right so tomorrow you've got the…\"",
                "0:04 Google stopped Obi with only silence going up",
                "0:05 Obi repeated \"right so tomorrow you've got the…\": Live stopped",
            ),
            log.lines(),
        )
        assertEquals(1, log.stops)
    }

    @Test
    fun differentTurnsShortOnesAndOldOnesAreNotRepeats() {
        val log = LiveTalkLog(0)
        assertFalse(log.turnComplete("Okay.", 1_000))
        assertFalse(log.turnComplete("Okay.", 2_000), "a short answer twice is fine")
        assertFalse(log.said("It's twelve degrees and dry at home", 3_000))
        assertFalse(log.turnComplete("It's twelve degrees and dry at home.", 5_000))
        assertFalse(log.said("The match kicks off at eight tonight", 6_000))
        log.turnComplete("", 8_000)
        assertFalse(log.said("It's twelve degrees and dry at home", 3_000 + LiveTalkLog.REPEAT_WINDOW_MS + 1), "over half a minute later")
        assertNull(log.repeated)
        assertEquals(3, log.turns)
    }

    @Test
    fun whenHeAsksForItAgainOneRepeatIsFine() {
        val log = LiveTalkLog(0)
        log.said("It's twelve degrees and dry at home", 0)
        log.turnComplete("It's twelve degrees and dry at home.", 2_000)
        log.heard("Sorry, say that again?")
        assertFalse(log.said("It's twelve degrees and dry at home", 3_000))
        log.turnComplete("It's twelve degrees and dry at home.", 5_000)
        assertTrue(log.said("It's twelve degrees and dry at home", 6_000), "a third time unasked is a loop")
        assertFalse(LiveTalkLogRules.asksAgain("Sorry about that, what's the weather?"))
        assertTrue(LiveTalkLogRules.asksAgain("Pardon?"))
    }

    @Test
    fun windowsAndGoingAwayAreLoggedAndKeptShort() {
        val log = LiveTalkLog(0)
        log.goingAway(61_000)
        log.newWindow(61_500)
        log.carriedOn(62_000)
        log.interrupted(letThrough = true, nowMs = 70_000)
        assertEquals(
            listOf("1:01 Google said the session is closing", "1:01 a fresh window opened", "1:02 the talk carried on in it", "1:10 Google stopped Obi for your voice"),
            log.lines(),
        )
        repeat(60) { log.goingAway(it.toLong()) }
        assertEquals(LiveTalkLog.MAX_ENTRIES, log.all.size)
        val many = (1..12).map { "line $it" }
        assertEquals((5..12).map { "line $it" }, LiveTalkLogRules.decode(LiveTalkLogRules.encode(many)))
        assertEquals(emptyList(), LiveTalkLogRules.decode(null))
    }

    @Test
    fun healthShowsTheLastTalksEventsAndWhyItStopped() {
        val cal = LocalCalendar.UTC
        val at = CivilDate.toEpochDay(2026, 10, 26) * CivilDate.DAY_MS + (20 * 60 + 40) * HealthRules.MIN
        val o = LiveDiagnosisRules.LiveOutcome(at, live = false, fallback = LiveDiagnosisRules.LiveFallback.REPEATING, detail = null,
            events = listOf("0:00 Obi: \"right so tomorrow you've got the…\"", "0:05 Obi repeated \"right so tomorrow you've got the…\": Live stopped"))
        val line = LiveDiagnosisRules.healthLine(o, at + 5 * 60_000, cal)!!
        assertEquals(
            "Last talk 20:40 · normal Obi: Obi started repeating himself, so Live stopped\n0:00 Obi: \"right so tomorrow you've got the…\"\n0:05 Obi repeated \"right so tomorrow you've got the…\": Live stopped",
            line,
        )
        assertEquals("Normal Obi · Live unavailable: Obi started repeating himself, so Live stopped", LiveDiagnosisRules.stageLine(LiveDiagnosisRules.LiveFallback.REPEATING, null))
        // The stored outcome reads back (its events are kept beside it on the device).
        assertEquals(LiveDiagnosisRules.LiveFallback.REPEATING, LiveDiagnosisRules.decode(LiveDiagnosisRules.encode(o))!!.fallback)
    }

    @Test
    fun onTheSpeakerTheMicWaitsForTheEchoToDieAway() {
        assertTrue(LiveTalkRules.micHearsObi(BargeInOutput.SPEAKER, 10_000, 10_000 + LiveTalkRules.SPEAKING_TAIL_MS + 100))
        assertFalse(LiveTalkRules.micHearsObi(BargeInOutput.SPEAKER, 10_000, 10_000 + LiveTalkRules.SPEAKER_ECHO_TAIL_MS))
        assertFalse(LiveTalkRules.micHearsObi(BargeInOutput.HEADPHONES, 10_000, 10_000 + LiveTalkRules.SPEAKING_TAIL_MS))
        assertFalse(LiveTalkRules.micHearsObi(BargeInOutput.SPEAKER, 0, 100), "nothing played yet")
    }

    /** 2026-10-29 (Meka: "it says hello in English, then … replies in an Indian language"): Health shows when it happened. */
    @Test
    fun anotherLanguageHeardOrSpokenIsNotedOnceATurnWithoutHisWords() {
        assertTrue(LiveTalkLogRules.otherScript("आज मेरा दिन कैसा है"))
        assertTrue(LiveTalkLogRules.otherScript("இன்று என்ன"))
        assertFalse(LiveTalkLogRules.otherScript("What's on today, Obi?"))
        assertFalse(LiveTalkLogRules.otherScript("Café with Zoë at Barça"), "accented Latin is English enough")
        assertFalse(LiveTalkLogRules.otherScript("दि"), "too short to tell")
        val log = LiveTalkLog(0)
        log.heard("आज मेरा", 1_000)
        log.heard("आज मेरा दिन कैसा है", 1_500)
        log.said("आपका दिन आज", 2_000)
        log.turnComplete("आपका दिन आज बहुत अच्छा है", 4_000)
        log.heard("How is my day", 6_000)
        log.said("Your day looks calm, you've got", 7_000)
        log.turnComplete("Your day looks calm, you've got the dentist.", 9_000)
        val lines = log.lines()
        assertEquals(listOf("0:01 you were heard as another language", "0:02 Obi answered in another language"), lines.take(2))
        assertEquals(1, lines.count { it.endsWith("heard as another language") })
        assertEquals(1, lines.count { it.endsWith("answered in another language") })
        assertFalse(lines.any { "मेरा" in it }, "never his words")
        log.heard("फिर से बोलिए", 10_000)
        assertEquals(2, log.lines().count { it.endsWith("heard as another language") }, "a new turn notes it again")
    }
}
