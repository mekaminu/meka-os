package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through 2026-11-22 A17: the task sheet's notes and checklist add are rows, not open boxes. */
class TaskSheetInputsTest {
    @Test
    fun notesWithNothingInThemShowTheAddRowNotABox() {
        assertNull(TaskNotesRules.shown(null))
        assertNull(TaskNotesRules.shown("   \n "), "blank notes are no notes: the Add row shows")
        assertEquals("Bring the blue bag", TaskNotesRules.shown("  Bring the blue bag \n"))
        assertTrue(TaskNotesRules.ADD.startsWith("+ "), "the row says what it does, like every page's + Add")
    }

    @Test
    fun aClosedFieldSavesWhatIsTypedAndAnEmptyOneClears() {
        assertEquals("Ring first", TaskNotesRules.saved(" Ring first "))
        assertNull(TaskNotesRules.saved("  "))
    }

    @Test
    fun theRowsWordsFitOneLineOnTheCover() {
        assertTrue(ChecklistRules.ADD_ROW.startsWith("+ "))
        assertFalse("…" in ChecklistRules.ADD_ROW || "…" in TaskNotesRules.ADD, "a row is an action, not a placeholder")
        assertTrue((ChecklistRules.ADD_ROW + "  " + ChecklistRules.ADD_ROW_LINE).length <= 32)
        assertTrue(TaskNotesRules.HINT.length <= 36)
    }
}
