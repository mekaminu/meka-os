package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walk-through week 5, A17: an open goal's Milestones add and Add a step are rows opening sheets, not open boxes. */
class GoalStepSheetsTest {
    private val R = GoalStepRules

    private fun ms(n: Int) = (1..n).map { GoalMilestone("m$it", "g1", "Step $it", false) }

    private fun goal(milestones: List<GoalMilestone> = emptyList(), metric: GoalMetric? = null) =
        GoalItem("g1", "Run a half marathon", null, GoalHorizon.MONTH, 0, false, 0, 0, 0, false, "This month", false,
            milestones = milestones, metric = metric)

    @Test fun theMilestoneRowSaysWhatOneIsThenHowManyMoreFit() {
        assertEquals("+ Add a milestone", R.milestoneRow(goal()).label)
        assertEquals(R.MILESTONE_EMPTY, R.milestoneRow(goal()).line)
        assertEquals("Room for 9 more", R.milestoneRow(goal(ms(3))).line)
        assertEquals("Room for 1 more", R.milestoneRow(goal(ms(GoalRules.MAX_MILESTONES - 1))).line)
        assertTrue(R.showsMilestoneRow(goal(ms(GoalRules.MAX_MILESTONES - 1))))
        assertFalse(R.showsMilestoneRow(goal(ms(GoalRules.MAX_MILESTONES))))
    }

    @Test fun addLightsOnlyForANewName() {
        val g = goal(ms(2))
        assertFalse(R.canAddMilestone("   ", g))
        assertFalse(R.canAddMilestone("step 1", g)) // already there, any case
        assertFalse(R.canAddMilestone("  Step   2 ", g)) // spaces squeezed as the goal keeps them
        assertTrue(R.canAddMilestone("Run 10k", g))
        assertFalse(R.canAddMilestone("Run 10k", goal(ms(GoalRules.MAX_MILESTONES))))
        assertEquals("Run 10k", R.cleaned("  Run   10k ", R.MAX_MILESTONE))
    }

    @Test fun theMilestoneNoteIsTrueForANumberGoal() {
        assertTrue(R.milestoneSheet(goal()).note.contains("counts towards the goal's ring"))
        val m = GoalMetric(0, "£", 0, 0, null, emptyList())
        assertTrue(R.milestoneSheet(goal(metric = m)).note.contains("follows its number"))
        assertFalse(R.milestoneSheet(goal(metric = m)).note.contains("counts towards"))
        assertEquals("Add", R.milestoneSheet(goal()).addLabel)
    }

    @Test fun theStepRowAndSheet() {
        assertEquals("+ Add a step", R.stepRow.label)
        assertEquals("Add a step", R.stepSheet.title)
        assertFalse(R.canAddStep(" "))
        assertTrue(R.canAddStep("Book a coach"))
    }
}
