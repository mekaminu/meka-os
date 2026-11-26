package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Calendars' ON TODAY switches are rows opening On · Off sheets. */
class CalendarOnTodayChoicesTest {
    private val rules = CalendarOnTodayChoiceRules
    private val kids = CalendarChoice("google|alex@example.com|Kids", "Kids", "Google · Personal", onToday = true, canRename = true)
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun aCalendarIsARowSayingWhetherItsOnToday() {
        assertEquals(NotifyChoiceRow("Kids", "On", "Kids on Today, On. Opens the choices"), rules.row(kids))
        assertEquals("Off", rules.row(kids.copy(onToday = false)).value)
        assertEquals("Kids on Today", rules.spokenTitle(kids))
    }

    @Test
    fun itsSheetSaysWhatEachChoiceDoesWithTheOneInForceLit() {
        val s = rules.sheet(kids)
        assertEquals("Kids on Today", s.title)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("On"), s.lit())
        assertTrue(s.choices[0].spoken.endsWith(", chosen"))
        assertEquals(CalendarOnTodayChoiceRules.OFF_LINE, s.choices[1].line)
        assertEquals(listOf("Off"), rules.sheet(kids.copy(onToday = false)).lit())
        assertTrue("Calendar tab" in s.note)
    }

    @Test
    fun aChoiceWritesOnlyWhatChanges() {
        assertEquals(false, rules.named(true, CallChoiceRules.OFF))
        assertEquals(true, rules.named(false, CallChoiceRules.ON))
        assertNull(rules.named(true, CallChoiceRules.ON))
        assertNull(rules.named(false, "maybe"))
    }

    @Test
    fun aRenamedCalendarIsTitledByItsNewName() {
        val home = kids.copy(label = "Home", defaultLabel = "Kids")
        assertEquals("Home", rules.row(home).title)
        assertEquals("Home on Today", rules.sheet(home).title)
    }
}
