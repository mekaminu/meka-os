package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Week board's shots beside their mockups (2026-10-29): what the column guides showed. */
class BoardBesideTest {
    @Test
    fun listHeadingsAreASizeBelowADaysDateOnOneLine() {
        assertTrue(BoardBesideRules.LIST_HEAD_SP < BoardDenseRules.DATE_SP)
        assertTrue(BoardBesideRules.LIST_HEAD_SP >= BoardDenseRules.TITLE_SP)
        assertEquals(1, BoardBesideRules.LIST_HEAD_LINES)
    }

    @Test
    fun obisHeaderPillIsOneLine() {
        assertEquals(1, BoardBesideRules.HEAD_NUDGE_LINES)
        // The folded card keeps its own two lines.
        assertTrue(BoardMatchRules.NUDGE_LINES >= BoardBesideRules.HEAD_NUDGE_LINES)
    }

    @Test
    fun foldedCardsShareTheGutter() {
        // Gutter 16, the column's outer 4 and inner 8: the pager is inset 4, so a card's edge is 4 + 4 + 8 = 16.
        val inset = BoardBesideRules.foldedPagerInset(16f, 4f, 8f)
        assertEquals(4f, inset)
        assertEquals(16f, inset + 4f + 8f)
        assertEquals(0f, BoardBesideRules.foldedPagerInset(8f, 4f, 8f))
        assertEquals(0f, BoardBesideRules.foldedPagerInset(Float.NaN, 4f, 8f))
    }

    @Test
    fun foldedPageIsTheWindowLessItsInsets() {
        assertEquals(336f, BoardBesideRules.foldedColumn(344f, 4f))
        assertEquals(0f, BoardBesideRules.foldedColumn(4f, 4f))
        assertEquals(0f, BoardBesideRules.foldedColumn(Float.NaN, 4f))
        assertEquals(344f, BoardBesideRules.foldedColumn(344f, -2f))
    }

    @Test
    fun timelineBlocksAreOpaqueAndNoExtraGapUnderObi() {
        assertTrue(BoardBesideRules.BLOCKS_OPAQUE)
        assertEquals(0f, BoardBesideRules.FOLDED_SPACE_AFTER_OBI_DP)
    }
}
