package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walk-through week 5, A17: a number goal's Number to reach and Add to it are rows opening sheets, not open boxes. */
class GoalNumberSheetsTest {
    private val R = GoalNumberRules

    private fun metric(current: Long = 80_000, target: Long = 200_000, unit: String = "£", pace: String? = null) =
        GoalMetric(target, unit, current, 0, null, emptyList(), paceLine = pace)

    private fun goal(title: String = "Save £2,000", metric: GoalMetric? = null) =
        GoalItem("g1", title, null, GoalHorizon.MONTH, 0, false, 0, 0, 0, false, "This month", false, metric = metric)

    @Test fun theNumberRowSaysWhereItStandsOrWhatToType() {
        assertEquals("Number to reach", R.numberRow(goal()).label)
        assertEquals("Not set · try “£2,000” from the name", R.numberRow(goal()).line)
        assertEquals(R.NUMBER_EMPTY, R.numberRow(goal(title = "Get fitter")).line)
        assertEquals("£800 of £2,000", R.numberRow(goal(metric = metric())).line)
        assertEquals("£800 of £2,000 · On pace", R.numberRow(goal(metric = metric(pace = "On pace"))).line)
        assertEquals("5 of 12 books", R.numberRow(goal("Read 12 books", metric(500, 1200, "books"))).line)
    }

    @Test fun theNumberSheetStartsWithTheNumberKeptOrTheNamesNumber() {
        assertEquals("£2,000", R.numberInitial(goal()))
        assertEquals("", R.numberInitial(goal(title = "Get fitter")))
        assertEquals("£1,500", R.numberInitial(goal(metric = metric(target = 150_000))))
        assertEquals("Save", R.numberSheet(goal()).addLabel)
        assertTrue(R.numberSheet(goal(metric = metric())).note.contains("blank"))
        assertFalse(R.numberSheet(goal()).note.contains("blank"))
    }

    @Test fun saveLightsOnlyWhenTheNumberWouldChange() {
        // Nothing counted yet: anything typed can be tried; blank can't.
        assertFalse(R.canSaveNumber("  ", goal()))
        assertTrue(R.canSaveNumber("£2,000", goal()))
        // Counting £2,000: the same number again stays dark, written either way.
        val g = goal(metric = metric())
        assertFalse(R.canSaveNumber("£2,000", g))
        assertFalse(R.canSaveNumber(" £2k ", g))
        assertTrue(R.canSaveNumber("£2,500", g))
        assertTrue(R.canSaveNumber("2000 books", g))
        // Blank stops counting; a line that isn't a number is tried and said under the field.
        assertTrue(R.canSaveNumber("", g))
        assertTrue(R.canSaveNumber("lots", g))
        assertFalse(R.numberOk("lots"))
        assertTrue(R.numberOk(""))
        assertTrue(R.numberOk("12 books"))
    }

    @Test fun addToItSaysWhereItIsNowAndHowToType() {
        val m = metric()
        assertEquals("+ Add to it", R.addRow(m).label)
        assertEquals("Now £800 · +200, -1 or =1200", R.addRow(m).line)
        assertEquals("Now 5 books · +200, -1 or =1200", R.addRow(metric(500, 1200, "books")).line)
        assertTrue(R.addSheet(m).note.startsWith("£800 of £2,000 now."))
        assertEquals("Add", R.addSheet(m).addLabel)
        assertFalse(R.canAdd(" "))
        assertTrue(R.canAdd("+200"))
        assertEquals("Added +200", R.addedLine(" +200 ", changed = true))
        assertEquals("No change", R.addedLine("=800", changed = false))
    }
}
