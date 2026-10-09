package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextAutosaveTest {
    @Test
    fun aRenamedTitleSavesTrimmedAndOnOneLine() {
        assertEquals("Call the garage", TextAutosave.titleToSave("  Call the garage ", "Call garage"))
        assertEquals("Buy milk and eggs", TextAutosave.titleToSave("Buy milk\nand eggs", "Buy milk"))
    }

    @Test
    fun blankOrUnchangedSavesNothing() {
        assertNull(TextAutosave.titleToSave("", "Buy milk"))
        assertNull(TextAutosave.titleToSave("   \n ", "Buy milk"))
        assertNull(TextAutosave.titleToSave("Buy milk ", "Buy milk"))
    }

    @Test
    fun renameThenCloseKeepsTheTitle() {
        // The field's life: typed, closed before the pause ran out; closing saves what was typed.
        var saved = "Buy mlik"
        val typed = "Buy milk"
        TextAutosave.titleToSave(typed, saved)?.let { saved = it }
        assertEquals("Buy milk", saved)
        // Reopening shows it: nothing typed, the saved title is taken on.
        assertTrue(TextAutosave.adoptSaved("Buy mlik", saved, dirty = false))
    }

    @Test
    fun aSaveLandingMidTypingDoesNotEatTheTrailingSpace() {
        // "Buy " saved itself as "Buy" after the pause; the field keeps "Buy " so "milk" doesn't run on.
        assertFalse(TextAutosave.adoptSaved("Buy ", "Buy", dirty = false))
    }

    @Test
    fun aChangeFromTheOtherDeviceShowsOnlyWhenNothingIsWaiting() {
        assertTrue(TextAutosave.adoptSaved("Buy milk", "Buy oat milk", dirty = false))
        assertFalse(TextAutosave.adoptSaved("Buy milk!", "Buy oat milk", dirty = true))
    }

    @Test
    fun anAddFieldAddsWhatWasTypedWhenClosed() {
        assertEquals("Ring the school", TextAutosave.pendingAdd(" Ring the school "))
        assertNull(TextAutosave.pendingAdd("  "))
    }
}
