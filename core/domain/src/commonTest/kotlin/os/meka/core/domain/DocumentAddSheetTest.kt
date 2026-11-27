package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: the Vault's add box is a "+ Add a document" row opening its sheet. */
class DocumentAddSheetTest {
    @Test
    fun theRowSaysWhatItAsksFor() {
        assertEquals(GoalAddRow("+ Add a document", "Its name and the day it runs out"), DocumentRules.addRow(0))
        assertEquals("+ Add another document", DocumentRules.addRow(2).label)
    }

    @Test
    fun theSheetOffersAKindNotOnTheListYet() {
        val sheet = DocumentRules.addSheet(DocumentRules.addHint(listOf(DocKind.PASSPORT)))
        assertEquals("Add a document", sheet.title)
        assertEquals("Driving licence 12 Nov 2031", sheet.hint)
        assertEquals(DocumentRules.ADD_SPOKEN, sheet.spokenHint)
        assertEquals("Add", sheet.addLabel)
        assertTrue("no document numbers" in sheet.note)
    }

    @Test
    fun addLightsOnceSomethingIsTypedAndNotWhileAdding() {
        assertFalse(DocumentRules.canAdd("", busy = false))
        assertFalse(DocumentRules.canAdd("   ", busy = false))
        assertFalse(DocumentRules.canAdd("Railcard 05/2027", busy = true))
        assertTrue(DocumentRules.canAdd("Railcard 05/2027", busy = false))
    }

    @Test
    fun aRefusalStaysInTheSheetAndWhatWasKeptOnThePage() {
        val refused = DocumentAdded(DocumentRules.NOT_READ, refused = true)
        val kept = DocumentAdded("Added Railcard · runs out May 2027", refused = false, id = "d1")
        assertEquals(DocumentRules.NOT_READ, DocumentRules.sheetProblem(refused))
        assertNull(DocumentRules.pageSaid(refused))
        assertNull(DocumentRules.sheetProblem(kept))
        assertEquals(kept.line, DocumentRules.pageSaid(kept))
        assertNull(DocumentRules.sheetProblem(null))
    }
}
