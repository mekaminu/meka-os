package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: the week ahead's Sunday heads-up is a row opening a sheet, not an On pill over time chips. */
class WeekAheadChoicesTest {
    private fun view(on: Boolean, minute: Int) = WeekAheadRules.settingView(WeekAheadSetting(on, minute))

    @Test
    fun theRowSaysTheTimeOrOff() {
        val on = view(true, 19 * 60)
        assertEquals("Sunday heads-up", WeekAheadChoiceRules.row(on).title)
        assertEquals("19:00", WeekAheadChoiceRules.row(on).value)
        assertEquals("Sunday heads-up, 19:00. Opens the choices", WeekAheadChoiceRules.row(on).spoken)
        assertEquals("18:00", WeekAheadChoiceRules.row(WeekAheadSettingView.EMPTY).value)
        assertEquals("Off", WeekAheadChoiceRules.row(view(false, 18 * 60)).value)
    }

    @Test
    fun theSheetIsOffThenTheTimesWithTheOneInUseLit() {
        val sheet = WeekAheadChoiceRules.sheet(view(true, 17 * 60))
        assertEquals(listOf("Off", "16:00", "17:00", "18:00", "19:00", "20:00"), sheet.choices.map { it.label })
        assertEquals(listOf("17:00"), sheet.choices.filter { it.lit }.map { it.label })
        assertTrue(sheet.choices.all { it.line.isNotBlank() })
        assertTrue(sheet.choices.single { it.lit }.spoken.endsWith(", chosen"))
        assertEquals("Sunday heads-up", sheet.title)
        // Off is lit when it's off, though the time is kept.
        assertEquals(listOf("Off"), WeekAheadChoiceRules.sheet(view(false, 17 * 60)).choices.filter { it.lit }.map { it.label })
        // A time set elsewhere (18:30) is among the choices, lit.
        val odd = WeekAheadChoiceRules.sheet(view(true, 18 * 60 + 30))
        assertEquals(listOf("Off", "16:00", "17:00", "18:00", "18:30", "19:00", "20:00"), odd.choices.map { it.label })
        assertEquals(listOf("18:30"), odd.choices.filter { it.lit }.map { it.label })
    }

    @Test
    fun aChoiceReadsBackToTheSetting() {
        assertEquals(WeekAheadSetting(false, 19 * 60), WeekAheadChoiceRules.named(WeekAheadChoiceRules.OFF, 19 * 60))
        assertEquals(WeekAheadSetting(true, 16 * 60), WeekAheadChoiceRules.named((16 * 60).toString(), 19 * 60))
        assertNull(WeekAheadChoiceRules.named("17", 19 * 60))
        assertNull(WeekAheadChoiceRules.named("soon", 19 * 60))
        WeekAheadChoiceRules.sheet(WeekAheadSettingView.EMPTY).choices.forEach {
            assertTrue(WeekAheadChoiceRules.named(it.name, 18 * 60) != null, it.name)
        }
    }
}
