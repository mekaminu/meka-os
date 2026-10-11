package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Sunday week ahead (Meka approved 2026-10-10), slice 1: the coming week day by day and the family sync. */
class WeekAheadTest {
    // Sunday 11 October 2026, British Summer Time (UTC+1).
    private val offset = 3_600_000L
    private val cal = LocalCalendar.fixedOffset(offset)
    private val sun = CivilDate.toEpochDay(2026, 10, 11)
    private val mon = sun + 1
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun window(day: Long) = DayWindow(at(day, 0), at(day + 1, 0), offset)

    private fun ev(id: String, title: String, day: Long, h: Int, provider: String = "google", calendar: String? = "Personal", place: String? = null) =
        CalendarEvent(id, title, at(day, h), at(day, h + 1), false, place, provider, "meka@gmail.com", calendar)

    private fun allDay(id: String, title: String, first: Long, last: Long) =
        CalendarEvent(id, title, first * CivilDate.DAY_MS, (last + 1) * CivilDate.DAY_MS, true, null, "google", "meka@gmail.com", "Personal")

    private fun school(id: String, kind: SchoolKind, title: String, who: String, start: Long, end: Long = start, weekday: Int = 0) =
        SchoolItem(id, kind, title, who, start, end, weekday, null, emptyList(), 0L)

    private fun renewal(id: String, title: String, due: Long, cancelBy: Long? = null, pence: Long? = null) = RenewalItem(
        id, title, ObligationKind.SUBSCRIPTION, null, null, due, cancelBy, 7, pence, null, "Every month", RenewalState.SOON, "", "Renewed", "Cancelled it", false,
    )

