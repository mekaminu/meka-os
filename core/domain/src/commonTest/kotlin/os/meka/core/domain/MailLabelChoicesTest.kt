package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: a mail account's label is a row opening a sheet, not an open chip row. */
class MailLabelChoicesTest {
    @Test fun theRowSaysTheLabelInUse() {
        val row = MailLabelChoiceRules.row(MailLabel.PERSONAL)
        assertEquals("Label", row.title)
        assertEquals("Personal", row.value)
        assertEquals("Business", MailLabelChoiceRules.row(MailLabel.BUSINESS).value)
    }

    @Test fun theSheetSaysWhatEachLabelDoes() {
        val sheet = MailLabelChoiceRules.sheet(MailLabel.BUSINESS)
        assertEquals(listOf("Personal", "Business", "Outlook"), sheet.choices.map { it.label })
        assertEquals(listOf("Business"), sheet.choices.filter { it.lit }.map { it.label })
        assertTrue(sheet.choices[1].line.contains("Protected time can hold it till morning"))
        assertEquals("Business, " + sheet.choices[1].line + ", chosen", sheet.choices[1].spoken)
        // Only Business is held in protected time, as ProtectedMailRules holds only the Business account's mail.
        assertEquals(1, sheet.choices.count { it.line.contains("hold it") })
    }

    @Test fun pickingTheLabelInUseDoesNothing() {
        assertEquals(MailLabel.BUSINESS, MailLabelChoiceRules.named(MailLabel.PERSONAL, "BUSINESS"))
        assertNull(MailLabelChoiceRules.named(MailLabel.PERSONAL, "PERSONAL"))
        assertNull(MailLabelChoiceRules.named(MailLabel.PERSONAL, "WORK"))
    }
}
