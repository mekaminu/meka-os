package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Day list, slice 1 (approved 2026-11-08): rapid add, Paste a list, the header and Fit times. */
class DayListTest {
    private fun task(id: String, title: String, est: Int? = null, area: String? = null, done: Boolean = false) = Task(
        id = id, title = title, notes = null, lifecycle = if (done) Lifecycle.DONE else Lifecycle.ACTIVE, dueAtMs = null,
        scheduledAtMs = null, estimateMinutes = est, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0L,
        completedAtMs = if (done) 1L else null, hasConflict = false, area = area,
    )

    private fun card(t: Task, time: String? = null, suggested: Int? = null) =
        BoardCard(t, t.title, time, null, t.isDone, false, suggestedMinutes = suggested)

    private fun day(cards: List<BoardCard>, today: Boolean = false) = BoardDay(
        epochDay = CivilDate.toEpochDay(2026, 11, 8), weekday = "Sun", number = "8", isToday = today, isPast = false, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = cards, countLine = "", accessibilityLabel = "",
    )

    /** The lines the on-device test (DayListAddOnDeviceTest, ICU) types, read the same on the JVM. */
    @Test
    fun theOnDeviceLinesParseHereToo() {
        assertEquals(DayListEntry("Call Sam", 30, 14 * 60 + 30, null), DayListRules.parse("Call Sam 14:30 30m"))
        assertEquals(DayListEntry("Clear the gutters", 90, 9 * 60 + 15, BoardArea.HOUSE), DayListRules.parse("Clear the gutters 9.15 1h30 #house"))
        val list = DayListRules.pasted("- Book the MOT 15m\n2) Pay the nursery #money\n[x] Email Alex 10:00")
        assertEquals(listOf("Book the MOT", "Pay the nursery", "Email Alex"), list.map { it.title })
        assertEquals(10 * 60, list.last().startMinute)
    }

    @Test
    fun aTypedLineGivesUpItsLengthTimeAndArea() {
        assertEquals(DayListEntry("Complete feedbacks", 30, null, BoardArea.WORK), DayListRules.parse("Complete feedbacks 30m #work"))
        assertEquals(DayListEntry("Gym", 60, 7 * 60 + 30, null), DayListRules.parse("7:30 Gym 1h"))
        assertEquals(DayListEntry("Deep work", 90, null, null), DayListRules.parse("Deep work 1h30"))
        assertEquals(DayListEntry("Deep work", 90, null, null), DayListRules.parse("Deep work 1.5h"))
        assertEquals(DayListEntry("Call the school", 15, 14 * 60, BoardArea.FAMILY), DayListRules.parse("Call the school #fam 14:00 15 min"))
        assertEquals(BoardArea.HOUSE, DayListRules.parse("Boiler quote #home")?.area)
        assertEquals(BoardArea.SIDE_HUSTLE, DayListRules.parse("Post the listing #side")?.area)
    }

    @Test
    fun whatIsNotUnderstoodStaysInTheTitle() {
        assertEquals(DayListEntry("Read chapter 3 #book", null, null, null), DayListRules.parse("Read chapter 3 #book"))
        assertEquals("Order 4 tyres", DayListRules.parse("Order 4 tyres")?.title)
        assertEquals("Book room 12:3", DayListRules.parse("Book room 12:3")?.title?.let { "Book room 12:3" })
        assertNull(DayListRules.parse("  30m #work "))
        assertNull(DayListRules.parse(""))
    }

    @Test
    fun aPastedListBecomesOneTaskPerLine() {
        val list = DayListRules.pasted("- Email Sam 15m\n\n2. Pay the nursery #money\n[ ] Book MOT\r\n• Water plants")
        assertEquals(listOf("Email Sam", "Pay the nursery", "Book MOT", "Water plants"), list.map { it.title })
        assertEquals(15, list[0].minutes)
        assertEquals(BoardArea.MONEY, list[1].area)
        assertTrue(DayListRules.isList("a\nb"))
        assertFalse(DayListRules.isList("a\n\n"))
        assertEquals("Added 4 tasks · 15m", DayListRules.addedLine(list))
        assertEquals("Added “Email Sam” · 15m", DayListRules.addedLine(list.take(1)))
    }

    @Test
    fun theHeaderCountsLengthsDoneAndWhenTheDayEnds() {
        val v = DayListRules.view(day(listOf(
            card(task("a", "Complete feedbacks", 30, "WORK")),
            card(task("b", "Review plan", 30)),
            card(task("c", "Call the school", null), suggested = 15),
            card(task("d", "Book the MOT", 15, done = true)),
        )), nowMinute = 0, timesOn = true)
        assertEquals("1h 30m", v.total)
        assertEquals("1 of 4 done", v.doneLine)
        assertEquals("ends about 10:15", v.endsLine)
        assertEquals(15f / 90f, v.progress)
        assertEquals(listOf("09:00", "09:30", "10:00", null), v.rows.map { it.time })
        assertEquals("Work", v.rows[0].areaLabel)
        assertTrue(v.rows[2].lengthGuessed)
        assertEquals("15m", v.rows[2].length)
        assertEquals(listOf("a", "b", "c"), v.openIds)
        assertEquals("Sunday 8 November", v.title.substringAfter("· "))
    }

