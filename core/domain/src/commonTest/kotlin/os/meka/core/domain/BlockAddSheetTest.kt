package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Work mode's "Number to block" box is a "+ Block a number" row opening its sheet. */
class BlockAddSheetTest {
    @Test
    fun theRowSaysWhatBlockingDoes() {
        assertEquals("+ Block a number", BlockedCallerRules.addRow.label)
        assertTrue("won't ring" in BlockedCallerRules.addRow.line)
    }

    @Test
    fun theSheetAsksForTheNumberWithAnExampleItCanBlock() {
        val sheet = BlockedCallerRules.addSheet
        assertEquals("Block a number", sheet.title)
        assertEquals(BlockedCallerRules.ADD_SPOKEN, sheet.spokenHint)
        assertEquals(BlockedCallerRules.BLOCK_LABEL, sheet.addLabel)
        assertTrue("Unblock" in sheet.note)
        // The example is a number the list would take.
        assertNotNull(BlockedCallerRules.keyOf(sheet.hint))
    }

    @Test
    fun blockLightsOnceSomethingIsTypedAndNotWhileBlocking() {
        assertFalse(BlockedCallerRules.canAdd("", busy = false))
        assertFalse(BlockedCallerRules.canAdd("  ", busy = false))
        assertFalse(BlockedCallerRules.canAdd("07700 900123", busy = true))
        assertTrue(BlockedCallerRules.canAdd("07700 900123", busy = false))
    }

    @Test
    fun aRefusalIsSaidInTheSheet() {
        assertEquals(BlockedCallerRules.NOT_A_NUMBER, BlockedCallerRules.sheetProblem(true))
        assertNull(BlockedCallerRules.sheetProblem(false))
        // What the sheet refuses: letters, too short, withheld.
        assertNull(BlockedCallerRules.keyOf("call me"))
        assertNull(BlockedCallerRules.keyOf("123"))
    }
}
