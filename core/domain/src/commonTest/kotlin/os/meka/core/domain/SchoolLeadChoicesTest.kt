package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Family → School emails' On/Off pills are rows opening On · Off sheets. */
class SchoolLeadChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun eachSwitchIsARowSayingWhatsChosen() {
        val rows = SchoolLeadRules.view(SchoolLead(prepTask = false), autoOn = true).rows.map(SchoolLeadChoiceRules::row)
        assertEquals(listOf("Task on the day", "Reminder 3 days before", "Task the day before", "Reminder the day before"), rows.map { it.title })
        assertEquals(listOf("On", "On", "Off", "Off"), rows.map { it.value })
        assertEquals("Task on the day, On. Opens the choices", rows[0].spoken)
    }

    @Test
    fun aReminderWhoseTaskIsOffSaysWhyAndHasNoSheetToOpen() {
        val rows = SchoolLeadRules.view(SchoolLead(dayTask = false), autoOn = true).rows
        val early = rows.first { it.key == SchoolLeadRules.EARLY }
        assertEquals(false, early.enabled)
        assertEquals("Needs the task on the day (it rides on that task)", SchoolLeadChoiceRules.reason(early))
        assertTrue(SchoolLeadChoiceRules.row(early).spoken.endsWith("Needs the task on the day (it rides on that task)"))
        assertNull(SchoolLeadChoiceRules.reason(rows.first { it.key == SchoolLeadRules.DAY_TASK }))
        val off = SchoolLeadRules.view(SchoolLead(), autoOn = false).rows
        assertTrue(off.all { SchoolLeadChoiceRules.reason(it) == "School events are off in What Navilife may do" })
    }

    @Test
    fun theSheetLightsTheChosenOneAndSaysWhatEachMakes() {
        val s = assertNotNull(SchoolLeadChoiceRules.sheet(SchoolLeadRules.DAY_TASK, on = true, mac = false))
        assertEquals("Task on the day", s.title)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("On"), s.lit())
        assertTrue("reminder 3 days before" in s.choices[1].line, s.choices[1].line)
        assertTrue("Mac" in s.note)
        assertTrue(s.choices[0].spoken.endsWith(", chosen"))
        assertEquals(listOf("Off"), SchoolLeadChoiceRules.sheet(SchoolLeadRules.DAY_BEFORE, on = false, mac = true)!!.lit())
        assertTrue("Fold" in SchoolLeadChoiceRules.note(mac = true))
        SchoolLeadRules.KEYS.forEach { assertNotNull(SchoolLeadChoiceRules.sheet(it, true, mac = false), it) }
        assertNull(SchoolLeadChoiceRules.sheet("leadSomethingElse", true, mac = false))
    }

    @Test
    fun aChoiceSetsOnlyAChange() {
        assertEquals(false, SchoolLeadChoiceRules.named(on = true, name = SchoolLeadChoiceRules.OFF))
        assertEquals(true, SchoolLeadChoiceRules.named(on = false, name = SchoolLeadChoiceRules.ON))
        assertNull(SchoolLeadChoiceRules.named(on = true, name = SchoolLeadChoiceRules.ON))
        assertNull(SchoolLeadChoiceRules.named(on = false, name = "maybe"))
    }
}
