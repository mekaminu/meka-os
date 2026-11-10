package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The folded Week board as Day lists (approved 2026-11-08, `BoardFolded.dc.html`): the strip, header and week steps. */
class DayListBoardTest {
    private fun task(id: String, est: Int?, done: Boolean = false) = Task(
        id = id, title = id, notes = null, lifecycle = if (done) Lifecycle.DONE else Lifecycle.ACTIVE, dueAtMs = null,
        scheduledAtMs = null, estimateMinutes = est, priority = 0, goalId = null, somedayKind = null, createdAtMs = 0L,
        completedAtMs = if (done) 1L else null, hasConflict = false, area = null,
    )

    private fun card(t: Task) = BoardCard(t, t.title, null, null, t.isDone, false)

    private val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val mon = CivilDate.toEpochDay(2026, 11, 9)

    private fun day(e: Long, today: Long, cards: List<BoardCard> = emptyList()) = BoardDay(
        epochDay = e, weekday = names[CivilDate.isoDayOfWeek(e) - 1], number = CivilDate.fromEpochDay(e).day.toString(),
        isToday = e == today, isPast = e < today, place = null, placeLabel = null, chips = emptyList(), moreChips = 0,
        cards = cards, countLine = "", accessibilityLabel = "",
    )

    private fun week(start: Long, today: Long, filled: Map<Long, List<BoardCard>> = emptyMap()) =
        BoardWeek(start, "", "", (0 until 7).map { day(start + it, today, filled[start + it].orEmpty()) })

    private val today = mon + 1 // Tue 10 Nov
    private val board = WeekBoard(today, listOf(
        week(mon, today, mapOf(
            mon to listOf(card(task("a", 30)), card(task("b", 15, done = true))),
            today to listOf(card(task("c", null)), card(task("d", 60))),
        )),
        week(mon + 7, today),
    ))

    @Test
    fun theStripShowsEachDaysPlannedLengthDoneIncluded() {
        val strip = DayListBoardRules.strip(board.weeks[0])
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), strip.map { it.letter })
        assertEquals(listOf("9", "10", "11"), strip.take(3).map { it.number })
        assertEquals(listOf("45m", "1h 30m", "·"), strip.take(3).map { it.length })
        assertEquals("Tuesday 10 November, today, 1h 30m planned", strip[1].spoken)
        assertEquals("Wednesday 11 November, nothing planned", strip[2].spoken)
        // The day's total on its Day list says the same as its pill.
        assertEquals(strip[1].length, DayListRules.view(board.weeks[0].days[1], 0, true).total)
    }

    @Test
    fun theHeaderNamesTheMonthTheWeekAndItsTotal() {
        val h = DayListBoardRules.head(board.weeks[0])
        assertEquals("November", h.month)
        assertEquals("Week 46 · 2h 15m planned", h.weekLine)
        assertEquals("Week 47 · nothing planned", DayListBoardRules.head(board.weeks[1]).weekLine)
    }

    @Test
    fun theDayLineAndThePrompt() {
        val d = board.weeks[0].days
        assertEquals("Tue 10 November · today", DayListBoardRules.dayTitle(d[1]))
        assertEquals("Wed 11 November", DayListBoardRules.dayTitle(d[2]))
        assertEquals("Add to Wed · Enter for the next", DayListBoardRules.placeholder(d[2]))
        assertEquals("Add to today · Enter for the next", DayListBoardRules.placeholder(d[0]))
    }

    @Test
    fun itOpensOnTodayAndKeepsTheChosenDay() {
        assertEquals(today, DayListBoardRules.shownDay(board, null))
        assertEquals(mon + 4, DayListBoardRules.shownDay(board, mon + 4))
        assertEquals(today, DayListBoardRules.shownDay(board, mon + 40))
        assertNull(DayListBoardRules.shownDay(WeekBoard(today, emptyList()), null))
    }

    @Test
    fun theArrowsStepAWeekAndBackLandsOnToday() {
        assertEquals(mon + 9, DayListBoardRules.stepWeek(board, mon + 2, 1))
        assertEquals(today, DayListBoardRules.stepWeek(board, mon + 9, -1))
        assertNull(DayListBoardRules.stepWeek(board, mon + 9, 1))
        assertNull(DayListBoardRules.stepWeek(board, today, -1))
    }
}
