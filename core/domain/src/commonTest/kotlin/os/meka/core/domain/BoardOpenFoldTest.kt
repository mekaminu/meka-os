package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board's alignment pass on the open Fold (Meka 2026-10-29 02:12): rows sized to their days, one pill. */
class BoardOpenFoldTest {
    private val mon26 = CivilDate.toEpochDay(2026, 10, 26)

    private fun task(id: String) = Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun card(id: String, done: Boolean = false) = BoardCard(task(id), "Title $id", null, null, done, false)

    private fun day(past: Boolean, cards: List<BoardCard> = emptyList()) = BoardDay(
        epochDay = mon26, weekday = "Mon", number = "26", isToday = false, isPast = past, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = cards, countLine = "", accessibilityLabel = "Monday 26 October",
    )

    @Test
    fun aRowIsAsTallAsItsFullestDayButNeverLessThanItsLeast() {
        // Thursday's cards (b): the row grows to the fullest column, so nothing is clipped.
        assertEquals(612f, BoardOpenFoldRules.rowHeight(listOf(140f, 612f, 300f), BoardOpenFoldRules.LEAST_WEEKDAYS_DP))
        // A quiet week (d): no tall empty row before the weekend.
        assertEquals(BoardOpenFoldRules.LEAST_WEEKDAYS_DP, BoardOpenFoldRules.rowHeight(listOf(90f, 120f), BoardOpenFoldRules.LEAST_WEEKDAYS_DP))
        // Columns not measured yet count as nothing.
        assertEquals(BoardOpenFoldRules.LEAST_WEEKEND_DP, BoardOpenFoldRules.rowHeight(emptyList(), BoardOpenFoldRules.LEAST_WEEKEND_DP))
        assertEquals(200f, BoardOpenFoldRules.rowHeight(listOf(Float.NaN, 200f, -1f), BoardOpenFoldRules.LEAST_WEEKEND_DP))
    }

    @Test
    fun everyUnfoldedColumnStacksItsPillTheSameWay() {
        // (c) one style: the weekend's wide columns stack "planned" over the figures like the weekdays.
        assertTrue(BoardOpenFoldRules.pillStacked(dense = true, compact = false))
        assertTrue(BoardOpenFoldRules.pillStacked(dense = true, compact = true))
        assertTrue(BoardOpenFoldRules.pillStacked(dense = false, compact = true))
        assertFalse(BoardOpenFoldRules.pillStacked(dense = false, compact = false))
    }

    @Test
    fun aPastDayWearsTheSamePillSayingWhatWasDone() {
        val p = BoardOpenFoldRules.pastPill(day(past = true, cards = listOf(card("a", done = true), card("b", done = true), card("c"))))!!
        assertEquals("done", p.label)
        assertEquals("2 of 3", p.value)
        assertEquals(2f / 3f, p.fill)
        assertFalse(p.over)
        assertEquals("2 of 3 done", p.spoken)
        val empty = BoardOpenFoldRules.pastPill(day(past = true))!!
        assertEquals("–", empty.value)
        assertEquals(0f, empty.fill)
        assertEquals("Nothing planned", empty.spoken)
        assertNull(BoardOpenFoldRules.pastPill(day(past = false)))
    }
}
