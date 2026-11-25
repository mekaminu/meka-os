package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Mail's ten-minute inbox, meeting prep and warranty reminders are rows opening sheets. */
class MailChoicesTest {
    @Test
    fun eachRowSaysWhatIsChosen() {
        assertEquals("Ten-minute inbox", MailChoiceRules.momentRow(MailMomentTime.EVENING).title)
        assertEquals("Evening", MailChoiceRules.momentRow(MailMomentTime.EVENING).value)
        assertEquals("Meeting prep, 15 min. Opens the choices", MailChoiceRules.prepRow(MeetingPrepRules.DEFAULT).spoken)
        assertEquals("Off", MailChoiceRules.prepRow(MeetingPrepLead.OFF).value)
        assertEquals("£100 or more", MailChoiceRules.warrantyRow(MailWarrantyRules.DEFAULT).value)
        assertEquals("Off", MailChoiceRules.warrantyRow(MailWarrantyLevel.OFF).value)
    }

    @Test
    fun eachSheetListsEveryChoiceWithTheOneInUseLit() {
        val moment = MailChoiceRules.momentSheet(MailMomentTime.MORNING)
        assertEquals(listOf("Morning", "Evening", "Off"), moment.choices.map { it.label })
        assertEquals(listOf("Morning"), moment.choices.filter { it.lit }.map { it.label })
        assertTrue(moment.choices.first().line.contains("08:00"))

        val prep = MailChoiceRules.prepSheet(MeetingPrepLead.TEN)
        assertEquals(listOf("Off", "5 min", "10 min", "15 min", "30 min"), prep.choices.map { it.label })
        assertEquals(listOf("10 min"), prep.choices.filter { it.lit }.map { it.label })

        val warranty = MailChoiceRules.warrantySheet(MailWarrantyLevel.OFF)
        assertEquals(listOf("Off", "£50 or more", "£100 or more", "£250 or more", "£500 or more"), warranty.choices.map { it.label })
        assertEquals(listOf("Off"), warranty.choices.filter { it.lit }.map { it.label })

        listOf(moment, prep, warranty).forEach { s ->
            assertTrue(s.choices.all { it.line.isNotBlank() }, s.title)
            assertTrue(s.choices.single { it.lit }.spoken.endsWith(", chosen"), s.title)
            assertEquals(1, s.choices.count { it.spoken.endsWith(", chosen") }, s.title)
            assertEquals(s.choices.size, s.choices.map { it.name }.toSet().size, s.title)
        }
    }

    @Test
    fun aChoiceReadsBackToItsSetting() {
        MailMomentTime.entries.forEach { t -> MailChoiceRules.momentSheet(t).choices.forEach { assertTrue(MailChoiceRules.moment(it.name) != null) } }
        MeetingPrepLead.entries.forEach { l -> assertEquals(l, MailChoiceRules.prep(MailChoiceRules.prepSheet(l).choices.single { it.lit }.name)) }
        MailWarrantyLevel.entries.forEach { w -> assertEquals(w, MailChoiceRules.warranty(MailChoiceRules.warrantySheet(w).choices.single { it.lit }.name)) }
        assertNull(MailChoiceRules.prep("soon"))
        assertNull(MailChoiceRules.warranty("100"))
        assertNull(MailChoiceRules.moment("morning"))
    }
}
