package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Taps feel instant on the Week board (slice 13): Done and moves show in the next frame, over the core's board. */
class InstantBoardTest {
    private val mon = CivilDate.toEpochDay(2026, 10, 26)

    private fun task(id: String) = Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)
    private fun card(id: String, done: Boolean = false) = BoardCard(task(id), "Title $id", null, null, done, false)

    private fun day(epoch: Long, vararg cards: BoardCard, past: Boolean = false) = BoardDay(
        epochDay = epoch, weekday = "Mon", number = "26", isToday = epoch == mon, isPast = past, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = cards.toList(), countLine = "", accessibilityLabel = "",
    )

    private fun board(): WeekBoard = WeekBoard(
        todayEpochDay = mon,
        weeks = listOf(
            BoardWeek(mon, "This week", "26 Oct–1 Nov", listOf(
                day(mon, card("a"), card("b"), card("x", done = true)),
                day(mon + 1, card("c")),
            ) + (2..6).map { day(mon + it) }, lane = listOf(card("l1"))),
            BoardWeek(mon + 7, "Next week", "2–8 Nov", (0..6).map { day(mon + 7 + it) }),
        ),
        someday = listOf(card("s1")),
    )

    private fun ids(d: BoardDay?) = d?.cards?.map { it.id }

    @Test
    fun nothingWaitingIsTheCoresBoardItself() {
        val b = board()
        assertTrue(b === InstantBoardRules.shown(b, emptyMap()))
    }

    @Test
    fun doneTicksTheCardAtOnceInItsPlaceAndNotDoneUnticks() {
        val taps = InstantBoardRules.done(emptyMap(), "a", true)
        val shown = InstantBoardRules.shown(board(), taps)
        val today = shown.day(mon)!!
        assertEquals(listOf("a", "b", "x"), ids(today))
        assertTrue(today.cards.first().done)
        assertEquals(listOf("b"), today.openCards.map { it.id })
        val undone = InstantBoardRules.shown(board(), InstantBoardRules.done(emptyMap(), "x", false))
        assertEquals(listOf("a", "b", "x"), undone.day(mon)!!.openCards.map { it.id })
    }

    @Test
    fun aDropOnADayMovesTheCardToItsPlaceAmongTheOpenOnes() {
        val shown = InstantBoardRules.shown(board(), InstantBoardRules.toDay(emptyMap(), "c", mon, 1))
        assertEquals(listOf("a", "c", "b", "x"), ids(shown.day(mon)))
        assertEquals(emptyList(), ids(shown.day(mon + 1)))
        // To the end of a day (the folded pill): after the open ones, before the done.
        val end = InstantBoardRules.shown(board(), InstantBoardRules.toDay(emptyMap(), "a", mon + 1, Int.MAX_VALUE))
        assertEquals(listOf("c", "a"), ids(end.day(mon + 1)))
        assertEquals(listOf("b", "x"), ids(end.day(mon)))
        // Up and down within the same day.
        val down = InstantBoardRules.shown(board(), InstantBoardRules.toDay(emptyMap(), "a", mon, 5))
        assertEquals(listOf("b", "a", "x"), ids(down.day(mon)))
    }

    @Test
    fun fromTheLaneOrSomedayOntoADayAndOntoAWeeksLane() {
        val fromLane = InstantBoardRules.shown(board(), InstantBoardRules.toDay(emptyMap(), "l1", mon + 9, 0))
        assertEquals(listOf("l1"), ids(fromLane.day(mon + 9)))
        assertEquals(emptyList(), fromLane.weeks[0].lane.map { it.id })
        val fromSomeday = InstantBoardRules.shown(board(), InstantBoardRules.toDay(emptyMap(), "s1", mon + 1, 0))
        assertEquals(listOf("s1", "c"), ids(fromSomeday.day(mon + 1)))
        assertEquals(emptyList(), fromSomeday.someday)
        val parked = InstantBoardRules.shown(board(), InstantBoardRules.toWeek(emptyMap(), "b", mon + 7, 0))
        assertEquals(listOf("b"), parked.weeks[1].lane.map { it.id })
        assertEquals(listOf("a", "x"), ids(parked.day(mon)))
    }

    @Test
    fun whatTheCoreWouldRefuseIsLeftToTheCore() {
        val b = board().let { it.copy(weeks = it.weeks.map { w -> w.copy(days = w.days.map { d -> if (d.epochDay == mon + 1) d.copy(isPast = true) else d }) }) }
        // A day before today, a day off the board, a done card, a card that isn't there: nothing moves.
        assertEquals(ids(b.day(mon)), ids(InstantBoardRules.shown(b, InstantBoardRules.toDay(emptyMap(), "a", mon + 1, 0)).day(mon)))
        assertEquals(ids(b.day(mon)), ids(InstantBoardRules.shown(b, InstantBoardRules.toDay(emptyMap(), "a", mon + 40, 0)).day(mon)))
        assertEquals(ids(b.day(mon + 2)), ids(InstantBoardRules.shown(b, InstantBoardRules.toDay(emptyMap(), "x", mon + 2, 0)).day(mon + 2)))
        assertEquals(b, InstantBoardRules.shown(b, InstantBoardRules.toDay(emptyMap(), "gone", mon, 0)))
    }

    @Test
    fun theTapIsForgottenWhenItsCallReturnsAndAMoveKeepsATick() {
        val taps = InstantBoardRules.toDay(InstantBoardRules.done(emptyMap(), "a", true), "b", mon + 1, 0)
        assertEquals(setOf("a", "b"), taps.keys)
        assertEquals(setOf("b"), InstantBoardRules.forget(taps, "a").keys)
        val both = InstantBoardRules.toDay(InstantBoardRules.done(emptyMap(), "c", false), "c", mon, 0)
        assertEquals(BoardTap(done = false, toDay = mon, index = 0), both["c"])
        assertTrue(taps === InstantBoardRules.forget(taps, "zzz"))
    }

    // ---- Slice 17: the nudges, Someday, the lists and the area show at once too ----

    @Test
    fun aNudgeMovesTheCardADayOnToTheEndOrAPlaceUpOrDown() {
        val b = board()
        val later = InstantBoardRules.shown(b, InstantBoardRules.nudge(b, emptyMap(), "a", 1, 0))
        assertEquals(listOf("c", "a"), ids(later.day(mon + 1)))
        assertEquals(listOf("b", "x"), ids(later.day(mon)))
        val down = InstantBoardRules.shown(b, InstantBoardRules.nudge(b, emptyMap(), "a", 0, 1))
        assertEquals(listOf("b", "a", "x"), ids(down.day(mon)))
        val up = InstantBoardRules.shown(b, InstantBoardRules.nudge(b, emptyMap(), "b", 0, -1))
        assertEquals(listOf("b", "a", "x"), ids(up.day(mon)))
        // Two quick nudges add up: a day on, then another.
        val twice = InstantBoardRules.nudge(b, InstantBoardRules.nudge(b, emptyMap(), "a", 1, 0), "a", 1, 0)
        assertEquals(listOf("a"), ids(InstantBoardRules.shown(b, twice).day(mon + 2)))
    }

    @Test
    fun aNudgeTheCoreWouldRefuseLeavesTheTapsAlone() {
        val b = board()
        val none = emptyMap<String, BoardTap>()
        assertTrue(none === InstantBoardRules.nudge(b, none, "a", 0, -1)) // already first
        assertTrue(none === InstantBoardRules.nudge(b, none, "b", 0, 1)) // already last open
        assertTrue(none === InstantBoardRules.nudge(b, none, "a", -1, 0)) // no day before Monday on the board
        assertTrue(none === InstantBoardRules.nudge(b, none, "l1", 1, 0)) // in the lane, not on a day
        assertTrue(none === InstantBoardRules.nudge(b, none, "x", 1, 0)) // done
    }

    @Test
    fun somedayTakesTheCardOffItsDayOrLaneAndPutsItFirstInTheTray() {
        val b = board()
        val fromDay = InstantBoardRules.shown(b, InstantBoardRules.someday(b, emptyMap(), "b"))
        assertEquals(listOf("b", "s1"), fromDay.someday.map { it.id })
        assertEquals(listOf("a", "x"), ids(fromDay.day(mon)))
        val fromLane = InstantBoardRules.shown(b, InstantBoardRules.someday(b, emptyMap(), "l1"))
        assertEquals(listOf("l1", "s1"), fromLane.someday.map { it.id })
        assertEquals(emptyList(), fromLane.weeks[0].lane)
        // Already on Someday, done or a repeat: left to the core.
        val none = emptyMap<String, BoardTap>()
        assertTrue(none === InstantBoardRules.someday(b, none, "s1"))
        assertTrue(none === InstantBoardRules.someday(b, none, "x"))
        // A drop back on a day after Someday wins.
        val back = InstantBoardRules.toDay(InstantBoardRules.someday(b, none, "b"), "b", mon + 1, 0)
        assertEquals(listOf("b", "c"), ids(InstantBoardRules.shown(b, back).day(mon + 1)))
    }

    @Test
    fun aDropOnAListGoesToSomedayWithTheListsArea() {
        val b = board()
        val taps = InstantBoardRules.toList(b, emptyMap(), "a", BoardArea.HOUSE)
        val shown = InstantBoardRules.shown(b, taps)
        val moved = shown.someday.first()
        assertEquals("a", moved.id)
        assertEquals(BoardArea.HOUSE, BoardAreaRules.listOf(moved))
        assertEquals(listOf("b", "x"), ids(shown.day(mon)))
        // From Someday to Side hustle: it stays in the tray and only its list changes.
        val within = InstantBoardRules.shown(b, InstantBoardRules.toList(b, emptyMap(), "s1", BoardArea.SIDE_HUSTLE))
        assertEquals(listOf("s1"), within.someday.map { it.id })
        assertEquals(BoardArea.SIDE_HUSTLE, BoardAreaRules.listOf(within.someday.first()))
        // Back to Someday itself takes the list off; already on plain Someday, or not a list: nothing to show.
        val houseThenSomeday = InstantBoardRules.toList(b, taps, "a", null)
        assertEquals(null, BoardAreaRules.listOf(InstantBoardRules.shown(b, houseThenSomeday).someday.first()))
        val none = emptyMap<String, BoardTap>()
        assertTrue(none === InstantBoardRules.toList(b, none, "s1", null))
        assertTrue(none === InstantBoardRules.toList(b, none, "a", BoardArea.WORK))
        assertTrue(none === InstantBoardRules.toList(b, none, "x", BoardArea.HOUSE))
    }

    @Test
    fun theCardMenusAreaChangesTheDotAtOnceAndKeepsItsPlace() {
        val b = board()
        val shown = InstantBoardRules.shown(b, InstantBoardRules.area(b, emptyMap(), "c", BoardArea.MONEY))
        assertEquals(listOf("c"), ids(shown.day(mon + 1)))
        assertEquals(BoardArea.MONEY, BoardAreaRules.of(shown.day(mon + 1)!!.cards.first()))
        val off = InstantBoardRules.area(b, emptyMap(), "c", null)
        assertEquals(null, BoardAreaRules.of(InstantBoardRules.shown(b, off).day(mon + 1)!!.cards.first()))
        // The same area again: nothing to show.
        val none = emptyMap<String, BoardTap>()
        val money = InstantBoardRules.shown(b, InstantBoardRules.area(b, none, "c", BoardArea.MONEY))
        assertTrue(none === InstantBoardRules.area(money, none, "c", BoardArea.MONEY))
    }
}
