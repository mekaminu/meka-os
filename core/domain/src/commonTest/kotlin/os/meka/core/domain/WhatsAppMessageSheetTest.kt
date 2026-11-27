package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Walk-through week 5, A17: WhatsApp calls' open message box is a "Your at-work message" row opening its sheet. */
class WhatsAppMessageSheetTest {
    @Test
    fun aShortMessageShowsWhole() {
        val row = WhatsAppCallRules.messageRow("At work, I'll call you back after 5.")
        assertEquals(WhatsAppCallRules.MESSAGE_LABEL, row.label)
        assertEquals("At work, I'll call you back after 5.", row.line)
    }

    @Test
    fun aLongMessageIsCutAtAWord() {
        val line = WhatsAppCallRules.messageRow(WhatsAppCallRules.DEFAULT_MESSAGE).line
        assertTrue(line.endsWith("…"), line)
        assertTrue(line.length <= WhatsAppCallRules.MESSAGE_ROW_MAX + 1, line)
        assertTrue(WhatsAppCallRules.DEFAULT_MESSAGE.startsWith(line.removeSuffix("…")), line)
        // Never a half word before the dots.
        val kept = line.removeSuffix("…")
        assertEquals(' ', WhatsAppCallRules.DEFAULT_MESSAGE.getOrNull(kept.length) ?: ' ')
    }

    @Test
    fun aBlankMessageShowsTheSuggestedOne() {
        assertEquals(WhatsAppCallRules.messagePreview(WhatsAppCallRules.DEFAULT_MESSAGE), WhatsAppCallRules.messagePreview("  "))
    }

    @Test
    fun theSheetSaysWhatHappensAndSaves() {
        val sheet = WhatsAppCallRules.messageSheet
        assertEquals(WhatsAppCallRules.MESSAGE_LABEL, sheet.title)
        assertEquals("Save", sheet.addLabel)
        assertTrue("once a day" in sheet.note)
        assertTrue("blank" in sheet.note.lowercase())
    }

    @Test
    fun saveLightsOnlyForAChange() {
        val saved = WhatsAppCallRules.DEFAULT_MESSAGE
        assertFalse(WhatsAppCallRules.canSaveMessage(saved, saved))
        assertFalse(WhatsAppCallRules.canSaveMessage("  $saved ", saved))
        assertFalse(WhatsAppCallRules.canSaveMessage("", saved))
        assertTrue(WhatsAppCallRules.canSaveMessage("Back after 5", saved))
        assertTrue(WhatsAppCallRules.canSaveMessage("", "Back after 5"))
    }
}
