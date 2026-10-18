package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Travel mode completeness: a trip's days are away days, so no office, commute or "who's covering?", and the brief says where. */
class TripAwayTest {
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026
    private val fri = thu + 1
    private val sat = thu + 2
    private val mon = thu + 4
    private val tue = thu + 5
    private fun trip(start: Long, end: Long, vararg bookings: TripBooking) = TripItem("t", "Barcelona", start, end, 0, bookings.toList())
    private fun flight(day: Long, minute: Int?) = TripBooking("f$day$minute", "t", BookingKind.FLIGHT, "Flight BA478", day, null, minute, null, 0)

    @Test
    fun everyDayOfATripIsAwayButAnEveningDepartureLeavesTheFirstDayAtWork() {
        assertEquals((fri..mon).associateWith { "Barcelona" }, TripRules.awayDays(listOf(trip(fri, mon, flight(fri, 7 * 60 + 40)))))
        // A flight at 18:20 on the first day: he can work that day.
        assertEquals((sat..mon).toSet(), TripRules.awayDays(listOf(trip(fri, mon, flight(fri, 18 * 60 + 20)))).keys)
        // Nothing timed on the first day: away from the first day.
        assertEquals((fri..mon).toSet(), TripRules.awayDays(listOf(trip(fri, mon))).keys)
        // Two trips on one day: the earlier names it.
        val two = TripRules.awayDays(listOf(trip(mon, tue).copy(id = "u", name = "Lisbon"), trip(fri, mon)))
        assertEquals("Barcelona", two[mon])
        assertEquals("Lisbon", two[tue])
    }

    @Test
    fun anAwayWorkDayIsNoOfficeDayAndSaysSo() {
        val work = WorkHours(WorkSchedule.DEFAULT, away = mapOf(fri to "Barcelona"))
        assertTrue(work.isWorkDay(fri))
        assertFalse(work.atOffice(fri))
        assertTrue(work.atOffice(thu))
        assertNull(CommuteRules.toWorkOn(work, fri))
        assertTrue(CommuteRules.bands(work, fri, cal).isEmpty())
        assertNull(PlacesRules.officeWindow(work, fri, cal))
        assertEquals("away in Barcelona · no commute", CommuteAskRules.dayLine(work, fri, cal))
        assertEquals("Work while away 09:00–17:30", work.line(fri))
        assertEquals(WorkHours.AWAY_TITLE, work.blocks(fri, cal).single().title)
        assertEquals("Away · Barcelona · work 09:00–17:30", WeekAheadRules.dayLine(work, HolidayCalendar.NONE, fri))
        assertFalse(WeekAheadRules.isOfficeDay(work, fri))
        // Annual leave wins: no work at all.
        val leave = work.copy(leaveDays = setOf(fri))
        assertFalse(leave.isWorkDay(fri))
        assertEquals("annual leave · no commute", CommuteAskRules.dayLine(leave, fri, cal))
    }

    @Test
    fun theSchoolDoesNotAskWhoIsCoveringWhileHesAway() {
        val off = SchoolItem("o", SchoolKind.OFF, "INSET day", "", fri, fri, 0, null, emptyList(), 0)
        assertEquals(1, SchoolRules.covers(listOf(off), thu, WorkHours(WorkSchedule.DEFAULT)).size)
        assertTrue(SchoolRules.covers(listOf(off), thu, WorkHours(WorkSchedule.DEFAULT, away = mapOf(fri to "Barcelona"))).isEmpty())
    }

    @Test
    fun theBriefSaysWhereHeIs() {
        val v = MorningBriefView.EMPTY.copy(dateLabel = "Fri 9 Oct", workLine = "Work while away 09:00–17:30",
            story = BriefStory(away = "Barcelona", workEndMin = 17 * 60 + 30))
        assertEquals("It's Friday 9 October, away in Barcelona, a work day, finishing at half past 5.", BriefScriptRules.dayKind(v))
        val weekend = MorningBriefView.EMPTY.copy(dateLabel = "Sat 10 Oct", story = BriefStory(weekend = true, away = "Barcelona"))
        assertEquals("It's Saturday 10 October, away in Barcelona.", BriefScriptRules.dayKind(weekend))
    }
}
