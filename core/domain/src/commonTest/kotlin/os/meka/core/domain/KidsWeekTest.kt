package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Kids' admin, slice 3: what the children need in the next 7 days (invented children, school and club). */
class KidsWeekTest {
    // Thursday 15 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val today = CivilDate.toEpochDay(2026, 10, 15)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val now = at(today, 8)
    private val alex = AboutChild("Alex", 7, "Oakfield School")
    private val sam = AboutChild("Sam", 10, "Oakfield School")
    private val kids = listOf(alex, sam)
    private val noHolidays = HolidayCalendar()

    private fun school(id: String, kind: SchoolKind, title: String, who: String, start: Long, end: Long = start, weekday: Int = 0) =
        SchoolItem(id, kind, title, who, start, end, weekday, null, emptyList(), 0L)

    private fun ev(id: String, title: String, day: Long, h: Int, m: Int = 0, calendarName: String? = "Family") =
        CalendarEvent(id, title, at(day, h, m), at(day, h + 1, m), false, null, "google", "me@example.com", calendarName)

    private fun fee(id: String, title: String, due: Long, pence: Long?, child: String) = RenewalItem(
        id = id, title = title, kind = ObligationKind.BILL, subject = null, notes = null, dueDay = due, cancelByDay = null,
        leadDays = 7, costPence = pence, repeats = RenewalRepeat.MONTHLY, repeatLabel = RenewalRules.repeatLabel(RenewalRepeat.MONTHLY),
        state = RenewalRules.state(due, null, 7, today), meta = "", doneLabel = "Paid", stopLabel = "Stop tracking",
        hasConflict = false, forChild = child,
    )

    private fun week(
        school: List<SchoolItem> = emptyList(), events: List<CalendarEvent> = emptyList(), radar: List<RenewalItem> = emptyList(),
        set: Map<String, String> = emptyMap(), children: List<AboutChild> = kids, nowMs: Long = now,
    ) = KidsWeekRules.week(children, school, events, set, radar, nowMs, cal, noHolidays)

    @Test
    fun schoolThingsComeOnTheirDaysWithKitAndWhoseTheyAre() {
        val w = week(school = listOf(
            school("pe", SchoolKind.WEEKLY, "PE", "Alex", today - 30, weekday = 5), // Fridays
            school("nu", SchoolKind.DAY, "Non-uniform day", "", today + 4), // Mon 19 Oct
            school("in", SchoolKind.OFF, "INSET day", "Sam", today + 5), // Tue 20 Oct
        ))
        assertEquals(listOf("Tomorrow", "Mon 19 Oct", "Tue 20 Oct"), w.days.map { it.label })
        assertEquals("Alex: PE kit", w.days[0].items.single().text)
        assertTrue(w.days[0].items.single().lit)
        assertEquals("Non-uniform day", w.days[1].items.single().text)
        assertFalse(w.days[1].items.single().lit)
        assertEquals("Sam off school · INSET day", w.days[2].items.single().text)
        assertEquals("3 things in the next 7 days · 1 today or tomorrow", w.line)
    }

    @Test
    fun aSchoolLineForSomeoneNotInAboutYouIsLeftOut() {
        val w = week(school = listOf(school("pe", SchoolKind.WEEKLY, "Swimming", "Jo", today - 30, weekday = 5)))
        assertTrue(w.isEmpty)
        assertEquals(KidsWeekRules.EMPTY, w.line)
    }

    @Test
    fun clubSessionsNameTheChildByAgeGroupOrTheSeriesHeSet() {
        val u10 = ev("m", "Training U10s", today + 2, 10) // Sat
        val training = ev("t", "Training", today + 5, 16, 55) // Tue
        val unknown = ev("u", "Practice", today + 6, 17) // Wed
        val set = mapOf(ClubChildRules.seriesId(training, cal) to "Alex")
        val w = week(events = listOf(unknown, training, u10, ev("x", "Dentist", today + 1, 9)), set = set)
        assertEquals(listOf("Sam: Training U10s 10:00", "Alex: Training 16:55", "Practice 17:00"), w.days.flatMap { d -> d.items.map { it.text } })
        // A session that has finished today isn't listed.
        assertTrue(week(events = listOf(ev("e", "Training", today, 6)), set = set).isEmpty)
    }

    @Test
    fun feesDueThisWeekAreLitAndOverdueOnesSitOnToday() {
        val w = week(radar = listOf(
            fee("f1", "Football", today + 3, 3500, "c-alex"),
            fee("f2", "Swimming", today - 2, 6000, "c-sam"),
            fee("f3", "Chess", today + 20, null, "c-sam"),
            fee("f4", "Gym", today + 1, 1000, "c-jo"),
        ))
        assertEquals("Today", w.days[0].label)
        assertEquals("Sam's Swimming · £60 was due Tue 13 Oct", w.days[0].items.single().text)
        assertEquals("Alex's Football · £35 due", w.days[1].items.single().text)
        assertTrue(w.days.all { d -> d.items.all { it.lit } })
        assertEquals(2, w.count)
    }

    @Test
    fun noChildrenMeansNoWeek() {
        val w = week(children = emptyList(), school = listOf(school("nu", SchoolKind.DAY, "Trip", "", today + 1)))
        assertTrue(w.isEmpty)
    }
}
