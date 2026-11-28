package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: a goal's What done looks like and Why it matters are rows opening sheets, not open boxes. */
class GoalTextsTest {
    private val R = GoalTextRules

    private fun goal(target: String? = null, why: String? = null) =
        GoalItem("g1", "Run a 10k", target, GoalHorizon.MONTH, 0, false, 0, 0, 0, false, "This month", false, why = why)

    @Test fun eachRowShowsTheWordsKeptOrWhatToSay() {
        assertEquals("What done looks like", R.targetRow(goal()).label)
        assertEquals(R.TARGET_EMPTY, R.targetRow(goal()).line)
        assertEquals("Under 55 minutes", R.targetRow(goal(target = "Under 55 minutes")).line)
        assertEquals("Why it matters", R.whyRow(goal()).label)
        assertEquals(R.WHY_EMPTY, R.whyRow(goal(why = "  ")).line)
        assertEquals("More energy", R.whyRow(goal(why = " More energy ")).line)
        assertEquals("What done looks like, Under 55 minutes", GoalAddRules.rowSpoken(R.targetRow(goal(target = "Under 55 minutes"))))
    }

    @Test fun aLongWhyIsCutAtAWordOnTheRow() {
        val long = "Because I want to keep up on long weekend walks and feel strong enough to carry the shopping up the hill every week"
        val line = R.whyRow(goal(why = long)).line
        assertTrue(line.endsWith("…"), line)
        assertTrue(line.length <= R.PREVIEW_MAX + 1, line)
        assertTrue(long.startsWith(line.removeSuffix("…")), line)
        assertFalse(line.removeSuffix("…").endsWith(" "), line)
        // A second line is hinted at, not shown.
        assertEquals("More energy…", R.preview("More energy\nand sleep better"))
        assertNull(R.preview(null))
        assertNull(R.preview(" \n "))
    }

    @Test fun saveLightsOnlyWhenTheWordsWouldChange() {
        assertFalse(R.canSave("", null, R.MAX_WHY))
        assertFalse(R.canSave("   ", null, R.MAX_WHY))
        assertTrue(R.canSave("More energy", null, R.MAX_WHY))
        assertFalse(R.canSave(" More energy ", "More energy", R.MAX_WHY))
        assertTrue(R.canSave("More energy, sleep", "More energy", R.MAX_WHY))
        // Blank clears words that are there.
        assertTrue(R.canSave("", "More energy", R.MAX_WHY))
        assertNull(R.cleaned("  ", R.MAX_TARGET))
        assertEquals(R.MAX_TARGET, R.cleaned("x".repeat(R.MAX_TARGET + 20), R.MAX_TARGET)?.length)
    }

    @Test fun theSheetsSaySaveAndWhatBlankDoes() {
        assertEquals("What done looks like", R.targetSheet.title)
        assertEquals("Why it matters", R.whySheet.title)
        assertEquals("Save", R.targetSheet.addLabel)
        assertTrue(R.targetSheet.note.contains("blank"))
        assertTrue(R.whySheet.note.contains("blank"))
    }
}