    private fun inputs(
        homeDays: Set<Long> = setOf(mon + 2), leaveDays: Set<Long> = setOf(mon + 4),
    ) = WeekAheadInputs(
        events = listOf(
            ev("p", "Parents' evening", mon + 1, 18, calendar = "Family"),
            ev("b", "Barça v Getafe", mon + 5, 17, provider = "fixtures", calendar = null),
            ev("f", "BUFC U10 v Arlesey", mon + 6, 10, place = "Arlesey Town FC, Hitchin Rd"),
            ev("w", "Team lunch", mon, 12),
            allDay("h", "Half term", mon + 3, mon + 4),
        ),
        work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar.NONE, homeDays = homeDays, leaveDays = leaveDays),
        holidays = HolidayCalendar.NONE,
        school = listOf(
            school("inset", SchoolKind.OFF, "INSET day", "", mon + 3),
            school("pe", SchoolKind.WEEKLY, "PE", "Rex", mon - 30, mon + 365, weekday = 2),
        ),
        dateNight = DateNightSetting(weekday = 5, startMin = 19 * 60, anchorDay = mon + 4),
        renewals = listOf(renewal("n", "Netflix", mon + 2, pence = 1099), renewal("g", "Gym", mon + 9, cancelBy = mon + 3)),
        appointments = listOf(AppointmentItem("d", "Dentist", 0, 6, null, mon, 9 * 60 + 30, null, 0L)),
    )

    private fun view(nowMs: Long = at(sun, 18), i: WeekAheadInputs = inputs()) = WeekAheadRules.view(i, nowMs, cal, ::window)

    @Test
    fun onSundayItIsNextWeekDayByDay() {
        val v = view()
        assertEquals(mon, v.weekStart)
        assertEquals("Next week", v.title)
        assertEquals("Mon 12 – Sun 18 Oct", v.rangeLabel)
        assertEquals(7, v.days.size)
        assertEquals(
            listOf("Office 09:00–17:30", "Office 09:00–17:30", "Work from home 09:00–17:30", "Office 09:00–15:30", "Annual leave", null, null),
            v.days.map { it.dayLine },
        )
        // Monday: the dentist, then lunch, in time order.
        assertEquals(listOf("09:30 Dentist", "12:00 Team lunch"), v.days[0].lines.map { "${it.time} ${it.text}" })
        // Tuesday: PE (no time) before the evening.
        assertEquals(listOf("Rex: PE", "Parents' evening"), v.days[1].lines.map { it.text })
        // Wednesday: Netflix due with its price.
        assertEquals("Netflix due · £10.99", v.days[2].lines.single().text)
        // Thursday: half term (all day, until Friday) first, then the INSET day lit (an office day), Gym's cancel-by lit.
        val thu = v.days[3].lines
        assertEquals("Half term · until Fri 16 Oct", thu[0].text)
        assertEquals("All day", thu[0].time)
        assertTrue(thu.first { it.kind == WeekAheadKind.SCHOOL }.lit)
        assertTrue(thu.first { it.kind == WeekAheadKind.DUE }.lit)
        // The all-day event isn't repeated on Friday; date night is there.
        assertEquals(listOf("19:00 Date night"), v.days[4].lines.map { "${it.time} ${it.text}" })
        // Barça and the boys' club both count as fixtures; the ground is shortened.
        assertEquals(WeekAheadKind.FIXTURE, v.days[5].lines.single().kind)
        assertEquals(WeekAheadKind.FIXTURE, v.days[6].lines.single().kind)
        assertEquals(
            "3 office days · 1 from home · 2 matches · Rex and Logan off Thu · date night Fri · 4 events · 2 due",
            v.summary,
        )
    }

    @Test
    fun theFamilySyncListsWhatNeedsThemBoth() {
        val v = view()
        assertEquals(
            listOf(
                "Thu 15 Oct · Rex and Logan are off and you're in the office — who's covering?",
                "Tue 13 Oct 18:00 · Parents' evening",
                "Fri 16 Oct · Date night — sitter and plans sorted?",
                "Sun 18 Oct 10:00 · BUFC U10 v Arlesey — who's taking them?",
            ),
            v.sync,
        )
        // Working from home on the INSET day: nobody needs to cover, and it isn't lit.
        val home = view(i = inputs(homeDays = setOf(mon + 3)))
        assertTrue(home.sync.none { it.contains("who's covering") })
        assertTrue(home.days[3].lines.first { it.kind == WeekAheadKind.SCHOOL }.lit.not())
        assertTrue(home.spoken.startsWith("Your week ahead. "))
        assertTrue(home.spoken.contains("To sort out with Jeanette: "))
    }

    @Test
    fun midweekItIsTheRestOfThisWeek() {
        val wed = mon + 2
        val v = view(nowMs = at(wed, 9))
        assertEquals(mon, v.weekStart)
        assertEquals("This week", v.title)
        assertEquals((wed..mon + 6).toList(), v.days.map { it.epochDay })
        // No heads-up and no Today line outside Sunday.
        assertTrue(WeekAheadRules.notices(v, at(wed, 18), cal).isEmpty())
        assertNull(WeekAheadRules.todayLine(v, at(wed, 19), cal))
        // An empty week says so.
        val empty = WeekAheadInputs(emptyList(), WorkHours(WorkSchedule.DEFAULT.copy(enabled = false)), HolidayCalendar.NONE)
        assertEquals(WeekAheadRules.QUIET, WeekAheadRules.view(empty, at(sun, 18), cal, ::window).summary)
        val workOnly = WeekAheadInputs(emptyList(), WorkHours(WorkSchedule.DEFAULT), HolidayCalendar.NONE)
        assertEquals("5 office days · otherwise quiet", WeekAheadRules.view(workOnly, at(sun, 18), cal, ::window).summary)
    }

    @Test
    fun sundayEveningsHeadsUpAndTodayLine() {
        val v = view(nowMs = at(sun, 9))
        val n = WeekAheadRules.notices(v, at(sun, 9), cal).single()
        assertEquals(NoticeSource.WEEK_AHEAD, n.source)
        assertEquals(NoticeTarget.WEEK_AHEAD, n.target)
        assertEquals(at(sun, 18, 5), n.atMs) // after the 18:00 evening digest
        assertEquals(at(mon, 12), n.expiresAtMs)
        assertEquals("Your week ahead", n.title)
        assertTrue(n.text.endsWith("4 things to sort out with Jeanette."))
        // Today's header carries it from 18:00 until midnight.
        assertNull(WeekAheadRules.todayLine(v, at(sun, 17, 59), cal))
        assertEquals("Your week ahead · ${v.summary}", WeekAheadRules.todayLine(v, at(sun, 18), cal)!!.text)
        // A bank holiday says so instead of the hours.
        val hol = HolidayCalendar(mapOf(mon to "Bank holiday"))
        assertEquals("Bank holiday · no work", WeekAheadRules.dayLine(WorkHours(WorkSchedule.DEFAULT, hol), hol, mon))
    }
}
