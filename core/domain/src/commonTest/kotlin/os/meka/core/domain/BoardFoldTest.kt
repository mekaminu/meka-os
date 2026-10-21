package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Week board by hand, slice 3: folded, one day at a time with a snap and the 7-day strip of pills. */
class BoardFoldTest {
    private fun task(id: String, done: Boolean = false) = Task(
        id, id, null, if (done) Lifecycle.DONE else Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, if (done) 1L else null, false,
    )

    private fun card(id: String, done: Boolean = false) = BoardCard(task(id, done), "Title $id", null, null, done, false)

    private fun day(
        epoch: Long, number: String, weekday: String = "Thu", cards: List<BoardCard> = emptyList(), today: Boolean = false,
        past: Boolean = false, pressure: DayPressure? = null,
    ) = BoardDay(
        epochDay = epoch, weekday = weekday, number = number, isToday = today, isPast = past, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = cards, countLine = "", accessibilityLabel = "Thursday $number October, 2 events",
        pressure = pressure,
    )

    private val stacked = DayPressure(20_003, "Thursday", false, emptyList(), "Thursday is stacking up", "Stacking up", "Thursday is stacking up", emptyList())

    @Test
    fun theStripHasTodayDotsAndAmber() {
        val days = (0 until 7).map { i ->
            day(
                20_000L + i, "${19 + i}", cards = if (i == 3) listOf(card("a"), card("b", done = true)) else if (i == 4) listOf(card("c", done = true)) else emptyList(),
                today = i == 2, past = i < 2, pressure = if (i == 3) stacked else null,
            )
        }
        val pills = BoardFoldRules.strip(BoardWeek(20_000, "This week", "19–25 Oct", days))
        assertEquals(7, pills.size)
        assertEquals(listOf(false, false, true, false, false, false, false), pills.map { it.isToday })
        // A dot only for open tasks: a day with only done ones has none.
        assertTrue(pills[3].hasTasks)
        assertFalse(pills[4].hasTasks)
        assertTrue(pills[3].stacking)
        assertFalse(pills[0].canDrop)
        assertTrue(pills[2].canDrop)
        assertEquals("Thursday 22 October, 1 task, stacking up. Drop a task here to move it to this day", pills[3].accessibilityLabel)
        assertEquals("Thursday 21 October, today, nothing to do. Drop a task here to move it to this day", pills[2].accessibilityLabel)
        assertEquals("Thursday 19 October, nothing to do", pills[0].accessibilityLabel)
    }

    @Test
    fun aFlingAlwaysSettlesOnAWholeDay() {
        val page = 400f
        // A slow drag settles on the nearest day.
        assertEquals(1, BoardFoldRules.snapPage(560f, page, 0f, 1000f, 35))
        assertEquals(2, BoardFoldRules.snapPage(620f, page, 0f, 1000f, 35))
        // A quick swipe turns one page the way it was going, even from just past a day.
        assertEquals(2, BoardFoldRules.snapPage(430f, page, 3000f, 1000f, 35))
        assertEquals(1, BoardFoldRules.snapPage(780f, page, -3000f, 1000f, 35))
        // Exactly on a day with a tiny overshoot doesn't skip a day.
        assertEquals(1, BoardFoldRules.snapPage(400.5f, page, 3000f, 1000f, 35))
        assertEquals(1, BoardFoldRules.snapPage(399.5f, page, -3000f, 1000f, 35))
        // Never past the ends.
        assertEquals(0, BoardFoldRules.snapPage(-20f, page, -3000f, 1000f, 35))
        assertEquals(34, BoardFoldRules.snapPage(34 * page + 10f, page, 3000f, 1000f, 35))
        // Every result is a whole page for any offset and velocity.
        for (o in 0..14_000 step 37) for (v in listOf(-5000f, -200f, 0f, 200f, 5000f)) {
            val p = BoardFoldRules.snapPage(o.toFloat(), page, v, 1000f, 35)
            assertTrue(p in 0..34)
        }
        assertEquals(3, BoardFoldRules.pageOf(1250f, page, 35))
    }

    @Test
    fun aHeldCardAtTheEdgeTurnsThePage() {
        assertEquals(-1, BoardFoldRules.edgeFlip(10f, 400f, 56f))
        assertEquals(1, BoardFoldRules.edgeFlip(380f, 400f, 56f))
        assertEquals(0, BoardFoldRules.edgeFlip(200f, 400f, 56f))
    }

    @Test
    fun foldingKeepsTheSameDay() {
        val days = (0 until 14).map { i -> day(20_000L + i, "${19 + i}", today = i == 2) }
        // Nothing in view yet: today.
        assertEquals(2, BoardFoldRules.focusAfterFold(days, null))
        // Folded on next Thursday: unfolding shows next week.
        assertEquals(10, BoardFoldRules.focusAfterFold(days, 20_010))
        assertEquals(7, BoardFoldRules.weekStartIndex(10))
        // Unfolded on this week, folding shows today; on next week, its Monday.
        assertEquals(20_002L, BoardFoldRules.focusInWeek(days, 0))
        assertEquals(20_007L, BoardFoldRules.focusInWeek(days, 1))
    }
}
