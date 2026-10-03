package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = DayWindow(0, 24 * hour)

    private fun task(id: String, est: Int? = null, priority: Int = 0, created: Long = 0, due: Long? = null, scheduled: Long? = null) =
        Task(id, id, null, Lifecycle.ACTIVE, due, scheduled, est, priority, null, null, created, null, false)

    private fun ev(id: String, startH: Double, endH: Double, provider: String = "google") =
        CalendarEvent(id, id, (startH * hour).toLong(), (endH * hour).toLong(), false, null, provider, null, null)

    private fun two(n: Long) = n.toString().padStart(2, '0')
    private fun at(p: DayPlanner.Placement) = "${p.task.id}@${two(p.startMs / hour)}:${two(p.startMs % hour / min)}"

    @Test
    fun fillsGapsAroundEventsHighestPriorityFirstOnQuarterHours() {
        val plan = DayPlanner.plan(
            tasks = listOf(task("low", 30, priority = 0), task("high", 60, priority = 2), task("mid", 45, priority = 1)),
            events = listOf(ev("standup", 10.0, 10.5)),
            nowMs = 8 * hour, day = day,
        )
        // 09:00–09:50 free (10 min buffer before 10:00): only the 45-min task fits there after "high" can't.
        // high (60) → 10:40 aligned to 10:45; mid (45) → 09:00; low (30) → 11:45.
        assertEquals(listOf("mid@09:00", "high@10:45", "low@11:45"), plan.placements.sortedBy { it.startMs }.map(::at))
        assertTrue(plan.unplaced.isEmpty())
    }

    @Test
    fun startsFromNowNotTheMorningAndNeverOverlapsAnEvent() {
        val plan = DayPlanner.plan(listOf(task("a", 30)), listOf(ev("lunch", 13.0, 14.0)), nowMs = 12 * hour + 50 * min, day = day)
        // 12:50 → 13:00 aligned, but 12:50–14:10 is blocked by lunch with buffers → 14:15.
        assertEquals(listOf("a@14:15"), plan.placements.map(::at))
    }

    @Test
    fun fixturesKeepTheLeadInFreeAndTasksThatDontFitAreReportedNotSqueezed() {
        val plan = DayPlanner.plan(
            tasks = listOf(task("long", 120), task("short", 30)),
            events = listOf(ev("Barça v Real Madrid", 19.0, 21.0, provider = "fixtures")),
            nowMs = 16 * hour + 30 * min, day = day,
        )
        // Free: 16:30–18:00 (match at 19:00 with a 60-min lead). 120 min doesn't fit; 30 does.
        assertEquals(listOf("short@16:30"), plan.placements.map(::at))
        assertEquals(listOf("long"), plan.unplaced.map { it.id })
        assertEquals(60, plan.freeMinutesLeft)
    }

    @Test
    fun scheduledDoneAndLaterDueTasksAreLeftAlone() {
        val plan = DayPlanner.plan(
            tasks = listOf(task("already", scheduled = 15 * hour), task("next-week", due = 30 * 24 * hour), task("today", due = 18 * hour)),
            events = emptyList(), nowMs = 9 * hour, day = day,
        )
        assertEquals(listOf("today"), plan.placements.map { it.task.id })
    }
}
