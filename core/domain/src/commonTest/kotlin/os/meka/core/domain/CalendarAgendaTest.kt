package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarAgendaTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // London in October is BST (+1 h); the clocks go back on Sun 25 Oct 2026, so use a fixed +1 h calendar and keep
    // the dates before then, except where the test is about the change.
    private val cal = LocalCalendar.fixedOffset(hour)
    private val tue6 = CivilDate.toEpochDay(2026, 10, 6)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false, location: String? = null, provider: String = "google") =
        CalendarEvent(id, id, from, to, allDay, location, provider, null, null)

    /** An all-day event on [first]..[last] (UTC midnights, as the server stores them). */
    private fun allDay(id: String, first: Long, last: Long = first, provider: String = "google") =
        ev(id, first * CivilDate.DAY_MS, (last + 1) * CivilDate.DAY_MS, allDay = true, provider = provider)

    private fun task(id: String, scheduled: Long? = null, estimate: Int? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE) =
        Task(id, id, null, lifecycle, null, scheduled, estimate, 0, null, null, 0, null, false)

    private fun build(tasks: List<Task> = emptyList(), events: List<CalendarEvent> = emptyList(), now: Long = at(tue6, 10)) =
        CalendarAgenda.build(tasks, events, now, cal)

    @Test
    fun todayAndTomorrowAlwaysShowAndEmptyDaysFoldIntoStretches() {
        val v = build(events = listOf(ev("dentist", at(tue6 + 3, 9), at(tue6 + 3, 9, 30))))
        assertEquals("Tuesday 6 October", v.todayLabel)
        val first = v.sections.take(4)
        assertEquals(listOf("Today", "Tomorrow", "Thu 8 Oct", "Fri 9 Oct"), first.map { it.title })
        assertEquals(listOf(AgendaKind.DAY, AgendaKind.DAY, AgendaKind.FREE, AgendaKind.DAY), first.map { it.kind })
        assertEquals("Tuesday 6 October", first[0].subtitle)
        assertEquals("Wednesday 7 October", first[1].subtitle)
        assertEquals("Nothing planned", first[0].emptyLine)
        assertEquals("Nothing planned", first[2].subtitle)
        assertEquals("1 event", first[3].subtitle)
        assertNull(first[3].emptyLine)
        // The rest of the window (Sat 10 Oct – Wed 4 Nov) is one free stretch.
        val rest = v.sections.drop(4).single()
        assertEquals(AgendaKind.FREE, rest.kind)
        assertEquals("Sat 10 Oct – Wed 4 Nov", rest.title)
        assertEquals(tue6 + 29, rest.lastDay)
        // Every day of the window belongs to exactly one section.
        for (d in tue6 until tue6 + 30) assertEquals(1, v.sections.count { d in it.firstDay..it.lastDay }, "day $d")
    }

    @Test
    fun freeStretchesInOneMonthNameTheMonthOnce() {
        assertEquals("Thu 8 – Sat 10 Oct", CalendarAgenda.spanLabel(tue6 + 2, tue6 + 4))
        assertEquals("Thu 8 Oct", CalendarAgenda.spanLabel(tue6 + 2, tue6 + 2))
        assertEquals("5–11 Oct", CalendarAgenda.rangeLabel(tue6 - 1, tue6 + 5))
        assertEquals("28 Sep – 4 Oct", CalendarAgenda.rangeLabel(CivilDate.toEpochDay(2026, 9, 28), CivilDate.toEpochDay(2026, 10, 4)))
    }

    @Test
    fun aDayListsEventsAndPlannedTasksInTimeOrderWithAllDayChips() {
        val thu = tue6 + 2
        val v = build(
            tasks = listOf(task("write", scheduled = at(thu, 14), estimate = 45), task("loose"), task("idea", at(thu, 9), lifecycle = Lifecycle.SOMEDAY)),
            events = listOf(
                ev("lunch", at(thu, 12), at(thu, 13), location = "Canteen"),
                ev("standup", at(thu, 9), at(thu, 9, 15), provider = "microsoft"),
                ev("same-minute", at(thu, 14), at(thu, 14, 30)),
                allDay("holiday", thu),
            ),
        )
        val s = v.sections.first { it.firstDay == thu }
        assertEquals(AgendaKind.DAY, s.kind)
        assertEquals("Thu 8 Oct", s.title)
        assertEquals("4 events · 1 task", s.subtitle)
        assertEquals(listOf("holiday"), s.allDay.map { it.id })
        // Events come before a task at the same minute; Someday and unscheduled tasks never show.
        assertEquals(listOf("e-standup", "e-lunch", "e-same-minute", "t-write"), s.rows.map { it.id })
        assertEquals("09:00–09:15", s.rows[0].time)
        assertEquals("Outlook", s.rows[0].detail)
        assertEquals("Canteen", s.rows[1].detail)
        assertEquals("14:00", s.rows[3].time)
        assertEquals("45 min", s.rows[3].detail)
        assertTrue(s.rows.none { it.kind == TimelineKind.NOW })
    }

    @Test
    fun todayListsWhatEndedThenWhatStartedThenNowThenWhatsAhead() {
        val v = build(
            tasks = listOf(task("late", scheduled = at(tue6, 9, 30)), task("write", scheduled = at(tue6, 15))),
            events = listOf(ev("standup", at(tue6, 9), at(tue6, 9, 15)), ev("workshop", at(tue6, 9, 45), at(tue6, 11)), ev("call", at(tue6, 16), at(tue6, 16, 30))),
            now = at(tue6, 10),
        )
        val today = v.sections.first()
        assertEquals(listOf("e-standup"), today.ended.map { it.id })
        // A planned task whose time has passed stays (it still needs doing); the running workshop is marked running.
        assertEquals(listOf("t-late", "e-workshop", "now", "t-write", "e-call"), today.rows.map { it.id })
        assertTrue(today.rows.first { it.id == "e-workshop" }.running)
        assertEquals("10:00", today.rows.first { it.kind == TimelineKind.NOW }.time)
        assertNull(today.emptyLine)
    }

    @Test
    fun anEmptyTodayHasNoNowLine() {
        val today = build().sections.first()
        assertTrue(today.rows.isEmpty())
        assertEquals("Nothing planned", today.emptyLine)
    }

    @Test
    fun eventsOverMidnightShowOnEveryDayTheyTouch() {
        val fri = tue6 + 3
        val v = build(events = listOf(ev("party", at(fri, 22), at(fri + 1, 1)), ev("festival", at(fri + 3, 18), at(fri + 5, 2))))
        fun row(day: Long) = v.sections.first { it.firstDay == day && it.kind == AgendaKind.DAY }.rows.single()
        assertEquals("From 22:00", row(fri).time)
        assertEquals("Until 01:00", row(fri + 1).time)
        assertEquals("From 18:00", row(fri + 3).time)
        assertEquals("All day", row(fri + 4).time)
        assertEquals("Until 02:00", row(fri + 5).time)
    }

    @Test
    fun fixturesAreMarkedAndCounted() {
        val sat = tue6 + 4
        val v = build(events = listOf(ev("Barça v Sevilla", at(sat, 20), at(sat, 22), location = "Camp Nou", provider = "fixtures"), ev("dinner", at(sat, 18), at(sat, 19))))
        val s = v.sections.first { it.firstDay == sat }
        assertEquals("2 events · 1 fixture", s.subtitle)
        assertTrue(s.rows.first { it.title == "Barça v Sevilla" }.event!!.isFixture)
        assertEquals("Camp Nou · Fixture", s.rows.first { it.title == "Barça v Sevilla" }.detail)
        assertEquals("2 events in the next 30 days · 1 fixture", v.summary)
    }

    @Test
    fun weekStripsRunMondayToSundayWithDotsAndJumps() {
        val thu = tue6 + 2
        val v = build(
            tasks = listOf(task("a", at(thu, 9)), task("b", at(thu, 10))),
            events = listOf(ev("x", at(thu, 11), at(thu, 12)), ev("y", at(thu, 13), at(thu, 14)), allDay("trip", tue6 + 7)),
        )
        // Mon 5 Oct to Sun 8 Nov holds the 30 days from Tue 6 Oct to Wed 4 Nov: 5 weeks.
        assertEquals(5, v.weeks.size)
        assertEquals(listOf("This week", "Next week", "19–25 Oct", "26 Oct – 1 Nov", "2–8 Nov"), v.weeks.map { it.title })
        assertEquals("5–11 Oct", v.weeks[0].range)
        val week = v.weeks[0].days
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), week.map { it.letter })
        assertEquals(listOf("5", "6", "7", "8", "9", "10", "11"), week.map { it.number })
        // Monday is before today: dimmed, no jump. Thursday has 4 things: capped at 3 dots.
        assertEquals(false, week[0].inRange)
        assertNull(week[0].sectionId)
        assertTrue(week[1].isToday)
        assertEquals("d-$thu", week[3].sectionId)
        assertEquals(3, week[3].dots)
        assertEquals("Thursday 8 October, 2 events · 2 tasks", week[3].accessibilityLabel)
        // Fri 9 – Mon 12 is one free stretch; each of its pills jumps to it.
        assertEquals("f-${tue6 + 3}", week[4].sectionId)
        assertEquals("f-${tue6 + 3}", week[6].sectionId)
        assertEquals("Saturday 10 October, nothing planned", week[5].accessibilityLabel)
        assertEquals("d-${tue6 + 7}", v.weeks[1].days[1].sectionId)
        assertEquals(1, v.weeks[1].days[1].dots)
        // Thu 5 Nov onwards is past the window.
        assertEquals(false, v.weeks[4].days[3].inRange)
        assertEquals(1, v.weekIndexOf(tue6 + 7))
        assertEquals(v.sections.indexOfFirst { it.firstDay == thu }, v.sectionIndexOf(thu))
        assertEquals(-1, v.sectionIndexOf(tue6 - 1))
    }

    @Test
    fun theClockChangeKeepsWallClockTimes() {
        // Real London rules aren't available in the kernel, so model the change: +1 h until Sun 25 Oct, then 0.
        val change = CivilDate.toEpochDay(2026, 10, 25)
        val london = object : LocalCalendar {
            fun off(ms: Long) = if (ms < change * CivilDate.DAY_MS + hour) hour else 0L
            override fun epochDayOf(epochMs: Long) = (epochMs + off(epochMs)).floorDiv(CivilDate.DAY_MS)
            override fun minuteOfDay(epochMs: Long) = ((epochMs + off(epochMs)).mod(CivilDate.DAY_MS) / 60_000L).toInt()
            override fun toEpochMs(epochDay: Long, minuteOfDay: Int): Long {
                val guess = epochDay * CivilDate.DAY_MS + minuteOfDay * 60_000L
                return guess - off(guess - hour)
            }
        }
        val mon26 = change + 1
        val e = ev("gym", london.toEpochMs(mon26, 7 * 60), london.toEpochMs(mon26, 8 * 60))
        val v = CalendarAgenda.build(emptyList(), listOf(e), london.toEpochMs(tue6, 10 * 60), london)
        val s = v.sections.first { it.firstDay == mon26 && it.kind == AgendaKind.DAY }
        assertEquals("07:00–08:00", s.rows.single().time)
        assertEquals("Mon 26 Oct", s.title)
    }

    @Test
    fun countsLineAndEmptyCalendarSummary() {
        assertNull(CalendarAgenda.countsLine(0, 0, 0))
        assertEquals("1 event · 1 fixture · 3 tasks", CalendarAgenda.countsLine(1, 1, 3))
        assertEquals("Nothing in your calendars for the next 30 days", build().summary)
    }
}
