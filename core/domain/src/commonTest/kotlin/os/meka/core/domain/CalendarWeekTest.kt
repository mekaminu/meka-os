package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calendar → Week, Option A (approved 2026-11-10): the calm time grid. Invented titles only (rule 13). */
class CalendarWeekTest {
    private val cal = LocalCalendar.UTC
    private val dayMs = CivilDate.DAY_MS
    private val min = 60_000L
    /** Monday 9 November 2026. */
    private val mon = CivilDate.toEpochDay(2026, 11, 9)

    private fun ms(d: Long, minute: Int) = d * dayMs + minute * min
    private fun ev(id: String, title: String, d: Long, from: Int, to: Int, allDay: Boolean = false, provider: String = "google") =
        CalendarEvent(id, title, ms(d, from), ms(d, to), allDay, null, provider, null, null)

    private fun task(id: String, title: String) = Task(id, title, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun board(d: Long, vararg cards: BoardCard): WeekBoard {
        val days = (0 until 7).map { i ->
            val e = mon + i
            BoardDay(e, LocalClock.DAY_SHORT[i], "${9 + i}", e == mon, false, null, null, emptyList(), 0,
                if (e == d) cards.toList() else emptyList(), "", "")
        }
        return WeekBoard(mon, listOf(BoardWeek(mon, "This week", "9–15 Nov", days)))
    }

    @Test
    fun theOpenGridIsMondayToFridayWithTheMonthWeekAndBookedHours() {
        val events = listOf(
            ev("s", "Stand-up", mon, 9 * 60, 9 * 60 + 15),
            ev("c", "Booked call", mon + 1, 11 * 60, 12 * 60),
            ev("m", "Match", mon + 5, 10 * 60 + 40, 12 * 60, provider = "fixtures"),
        )
        val g = CalendarWeekRules.build(mon, 5, events, null, null, ms(mon, 19 * 60 + 20), cal)
        assertEquals("November 2026", g.title)
        assertEquals("Week 46", g.weekLabel)
        assertEquals(listOf("Mon 9", "Tue 10", "Wed 11", "Thu 12", "Fri 13"), g.days.map { "${it.weekday} ${it.number}" })
        // The whole week counts, Saturday's match too: 15 + 60 + 80 minutes = 2.6 h.
        assertEquals("2.6 h", g.booked)
        assertEquals("Booked this week 2.6 h", g.bookedLine)
        assertEquals("Week 46 · booked 2.6 h", g.foldedLine)
        assertTrue(g.days[0].isToday)
        assertEquals(19 * 60 + 20, g.days[0].nowMinute)
        assertNull(g.days[1].nowMinute)
    }

    @Test
    fun aShortEventIsOneLineAndBlocksSitOnFortyDpHoursFromEight() {
        val g = CalendarWeekRules.build(mon, 5, listOf(ev("s", "Stand-up", mon, 9 * 60, 9 * 60 + 15), ev("t", "Training", mon, 18 * 60, 19 * 60)), null, null, ms(mon, 0), cal)
        val (stand, training) = g.days[0].blocks
        assertTrue(stand.oneLine(CalendarWeekRules.HOUR_DP))
        assertFalse(training.oneLine(CalendarWeekRules.HOUR_DP))
        assertEquals("09:00–09:15", stand.time)
        assertEquals(40f, stand.topDp(CalendarWeekRules.HOUR_DP))
        assertEquals(18f, stand.heightDp(CalendarWeekRules.HOUR_DP))
        assertEquals(400f, training.topDp(CalendarWeekRules.HOUR_DP))
        assertEquals(38f, training.heightDp(CalendarWeekRules.HOUR_DP))
        assertEquals(520f, CalendarWeekRules.heightDp(CalendarWeekRules.HOUR_DP))
        assertEquals("08:00", CalendarWeekRules.hours.first())
        assertEquals("20:00", CalendarWeekRules.hours.last())
        assertEquals(0f, CalendarWeekRules.CORNER_DP)
    }

    @Test
    fun aPersonalEventInWorkHoursClashesAndTheWorkBandIsDrawnWithoutTheCommute() {
        val work = WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(mon))
        val g = CalendarWeekRules.build(
            mon, 5, listOf(ev("t", "Training", mon, 16 * 60 + 55, 17 * 60 + 55), ev("e", "Dinner", mon, 19 * 60, 20 * 60)),
            null, work, ms(mon, 0), cal,
        )
        val d = g.days[0]
        assertEquals(listOf(true, false), d.blocks.map { it.clash })
        assertEquals("Training, 16:55 to 17:55, clashes with work", d.blocks[0].spoken)
        assertEquals(9 * 60, d.workStartMinute)
        assertEquals(17 * 60 + 30, d.workEndMinute)
        // No commute blocks on the grid: only the two events.
        assertEquals(2, d.blocks.size)
        // A Saturday has no band.
        assertNull(CalendarWeekRules.build(mon + 5, 3, emptyList(), null, work, ms(mon, 0), cal).days[0].workStartMinute)
    }

