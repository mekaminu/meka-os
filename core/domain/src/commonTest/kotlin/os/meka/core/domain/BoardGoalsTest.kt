package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, slice 7: the week's goals across the top of the Board, tasks tagged to them, "+ Goal". */
class BoardGoalsTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC; this week starts Mon 19 Oct.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val monday = today - 2
    private val now = today * dayMs + 10 * 3_600_000L

    private fun task(
        id: String, goal: String? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE, scheduled: Long? = null,
        week: Long? = null, completed: Long? = null,
    ) = Task(
        id, id, null, lifecycle, null, scheduled, null, 0, goal, null, 0, completed, false, boardWeek = week,
    )

    private fun goal(
        id: String, title: String = id, weekOf: Long? = null, horizon: GoalHorizon = GoalHorizon.MONTH, done: Boolean = false,
    ) = GoalItem(
        id, title, null, horizon, 0, true, 0, 0, 0, done, "", false, weekOf = weekOf,
    )

    private fun board(tasks: List<Task>, goals: List<GoalItem>, finished: List<GoalItem> = emptyList()) =
        WeekBoardRules.build(tasks, emptyList(), now, cal, goals = goals, weekGoals = GoalWeekRules.build(goals, finished, today))

    @Test
    fun eachWeekShowsItsPickedGoalsWithTheTasksDoneOfThatWeek() {
        val goals = listOf(goal("run", "Run 5k", weekOf = monday), goal("garden", "Garden ready", weekOf = monday + 7), goal("other"))
        val b = board(
            listOf(
                task("shoes", "run"),
                task("route", "run", week = monday),
                task("jog", "run", Lifecycle.DONE, completed = (today - 1) * dayMs + 3_600_000L),
                task("later", "run", scheduled = (today + 8) * dayMs),
                task("seeds", "garden", scheduled = (today + 7) * dayMs),
                task("loose"),
            ),
            goals,
        )
        val run = b.weeks[0].goals.single()
        assertEquals("run", run.goalId)
        assertEquals("1 of 3 done", run.line)
        assertEquals(1f / 3, run.fill)
        assertTrue(run.picked)
        assertEquals("Goal Run 5k, 1 of 3 done", run.accessibilityLabel)
        // Next week's: its own pick, counting next week's cards only.
        val garden = b.weeks[1].goals.single()
        assertEquals("Garden ready", garden.title)
        assertEquals("0 of 1 done", garden.line)
        assertTrue(b.weeks[2].goals.isEmpty())
        assertTrue(b.weeks[0].canAddGoal)
        // Cards carry their goal.
        assertEquals("◎ Run 5k", b.day(today)!!.cards.first { it.id == "shoes" }.goalTag)
        assertNull(b.day(today)!!.cards.first { it.id == "loose" }.goalTag)
        assertEquals("Shoes, for goal Run 5k", b.day(today)!!.cards.first { it.id == "shoes" }.accessibilityLabel)
        assertEquals("◎ Run 5k", b.weeks[0].lane.single().goalTag)
    }

    @Test
    fun thisWeekWithNothingPickedShowsWhatTodaysLineCountsAndFinishedOnesSayDone() {
        val open = listOf(goal("tidy", "Tidy the loft", horizon = GoalHorizon.WEEK), goal("year", horizon = GoalHorizon.YEAR))
        val finished = listOf(goal("tax", "Tax return", horizon = GoalHorizon.WEEK, done = true))
        val b = board(listOf(task("boxes", "tidy")), open, finished)
        assertEquals(listOf("tidy", "tax"), b.weeks[0].goals.map { it.goalId })
        assertFalse(b.weeks[0].goals[0].picked)
        assertEquals("0 of 1 done", b.weeks[0].goals[0].line)
        assertEquals(BoardGoalRules.GOAL_DONE, b.weeks[0].goals[1].line)
        assertEquals(1f, b.weeks[0].goals[1].fill)
        assertTrue(b.weeks[1].goals.isEmpty(), "the This-week fallback is this week's only")
        assertEquals(BoardGoalRules.NO_TASKS, board(emptyList(), open).weeks[0].goals[0].line)
    }

    @Test
    fun aCardsMenuOffersItsWeeksUnfinishedGoalsTickedAndNoGoalOnceTagged() {
        val goals = listOf(goal("run", "Run 5k", weekOf = monday), goal("read", "Read a book", weekOf = monday), goal("garden", weekOf = monday + 7))
        val b = board(
            listOf(
                task("shoes", "run"), task("plain"), task("seeds", scheduled = (today + 7) * dayMs),
                task("idea", lifecycle = Lifecycle.SOMEDAY), task("done", lifecycle = Lifecycle.DONE, completed = now - 1),
            ),
            goals,
        )
        val tagged = BoardGoalRules.tagChoices(b, "shoes")
        assertEquals(listOf("✓ Run 5k", "Read a book", BoardGoalRules.NO_GOAL), tagged.map { it.label })
        assertTrue(tagged[0].selected)
        assertNull(tagged[2].goalId)
        assertEquals(listOf("Run 5k", "Read a book"), BoardGoalRules.tagChoices(b, "plain").map { it.label })
        // Next week's card offers next week's goals; Someday offers this week's; a done card offers none.
        assertEquals(listOf("garden"), BoardGoalRules.tagChoices(b, "seeds").map { it.goalId })
        assertEquals(listOf("run", "read"), BoardGoalRules.tagChoices(b, "idea").map { it.goalId })
        assertTrue(BoardGoalRules.tagChoices(b, "done").isEmpty())
        // A week with no goals offers nothing.
        assertTrue(BoardGoalRules.tagChoices(board(listOf(task("x")), emptyList()), "x").isEmpty())
    }

    @Test
    fun plusGoalOffersOpenGoalsNotPickedShortestHorizonFirstUntilTheWeekHasThree() {
        val goals = listOf(
            goal("year", "Year one", horizon = GoalHorizon.YEAR), goal("wk", "Week one", horizon = GoalHorizon.WEEK),
            goal("picked", weekOf = monday), goal("month", "Month one"),
        )
        val b = board(emptyList(), goals)
        val offers = BoardGoalRules.pickChoices(goals, b.weeks[0])
        assertEquals(listOf("wk", "month", "year"), offers.map { it.goalId })
        assertEquals("This week", offers[0].horizon)
        assertEquals(listOf("wk", "picked", "month", "year"), BoardGoalRules.pickChoices(goals, b.weeks[1]).map { it.goalId })
        val full = goals.map { if (it.id == "year") it else it.copy(weekOf = monday) }
        val bf = board(emptyList(), full)
        assertFalse(bf.weeks[0].canAddGoal)
        assertTrue(BoardGoalRules.pickChoices(full, bf.weeks[0]).isEmpty())
    }

    @Test
    fun theLinesSayWhatChanged() {
        assertEquals("“Book MOT” · for “Run 5k”", BoardGoalRules.tagLine("book MOT", "Run 5k"))
        assertEquals("“Book MOT” · no goal", BoardGoalRules.tagLine("book MOT", null))
        val b = board(emptyList(), emptyList())
        assertEquals("“Run 5k” is one of this week's goals", BoardGoalRules.pickLine("Run 5k", b.weeks[0], true))
        assertEquals("“Run 5k” is off next week's goals", BoardGoalRules.pickLine("Run 5k", b.weeks[1], false))
        assertEquals("“Run 5k” is one of the goals for ${b.weeks[2].range}", BoardGoalRules.pickLine("Run 5k", b.weeks[2], true))
        assertEquals("Get the whole garden re…", BoardGoalRules.shorten("Get the whole garden ready for spring"))
        assertEquals("◎ Get the whole gar…", BoardGoalRules.tag("Get the whole garden ready"))
    }
}
