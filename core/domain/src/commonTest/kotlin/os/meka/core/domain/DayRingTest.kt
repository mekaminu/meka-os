package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DayRingTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // Tue 6 Oct 2026 in London (BST, +1 h): local midnight = 5 Oct 23:00 UTC.
    private val oct6Utc = 1_791_244_800_000L
    private val start = oct6Utc - hour
    private val day = DayWindow(startMs = start, endMs = start + 24 * hour, utcOffsetMs = hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false) =
        CalendarEvent(id, id, from, to, allDay, null, "google", null, null)

    private fun task(id: String, scheduled: Long? = null, estimate: Int? = null, done: Boolean = false) =
        Task(id, id, null, if (done) Lifecycle.DONE else Lifecycle.ACTIVE, null, scheduled, estimate, 0, null, null, 0,
            if (done) at(8) else null, false)

    private fun ring(tasks: List<Task>, events: List<CalendarEvent>, now: Long, sessions: List<BookedSession> = emptyList()) =
        TodayProjection.project(tasks, now, day, events, sessions = sessions).dayRing

    @Test
    fun arcsAreTheDaysEventsPlannedTasksAndSessionsInClockOrder() {
        val gym = BookedSession("gym", "Gym", "Push", day.epochDay, at(17, 45), at(18, 45), "Today 17:45")
        val r = ring(
            tasks = listOf(task("write", at(14), 60), task("loose"), task("quick", at(11))),
            events = listOf(ev("standup", at(9, 30), at(9, 45)), ev("lunch", at(12), at(13)), ev("holiday", start, start + 24 * hour, allDay = true)),
            now = at(10, 2),
            sessions = listOf(gym),
        )
        assertEquals(listOf("e-standup", "t-quick", "e-lunch", "t-write", "s-gym"), r.arcs.map { it.id })
        assertEquals(listOf(DayArcKind.EVENT, DayArcKind.TASK, DayArcKind.EVENT, DayArcKind.TASK, DayArcKind.SESSION), r.arcs.map { it.kind })
        val quick = r.arcs.first { it.id == "t-quick" }
        assertEquals(11 * 60, quick.startMinute)
        assertEquals(11 * 60 + 30, quick.endMinute) // no estimate: 30 min
        assertTrue(r.arcs.first().past)
        assertFalse(r.arcs.drop(1).any { it.past })
        assertEquals(10 * 60 + 2, r.nowMinute)
        assertEquals(3, r.toDo) // write, loose, quick
    }

    @Test
    fun freeTimeIsTheRestOfTheWakingDayOutsideEveryArc() {
        // From 10:02 to 22:00 = 718 min, less quick 11:00–11:30 (30), lunch 12:00–13:00 (60), write 14:00–15:00 (60) = 568.
        val r = ring(
            tasks = listOf(task("write", at(14), 60), task("quick", at(11))),
            events = listOf(ev("lunch", at(12), at(13)), ev("overlap", at(12, 30), at(13, 30))),
            now = at(10, 2),
        )
        // The overlapping 12:30–13:30 adds only 30 minutes: 718 − 30 − 90 − 60 = 538.
        assertEquals(538, r.freeMinutes)
        assertEquals("8 h 55 free · 2 to do", r.line)
        assertEquals("8 h 55 free", r.freeLine)
        assertEquals("2 to do", r.toDoLine)
    }

    @Test
    fun aRunningEventCountsFromNowAndEarlyMorningsStartAtSeven() {
        val running = ring(emptyList(), listOf(ev("call", at(9), at(10))), now = at(9, 30))
        assertEquals(DAY(9 * 60 + 30) - 30, running.freeMinutes)
        val early = ring(emptyList(), emptyList(), now = at(5))
        assertEquals(15 * 60, early.freeMinutes)
        assertEquals("15 h free · Nothing to do", early.line)
    }

    private fun DAY(from: Int) = DayRingRules.DAY_END_MIN - from

    @Test
    fun theEveningHasNoFreeTimeLeftAndEventsAcrossMidnightAreClipped() {
        val r = ring(listOf(task("t")), listOf(ev("late", at(23), at(26)), ev("night", at(-2), at(1))), now = at(22, 10))
        assertEquals(0, r.freeMinutes)
        assertEquals("Evening · 1 to do", r.line)
        val night = r.arcs.first { it.id == "e-night" }
        assertEquals(0, night.startMinute)
        assertEquals(60, night.endMinute)
        val late = r.arcs.first { it.id == "e-late" }
        assertEquals(DayRingRules.MINUTES, late.endMinute)
        assertEquals(345f, late.startDegrees)
        assertEquals(15f, late.sweepDegrees)
    }

    @Test
    fun aTinyEventStillShowsAndNoFreeTimeSaysSo() {
        val tiny = DayArc("e-x", DayArcKind.EVENT, 600, 601, false)
        assertEquals(DayRingRules.MIN_SWEEP_DEGREES, tiny.sweepDegrees)
        assertEquals(150f, tiny.startDegrees)
        val busy = ring(emptyList(), listOf(ev("all", at(10), at(23))), now = at(10))
        assertEquals(0, busy.freeMinutes)
        assertEquals("No free time · Nothing to do", busy.line)
        assertEquals("1 h free", DayRingRules.freeLine(62))
    }

    @Test
    fun doneTasksAndAllDayItemsAreNotArcs() {
        val r = ring(listOf(task("done", at(9), done = true)), listOf(ev("hol", start, start + 24 * hour, allDay = true)), now = at(10))
        assertTrue(r.arcs.isEmpty())
        assertEquals(0, r.toDo)
        assertEquals("Your day: nothing booked. Now 10:00. 12 h free · Nothing to do.", r.spokenLine)
    }

    @Test
    fun theCentreCountsUpThroughTheSameWords() {
        assertEquals("1 h 50 free · 2 to do", DayRingRules.line(112, 2))
        assertEquals("No free time · Nothing to do", DayRingRules.line(0, 0))
    }

    @Test
    fun describesTheRingForScreenReaders() {
        val r = ring(listOf(task("w", at(14), 60)), listOf(ev("standup", at(9), at(9, 15))), now = at(10))
        assertEquals("Your day: 2 things booked, 1 over. Now 10:00. 11 h free · 1 to do.", r.spokenLine)
    }

    @Test
    fun playsInFullOnTheFirstOpenOfTheDayThenQuickly() {
        assertEquals(DayRingPlay.FULL, DayRingRules.play(null, 100, reduced = false))
        assertEquals(DayRingPlay.FULL, DayRingRules.play(99, 100, reduced = false))
        assertEquals(DayRingPlay.QUICK, DayRingRules.play(100, 100, reduced = false))
        assertEquals(DayRingPlay.STILL, DayRingRules.play(null, 100, reduced = true))
        assertEquals(DayRingPlay.STILL, DayRingRules.play(100, 100, reduced = true))
    }
}
