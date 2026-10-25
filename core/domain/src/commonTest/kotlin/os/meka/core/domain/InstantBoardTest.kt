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
}
