package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board (j): the phone's own calendars, read on the Fold, shown once, never as an address, never editable. */
class PhoneCalendarsTest {
    private val hour = 3_600_000L
    private val thu = CivilDate.toEpochDay(2026, 10, 29)
    private val gmt = LocalCalendar.fixedOffset(0)
    private val start = gmt.toEpochMs(thu, 21 * 60)

    private fun row(id: Long, title: String?, calendar: String? = "My calendar", type: String? = "com.osp.app.signin", at: Long = start) =
        PhoneCalendarRow(id, calendar, type, title, at, at + 2 * hour, false)

    private fun server(title: String, at: Long = start) =
        CalendarEvent("g1", title, at, at + 2 * hour, false, null, "google", "alex@example.com", "alex@example.com")

    @Test
    fun aSessionInTheSamsungCalendarBecomesAViewOnlyEventNamedAfterItsCalendar() {
        val e = PhoneCalendarRules.events(listOf(row(7, " Study · Data analytics ")), emptyList()).single()
        assertEquals("phone:7:$start", e.id)
        assertEquals("Study · Data analytics", e.title)
        assertEquals(PhoneCalendarRules.PROVIDER, e.provider)
        assertNull(e.account)
        assertEquals("My calendar", CalendarRules.label(e))
        assertEquals("On this phone", CalendarRules.detail(e))
        assertTrue(e.readOnly)
        assertTrue(StudyAutoRules.isStudy(e.title))
        // Never editable from Navilife, and it says where to change it.
        assertFalse(CalendarEditRules.editable(e) { _, _ -> true })
        assertEquals(PhoneCalendarRules.EDIT_LINE, CalendarEditRules.notEditableReason(e) { _, _ -> true })
    }

    @Test
    fun googleCalendarsOnThePhoneAndEventsTheServerHasAreLeftOut() {
        val rows = listOf(
            row(1, "Gym", type = "com.google"),
            row(2, "Dentist"),
            row(3, "dentist ", type = "LOCAL", at = start + 30_000L),
            row(4, "Dinner", at = start + hour),
        )
        val shown = PhoneCalendarRules.events(rows, listOf(server("Dentist")))
        assertEquals(listOf("Dinner"), shown.map { it.title })
        // An all-day one with the same title as a timed server event stays.
        val allDay = PhoneCalendarRow(9, "My calendar", "LOCAL", "Dentist", gmt.toEpochMs(thu, 0), gmt.toEpochMs(thu + 1, 0), true)
        assertEquals(listOf("Dentist"), PhoneCalendarRules.events(listOf(allDay), listOf(server("Dentist"))).map { it.title })
    }

    @Test
    fun aCalendarNamedAfterAnAddressOrNothingReadsThisPhone() {
        assertEquals(PhoneCalendarRules.NAME, PhoneCalendarRules.name("alex@example.com"))
        assertEquals(PhoneCalendarRules.NAME, PhoneCalendarRules.name("  "))
        assertEquals(PhoneCalendarRules.NAME, PhoneCalendarRules.name(null))
        assertEquals("Family", PhoneCalendarRules.name(" Family "))
        val e = PhoneCalendarRules.events(listOf(row(5, null, calendar = "alex@example.com")), emptyList()).single()
        assertEquals("(No title)", e.title)
        assertEquals(PhoneCalendarRules.NAME, CalendarRules.label(e))
    }

    @Test
    fun calendarsListsEachPhoneCalendarWithItsCountAndLeavesGoogleOut() {
        val rows = listOf(row(1, "A"), row(2, "B", at = start + hour), row(3, "C", calendar = "Family", type = "LOCAL"), row(4, "D", type = "com.google", calendar = "Personal"))
        val names = listOf<Pair<String?, String?>>("My calendar" to "com.osp.app.signin", "Family" to "LOCAL", "Birthdays" to "LOCAL", "Personal" to "com.google")
        val lines = PhoneCalendarRules.calendars(rows, names, start - hour)
        assertEquals(listOf("Birthdays · no events in the next 5 weeks", "Family · 1 event in the next 5 weeks", "My calendar · 2 events in the next 5 weeks"), lines.map { it.line })
    }

    @Test
    fun thePhonesCalendarCanBeTurnedOffTodayLikeAnyOther() {
        val e = PhoneCalendarRules.events(listOf(row(1, "Dinner")), emptyList()).single()
        assertEquals("phone||My calendar", CalendarRules.key(e))
    }
}
