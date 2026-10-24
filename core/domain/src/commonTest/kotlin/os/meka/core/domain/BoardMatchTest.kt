package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board made to match Option B exactly, slice 1: the columns (no folded days, headers, cards, events, "+ add"). */
class BoardMatchTest {
    private val mon26 = CivilDate.toEpochDay(2026, 10, 26)

    private fun task(id: String, estimate: Int? = null) =
        Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false).copy(estimateMinutes = estimate)

    private fun card(id: String, time: String? = null, estimate: Int? = null) = BoardCard(task(id, estimate), "Title $id", time, null, false, false)

    private fun event(id: String, startMin: Int, endMin: Int, allDay: Boolean = false, calendar: String? = "Work") =
        CalendarEvent(id, "Event $id", startMin * 60_000L, endMin * 60_000L, allDay, null, "google", null, calendar)

    private fun day(epoch: Long, chips: List<BoardChip> = emptyList(), past: Boolean = false) = BoardDay(
        epochDay = epoch, weekday = "Mon", number = "26", isToday = false, isPast = past, place = null, placeLabel = null,
        chips = chips, moreChips = 0, cards = emptyList(), countLine = "", accessibilityLabel = "Monday 26 October",
    )

    private fun week(start: Long) = BoardWeek(start, "This week", "", (0L..6L).map { day(start + it) })

    @Test
    fun theWeeksHeaderIsTheMonthAndItsIsoWeek() {
        val h = BoardMatchRules.weekHead(mon26)
        assertEquals("October 2026", h.month)
        assertEquals("W44", h.week)
        assertEquals("October 2026, week 44", h.spoken)
        assertEquals("Oct 2026", h.monthShort)
        // A week across two months takes its Thursday's: Mon 28 Sep – Sun 4 Oct 2026 is October's (Thu 1 Oct).
        assertEquals("October 2026", BoardMatchRules.weekHead(CivilDate.toEpochDay(2026, 9, 28)).month)
        // The ISO edges: Thu 1 Jan 2026 is in week 1; Mon 29 Dec 2025 too; Sun 3 Jan 2027 is 2026's week 53.
        assertEquals(1, BoardMatchRules.isoWeek(CivilDate.toEpochDay(2026, 1, 1)))
        assertEquals(1, BoardMatchRules.isoWeek(CivilDate.toEpochDay(2025, 12, 29)))
        assertEquals(53, BoardMatchRules.isoWeek(CivilDate.toEpochDay(2027, 1, 3)))
        assertEquals(1, BoardMatchRules.isoWeek(CivilDate.toEpochDay(2027, 1, 4)))
    }

    @Test
    fun aDaysHeaderIsItsDateAndWeekday() {
        val h = BoardMatchRules.dayHead(day(mon26))
        assertEquals("26 Oct", h.date)
        assertEquals("Mon", h.weekday)
    }

    @Test
    fun pastDaysNeverFold() {
        assertFalse(BoardMatchRules.FOLDS_PAST)
        assertFalse(BoardTidyRules.collapsed(day(mon26, past = true), emptySet()))
    }

    @Test
    fun onAWeekendTheBoardOpensOnNextWeek() {
        val weeks = listOf(week(mon26), week(mon26 + 7), week(mon26 + 14))
        // Monday to Friday: this week, today.
        (0L..4L).forEach { d ->
            val b = WeekBoard(mon26 + d, weeks)
            assertEquals(0, BoardMatchRules.opensOnWeek(b))
            assertEquals(mon26 + d, BoardMatchRules.opensOnDay(b))
        }
        // Saturday and Sunday: next week, its Monday.
        listOf(5L, 6L).forEach { d ->
            val b = WeekBoard(mon26 + d, weeks)
            assertEquals(1, BoardMatchRules.opensOnWeek(b))
            assertEquals(mon26 + 7, BoardMatchRules.opensOnDay(b))
        }
        // A board with only this week stays on it.
        assertEquals(0, BoardMatchRules.opensOnWeek(WeekBoard(mon26 + 5, weeks.take(1))))
    }

    @Test
    fun everyCardHasItsTopLineNeverALoneDash() {
        val timed = BoardMatchRules.top(card("a", time = "12:30", estimate = 45))
        assertEquals("12:30", timed!!.time)
        assertEquals("0:45", timed.duration)
        // The density pass: no "Any time" chip; no time and no length, no top line at all.
        assertNull(BoardMatchRules.top(card("b")))
        val sized = BoardMatchRules.top(card("c", estimate = 30))!!
        assertNull(sized.time)
        assertEquals("0:30", sized.duration)
        assertTrue(BoardMatchRules.timed(card("a", time = "12:30")))
        assertFalse(BoardMatchRules.timed(card("b")))
        assertEquals("", BoardMatchRules.timeChip(card("b")))
    }

    @Test
    fun eventsAreCardsWithTheirLengthAndCalendar() {
        val standUp = event("s", 570, 600)
        val trip = event("t", 0, 1440, allDay = true, calendar = null)
        val d = day(
            mon26,
            listOf(BoardChip("t", "All day", "Half term", 2, trip), BoardChip("s", "09:30", "Team stand-up", 1, standUp)),
        )
        val cards = BoardMatchRules.eventCards(d)
        assertEquals(listOf("All day", "09:30"), cards.map { it.time })
        assertNull(cards[0].duration)
        assertEquals("Calendar", cards[0].calendar)
        assertEquals("0:30", cards[1].duration)
        assertEquals("Work", cards[1].calendar)
        assertEquals("Team stand-up, 09:30, 30m, Work", cards[1].spoken)
        assertEquals("Half term, all day, Calendar", cards[0].spoken)
    }

    @Test
    fun theAddButtonIsQuiet() {
        assertEquals("+ add", BoardMatchRules.ADD)
    }

    // ---- Slice 2: the lists as card columns, the timeline's colours by area ----

    private fun withCards(epoch: Long, cards: List<BoardCard>) = day(epoch).copy(cards = cards)

    @Test
    fun theListsComeInTheMockupsOrderWithTheirNameAndCountApart() {
        val w = week(mon26).copy(lane = listOf(card("a"), card("b")))
        val house = card("h").copy(task = task("h").copy(area = BoardArea.HOUSE.name))
        val board = WeekBoard(mon26, listOf(w), someday = listOf(card("s"), house))
        val lists = BoardMatchRules.lists(w, board)
        assertEquals(listOf("Someday", "Side hustle", "House", "No day yet"), lists.map { BoardMatchRules.listHead(it).name })
        val noDay = BoardMatchRules.listHead(lists.last())
        assertEquals("2", noDay.count)
        assertEquals(lists.last().accessibilityLabel, noDay.spoken)
        assertEquals("1", BoardMatchRules.listHead(lists[2]).count)
        // An empty list has no count, never "0".
        assertNull(BoardMatchRules.listHead(lists[1]).count)
        assertTrue(lists.none { BoardMatchRules.listHead(it).name.contains("·") })
    }

    @Test
    fun aListCardShowsItsTopLineOnlyWithATimeOrALength() {
        assertNull(BoardMatchRules.listTop(card("a")))
        assertEquals("", BoardMatchRules.listTime(card("a")))
        assertEquals("", BoardMatchRules.listDuration(card("a")))
        assertEquals("0:30", BoardMatchRules.listTop(card("b", estimate = 30))?.duration)
        assertEquals("0:30", BoardMatchRules.listDuration(card("b", estimate = 30)))
        assertEquals("09:00", BoardMatchRules.listTime(card("c", time = "09:00")))
        // The density pass: a day's card has no "Any time" chip either.
        assertNull(BoardMatchRules.top(card("a")))
    }

    @Test
    fun aTaskBlockWearsItsCardsArea() {
        val work = card("w").copy(task = task("w").copy(area = BoardArea.WORK.name))
        val plain = card("p").copy(task = task("p").copy(area = "NONE"))
        val parked = card("k").copy(task = task("k").copy(area = BoardArea.MONEY.name))
        val w = week(mon26).let { wk -> wk.copy(days = listOf(withCards(mon26, listOf(work, plain))) + wk.days.drop(1), lane = listOf(parked)) }
        val board = WeekBoard(mon26, listOf(w))
        fun block(id: String?, kind: DayBlockKind = DayBlockKind.TASK) = DayBlock("t:$id", "x", 600, 630, kind, taskId = id)
        assertEquals(BoardArea.WORK, BoardMatchRules.blockArea(block("w"), board))
        assertNull(BoardMatchRules.blockArea(block("p"), board))
        assertEquals(BoardArea.MONEY, BoardMatchRules.blockArea(block("k"), board))
        assertNull(BoardMatchRules.blockArea(block("gone"), board))
        assertNull(BoardMatchRules.blockArea(block(null, DayBlockKind.EVENT), board))
        assertNull(BoardMatchRules.blockArea(block(null, DayBlockKind.COMMUTE), board))
    }

    @Test
    fun obisNudgeSitsBesideTodayWithRoomAndUnderTheMonthOnTheCoverScreen() {
        assertEquals(BoardNudgePlace.BESIDE_TODAY, BoardMatchRules.nudgePlace(wide = true))
        assertEquals(BoardNudgePlace.UNDER_TITLE, BoardMatchRules.nudgePlace(wide = false))
        assertTrue(BoardMatchRules.NUDGE_MAX_WIDTH in 300..480)
        assertTrue(BoardMatchRules.NUDGE_LINES >= 1)
    }

    // ---- Slice 4: the side-by-side check's gaps ----

    @Test
    fun theFootNamesTheAreaOnEveryLaneTheNarrowOnesToo() {
        val house = card("h").copy(task = task("h").copy(area = BoardArea.HOUSE.name))
        val hustle = card("x").copy(task = task("x").copy(area = BoardArea.SIDE_HUSTLE.name))
        val none = card("n").copy(task = task("n").copy(area = "NONE"))
        assertEquals("House", BoardMatchRules.footLabel(house))
        assertEquals("Side hustle", BoardMatchRules.footLabel(hustle))
        assertNull(BoardMatchRules.footLabel(none))
        assertTrue(BoardMatchRules.footWords(compact = true))
        assertTrue(BoardMatchRules.footWords(compact = false))
    }

    @Test
    fun theWordPlannedStaysInEveryPillStackedOnANarrowLane() {
        val b = BoardPlannerRules.budget(BoardCapacity(245, 180, 0))
        assertEquals("planned", BoardMatchRules.pillLabel(b))
        assertEquals("4h 05 / 3h", b.value)
        assertTrue(BoardMatchRules.pillStacked(compact = true))
        assertFalse(BoardMatchRules.pillStacked(compact = false))
    }

    @Test
    fun theHeadersButtonsAreTheMockupsRoundOnes() {
        assertEquals(40, BoardMatchRules.BUTTON)
    }

    @Test
    fun anEmptyPastDaySaysSoQuietly() {
        assertEquals("Nothing planned", BoardMatchRules.pastEmpty(day(mon26, past = true)))
        assertNull(BoardMatchRules.pastEmpty(day(mon26)))
        assertNull(BoardMatchRules.pastEmpty(withCards(mon26, listOf(card("a"))).copy(isPast = true)))
        val withEvent = day(mon26, listOf(BoardChip("s", "09:30", "Stand-up", 1, event("s", 570, 600))), past = true)
        assertNull(BoardMatchRules.pastEmpty(withEvent))
        assertNull(BoardMatchRules.pastEmpty(day(mon26, past = true).copy(moreChips = 2)))
    }
}
