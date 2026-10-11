package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goals, properly, slice 3: numeric goals — the number to reach, updates, habit ticks, the chart and the pace. */
class GoalNumbersTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val g = Goals(fold.replica, { "N${n++}" }, { world.clock.nowMs })
    private val gm = Goals(mac.replica, { "Y${n++}" }, { world.clock.nowMs })
    private val oct14 = CivilDate.toEpochDay(2026, 10, 14)

    init {
        world.clock.nowMs = oct14 * dayMs + 8 * 3_600_000L
    }

    private fun goal(id: String, goals: Goals = g, dev: os.meka.core.testing.Device = fold) = goals.view(dev.tasks.all()).goals.single { it.id == id }

    @Test
    fun targetsAndUpdatesParseAsTyped() {
        assertEquals(200_000L to "£", GoalMetricRules.parseTarget("£2,000"))
        assertEquals(200_000L to "£", GoalMetricRules.parseTarget("£2k"))
        assertEquals(1_200L to "books", GoalMetricRules.parseTarget("12 books"))
        assertEquals(10_000L to "km", GoalMetricRules.parseTarget(" 100   km "))
        assertEquals(500_000L to "", GoalMetricRules.parseTarget("5000"))
        assertEquals(1_250L to "£", GoalMetricRules.parseTarget("£12.5"))
        assertNull(GoalMetricRules.parseTarget("lots"))
        assertNull(GoalMetricRules.parseTarget("0 books"))
        assertNull(GoalMetricRules.parseTarget("12 books 3"))

        assertEquals("£2,000", GoalMetricRules.suggestFromTitle("Save £2,000 for the car"))
        assertEquals("12 books", GoalMetricRules.suggestFromTitle("Read 12 Books this year"))
        assertNull(GoalMetricRules.suggestFromTitle("Run a marathon"))

        assertEquals(MetricInput(20_000, false), GoalMetricRules.parseInput("+£200"))
        assertEquals(MetricInput(20_000, false), GoalMetricRules.parseInput("200"))
        assertEquals(MetricInput(-100, false), GoalMetricRules.parseInput("−1"))
        assertEquals(MetricInput(-100, false), GoalMetricRules.parseInput("-1 book"))
        assertEquals(MetricInput(120_000, true), GoalMetricRules.parseInput("=1,200"))
        assertNull(GoalMetricRules.parseInput("=-5"))
        assertNull(GoalMetricRules.parseInput("some"))

        assertEquals("£2,000", GoalMetricRules.format(200_000, "£"))
        assertEquals("£12.50", GoalMetricRules.format(1_250, "£"))
        assertEquals("1 book", GoalMetricRules.format(100, "books"))
        assertEquals("1 km", GoalMetricRules.format(100, "km"))
        assertEquals("−£50", GoalMetricRules.format(-5_000, "£"))
        assertEquals("+3 books", GoalMetricRules.signed(300, "books"))
        assertEquals("5 of 12 books", GoalMetricRules.line(500, 1_200, "books"))
        assertEquals("£800 of £2,000", GoalMetricRules.line(80_000, 200_000, "£"))
    }

    @Test
    fun updatesDecideTheProgressShowInTheMetaAndSetWritesTheDifference() {
        val save = g.addGoal("Save £2,000", horizon = GoalHorizon.YEAR)
        assertFalse(g.setMetric(save, "lots")) // not a number: nothing written
        assertNull(goal(save).metric)
        assertFailsWith<ValidationException> { g.addMetricEntry(save, "+200") } // no number yet
        assertTrue(g.setMetric(save, "£2,000"))
        // A linked task no longer decides the progress: the number does.
        fold.tasks.create(NewTask("Open a savings pot", goalId = save))
        assertEquals(0, goal(save).progressPct)
        assertEquals("This year · £0 of £2,000 · 0 of 1 task", goal(save).meta)

        val first = g.addMetricEntry(save, "+£500")!!
        world.clock.nowMs += 2 * dayMs
        g.addMetricEntry(save, "300")
        assertEquals(40, goal(save).progressPct)
        val m = goal(save).metric!!
        assertEquals("£800 of £2,000", m.line)
        assertEquals("£2,000", m.targetText)
        assertEquals(listOf("+£300 · today", "+£500 · Wed 14 Oct"), m.recent.map { it.label })
        // The start day folds in that day's update.
        assertEquals(listOf(MetricPoint(oct14, 50_000), MetricPoint(oct14 + 2, 80_000)), m.points)

        // "=1200" sets the number: the difference (+£400) is written; the same number again changes nothing.
        assertTrue(g.addMetricEntry(save, "=1,200") != null)
        assertEquals("+£400 · today", goal(save).metric!!.recent.first().label)
        assertNull(g.addMetricEntry(save, "=1200"))
        assertFailsWith<ValidationException> { g.addMetricEntry(save, "a bit") }

        // Undo of the first update; the Mac sees the same after a sync.
        assertTrue(g.removeMetricEntry(first))
        assertFalse(g.removeMetricEntry(first))
        fold.sync(); mac.sync()
        assertEquals("£700 of £2,000", goal(save, gm, mac).metric!!.line)
        assertEquals(35, goal(save, gm, mac).progressPct)

        // Back to an ordinary goal: the task counts again; the number comes back with its updates.
        assertTrue(g.setMetric(save, ""))
        assertNull(goal(save).metric)
        assertEquals("This year · 0 of 1 task", goal(save).meta)
        g.setMetric(save, "£2,000")
        assertEquals("£700 of £2,000", goal(save).metric!!.line)
    }

    @Test
    fun habitTicksFeedTheNumberFromTheDayItStarted() {
        val read = g.addHabit("Read a book", perWeek = 1)
        g.setHabitDone(read, true) // before the goal counts: not counted
        world.clock.nowMs += 7 * dayMs
        val books = g.addGoal("Read 12 books", horizon = GoalHorizon.YEAR)
        g.setMetric(books, "12 books")
        g.setMetricHabit(books, read)
        g.setHabitDone(read, true)
        world.clock.nowMs += dayMs
        g.setHabitDone(read, true)
        g.addMetricEntry(books, "+1")
        val m = goal(books).metric!!
        assertEquals("3 of 12 books", m.line)
        assertEquals("Read a book", m.habitTitle)
        assertEquals(25, goal(books).progressPct)
        // Unticking takes it back; stopping the habit feed drops its ticks.
        g.setHabitDone(read, false)
        assertEquals("2 of 12 books", goal(books).metric!!.line)
        g.setMetricHabit(books, null)
        assertEquals("1 of 12 books", goal(books).metric!!.line)
        assertFailsWith<ValidationException> { g.setMetricHabit(books, "nope") }
    }

    @Test
    fun paceLineAndPlotFollowTheDueDate() {
        val save = g.addGoal("Save £1,000", horizon = GoalHorizon.MONTH)
        g.setMetric(save, "£1,000")
        g.setGoalDue(save, oct14 + 10)
        world.clock.nowMs += 5 * dayMs // half way: £500 expected
        g.addMetricEntry(save, "+200")
        assertEquals("Behind pace · £300 to catch up", goal(save).metric!!.paceLine)
        g.addMetricEntry(save, "+300")
        assertEquals("On pace", goal(save).metric!!.paceLine)
        val plot = GoalMetricRules.plot(goal(save).metric!!, oct14 + 5)
        assertEquals(PlotPoint(0f, 0f), plot.line.first())
        assertEquals(PlotPoint(0.5f, 0.5f), plot.line.last())
        assertEquals(listOf(PlotPoint(0f, 0f), PlotPoint(1f, 1f)), plot.pace)
        assertEquals(1f, plot.targetY)
        g.addMetricEntry(save, "=1000")
        assertEquals("Reached", goal(save).metric!!.paceLine)
        assertEquals(100, goal(save).progressPct)
        // Without a due date: no pace line, and the chart runs to today.
        g.setGoalDue(save, null)
        g.addMetricEntry(save, "-1")
        assertNull(goal(save).metric!!.paceLine)
        assertTrue(GoalMetricRules.plot(goal(save).metric!!, oct14 + 5).pace.isEmpty())
    }

    @Test
    fun chartKeepsAtMostSixtyPointsAndDeletingTheGoalRemovesItsUpdates() {
        val changes = (0L until 100L).map { oct14 + it to 100L }
        val pts = GoalMetricRules.points(oct14, oct14 + 99, changes)
        assertEquals(GoalMetricRules.MAX_POINTS, pts.size)
        assertEquals(MetricPoint(oct14 + 99, 10_000), pts.last())
        assertEquals(oct14, pts.first().day)

        val save = g.addGoal("Save £100")
        g.setMetric(save, "£100")
        g.addMetricEntry(save, "+10")
        g.deleteGoal(save)
        assertTrue(fold.replica.entities(EntityTypes.GOAL_ENTRY).all { it.deleted })
    }
}
