package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoldPostureTest {
    private val landscape = FoldReading(FoldHinge.NONE, windowWidthPx = 2176, windowHeightPx = 1812)
    private val portrait = FoldReading(FoldHinge.NONE, windowWidthPx = 1812, windowHeightPx = 2176)

    @Test
    fun windowManagerHalfOpenAcrossIsTheTableStand() {
        val r = portrait.copy(hinge = FoldHinge.HALF_OPENED, across = true, separating = false, occlusion = "NONE")
        assertEquals(PostureCall(FoldPosture.TABLETOP, PostureSource.WINDOW), FoldPostureRules.posture(r))
        assertEquals("Fold: half open, hinge across", FoldPostureRules.line(r))
    }

    @Test
    fun halfOpenInALandscapeWindowIsTheTableStandWhateverTheOrientationSays() {
        // The Fold 8's case: inner screen, landscape, half folded like a laptop, but the feature says VERTICAL.
        val r = landscape.copy(hinge = FoldHinge.HALF_OPENED, across = false)
        assertEquals(PostureCall(FoldPosture.TABLETOP, PostureSource.LANDSCAPE), FoldPostureRules.posture(r))
        assertEquals("Fold: half open, hinge down the middle · bedside from the landscape window", FoldPostureRules.line(r))
        // Held like a book (portrait window, hinge down the middle) stays the big screen.
        assertEquals(PostureCall(FoldPosture.BOOK, PostureSource.WINDOW), FoldPostureRules.posture(portrait.copy(hinge = FoldHinge.HALF_OPENED, across = false)))
    }

    @Test
    fun theHingeSensorAloneStandsItWhenSteadyLandscapeAndBetween60And130() {
        val r = landscape.copy(hingeAngle = 95f, hingeSteady = true)
        assertEquals(PostureCall(FoldPosture.TABLETOP, PostureSource.HINGE_SENSOR), FoldPostureRules.posture(r))
        assertEquals("Fold: no fold reported · hinge 95° · bedside from the hinge sensor", FoldPostureRules.line(r))
        // Moving, portrait, or outside the range: flat.
        assertEquals(FoldPosture.FLAT, FoldPostureRules.posture(r.copy(hingeSteady = false)).posture)
        assertEquals(FoldPosture.FLAT, FoldPostureRules.posture(portrait.copy(hingeAngle = 95f, hingeSteady = true)).posture)
        assertEquals(FoldPosture.FLAT, FoldPostureRules.posture(r.copy(hingeAngle = 170f)).posture)
        assertEquals(FoldPosture.FLAT, FoldPostureRules.posture(r.copy(hingeAngle = 40f)).posture)
        assertEquals(FoldPosture.TABLETOP, FoldPostureRules.posture(r.copy(hingeAngle = 60f)).posture)
        assertEquals(FoldPosture.TABLETOP, FoldPostureRules.posture(r.copy(hingeAngle = 130f)).posture)
        // WindowManager saying flat with the sensor at 175° is flat.
        val flat = landscape.copy(hinge = FoldHinge.FLAT, across = false, hingeAngle = 175f, hingeSteady = true)
        assertEquals(PostureCall(FoldPosture.FLAT, PostureSource.NONE), FoldPostureRules.posture(flat))
        assertEquals("Fold: flat · hinge 175°", FoldPostureRules.line(flat))
    }

    @Test
    fun theAngleIsSteadyOnlyWhenItHeldForASecondAndAHalf() {
        val now = 10_000L
        assertFalse(FoldPostureRules.steady(emptyList(), now))
        // Only a second of readings: not yet.
        assertFalse(FoldPostureRules.steady(listOf(9_000L to 95f, 9_800L to 96f), now))
        // Held within 6° since before the window: steady.
        assertTrue(FoldPostureRules.steady(listOf(8_000L to 95f, 9_000L to 97f, 9_900L to 94f), now))
        // Wandering more than 6°: not.
        assertFalse(FoldPostureRules.steady(listOf(8_000L to 95f, 9_000L to 110f), now))
        // An old swing before the window doesn't count.
        assertTrue(FoldPostureRules.steady(listOf(5_000L to 170f, 8_400L to 95f, 9_500L to 96f), now))
        assertEquals(listOf(8_400L to 95f, 9_500L to 96f), FoldPostureRules.trim(listOf(5_000L to 170f, 8_400L to 95f, 9_500L to 96f), now))
    }

    @Test
    fun theLogKeepsTwentyChangesNewestFirstAndSkipsRepeats() {
        val stand = landscape.copy(hinge = FoldHinge.HALF_OPENED, across = true)
        var log = FoldPostureLog.add(emptyList(), landscape, 1_000)
        assertEquals(1, log.size)
        assertEquals("NONE across=null sep=null occ=null 2176x1812 → FLAT/NONE", log[0].entry)
        log = FoldPostureLog.add(log, landscape.copy(windowWidthPx = 2000), 2_000) // a resize alone: no change
        assertEquals(1, log.size)
        log = FoldPostureLog.add(log, stand, 3_000)
        assertEquals(3_000, log[0].atMs)
        assertTrue(log[0].entry.endsWith("→ TABLETOP/WINDOW"))
        repeat(30) { i -> log = FoldPostureLog.add(log, if (i % 2 == 0) landscape else stand, 4_000L + i) }
        assertEquals(FoldPostureLog.KEEP, log.size)
        assertEquals(log, FoldPostureLog.decode(FoldPostureLog.encode(log)))
        assertEquals(emptyList(), FoldPostureLog.decode(null))
        assertEquals(emptyList(), FoldPostureLog.decode("junk\n|x\nabc|y"))
        val cal = LocalCalendar.UTC
        val at = CivilDate.toEpochDay(2026, 10, 12) * CivilDate.DAY_MS + (18 * 60 + 14) * 60_000L
        assertEquals("Mon 12 Oct 18:14 · x", FoldPostureLog.line(FoldPostureLog.Change(at, "x"), cal))
    }

    @Test
    fun theClockShowsOnATableOrByHandAndBackStepsOutUntilTheStandEnds() {
        assertTrue(BedsideEntryRules.shows(auto = true, manual = false, dismissed = false))
        assertFalse(BedsideEntryRules.shows(auto = true, manual = false, dismissed = true))
        assertTrue(BedsideEntryRules.shows(auto = false, manual = true, dismissed = false))
        assertFalse(BedsideEntryRules.shows(auto = false, manual = false, dismissed = false))
        // Back on a table stand steps out until it ends; Back from a hand-opened clock just closes it.
        assertTrue(BedsideEntryRules.dismissedAfterBack(auto = true))
        assertFalse(BedsideEntryRules.dismissedAfterBack(auto = false))
        assertFalse(BedsideEntryRules.dismissedAfterPosture(auto = false, dismissed = true))
        assertTrue(BedsideEntryRules.dismissedAfterPosture(auto = true, dismissed = true))
    }

    @Test
    fun talkOpensTheBedsideClockOnlyOnTheFold() {
        assertEquals(TalkPlace.BEDSIDE, TalkOpenRules.request("bedside clock", TalkOpenRules.FOLD))
        assertEquals(TalkPlace.BEDSIDE, TalkOpenRules.request("ok Navilife, open the bedside clock please", TalkOpenRules.FOLD))
        assertEquals(TalkPlace.BEDSIDE, TalkOpenRules.request("show me the night clock", TalkOpenRules.FOLD))
        assertEquals(TalkPlace.PLAN, TalkOpenRules.request("plan my day", TalkOpenRules.FOLD))
        assertNull(TalkOpenRules.request("bedside clock"))
        assertNull(TalkOpenRules.request("what time does the bedside clock dim", TalkOpenRules.FOLD))
        val heard = TalkFlow.heard(TalkFlow.start().session, "bedside clock", TalkOpenRules.FOLD)
        assertEquals(TalkEffect.Speak(BedsideEntryRules.OPENING), heard.effects.first())
        val done = TalkFlow.spoke(heard.session)
        assertEquals(TalkEffect.Open(TalkPlace.BEDSIDE), done.effects.last())
        // The Mac (no places beyond the planner): a question for MEKA.
        assertTrue(TalkFlow.heard(TalkFlow.start().session, "bedside clock").effects.first() is TalkEffect.Ask)
    }

    @Test
    fun postureChangesGoToTheServerOnceSettledAndRetryAfterAFailure() {
        val log = listOf(FoldPostureLog.Change(100_000, "b"), FoldPostureLog.Change(50_000, "a"))
        val r = FoldPostureReportRules
        assertFalse(r.due(emptyList(), 0, null, 1_000_000))
        // Settling: not before 20 s after the newest change.
        assertFalse(r.due(log, 0, null, 110_000))
        assertEquals(10_000L, r.waitMs(log, 0, null, 110_000))
        assertTrue(r.due(log, 0, null, 120_000))
        // Already sent through the newest: nothing to do.
        assertFalse(r.due(log, 100_000, null, 500_000))
        assertNull(r.waitMs(log, 100_000, null, 500_000))
        // A failed send waits 15 minutes.
        assertFalse(r.due(log, 0, 130_000, 140_000))
        assertEquals(r.RETRY_MS - 10_000, r.waitMs(log, 0, 130_000, 140_000))
        assertTrue(r.due(log, 0, 130_000, 130_000 + r.RETRY_MS))
        // Health's line.
        val cal = LocalCalendar.UTC
        val at = CivilDate.toEpochDay(2026, 10, 12) * CivilDate.DAY_MS + (18 * 60 + 20) * 60_000L
        assertNull(r.line(emptyList(), 0, null, cal))
        assertEquals("Not sent to Navilife's server yet", r.line(log, 0, null, cal))
        assertEquals("Sent to Navilife's server Mon 12 Oct 18:20", r.line(log, 100_000, at, cal))
        assertEquals("Sent to Navilife's server Mon 12 Oct 18:20 · the newest changes go shortly", r.line(log, 50_000, at, cal))
    }
}
