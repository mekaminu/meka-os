package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import os.meka.core.domain.TripBesideRules.Piece

class TripBesideTest {
    @Test
    fun aFoldedGroupIsOneWholeCard() {
        val p = TripBesideRules.pieces(open = false, rowIds = listOf("a", "b"), menuOpen = false, sheetFor = null)
        assertEquals(listOf(TripBesideRules.HEAD), p)
        val head = TripBesideRules.pieceOf(TripBesideRules.HEAD, p)
        assertEquals(Piece.ONLY, head)
        assertTrue(head.roundTop && head.roundBottom)
        assertFalse(head.lineOnTop)
    }

    @Test
    fun anOpenGroupRoundsItsHeadAndItsLastRow() {
        val p = TripBesideRules.pieces(open = true, rowIds = listOf("a", "b", "c"), menuOpen = false, sheetFor = null)
        assertEquals(Piece.FIRST, TripBesideRules.pieceOf(TripBesideRules.HEAD, p))
        assertEquals(Piece.MIDDLE, TripBesideRules.pieceOf("a", p))
        assertEquals(Piece.MIDDLE, TripBesideRules.pieceOf("b", p))
        assertEquals(Piece.LAST, TripBesideRules.pieceOf("c", p))
        assertTrue(Piece.MIDDLE.lineOnTop && Piece.LAST.lineOnTop)
        assertFalse(Piece.FIRST.roundBottom)
        assertFalse(Piece.LAST.roundTop)
    }

    @Test
    fun anOpenSheetUnderTheLastRowTakesTheCardsFoot() {
        val p = TripBesideRules.pieces(open = true, rowIds = listOf("a", "b"), menuOpen = true, sheetFor = "b")
        assertEquals(listOf(TripBesideRules.HEAD, TripBesideRules.MENU, "a", "b", TripBesideRules.SHEET + "b"), p)
        assertEquals(Piece.MIDDLE, TripBesideRules.pieceOf("b", p))
        assertEquals(Piece.LAST, TripBesideRules.pieceOf(TripBesideRules.SHEET + "b", p))
        assertEquals(Piece.MIDDLE, TripBesideRules.pieceOf(TripBesideRules.MENU, p))
    }

    @Test
    fun anEmptyOpenGroupIsItsHeadAlone() {
        val p = TripBesideRules.pieces(open = true, rowIds = emptyList(), menuOpen = false, sheetFor = null)
        assertEquals(Piece.ONLY, TripBesideRules.pieceOf(TripBesideRules.HEAD, p))
        assertEquals(Piece.ONLY, TripBesideRules.pieceOf("gone", p))
    }

    @Test
    fun aSlimPillStillSitsInsideATouchTarget() {
        assertTrue(TripBesideRules.PILL_DP < 48)
        assertEquals(0, TripBesideRules.CARD_PAD_DP % 8)
    }
}
