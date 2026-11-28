package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: an open goal's Link a task is a row opening a sheet of your open tasks, not an open box. */
class GoalLinkSheetTest {
    private val R = GoalLinkRules

    private fun task(id: String, title: String, goalId: String? = null) =
        Task(id, title, null, Lifecycle.ACTIVE, null, null, null, 0, goalId, null, 0, null, false)

    private fun goal(tasks: Int = 0) =
        GoalItem("g1", "Run a half marathon", null, GoalHorizon.MONTH, 0, true, 0, tasks, 0, false, "This month", false)

    @Test fun theRowSaysWhatLinkingDoesThenHowManyCount() {
        assertEquals("+ Link a task", R.row(goal()).label)
        assertEquals(R.EMPTY_LINE, R.row(goal()).line)
        assertEquals("1 task counts towards it · link another", R.row(goal(1)).line)
        assertEquals("3 tasks count towards it · link another", R.row(goal(3)).line)
    }

    @Test fun aTaskOnAnotherGoalSaysLinkingMovesIt() {
        val titles = mapOf("g2" to "Get fit")
        assertNull(R.taskLine(task("t1", "Buy trainers"), "g1", titles))
        assertEquals("On Get fit · linking moves it here", R.taskLine(task("t1", "Buy trainers", "g2"), "g1", titles))
        assertEquals("On another goal · linking moves it here", R.taskLine(task("t1", "Buy trainers", "g9"), "g1", titles))
        assertNull(R.taskLine(task("t1", "Buy trainers", "g1"), "g1", titles))
    }

    @Test fun theEmptyLineSaysWhyNothingIsOffered() {
        assertEquals(R.NO_TASKS, R.emptyLine("  "))
        assertEquals("No open task has \"dentist appt\" in its name.", R.emptyLine("  dentist   appt "))
    }

    @Test fun linkLightsOnlyForAPickedTaskStillOffered() {
        val offered = listOf(task("t1", "Buy trainers"), task("t2", "Book a coach"))
        assertFalse(R.canLink(null, offered))
        assertTrue(R.canLink("t2", offered))
        assertFalse(R.canLink("t3", offered)) // narrowed out by the words
        assertEquals("Link", R.sheet.addLabel)
        assertEquals("Buy trainers, On Get fit · linking moves it here, picked",
            R.spoken(task("t1", "Buy trainers"), "On Get fit · linking moves it here", true))
    }

    @Test fun theListIsShownBeforeAnythingIsTyped() {
        // The sheet asks for candidates with the field blank: every open task not on the goal, soonest first.
        val tasks = listOf(task("t1", "Buy trainers"), task("t2", "Book a coach", "g1"), task("t3", "Stretch", "g2"))
        assertEquals(listOf("t1", "t3"), GoalRules.linkCandidates(tasks, "g1", "").map { it.id })
    }
}