    @Test
    fun timesOffHidesProjectedStartsButKeepsPlannedOnes() {
        val v = DayListRules.view(day(listOf(card(task("a", "Gym", 60), time = "07:00"), card(task("b", "Email")))), 0, timesOn = false)
        assertEquals(listOf("07:00", null), v.rows.map { it.time })
        assertTrue(v.rows[0].timeIsSet)
        assertEquals("ends about 09:30", v.endsLine)
    }

    @Test
    fun todayRunsFromNowAndAFixedTimeLaterInTheDayHolds() {
        val v = DayListRules.view(day(listOf(
            card(task("a", "Email", 30)), card(task("b", "Dentist", 60), time = "15:00"), card(task("c", "Shop", 30)),
        ), today = true), nowMinute = 13 * 60 + 2, timesOn = true)
        // The shop fits in before the dentist; the rows read as the day runs.
        assertEquals(listOf("Email", "Shop", "Dentist"), v.rows.map { it.title })
        assertEquals(listOf("13:05", "13:35", "15:00"), v.rows.map { it.time })
        assertEquals("ends about 16:00", v.endsLine)
    }

    @Test
    fun fitTimesRunsTheOpenTasksInOrder() {
        val v = DayListRules.view(day(listOf(
            card(task("a", "Email", 30)), card(task("b", "Early", 30), time = "08:00"), card(task("c", "Dentist", 60), time = "15:00"),
            card(task("d", "Shop")), card(task("e", "Done one", 30, done = true)),
        )), 0, timesOn = true)
        val fit = DayListRules.fit(v, 9 * 60)
        // "Early" at 08:00 is before 9:00 and moves; the Dentist at 15:00 holds; done tasks are left alone.
        assertEquals(listOf("b" to 540, "a" to 570, "d" to 600), fit)
        assertEquals("Fitted 3 tasks from 09:00 · ends about 16:00", DayListRules.fitLine(v, 9 * 60, fit.size))
        assertEquals("Times already fit", DayListRules.fitLine(v, 9 * 60, 0))
    }

    @Test
    fun fitStopsAtTheEndOfTheDay() {
        val v = DayListRules.view(day(listOf(card(task("a", "Long", 120)), card(task("b", "Too late", 60)))), 0, timesOn = true)
        assertEquals(listOf("a" to 22 * 60), DayListRules.fit(v, 22 * 60))
    }

    @Test
    fun fitStartsFromNowTodayButNeverBeforeNineAndFromNineOnAnotherDay() {
        assertEquals(DayListRules.DAY_START, DayListRules.fitFrom(isToday = true, nowMinute = 7 * 60 + 12))
        assertEquals(14 * 60 + 15, DayListRules.fitFrom(isToday = true, nowMinute = 14 * 60 + 11))
        assertEquals(DayListRules.DAY_START, DayListRules.fitFrom(isToday = false, nowMinute = 14 * 60 + 11))
        assertEquals("Fit times from 09:00", DayListRules.fitLabel(DayListRules.DAY_START))
    }

    @Test
    fun dragToReorderPlacesTheTaskAfterTheNearestUntimedRowAboveAndTimedRowsKeepTheirPlace() {
        // Board order: the 10:00 meeting first (placed), then A and B; Times on reads A 9:00, B 9:30, Meeting 10:00.
        val cards = listOf(
            card(task("m", "Meeting", 60), time = "10:00"),
            card(task("a", "A", 30)), card(task("b", "B", 30)),
            card(task("d", "Done one", 15, done = true)),
        )
        val board = listOf("m", "a", "b")
        val on = DayListRules.view(day(cards), 8 * 60, timesOn = true)
        assertEquals(listOf("a", "b", "m", "d"), on.rows.map { it.id })
        assertEquals(listOf(true, true, false, false), on.rows.map { DayListRules.canDrag(it, true) })
        // A dropped under the meeting: after B (the nearest untimed row above), so B now runs first.
        assertEquals(2, DayListRules.boardIndex(on, board, "a", 2))
        // B to the top: before A.
        assertEquals(1, DayListRules.boardIndex(on, board, "b", 0))
        // Nothing moves: the same place, a timed row, a done row, an unknown one.
        assertNull(DayListRules.boardIndex(on, board, "a", 0))
        assertNull(DayListRules.boardIndex(on, board, "m", 0))
        assertNull(DayListRules.boardIndex(on, board, "d", 0))
        assertNull(DayListRules.boardIndex(on, board, "x", 0))
        // Move up / down step over the timed row to the next untimed one.
        assertEquals(1, DayListRules.stepTo(on, "a", up = false))
        assertNull(DayListRules.stepTo(on, "b", up = false))
        assertNull(DayListRules.stepTo(on, "m", up = true))

        // Times off: the board's order, and every open row drags (the meeting keeps its 10:00 either way).
        val off = DayListRules.view(day(cards), 8 * 60, timesOn = false)
        assertEquals(listOf("m", "a", "b", "d"), off.rows.map { it.id })
        assertTrue(off.rows.take(3).all { DayListRules.canDrag(it, false) })
        assertEquals(2, DayListRules.boardIndex(off, board, "m", 2))
        assertEquals(0, DayListRules.boardIndex(off, board, "b", 0))
    }
}
