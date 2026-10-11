package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goals, properly, slice 7: "This week: 2 of 3 goals moving" on Today and the week's goals on the watch face's ring. */
class GoalWeekTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val d = world.device("android")
    private val g = Goals(d.replica, { "W${n++}" }, { world.clock.nowMs })

    init {
        // Wednesday 14 October 2026, 08:00 UTC (the week started on Monday 12th).
        world.clock.nowMs = CivilDate.toEpochDay(2026, 10, 14) * dayMs + 8 * 3_600_000L
    }

    private fun week() = g.view(d.tasks.all()).week
    private fun near(a: Float, b: Float) = assertTrue(abs(a - b) < 0.01f, "$a ≠ $b")

    @Test
    fun onlyThisWeeksGoalsCountAndMovingMeansSomethingMovedSinceMonday() {
        assertEquals(WeekGoals.EMPTY, week())
        assertNull(week().line)
        g.addGoal("Run a marathon", horizon = GoalHorizon.YEAR)
        assertNull(week().line)

        // Last week a step was done: that doesn't move it this week.
        val runs = g.addGoal("Three runs", horizon = GoalHorizon.WEEK)
        val old = d.tasks.create(NewTask("Run 1", goalId = runs))
        world.clock.nowMs -= 3 * dayMs // Sunday 11th
        d.tasks.complete(old)
        world.clock.nowMs += 3 * dayMs
        d.tasks.create(NewTask("Run 2", goalId = runs))
        assertEquals("This week: “Three runs” not moving yet", week().line)
        assertEquals(CivilDate.toEpochDay(2026, 10, 11), g.view(d.tasks.all()).goals.single { it.id == runs }.lastMovedDay)

        val garage = g.addGoal("Tidy the garage", horizon = GoalHorizon.WEEK)
        val save = g.addGoal("Put £50 away", horizon = GoalHorizon.WEEK)
        assertEquals("This week: 3 goals, none moving yet", week().line)

        // A milestone ticked, a number updated: both move their goals.
        g.setMilestoneDone(g.addMilestone(garage, "Clear the shelves"), true)
        g.setMetric(save, "£50")
        g.addMetricEntry(save, "+20")
        assertEquals("This week: 2 of 3 goals moving", week().line)
        assertEquals("This week: 2 of 3 goals moving.", week().spoken)

        // A step done today moves the third; then all are moving.
        d.tasks.complete(d.tasks.all().single { it.title == "Run 2" }.id)
        assertEquals("This week: all 3 goals moving", week().line)

        // Finished this week, it still counts (and is full on the ring).
        g.finishGoal(garage)
        val w = week()
        assertEquals(3, w.total)
        assertEquals(1, w.done)
        assertEquals(listOf(runs, save, garage), w.arcs.map { it.goalId })
        assertEquals(100, w.arcs.last().pct)
        g.finishGoal(runs)
        g.finishGoal(save)
        assertEquals("This week: all 3 goals done", week().line)
    }

    @Test
    fun aHabitTickAndAGoalUnderItMoveAGoal() {
        val fit = g.addGoal("Get fitter", horizon = GoalHorizon.WEEK)
        val stretch = g.addHabit("Stretch", 7)
        g.setHabitGoal(stretch, fit)
        assertEquals("This week: “Get fitter” not moving yet", week().line)
        g.setHabitDone(stretch, true)
        assertEquals("This week: “Get fitter” is moving", week().line)

        val month = g.addGoal("Learn to swim", horizon = GoalHorizon.MONTH)
        val lessons = g.addGoal("Book lessons", horizon = GoalHorizon.WEEK, parentId = month)
        assertEquals("This week: 1 of 2 goals moving", week().line)
        g.setMilestoneDone(g.addMilestone(lessons, "Call the pool"), true)
        assertEquals("This week: all 2 goals moving", week().line)
        val today = CivilDate.toEpochDay(2026, 10, 14)
        // The month goal moves with the goal under it.
        assertEquals(today, g.view(d.tasks.all()).goals.single { it.id == month }.lastMovedDay)
    }

    @Test
    fun theRingSplitsIntoOneSegmentPerGoalFilledByProgressAndDrawsInWithTheRim() {
        val a = g.addGoal("A", horizon = GoalHorizon.WEEK)
        g.setGoalProgress(a, 50)
        var arcs = week().arcs
        // One goal: the whole ring, no gap.
        near(0f, arcs.single().startDegrees)
        near(360f, arcs.single().sweepDegrees)
        near(180f, arcs.single().fillDegrees)
        listOf("B", "C", "D").forEach { g.addGoal(it, horizon = GoalHorizon.WEEK) }
        val w = week()
        arcs = w.arcs
        // Four goals: three arcs (the line still counts all four).
        assertEquals(GoalWeekRules.MAX_ARCS, arcs.size)
        assertEquals("This week: 4 goals, none moving yet", w.line)
        near(GoalWeekRules.GAP_DEGREES / 2f, arcs[0].startDegrees)
        near(120f - GoalWeekRules.GAP_DEGREES, arcs[0].sweepDegrees)
        near(120f + GoalWeekRules.GAP_DEGREES / 2f, arcs[1].startDegrees)
        near(240f + GoalWeekRules.GAP_DEGREES / 2f, arcs[2].startDegrees)
        // Not moving yet: the quieter shade.
        assertEquals(GoalWeekRules.WAITING_ALPHA, GoalWeekRules.fillAlpha(arcs[0]))
        // Drawing in: nothing until the rim reaches it, all of it once landed.
        assertEquals(0f, GoalWeekRules.shown(0.3f, arcs[1]))
        assertEquals(1f, GoalWeekRules.shown(1f, arcs[2]))
        assertTrue(GoalWeekRules.shown(0.5f, arcs[1]) in 0.1f..0.9f)
    }

    @Test
    fun theGoalRingSitsOutsideTheRimInsideTheFace() {
        // Today's faces (closed and open Fold, Mac); the bedside clock's large face has no goal ring.
        for (size in listOf(DayRingHeader.COMPACT_DP, DayRingHeader.WIDE_DP, WatchFaceRules.LARGE_MIN_DP - 1)) {
            val ring = GoalWeekRules.ringRadiusDp(size)
            val ringInner = ring - GoalWeekRules.RING_DP / 2f
            val rimOuter = WatchFaceRules.rimRadiusDp(size) + WatchFaceRules.rimStrokeDp(size) * WatchFaceRules.HIGHLIGHT_WIDTH / 2f
            assertTrue(ringInner - rimOuter >= 0.5f, "size $size: the ring touches the rim")
            assertTrue(ring + GoalWeekRules.RING_DP / 2f <= size / 2f, "size $size: the ring leaves the face")
        }
    }
}
