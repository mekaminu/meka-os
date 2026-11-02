package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada plans with you, part 1 slice 1: Obi drafts the week as faint cards on the Board. */
class WeekDraftTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC.
    private val mon = CivilDate.toEpochDay(2026, 10, 19)
    private val today = mon + 2
    private val now = today * dayMs + 10 * 3_600_000L
    private val fri = today + 2

    private fun at(day: Long, hour: Int) = day * dayMs + hour * 3_600_000L

    private fun task(id: String, title: String = id, due: Long? = null, scheduled: Long? = null, week: Long? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE) = Task(
        id, title, null, lifecycle, due, scheduled, null, 0, null, null, 0, null, false, boardWeek = week,
    )

    private val tasks = listOf(
        task("old", "Book MOT", scheduled = at(mon, 10)),
        task("a", "Sort photos", week = mon),
        task("form", "Trip form", due = at(fri, 17)),
    )

    @Test
    fun theSpreadIsDrawnAsGhostsOnTheDaysTheyWouldGoTo() {
        val board = WeekBoardRules.build(tasks, emptyList(), now, cal)
        val plan = PlanWeekRules.plan(board, emptyList(), today, cal)
        val draft = WeekDraftRules.draft(plan)!!
        val ghosts = WeekDraftRules.ghosts(draft, board)
        val placed = plan.placing.associate { it.taskId to it.chosen!! }
        assertEquals(placed.keys, ghosts.values.flatten().map { it.taskId }.toSet())
        ghosts.forEach { (day, list) -> list.forEach { g -> assertEquals(placed[g.taskId], day); assertEquals(day, g.day) } }
        assertTrue(WeekDraftRules.showing(draft, board))

        val form = ghosts.values.flatten().single { it.taskId == "form" }
        assertEquals("0:30", form.duration)
        assertEquals("Obi's draft: Trip form, about 30m, due Fri", form.spoken)
        assertEquals("✦ Obi's draft · 3 tasks over 3 days", draft.line)
        // Due first, then the carried one; the third is "and 1 more"; the lightest free day left is kept light.
        val formDay = draft.plan.dayLabel(placed.getValue("form"))
        val motDay = draft.plan.dayLabel(placed.getValue("old"))
        assertTrue(draft.said.startsWith("Trip form on $formDay, Book MOT on $motDay and 1 more"), draft.said)
        assertTrue(draft.said.endsWith(" kept light"), draft.said)
    }

    @Test
    fun ghostsKeepUpWithTheLiveBoard() {
        val board = WeekBoardRules.build(tasks, emptyList(), now, cal)
        val draft = WeekDraftRules.draft(PlanWeekRules.plan(board, emptyList(), today, cal))!!
        val formDay = draft.plan.placing.single { it.taskId == "form" }.chosen!!
        // He puts the form on that day by hand: its ghost goes.
        val moved = tasks.map { if (it.id == "form") it.copy(scheduledAtMs = at(formDay, 9)) else it }
        val after = WeekDraftRules.ghosts(draft, WeekBoardRules.build(moved, emptyList(), now, cal))
        assertTrue(after.values.flatten().none { it.taskId == "form" })
        // Done, it goes too; with every task done there's nothing left to show.
        val done = tasks.map { it.copy(lifecycle = Lifecycle.DONE) }
        assertFalse(WeekDraftRules.showing(draft, WeekBoardRules.build(done, emptyList(), now, cal)))
    }

    @Test
    fun aWeekWithNothingToPlaceHasNoDraft() {
        val board = WeekBoardRules.build(emptyList(), emptyList(), now, cal)
        assertNull(WeekDraftRules.draft(PlanWeekRules.plan(board, emptyList(), today, cal)))
        assertTrue(WeekDraftRules.ghostsOn(null, board, today).isEmpty())
        assertEquals(WeekDraftRules.OPENING, TalkOpenRules.opening(TalkPlace.PLAN_WEEK))
    }
}
