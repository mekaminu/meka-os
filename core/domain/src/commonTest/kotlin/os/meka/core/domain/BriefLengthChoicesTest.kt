package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Walk-through week 5, A17: the brief's length is a row opening a sheet, not an open chip row. */
class BriefLengthChoicesTest {
    private val standard = BriefCardRules.lengths(BriefLength.STANDARD)

    @Test fun theRowSaysTheLengthInUse() {
        val row = BriefLengthChoiceRules.row(standard)
        assertEquals("Length", row.title)
        assertEquals("Standard", row.value)
        assertEquals("Short", BriefLengthChoiceRules.row(BriefCardRules.lengths(BriefLength.SHORT)).value)
    }

    @Test fun theSheetSaysWhatEachLengthSays() {
        val sheet = BriefLengthChoiceRules.sheet(standard)
        assertEquals(listOf("Short", "Standard", "Full"), sheet.choices.map { it.label })
        assertEquals(listOf("Standard"), sheet.choices.filter { it.lit }.map { it.label })
        assertEquals("About 45 seconds · your first event, 1 task and 1 headline", sheet.choices[0].line)
        assertEquals("About 90 seconds · your first 3 events, 3 tasks, 3 headlines and your week's goals", sheet.choices[1].line)
        assertEquals("About 3 minutes · your first 6 events, 5 tasks, 5 headlines and your week's goals", sheet.choices[2].line)
        assertEquals("Standard, " + sheet.choices[1].line + ", chosen", sheet.choices[1].spoken)
    }

    @Test fun pickingTheLengthInUseDoesNothing() {
        assertEquals("short", BriefLengthChoiceRules.named(standard, "short"))
        assertNull(BriefLengthChoiceRules.named(standard, "standard"))
        assertNull(BriefLengthChoiceRules.named(standard, "huge"))
    }
}