    @Test
    fun overlappingEventsShareTheColumnSideBySide() {
        val g = CalendarWeekRules.build(mon, 5, listOf(ev("a", "A", mon, 10 * 60, 11 * 60), ev("b", "B", mon, 10 * 60 + 30, 11 * 60 + 30)), null, null, ms(mon, 0), cal)
        assertEquals(listOf(0 to 2, 1 to 2), g.days[0].blocks.map { it.column to it.columns })
    }

    @Test
    fun theFoldedStripsLoadBarGrowsWithTheDaysThingsUpToFourteenDp() {
        val events = listOf(
            ev("s", "Stand-up", mon, 9 * 60, 9 * 60 + 15),
            ev("h", "Half term", mon, 0, 24 * 60, allDay = true),
            ev("c", "Booked call", mon + 1, 11 * 60, 12 * 60),
        )
        val t = task("t1", "Cancel a subscription")
        val b = board(mon, BoardCard(t, t.title, null, null, false, false), BoardCard(t.copy(id = "t2"), "Call the garage", null, null, false, false))
        val g = CalendarWeekRules.build(mon, 7, events, b, null, ms(mon, 0), cal)
        assertEquals(listOf(4, 1, 0, 0, 0, 0, 0), g.days.map { it.load })
        assertEquals(listOf(14f, 5f, 0f, 0f, 0f, 0f, 0f), g.days.map { it.loadBarDp })
        assertEquals(10f, CalendarWeekRules.loadBarDp(2))
        assertEquals(0f, CalendarWeekRules.loadBarDp(-1))
    }

    @Test
    fun theStripHoldsAllDayEventsEarlyOrLateOnesAndOpenTasks() {
        val events = listOf(
            ev("h", "Half term", mon, 0, 24 * 60, allDay = true),
            ev("f", "Flight", mon, 6 * 60 + 30, 7 * 60 + 30),
            ev("x", "Late film", mon, 21 * 60 + 15, 23 * 60),
        )
        val t = task("t1", "Cancel a subscription")
        val b = board(mon, BoardCard(t, t.title, null, null, false, false), BoardCard(t.copy(id = "t2"), "Call the garage", "14:30", null, false, false))
        val d = CalendarWeekRules.build(mon, 5, events, b, null, ms(mon, 0), cal).days[0]
        assertTrue(d.blocks.isEmpty())
        assertEquals(listOf("Half term", "06:30 Flight", "21:15 Late film", "Cancel a subscription", "14:30 Call the garage"), d.chips.map { it.text })
        assertEquals(listOf(WeekChipKind.ALL_DAY, WeekChipKind.OUTSIDE, WeekChipKind.OUTSIDE, WeekChipKind.TASK, WeekChipKind.TASK), d.chips.map { it.kind })
        assertEquals("Monday 9 November, today, 3 events, 2 tasks", d.spoken)
        val shown = CalendarWeekRules.shownChips(d.chips)
        assertEquals(listOf("Half term", "06:30 Flight", "+3 more"), shown.map { it.text })
    }

    @Test
    fun fiveOrSevenDaysGoAWeekAtATimeAndFoldedThreeDaysAtATime() {
        val wed = mon + 2
        assertEquals(mon, CalendarWeekRules.start(wed, 5))
        assertEquals(mon, CalendarWeekRules.start(wed, 7))
        assertEquals(wed, CalendarWeekRules.start(wed, 3))
        assertEquals(mon + 7, CalendarWeekRules.step(mon, 5, 1))
        assertEquals(wed - 3, CalendarWeekRules.step(wed, 3, -1))
        assertEquals(5, CalendarWeekRules.openDays(null))
        assertEquals(7, CalendarWeekRules.openDays(7))
        assertEquals(5, CalendarWeekRules.openDays(4))
        assertEquals("7 days", CalendarWeekRules.daysLabel(7))
    }

    @Test
    fun weekIsCalendarsFirstView() {
        assertEquals(CalendarMode.WEEK, CalendarMode.of(null))
        assertEquals(CalendarMode.AGENDA, CalendarMode.of("agenda"))
        assertEquals(listOf("Week", "Board", "Agenda"), CalendarMode.entries.map { it.label })
        assertEquals("0 h", CalendarWeekRules.hoursLabel(0))
        assertEquals("12 h", CalendarWeekRules.hoursLabel(720))
    }

