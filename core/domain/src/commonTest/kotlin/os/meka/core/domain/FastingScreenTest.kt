package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastingScreenTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private var n = 0
    private val d = world.device("android")
    private val f = Fasting(d.replica, { "F${n++}" }, { world.clock.nowMs })

    init {
        // Start every test on a Monday at 20:00 UTC.
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toMonday = (8 - CivilDate.isoDayOfWeek(today)) % 7
        world.clock.nowMs = (today + toMonday) * dayMs + 20 * hourMs
    }

    private fun mins(m: Int) = world.clock.advance(m * 60_000L)
    private fun hours(h: Int) = world.clock.advance(h * hourMs)
    private fun near(want: Float, got: Float) = assertTrue(kotlin.math.abs(want - got) < 0.001f, "$want vs $got")
    private fun screen() = FastingScreenRules.screen(f.view(), world.clock.nowMs)

    @Test
    fun aFastStartedAndEndedAtOnceIsNeverCounted() {
        // The quality gate's "Last fast 0 m · average 0 m · longest 0 m": Start then End straight after.
        f.start(); f.end()
        hours(1)
        f.start(); mins(9); f.end()
        val v = f.view()
        assertNull(v.last)
        assertNull(v.weekLine)
        assertNull(v.history.totalsLine)
        assertTrue(v.history.fasts.isEmpty())
        assertTrue(v.week.all { it.hours == 0.0 })
        assertTrue(f.ended().isEmpty())
        // A real fast after them is counted as usual, and nothing says 0 m.
        hours(1)
        f.start(); hours(16); f.end()
        val after = f.view()
        assertEquals("Last fast 16 h 00 m · goal reached", assertNotNull(after.last).line)
        assertEquals("1 fast in 7 days · average 16 h 00 m · 1 reached the goal", after.weekLine)
        assertEquals(1, after.history.fasts.size)
        assertFalse(listOfNotNull(after.last?.line, after.weekLine, after.history.totalsLine).any { "0 m" in it && "00 m" !in it })
    }

    @Test
    fun notFastingTheRingShowsThePlanAndWhenTheNextFastBegins() {
        val s = screen()
        assertEquals("16:8", s.planChip)
        assertEquals("Not fasting", s.topLine)
        assertEquals("16 h", s.bigTime)
        assertEquals("Next fast from 20:00", s.subLine)
        assertEquals("Start fast", s.mainLabel)
        assertFalse(s.isFasting)
        assertFalse(s.showTip)
        assertNull(s.now)
        assertNull(s.startedTile)
        assertEquals(listOf("4h", "8h", "12h"), s.marks.map { it.label })
        assertTrue(s.stages.none { it.reached || it.here })
        assertNull(s.weekLine)
        assertEquals(7, s.week.size)
    }

    @Test
    fun aRunningFastReadsLikeTheDesign() {
        f.start() // Monday 20:00, goal Tuesday 12:00
        hours(13); mins(30) // Tuesday 09:30
        val s = screen()
        assertEquals("Fasting", s.topLine)
        assertEquals("13:30", s.bigTime)
        assertEquals("Ends in 2:30 · 12:00", s.subLine)
        assertEquals("Mon 20:00", s.startedTile)
        assertEquals("Tue 12:00 · 16h", s.goalTile)
        assertEquals("End fast", s.mainLabel)
        assertTrue(s.showTip)
        near(13.5f / 16f, s.fraction)
        assertEquals(listOf(true, true, true), s.marks.map { it.passed })
        near(0.75f, s.marks.last().fraction)
        val now = assertNotNull(s.now)
        assertEquals("Switching to fat", now.stage.name)
        assertEquals("Well established", now.stage.evidence)
        assertEquals(EvidenceTone.STRONG, now.tone)
        assertEquals("Next: ketones rising in 2:30", now.next)
        assertEquals(listOf("digest", "sugar", "fat"), s.stages.filter { it.reached }.map { it.stage.id })
        assertEquals(listOf("fat"), s.stages.filter { it.here }.map { it.stage.id })
        // Today's small ring fills with the running fast.
        near(13.5f / 16f, s.week.last().fill)
        assertEquals("Tue, fasting now", s.week.last().spoken)

        hours(3) // 16:30 in
        val past = screen()
        assertEquals("Goal reached", past.topLine)
        assertEquals("+0:30 past your goal", past.subLine)
        assertEquals("Ketones rising", assertNotNull(past.now).stage.name)
    }

    @Test
    fun theStagesAreTheDesignsWithTheirEvidence() {
        val ids = FastingScreenRules.STAGES.map { it.id }
        assertEquals(listOf("digest", "sugar", "fat", "ketones", "gh", "auto", "long"), ids)
        val auto = FastingScreenRules.STAGES.first { it.id == "auto" }
        assertEquals("Early evidence", auto.evidence)
        assertEquals(EvidenceTone.EARLY, FastingScreenRules.tone(auto.evidence))
        assertTrue("animal studies" in auto.body)
        assertEquals(EvidenceTone.MODERATE, FastingScreenRules.tone("Moderate evidence"))
        assertTrue(FastingScreenRules.STAGES.none { "regenerat" in (it.name + it.body).lowercase() })
        // Autophagy is never "what's happening now".
        assertEquals("Growth hormone up", FastingScreenRules.stageAt(30.0).name)
        assertEquals("Deep ketosis", FastingScreenRules.stageAt(60.0).name)
        assertNull(FastingScreenRules.nextLine(50 * hourMs))
        assertTrue("GP" in FastingScreenRules.STAGES_NOTE)
    }

    @Test
    fun anExtendedFastMarksEachDay() {
        f.startExtended(120) // 5 days
        hours(30)
        val s = screen()
        assertEquals("30:00", s.bigTime)
        assertEquals(listOf("24h", "48h", "72h", "96h"), s.marks.map { it.label })
        assertEquals(listOf(true, false, false, false), s.marks.map { it.passed })
        assertEquals("Sat 20:00 · 5 days", s.goalTile)
        assertEquals("Ends in 3 d 18 h · Sat 20:00", s.subLine)
    }

    @Test
    fun theWeekAndHistoryReadShortAndPlain() {
        f.start(); hours(16); mins(40); f.end() // Mon 20:00 → Tue 12:40, on goal
        hours(7); mins(20) // Tue 20:00
        f.start(); hours(17); mins(10); f.end() // → Wed 13:10, on goal
        hours(7); mins(50) // Wed 21:00
        f.start(); hours(9); mins(30); f.end() // → Thu 06:30, 6h 30m short
        val s = screen()
        assertEquals(
            listOf(
                FastingHistoryRow("F2", "Wed 21 → Thu 22", "9h 30m", "6h 30m short", false),
                FastingHistoryRow("F1", "Tue 20 → Wed 21", "17h 10m", "on goal", true),
                FastingHistoryRow("F0", "Mon 19 → Tue 20", "16h 40m", "on goal", true),
            ).map { it.length to it.goal },
            s.history.map { it.length to it.goal },
        )
        assertTrue(s.history.all { "→" in it.span })
        // Today (Thu) is short; the streak counts back only through on-goal days.
        assertEquals("2 of 3 on goal", s.weekLine)
        val thu = s.week.last()
        near(9.5f / 16f, thu.fill)
        assertFalse(thu.onGoal)
        assertEquals(1f, s.week[s.week.size - 2].fill)
        assertEquals("Wed, 17h 10m, on goal", s.week[s.week.size - 2].spoken)
    }

    @Test
    fun aStreakCountsBackFromYesterdayWhileTodayIsEmpty() {
        f.start(); hours(16); f.end() // Tue 12:00
        hours(8)
        f.start(); hours(16); f.end() // Wed 12:00
        hours(12) // Thu 00:00, nothing today yet
        assertEquals("2 of 2 on goal · 2-day streak", screen().weekLine)
    }

    @Test
    fun lengthsNeverReadAsSixteenHoursZeroMinutes() {
        assertEquals("16h", FastingScreenRules.length(16 * hourMs))
        assertEquals("16h 05m", FastingScreenRules.length(16 * hourMs + 5 * 60_000L))
        assertEquals("45m", FastingScreenRules.length(45 * 60_000L))
        assertEquals("4d 18h", FastingScreenRules.length(114 * hourMs))
        assertEquals("on goal", FastingScreenRules.goalWord(16 * hourMs, 17 * hourMs))
        // Countdowns round up: ten seconds short of 2:30 left still reads 2:30, and the in-time plus the left make the goal.
        assertEquals("2:30", FastingScreenRules.left(150 * 60_000L - 10_000L))
        assertEquals("2:30", FastingScreenRules.left(150 * 60_000L))
        assertEquals("0:01", FastingScreenRules.left(1_000L))
        assertEquals("0:00", FastingScreenRules.left(0L))
        assertEquals("40m short", FastingScreenRules.goalWord(16 * hourMs, 15 * hourMs + 20 * 60_000L))
    }

    @Test
    fun theWeekShowsOnlyWithAFastInItAndUndoEndFollowsTheEnd() {
        // Nothing this week: no seven empty rings, a line instead; the Goals tile says so.
        val idle = screen()
        assertFalse(idle.weekShown)
        assertNull(idle.undoEndId)
        assertEquals("Not fasting", idle.tileTitle)
        // A running fast fills today's ring, so the week shows; the tile carries the time.
        val id = f.start(); hours(2)
        val running = screen()
        assertTrue(running.weekShown)
        assertEquals("Fasting · 2:00", running.tileTitle)
        hours(15)
        assertEquals("Goal reached · 17:00", screen().tileTitle)
        // Just ended: Undo end brings it back for a few minutes, then goes.
        f.end()
        val ended = screen()
        assertEquals(id, ended.undoEndId)
        assertTrue(ended.weekShown)
        hours(1)
        assertNull(screen().undoEndId)
    }
}
