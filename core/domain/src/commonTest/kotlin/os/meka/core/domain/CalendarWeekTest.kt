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
        // The match's 1.3 h is off the five-day grid, so the line says where it is (A10, week 4).
        assertEquals("1.3 h at the weekend", g.outOfView)
        assertEquals("Booked this week 2.6 h · 1.3 h at the weekend", g.bookedLine)
        assertEquals("Week 46 · booked 2.6 h", g.foldedLine)
        assertTrue(g.days[0].isToday)
        assertEquals(19 * 60 + 20, g.days[0].nowMinute)
        assertNull(g.days[1].nowMinute)
    }

    @Test
    fun theBookedLineAddsUpToTheGridItShows() {
        val events = listOf(
            ev("s", "Stand-up", mon, 9 * 60, 10 * 60),
            ev("m", "Match", mon + 5, 10 * 60, 11 * 60),
        )
        // Seven days show the weekend, so nothing is out of view.
        val seven = CalendarWeekRules.build(mon, 7, events, null, null, ms(mon, 0), cal)
        assertEquals("", seven.outOfView)
        assertEquals("Booked this week 2 h", seven.bookedLine)
        // Nothing booked at the weekend: the five-day line is the plain one.
        val weekdays = CalendarWeekRules.build(mon, 5, events.take(1), null, null, ms(mon, 0), cal)
        assertEquals("Booked this week 1 h", weekdays.bookedLine)
        // Folded three days from Wednesday: Monday's hour and Saturday's are on other days.
        val folded = CalendarWeekRules.build(mon + 2, 3, events, null, null, ms(mon, 0), cal)
        assertEquals("2 h on other days", folded.outOfView)
        assertEquals("", CalendarWeekRules.outOfViewLabel(0, true))
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
    fun aClashOverIsNoLongerMarkedAsTheAgendaAndTheKeyNamesTheAmber() {
        // Walk-through 2026-11-15, A18 (g4a 11): at 17:15 the Agenda marks only the call still to come, so Week does too.
        val work = WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(mon))
        val events = listOf(
            ev("p", "Planning", mon, 10 * 60, 11 * 60 + 30),
            ev("d", "Dentist", mon, 13 * 60 + 45, 14 * 60 + 15),
            ev("c", "Call", mon, 17 * 60, 17 * 60 + 45),
            ev("t", "Training", mon + 1, 16 * 60 + 55, 17 * 60 + 55),
        )
        val now = ms(mon, 17 * 60 + 15)
        val g = CalendarWeekRules.build(mon, 5, events, null, work, now, cal)
        assertEquals(listOf(false, false, true), g.days[0].blocks.map { it.clash })
        assertEquals(listOf(true), g.days[1].blocks.map { it.clash })
        assertEquals("Clashes with work", g.clashKey)
        // The same rule as the Agenda's rows.
        g.days[0].blocks.forEach { b ->
            val agenda = WorkClashRules.markWhileAhead(WorkClashRules.clash(b.event, work.blocks(mon, cal), cal), b.event.endAtMs, now)
            assertEquals(agenda != null, b.clash, b.title)
        }
        // Nothing in view clashes (still to come): no amber in the key.
        assertEquals("", CalendarWeekRules.build(mon, 5, events.take(2), null, work, now, cal).clashKey)
        assertEquals("", CalendarWeekRules.build(mon, 5, events, null, null, now, cal).clashKey)
    }

    @Test
    fun aJourneyClashIsNamedForTheJourneyNotWork() {
        // Walk-through 2026-11-22, A3 (g4a 3): the 18:30 lesson after work ends at 17:30 clashes with the journey home
        // (the Agenda says "Before you're home"), so the key and the block don't call it a clash with work.
        val work = WorkHours(WorkSchedule.DEFAULT)
        val home = CommuteRules.bands(work, mon, cal).first { !it.toWork }
        assertTrue(home.startMinute >= 17 * 60 + 30)
        val lesson = ev("s", "Swimming lesson", mon, home.startMinute + 10, home.endMinute + 20)
        val now = ms(mon, 17 * 60 + 15)
        val journey = CalendarWeekRules.build(mon, 5, listOf(lesson), null, work, now, cal)
        val b = journey.days[0].blocks.single()
        assertTrue(b.clash)
        assertEquals(ClashWith.HOME, b.clashWith)
        assertTrue(b.spoken.endsWith(", before you're home"), b.spoken)
        assertEquals("Clashes with the commute", journey.clashKey)
        // With a call still to come inside work too, the key names both.
        val both = CalendarWeekRules.build(mon, 5, listOf(lesson, ev("c", "Call", mon, 17 * 60, 17 * 60 + 25)), null, work, now, cal)
        assertEquals(listOf(ClashWith.WORK, ClashWith.HOME), both.days[0].blocks.map { it.clashWith })
        assertEquals("Clashes with work or the commute", both.clashKey)
        // The Agenda's own mark for the lesson is the journey's.
        assertEquals(ClashWith.HOME, WorkClashRules.clash(lesson, work.blocks(mon, cal), cal, commute = CommuteRules.bands(work, mon, cal))?.against)
    }

    @Test
    fun theNowLinePassesBehindWhatIsRunningAndTheDotSaysWhat() {
        // Walk-through 2026-11-15, A18 (g4a 13): the gold line ran through "Call with Sam"'s title. It is drawn under the
        // blocks now, so the dot names what it passes behind.
        val events = listOf(
            ev("p", "Planning", mon, 10 * 60, 11 * 60),
            ev("c", "Call with Sam", mon, 17 * 60, 17 * 60 + 45),
        )
        val walk = task("w", "Walk the dog").copy(scheduledAtMs = ms(mon, 17 * 60 + 10))
        val b = board(mon, BoardCard(walk, walk.title, "17:10", null, false, false))
        val d = CalendarWeekRules.build(mon, 5, events, b, null, ms(mon, 17 * 60 + 15), cal).days[0]
        assertEquals(listOf("Call with Sam", "Walk the dog"), d.nowDuring)
        assertEquals("Now, 17:15, during Call with Sam and Walk the dog", d.nowSpoken)
        // Between things: just the time. An event that ends at now is over.
        val quiet = CalendarWeekRules.build(mon, 5, events, null, null, ms(mon, 11 * 60), cal).days[0]
        assertEquals(emptyList(), quiet.nowDuring)
        assertEquals("Now, 11:00", quiet.nowSpoken)
        // Not today: no line, nothing to say.
        assertNull(CalendarWeekRules.build(mon, 5, events, null, null, ms(mon, 11 * 60), cal).days[1].nowSpoken)
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
    fun aTaskWithATimeInsideTheGridSitsOnTheGridNotInTheStrip() {
        // Walk-through 2026-11-15, A18: "12:00 Pay the window cleaner" sat in Thursday's all-day strip while the Agenda
        // listed it at 12:00.
        val thu = mon + 3
        val events = listOf(ev("p", "Planning", thu, 12 * 60 + 15, 13 * 60))
        val noon = task("t1", "Pay the window cleaner").copy(scheduledAtMs = ms(thu, 12 * 60))
        val early = task("t2", "Feed the cat").copy(scheduledAtMs = ms(thu, 7 * 60))
        val long = task("t3", "Sort the loft").copy(scheduledAtMs = ms(thu, 15 * 60), estimateMinutes = 90)
        val loose = task("t4", "Call the garage")
        val b = board(thu, BoardCard(noon, noon.title, "12:00", null, false, false), BoardCard(early, early.title, "07:00", null, false, false),
            BoardCard(long, long.title, "15:00", null, false, false), BoardCard(loose, loose.title, null, null, false, false))
        val d = CalendarWeekRules.build(mon, 5, events, b, null, ms(mon, 0), cal).days[3]
        assertEquals(listOf("Pay the window cleaner", "Sort the loft"), d.taskBlocks.map { it.title })
        assertEquals(listOf("12:00–12:30", "15:00–16:30"), d.taskBlocks.map { it.time })
        assertEquals("t1", d.taskBlocks[0].taskId)
        assertEquals("Task Pay the window cleaner, 12:00 to 12:30", d.taskBlocks[0].spoken)
        // It shares the width with the event it overlaps.
        assertEquals(2, d.taskBlocks[0].columns)
        assertEquals(2, d.blocks.single().columns)
        assertEquals(1, d.taskBlocks[1].columns)
        // Before 08:00 and with no time it stays a chip.
        assertEquals(listOf("07:00 Feed the cat", "Call the garage"), d.chips.map { it.text })
        assertEquals("Thursday 12 November, 1 event, 4 tasks", d.spoken)
        assertEquals(5, d.load)
        assertEquals(d.taskBlocks[0].topDp(40f), 160f)
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
