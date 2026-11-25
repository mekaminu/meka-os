package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Booked…'s sheet (walk-through week 5, A17): chips for the next week and the usual times, and an example ahead of today. */
class AppointmentBookTest {
    private fun day(m: Int, d: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, d)

    @Test
    fun theSheetOffersTheNextSevenDaysTodayFirstAndTheUsualTimes() {
        val today = day(12, 14) // Mon 14 Dec
        val s = AppointmentBookRules.sheet("Dentist", today)
        assertEquals("Dentist", s.title)
        assertEquals(listOf("Today", "Tomorrow", "Wed 16", "Thu 17", "Fri 18", "Sat 19", "Sun 20"), s.days.map { it.label })
        assertEquals((0L until 7L).map { today + it }, s.days.map { it.day })
        assertEquals("08:30", s.times.first().label)
        assertEquals("17:00", s.times.last().label)
        assertEquals(AppointmentRules.LATER_DAYS, 14) // the card's own Not yet is untouched
    }

    @Test
    fun theTypedExampleIsAlwaysAheadOfTodayNeverMonthsBehind() {
        // The walk-through's Dentist card said "When? “Tue 20 Oct 9:30”" while due Mon 14 Dec.
        val today = day(12, 14)
        val hint = AppointmentBookRules.sheet("Dentist", today).typeHint
        assertEquals("Or a later day: “Thu 24 Dec 9:30”", hint)
        val example = hint.substringAfter('“').substringBefore('”')
        val read = AppointmentRules.bookedWhen(example, today, 12 * 60)
        assertEquals(today + AppointmentBookRules.EXAMPLE_AHEAD to 9 * 60 + 30, read)
    }

    @Test
    fun everyChipTheSheetCanSaveIsReadBackAsTheSameDayAndTime() {
        // Across a month end and the turn of the year, and with no time.
        listOf(day(11, 25), day(12, 28), day(2, 26, 2027)).forEach { today ->
            AppointmentBookRules.sheet("Haircut", today).days.forEach { d ->
                AppointmentBookRules.TIMES.forEach { m ->
                    val text = AppointmentBookRules.text(d.day, m)
                    assertEquals(d.day to m, AppointmentRules.bookedWhen(text, today, 8 * 60), "$text from $today")
                }
                val dayOnly = AppointmentBookRules.text(d.day, null)
                assertEquals(d.day, AppointmentRules.bookedWhen(dayOnly, today, 8 * 60)?.first, "$dayOnly from $today")
            }
        }
    }

    @Test
    fun saveSendsTheTypedWordsFirstThenTheChipsAndIsDarkWithNeither() {
        val today = day(12, 14)
        assertNull(AppointmentBookRules.toSave(null, null, "  "))
        assertFalse(AppointmentBookRules.canSave(null, ""))
        assertEquals("Wed 16 Dec 9:30", AppointmentBookRules.toSave(today + 2, 9 * 60 + 30, ""))
        assertEquals("Fri 8 Jan 10am", AppointmentBookRules.toSave(today + 2, 9 * 60, " Fri 8 Jan 10am "))
        assertTrue(AppointmentBookRules.canSave(today, ""))
        assertEquals("Pick the day, or type a later one", AppointmentBookRules.chosenLine(null, null))
        assertEquals("Wed 16 Dec · 09:30", AppointmentBookRules.chosenLine(today + 2, 9 * 60 + 30))
        assertEquals("Wed 16 Dec · no time yet", AppointmentBookRules.chosenLine(today + 2, null))
    }

    @Test
    fun aBlankTitleStillHasAName() {
        assertEquals("Appointment", AppointmentBookRules.sheet("  ", day(12, 14)).title)
    }
}
