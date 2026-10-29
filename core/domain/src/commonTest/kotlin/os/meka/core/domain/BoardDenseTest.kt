package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board's density pass: the mockup's proportions on the open Fold and the Mac. */
class BoardDenseTest {
    private fun task(id: String, estimate: Int? = null) =
        Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false).copy(estimateMinutes = estimate)

    private fun card(id: String, time: String? = null, estimate: Int? = null) = BoardCard(task(id, estimate), "Title $id", time, null, false, false)

    @Test
    fun theTimelineIsThereByDefaultOnTheOpenFoldAndTheMac() {
        // The unfolded Fold (about 829 dp, 48 dp of gutters): the 200 dp panel leaves about 116 dp a column.
        assertTrue(BoardDenseRules.timelineByDefault(829f, 48f))
        assertTrue(BoardDenseRules.timelineByDefault(1280f, 48f))
        // A narrow window can't keep 100 dp columns beside it.
        assertFalse(BoardDenseRules.timelineByDefault(690f, 48f))
        val column = (829f - 48f - BoardTimelineRules.panelDp(829f)) / BoardWeekLayoutRules.WEEKDAYS
        assertTrue(column in 110f..130f, "column $column")
    }

    @Test
    fun noAnyTimeChipTheGoldChipOnlyForASetTime() {
        assertNull(BoardDenseRules.top(card("a")))
        assertEquals("", BoardDenseRules.chip(card("a")))
        assertEquals("", BoardDenseRules.duration(card("a")))
        val sized = BoardDenseRules.top(card("b", estimate = 45))!!
        assertNull(sized.time)
        assertEquals("0:45", sized.duration)
        assertEquals("", BoardDenseRules.chip(card("b", estimate = 45)))
        assertEquals("12:30", BoardDenseRules.chip(card("c", time = "12:30", estimate = 30)))
        assertEquals("0:30", BoardDenseRules.duration(card("c", time = "12:30", estimate = 30)))
    }

    @Test
    fun theMockupsProportions() {
        assertTrue(BoardDenseRules.DATE_SP in 18f..20f)
        assertTrue(BoardDenseRules.TITLE_SP in 13f..14f)
        assertTrue(BoardDenseRules.CHIP_SP in 10f..11f)
        assertEquals(10, BoardDenseRules.CARD_PAD)
    }
}
