package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goals, properly, slice 2: milestones count towards progress, show as the next step, sync, and leave with the goal. */
class GoalMilestonesTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val g = Goals(fold.replica, { "M${n++}" }, { world.clock.nowMs })
    private val gm = Goals(mac.replica, { "X${n++}" }, { world.clock.nowMs })

    init {
        world.clock.nowMs = CivilDate.toEpochDay(2026, 10, 14) * dayMs + 8 * 3_600_000L
    }

    private fun goal(id: String, goals: Goals = g, dev: os.meka.core.testing.Device = fold) = goals.view(dev.tasks.all()).goals.single { it.id == id }

    @Test
    fun milestonesCountTowardsProgressAndTheFirstOpenOneIsTheNextStep() {
        val run = g.addGoal("Run a half marathon", target = "Under 2 hours", horizon = GoalHorizon.QUARTER)
        val a = g.addMilestone(run, "  Run   5k ")
        world.clock.nowMs += 1_000 // milestones keep the order they were added in
        val b = g.addMilestone(run, "Run 10k")
        world.clock.nowMs += 1_000
        g.addMilestone(run, "Run 15k")
        assertEquals(a, g.addMilestone(run, "run 5k")) // the same title isn't added twice
        assertEquals(listOf("Run 5k", "Run 10k", "Run 15k"), goal(run).milestones.map { it.title })
        assertTrue(goal(run).counted)
        assertEquals("This quarter · 0 of 3 milestones", goal(run).meta)
        assertEquals("Next milestone · Run 5k", goal(run).nextLine)
        assertTrue(goal(run).nextIsMilestone)

        g.setMilestoneDone(a, true)
        assertEquals(33, goal(run).progressPct)
        assertEquals(1, goal(run).milestonesDone)
        assertEquals("Next milestone · Run 10k", goal(run).nextLine)
        // A linked open task is the next step before any milestone; milestones and tasks share the progress.
        fold.tasks.create(NewTask("Book a physio check", goalId = run))
        assertEquals("Next · Book a physio check", goal(run).nextLine)
        assertFalse(goal(run).nextIsMilestone)
        assertEquals(25, goal(run).progressPct)
        assertEquals("This quarter · 1 of 3 milestones · 0 of 1 task", goal(run).meta)

        g.setMilestoneDone(a, false)
        assertEquals(0, goal(run).progressPct)
        assertEquals("Run 10k", g.removeMilestone(b))
        assertNull(g.removeMilestone(b))
        assertEquals(listOf("Run 5k", "Run 15k"), goal(run).milestones.map { it.title })
    }

    @Test
    fun milestonesAreCheckedAndCapped() {
        val goal = g.addGoal("Read 12 books")
        assertFailsWith<ValidationException> { g.addMilestone(goal, "  ") }
        assertFailsWith<ValidationException> { g.addMilestone(goal, "x".repeat(GoalRules.MAX_MILESTONE_TITLE + 1)) }
        assertFailsWith<ValidationException> { g.addMilestone("nope", "Book 1") }
        repeat(GoalRules.MAX_MILESTONES) { g.addMilestone(goal, "Book ${it + 1}") }
        assertFailsWith<ValidationException> { g.addMilestone(goal, "Book 13") }
        assertEquals(0f, GoalRules.ringSweep(-5))
        assertEquals(180f, GoalRules.ringSweep(50))
        assertEquals(360f, GoalRules.ringSweep(140))
    }

    @Test
    fun milestonesSyncTheLatestTickWinsAndARemovalIsForGood() {
        val goal = g.addGoal("Save £2,000")
        val first = g.addMilestone(goal, "£500")
        fold.sync(); mac.sync()
        assertEquals(listOf("£500"), goal(goal, gm, mac).milestones.map { it.title })
        // Ticked on the Fold, then unticked on the Mac a minute later: unticked on both.
        g.setMilestoneDone(first, true)
        world.clock.nowMs += 60_000
        fold.sync(); mac.sync()
        assertTrue(goal(goal, gm, mac).milestones.single().done)
        gm.setMilestoneDone(first, false)
        fold.sync(); mac.sync(); fold.sync()
        assertFalse(goal(goal).milestones.single().done)
        assertFalse(goal(goal, gm, mac).milestones.single().done)
        // Removed on the Mac while the Fold ticks it: removed on both.
        gm.removeMilestone(first)
        world.clock.nowMs += 60_000
        g.setMilestoneDone(first, true)
        fold.sync(); mac.sync(); fold.sync()
        assertEquals(emptyList(), goal(goal).milestones)
        assertEquals(emptyList(), goal(goal, gm, mac).milestones)
    }

    @Test
    fun deletingAGoalRemovesItsMilestones() {
        val goal = g.addGoal("Clear the loft")
        g.addMilestone(goal, "Sort the boxes")
        g.deleteGoal(goal)
        assertEquals(emptyList(), g.milestones(goal))
    }
}
