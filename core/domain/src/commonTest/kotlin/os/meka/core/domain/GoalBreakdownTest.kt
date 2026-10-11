package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Goals, properly, slice 4: Break it down's checks and lines (non-AI, pure). */
class GoalBreakdownTest {

    @Test
    fun proposalsAreCleanedAndNeverRepeatWhatTheGoalHas() {
        val b = GoalBreakdownRules.check(
            milestones = listOf("1. run 5k without stopping.", "Run 10k", " - “Run 15k” ", "run 5K without stopping", null, "", "See https://example.com"),
            steps = listOf("Buy running shoes", "run 15k", "Book a gait analysis."),
            existingMilestones = listOf("Run 10k"),
            openSteps = listOf("buy running shoes"),
        )
        assertEquals(listOf("Run 5k without stopping", "Run 15k"), b.milestones)
        // A step that repeats an open step or a proposed milestone is dropped.
        assertEquals(listOf("Book a gait analysis"), b.steps)
    }

    @Test
    fun atMostTheGoalsRoomAndThreeSteps() {
        val many = (1..9).map { "Milestone $it" }
        assertEquals(7, GoalBreakdownRules.check(many, emptyList(), emptyList(), emptyList()).milestones.size)
        val existing = (1..10).map { "Old $it" }
        assertEquals(listOf("Milestone 1", "Milestone 2"), GoalBreakdownRules.check(many, emptyList(), existing, emptyList()).milestones)
        assertEquals(0, GoalBreakdownRules.room(12))
        assertEquals(3, GoalBreakdownRules.check(emptyList(), (1..5).map { "Step $it" }, emptyList(), emptyList()).steps.size)
        assertNull(GoalBreakdownRules.clean("x".repeat(121), GoalRules.MAX_MILESTONE_TITLE))
        assertTrue(GoalBreakdownRules.check(emptyList(), emptyList(), emptyList(), emptyList()).isEmpty)
    }

    @Test
    fun linesSayWhatIsOfferedAndKept() {
        assertEquals("Tick what to keep", GoalBreakdownRules.keepLabel(0))
        assertEquals("Keep 3", GoalBreakdownRules.keepLabel(3))
        assertEquals("Added 3 milestones and 1 step", GoalBreakdownRules.keptLine(3, 1))
        assertEquals("Added 1 milestone", GoalBreakdownRules.keptLine(1, 0))
        assertEquals("Added 2 steps", GoalBreakdownRules.keptLine(0, 2))
        assertEquals("Nothing added", GoalBreakdownRules.keptLine(0, 0))
        assertEquals("4 milestones and 1 first step to choose from", GoalBreakdownRules.readyLine(GoalBreakdown(List(4) { "m$it" }, listOf("s"))))
        assertEquals("2 first steps to choose from", GoalBreakdownRules.readyLine(GoalBreakdown(emptyList(), listOf("a", "b"))))
    }
}
