package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goals, properly, item 6: the 14-day gentle nudge and the 1st of the month's look back. */
class GoalRhythmTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private val d = world.device("android")
    private val g = Goals(d.replica, { "R${n++}" }, { world.clock.nowMs })
    private val cal = LocalCalendar.UTC

    private fun at(y: Int, m: Int, day: Int, hour: Int = 8) {
        world.clock.nowMs = CivilDate.toEpochDay(y, m, day) * dayMs + hour * 3_600_000L
    }

    private fun view() = g.view(d.tasks.all())
    private fun notices() = view().let { GoalRhythmRules.notices(it.goals, it.month, world.clock.nowMs, cal) }
    private fun goal(id: String) = view().goals.single { it.id == id }

    @Test
    fun aGoalNothingMovesIsNudgedOnceOnItsFourteenthDay() {
        at(2026, 10, 5)
        val run = g.addGoal("Run 5k", horizon = GoalHorizon.MONTH)
        g.addGoal("Learn Italian", horizon = GoalHorizon.SOMEDAY)
        d.tasks.create(NewTask("Buy running shoes", goalId = run))

        at(2026, 10, 18)
        assertTrue(notices().isEmpty())
        assertTrue("quiet" !in goal(run).meta)

        at(2026, 10, 19)
        val nudge = notices().single()
        assertEquals("goals:quiet:${CivilDate.toEpochDay(2026, 10, 19)}", nudge.key)
        assertEquals(NoticeSource.GOALS, nudge.source)
        assertEquals(NoticeTarget.GOALS, nudge.target)
        assertEquals("“Run 5k” · quiet for 2 weeks", nudge.title)
        assertEquals("Next · Buy running shoes. One small step this week?", nudge.text)
        assertEquals(cal.toEpochMs(CivilDate.toEpochDay(2026, 10, 19), 9 * 60), nudge.atMs)
        assertTrue(goal(run).meta.endsWith("quiet for 2 weeks"), goal(run).meta)

        // One nudge only: the next day nothing, though it is still quiet (a week on, the meta says so).
        at(2026, 10, 20)
        assertTrue(notices().isEmpty())
        at(2026, 10, 26)
        assertTrue(goal(run).meta.endsWith("quiet for 3 weeks"))

        // Moving it on starts the count again: 14 days after the step was done, one more nudge.
        d.tasks.complete(d.tasks.all().single().id)
        assertTrue("quiet" !in goal(run).meta)
        at(2026, 11, 9)
        assertEquals("No next step yet. Add one, or move it to Someday?", notices().single().text)
    }

    @Test
    fun goalsTurningQuietTheSameDayShareOneNudge() {
        at(2026, 10, 5)
        g.addGoal("Save £2,000", horizon = GoalHorizon.YEAR)
        val tidy = g.addGoal("Tidy the garage", horizon = GoalHorizon.MONTH)
        g.addMilestone(tidy, "Clear the shelves")
        at(2026, 10, 19)
        val nudge = notices().single()
        assertEquals("2 goals quiet for 2 weeks", nudge.title)
        assertEquals("“Tidy the garage”, “Save £2,000”. One small step on one this week?", nudge.text)
    }

    @Test
    fun theFirstOfTheMonthLooksBackOnLastMonth() {
        at(2026, 9, 20)
        val old = g.addGoal("Old goal", horizon = GoalHorizon.MONTH)
        g.finishGoal(old) // finished in September: not October's
        at(2026, 10, 2)
        val run = g.addGoal("Run 5k", horizon = GoalHorizon.MONTH)
        val save = g.addGoal("Save £2,000", horizon = GoalHorizon.YEAR)
        val read = g.addGoal("Read 3 books", horizon = GoalHorizon.MONTH)
        g.addGoal("Learn Italian", horizon = GoalHorizon.SOMEDAY)
        at(2026, 10, 20)
        g.setMilestoneDone(g.addMilestone(run, "Run 3k"), true)
        g.finishGoal(read)

        // Not before the 1st.
        at(2026, 10, 31)
        assertNull(view().month)

        at(2026, 11, 1)
        val m = view().month!!
        assertEquals("October", m.monthName)
        assertEquals(listOf("Read 3 books"), m.finished)
        assertEquals(1, m.moved)
        assertEquals(listOf("Save £2,000"), m.quiet)
        assertEquals("October: 1 goal finished · 1 moved on · 1 quiet", m.line)
        val month = notices().single { it.key.startsWith("goals:month:") }
        assertEquals("Goals in October", month.title)
        assertEquals("1 finished · 1 moved on · 1 quiet: “Save £2,000”. Pick this month's goals?", month.text)
        assertEquals(cal.toEpochMs(CivilDate.toEpochDay(2026, 11, 2), 0), month.expiresAtMs)

        // On Goals for the first week (quiet as of today: Run 5k has now gone 18 days); the notice on the 1st only.
        at(2026, 11, 7)
        assertEquals("October: 1 goal finished · 1 moved on · 2 quiet", view().month?.line)
        assertTrue(notices().none { it.key.startsWith("goals:month:") })
        at(2026, 11, 8)
        assertNull(view().month)
    }

    @Test
    fun aGoalAddedThisMonthOrNothingAtAllMeansNoReview() {
        at(2026, 11, 1)
        assertNull(view().month)
        g.addGoal("Run 5k", horizon = GoalHorizon.MONTH)
        assertNull(view().month)
        assertTrue(notices().isEmpty())
        assertEquals(null, GoalRhythmRules.quietLabel(13L))
        assertEquals("quiet for 2 months+", GoalRhythmRules.quietLabel(70L))
        assertEquals(CivilDate.toEpochDay(2026, 12, 1), GoalRhythmRules.previousMonthStart(CivilDate.toEpochDay(2027, 1, 15)))
    }
}
