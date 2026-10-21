package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, item (6) slice 1: Plan your week — what's left, what's fixed, the goals, and a suggested spread. */
class BoardPlanWeekTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC.
    private val mon = CivilDate.toEpochDay(2026, 10, 19)
    private val today = mon + 2
    private val now = today * dayMs + 10 * 3_600_000L
    private val thu = today + 1
    private val fri = today + 2
    private val sat = today + 3
    private val sun = today + 4

    private fun at(day: Long, hour: Int) = day * dayMs + hour * 3_600_000L

    private fun task(
        id: String, title: String = id, due: Long? = null, scheduled: Long? = null, estimate: Int? = null, priority: Int = 0,
        week: Long? = null, recurrence: String? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE,
    ) = Task(
        id, title, null, lifecycle, due, scheduled, estimate, priority, null, null, 0, null, false,
        recurrenceRule = recurrence, boardWeek = week,
    )

    private fun event(id: String, start: Long, end: Long) = CalendarEvent(id, id, start, end, false, null, "google", "a", "Personal")

    private val weekdays = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60)

    @Test
    fun theWeekIsThisOneUntilSundayThenTheNext() {
        assertEquals(mon, PlanWeekRules.weekStart(mon))
        assertEquals(mon, PlanWeekRules.weekStart(today))
        assertEquals(mon, PlanWeekRules.weekStart(mon + 5))
        assertEquals(mon + 7, PlanWeekRules.weekStart(mon + 6))
    }

    @Test
    fun leftoversAreSpreadToTheDaysWithMostRoomNeverPastTheirDueDay() {
        val tasks = listOf(
            task("old", "Book MOT", scheduled = at(mon, 10)),
            task("a", "Sort photos", week = mon),
            task("b", "Clear desk", week = mon),
            task("form", "Trip form", due = at(fri, 17)),
            task("big", "Tax return", scheduled = at(thu, 14), estimate = 120),
            task("rpt", "Stretch", scheduled = at(mon, 8), recurrence = "FREQ=DAILY"),
            task("done", "Done one", lifecycle = Lifecycle.DONE),
        )
        val events = listOf(event("Dentist", at(thu, 9) + 30 * 60_000L, at(thu, 10)))
        val board = WeekBoardRules.build(tasks, events, now, cal)
        val plan = PlanWeekRules.plan(board, events, today, cal)

        assertEquals("Plan this week", plan.title)
        assertEquals(listOf(today, thu, fri, sat, sun), plan.days.map { it.day })
        assertEquals(listOf("Today", "Tomorrow", "Fri 23", "Sat 24", "Sun 25"), plan.days.map { it.label })
        assertEquals(listOf("09:30 Dentist"), plan.day(thu)!!.fixed)
        assertEquals(listOf("Due · Trip form"), plan.day(fri)!!.fixed)

        // Due first, then the carried one, then the parked ones; each to the day with the most room left.
        assertEquals(listOf("form", "old", "a", "b"), plan.rows.map { it.taskId })
        assertEquals(listOf(fri, sat, sun, fri), plan.rows.map { it.suggested })
        assertEquals(listOf(today, thu, fri), plan.rows.first().days)
        assertEquals("Due Fri", plan.rows[0].why)
        assertEquals("No day yet", plan.rows[2].why)
        assertTrue(plan.rows[1].why.startsWith("Carried"))
        assertEquals("4 tasks to place", plan.leftLine)
        assertEquals("4 tasks over 3 days", plan.spreadLine)
        assertEquals("Due Fri · Fri 23, suggested", PlanWeekRules.rowLine(plan, plan.rows[0]))
    }

    @Test
    fun onSundayItPlansNextWeekAndKeepsOfficeEveningsLight() {
        val sunday = mon + 6
        val nowSun = at(sunday, 18)
        val next = mon + 7
        val tasks = listOf(task("p", "Garage", week = next, estimate = 120))
        val work = WorkHours(weekdays, commute = CommuteSetting(60, 60))
        val board = WeekBoardRules.build(tasks, emptyList(), nowSun, cal, work)
        val plan = PlanWeekRules.plan(board, emptyList(), sunday, cal)

        assertEquals("Plan next week", plan.title)
        assertEquals(next, plan.weekStart)
        assertEquals((next..next + 6).toList(), plan.days.map { it.day })
        assertEquals("Office", plan.day(next)!!.placeLabel)
        assertTrue(plan.day(next)!!.freeLine!!.endsWith("office evening"))
        // A two-hour job goes to the weekend, not an office evening.
        assertEquals(next + 5, plan.rows.single().suggested)
        assertEquals("1 task on 1 day · office evenings kept light", plan.spreadLine)
    }

    @Test
    fun heCanChangeOrLeaveEachRowAndNothingIsChosenOutsideItsDays() {
        val tasks = listOf(task("form", "Trip form", due = at(thu, 17)), task("a", "Sort photos", week = mon))
        val plan = PlanWeekRules.plan(WeekBoardRules.build(tasks, emptyList(), now, cal), emptyList(), today, cal)
        val form = plan.rows.first { it.taskId == "form" }
        assertEquals(listOf(today, thu), form.days)

        // Saturday is after its due day: ignored.
        assertEquals(form.chosen, plan.choose("form", sat).rows.first { it.taskId == "form" }.chosen)
        val changed = plan.choose("form", today).chooseDay("a", -1)
        assertEquals(today, changed.rows.first { it.taskId == "form" }.chosen)
        assertNull(changed.rows.first { it.taskId == "a" }.chosen)
        assertEquals("No day yet · stays where it is", PlanWeekRules.rowLine(changed, changed.rows.first { it.taskId == "a" }))
        assertEquals("Due tomorrow · Today", PlanWeekRules.rowLine(changed, changed.rows.first { it.taskId == "form" }))
        assertTrue(changed.canAccept)

        val none = changed.choose("form", null)
        assertFalse(none.canAccept)
        assertEquals(PlanWeekRules.NOTHING_CHOSEN, none.spreadLine)
        assertEquals("Week planned · 2 tasks over 2 days", PlanWeekRules.acceptedLine(2, 2))
    }

    @Test
    fun anEmptyWeekSaysSo() {
        val plan = PlanWeekRules.plan(WeekBoardRules.build(emptyList(), emptyList(), now, cal), emptyList(), today, cal)
        assertTrue(plan.rows.isEmpty())
        assertEquals(PlanWeekRules.NOTHING_LEFT, plan.leftLine)
        assertEquals(PlanWeekRules.NO_GOALS, plan.goalsLine)
        assertFalse(plan.canAccept)
    }
}