    private fun block(start: Int, end: Int) =
        WeekBlock("e:x", "Gym", "", start, end, 1, ev("x", "Gym", mon, start, end), clash = false)

    @Test
    fun aHeldEventSnapsToQuarterHoursAndWholeDays() {
        val b = block(10 * 60, 11 * 60)
        // 40 dp an hour, 100 dp columns: 25 dp down is 37.5 min, so 45 min (rounded to the quarter); 130 dp across is a day.
        val d = CalendarWeekRules.drop(b, 0, mon, 5, 130f, 25f, 100f, 40f, today = mon)!!
        assertEquals(1, d.days)
        assertEquals(45, d.minutes)
        assertEquals(mon + 1, d.day)
        assertEquals(10 * 60 + 45, d.startMinute)
        assertEquals("Move to Tomorrow · 10:45", d.hint)
        assertEquals(100f to 30f, CalendarWeekRules.dropOffsetDp(d, 100f, 40f))
    }

    @Test
    fun aHeldEventLetGoWhereItWasDoesNothing() {
        val b = block(10 * 60, 11 * 60)
        assertNull(CalendarWeekRules.drop(b, 2, mon, 5, 20f, 4f, 100f, 40f, today = mon))
        assertEquals(0f to 0f, CalendarWeekRules.dropOffsetDp(null, 100f, 40f))
    }

    @Test
    fun aHeldEventStaysOnTheDaysInViewAndOnTheGrid() {
        val b = block(19 * 60, 20 * 60)
        // Far right from Friday (index 4 of 5): stays Friday; far down: no later than 20:45.
        val d = CalendarWeekRules.drop(b, 4, mon, 5, 900f, 400f, 100f, 40f, today = mon)!!
        assertEquals(0, d.days)
        assertEquals(20 * 60 + 45, d.startMinute)
        // Far left from Monday: stays Monday; far up: no earlier than 08:00.
        val up = CalendarWeekRules.drop(block(9 * 60, 10 * 60), 0, mon, 5, -900f, -400f, 100f, 40f, today = mon)!!
        assertEquals(0, up.days)
        assertEquals(8 * 60, up.startMinute)
        assertEquals("Move to Today · 08:00", up.hint)
        // A later day is named.
        val thu = CalendarWeekRules.drop(b, 0, mon, 5, 300f, 0f, 100f, 40f, today = mon)!!
        assertEquals(mon + 3, thu.day)
        assertEquals("Move to " + CivilDate.shortLabel(mon + 3) + " · 19:00", thu.hint)
    }

    @Test
    fun aBlockTooShortForTwoLinesIsOneLineAtItsGridsHourHeight() {
        val g = CalendarWeekRules.build(mon, 5, listOf(
            ev("d", "Dentist", mon, 13 * 60 + 45, 14 * 60 + 15),
            ev("c", "Call", mon, 15 * 60, 15 * 60 + 45),
            ev("p", "Planning", mon, 10 * 60, 11 * 60),
        ), null, null, ms(mon, 0), cal)
        val (planning, dentist, call) = g.days[0].blocks.sortedBy { it.startMinute }
        // 30 min = 18 dp and 45 min = 28 dp: neither holds two lines; an hour (38 dp) does, open and folded.
        assertTrue(dentist.oneLine(CalendarWeekRules.HOUR_DP))
        assertTrue(call.oneLine(CalendarWeekRules.HOUR_DP))
        assertFalse(planning.oneLine(CalendarWeekRules.HOUR_DP))
        assertFalse(planning.oneLine(CalendarWeekRules.FOLDED_HOUR_DP))
        // The folded three days (~100 dp a column) keep the title whole and drop the time; open columns show both.
        assertFalse(CalendarWeekRules.oneLineShowsTime(100f))
        assertTrue(CalendarWeekRules.oneLineShowsTime(150f))
    }

    @Test
    fun hourLabelsSitOnTheirLinesAndFoldedOnesAreHoursOnly() {
        assertEquals("08", CalendarWeekRules.foldedHours.first())
        assertEquals("20", CalendarWeekRules.foldedHours.last())
        assertEquals(CalendarWeekRules.hours.size, CalendarWeekRules.foldedHours.size)
        assertEquals(0f, CalendarWeekRules.hourLabelTopDp(0, 40f))
        assertEquals(73f, CalendarWeekRules.hourLabelTopDp(2, 40f))
        assertEquals("all day", CalendarWeekRules.allDayLabel(folded = false))
        assertEquals("", CalendarWeekRules.allDayLabel(folded = true))
    }
}
