package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goals, properly, slice 1: horizons, nesting with progress rolling up, due dates, why, the next step, linking tasks. */
class GoalsProperlyTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val d = world.device("android")
    private val g = Goals(d.replica, { "P${n++}" }, { world.clock.nowMs })

    init {
        // Wednesday 14 October 2026, 08:00 UTC.
        world.clock.nowMs = CivilDate.toEpochDay(2026, 10, 14) * dayMs + 8 * 3_600_000L
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun view() = g.view(d.tasks.all()).goals
    private fun goal(id: String) = view().single { it.id == id }

    @Test
    fun horizonsEndWhereTheirPeriodEndsAndOldOnesReadAsMonthQuarterYear() {
        val t = today()
        assertEquals(CivilDate.toEpochDay(2026, 10, 18), GoalRules.horizonEnd(GoalHorizon.WEEK, t))
        assertEquals(CivilDate.toEpochDay(2026, 10, 31), GoalRules.horizonEnd(GoalHorizon.MONTH, t))
        assertEquals(CivilDate.toEpochDay(2026, 12, 31), GoalRules.horizonEnd(GoalHorizon.QUARTER, t))
        assertEquals(CivilDate.toEpochDay(2026, 12, 31), GoalRules.horizonEnd(GoalHorizon.YEAR, t))
        assertNull(GoalRules.horizonEnd(GoalHorizon.SOMEDAY, t))
        assertEquals(
            listOf("No date", "End of week · Sun 18 Oct", "End of month · Sat 31 Oct", "End of quarter · Thu 31 Dec", "End of year · Thu 31 Dec"),
            GoalRules.dueChoices(t).map { it.label },
        )
        assertEquals(GoalHorizon.MONTH, GoalRules.parseHorizon("SHORT"))
        assertEquals(GoalHorizon.QUARTER, GoalRules.parseHorizon("MEDIUM"))
        assertEquals(GoalHorizon.YEAR, GoalRules.parseHorizon("LONG"))
        assertEquals(GoalHorizon.WEEK, GoalRules.parseHorizon("WEEK"))
        // A goal written before slice 1 keeps its stored value and reads as the new horizon.
        val old = g.addGoal("Clear the loft")
        d.replica.commitLocal(EntityTypes.GOAL, old, mapOf(GoalFields.HORIZON to "LONG".fv()))
        assertEquals(GoalHorizon.YEAR, goal(old).horizon)
        assertEquals("This year · set by hand", goal(old).meta)
    }

    @Test
    fun goalsNestYearMonthWeekAndProgressRollsUp() {
        val year = g.addGoal("Run a marathon", horizon = GoalHorizon.YEAR, why = "Feel fit at 40")
        val month = g.addGoal("Run 5k without stopping", horizon = GoalHorizon.MONTH, parentId = year)
        val week = g.addGoal("Three runs", horizon = GoalHorizon.WEEK, parentId = month)
        val other = g.addGoal("Tidy the garage", horizon = GoalHorizon.WEEK)
        // Each goal right after its parent; top level by horizon.
        assertEquals(listOf(other, year, month, week), view().map { it.id })
        assertEquals(listOf(0, 0, 1, 2), view().map { it.depth })
        assertEquals("Run a marathon", goal(month).parentTitle)
        assertEquals("Feel fit at 40", goal(year).why)
        assertEquals(1, goal(year).childCount)

        g.setGoalProgress(week, 50) // nothing linked: set by hand
        // week 50 % → month (one goal under it) 50 % → year 50 %.
        assertEquals(50, goal(month).progressPct)
        assertEquals(50, goal(year).progressPct)
        assertEquals("This year · 1 goal under it", goal(year).meta)
        g.finishGoal(week)
        assertEquals(100, goal(month).progressPct)
        assertEquals("This month · 1 of 1 goals done", goal(month).meta)
        assertEquals(0, goal(month).childCount)
    }

    @Test
    fun aGoalCantSitUnderItselfOrUnderSomethingBelowIt() {
        val year = g.addGoal("Year", horizon = GoalHorizon.YEAR)
        val month = g.addGoal("Month", horizon = GoalHorizon.MONTH, parentId = year)
        assertFailsWith<ValidationException> { g.setGoalParent(year, month) }
        assertFailsWith<ValidationException> { g.setGoalParent(year, year) }
        val week = g.addGoal("Week", horizon = GoalHorizon.WEEK)
        // Offered: open goals not under it, with a horizon at least as long.
        assertEquals(listOf(year, month), GoalRules.parentChoices(view(), week).map { it.id })
        assertEquals(emptyList(), GoalRules.parentChoices(view(), year).map { it.id })
        // A loop made behind its back (two offline devices) is cut when read: both still show.
        d.replica.commitLocal(EntityTypes.GOAL, year, mapOf(GoalFields.PARENT_ID to month.fv()))
        assertEquals(setOf(year, month, week), view().map { it.id }.toSet())
    }

    @Test
    fun deletingAGoalMovesTheGoalsUnderItUp() {
        val year = g.addGoal("Year", horizon = GoalHorizon.YEAR)
        val month = g.addGoal("Month", horizon = GoalHorizon.MONTH, parentId = year)
        val week = g.addGoal("Week", horizon = GoalHorizon.WEEK, parentId = month)
        g.deleteGoal(month)
        assertEquals(year, goal(week).parentId)
        assertEquals(1, goal(week).depth)
    }

    @Test
    fun theNextStepIsTheSoonestOpenLinkedTaskAndDueShowsInTheMeta() {
        val goal = g.addGoal("Save £2,000", horizon = GoalHorizon.QUARTER, dueDay = CivilDate.toEpochDay(2026, 12, 31))
        assertNull(goal(goal).nextStep)
        assertEquals("This quarter · by Thu 31 Dec · set by hand", goal(goal).meta)
        val a = d.tasks.create(NewTask("Open a saver", goalId = goal))
        val b = d.tasks.create(NewTask("Set up a standing order", goalId = goal, scheduledAtMs = world.clock.nowMs + dayMs))
        assertEquals("Set up a standing order", goal(goal).nextStep)
        d.tasks.complete(b)
        assertEquals(a, goal(goal).nextStepTaskId)
        g.setGoalDue(goal, today() - 1)
        assertTrue(goal(goal).meta.contains("overdue · was Tue 13 Oct"))
        g.setGoalWhy(goal, "  ")
        assertNull(goal(goal).why)
    }

    @Test
    fun linkCandidatesAreOpenTasksNotAlreadyOnTheGoalMatchingTheWords() {
        val goal = g.addGoal("Fitter")
        val linked = d.tasks.create(NewTask("Buy running shoes", goalId = goal))
        val shoes = d.tasks.create(NewTask("Clean the shoes"))
        val done = d.tasks.create(NewTask("Shoe rack"))
        d.tasks.complete(done)
        d.tasks.create(NewTask("Call mum"))
        val tasks = d.tasks.all()
        assertEquals(listOf(shoes), GoalRules.linkCandidates(tasks, goal, "shoe").map { it.id })
        assertEquals(2, GoalRules.linkCandidates(tasks, goal, "").size)
        assertTrue(linked !in GoalRules.linkCandidates(tasks, goal, "").map { it.id })
    }
}
