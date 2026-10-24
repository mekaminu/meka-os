package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Week board's density pass, slice 2: the header, "+ add" and the narrow pills at the mockup's size. */
class BoardDenseHeadTest {
    private fun budget(planned: Int, free: Int) = BoardPlannerRules.budget(BoardCapacity(planned, free, 0))

    @Test
    fun theHeaderIsTheMockupsSizeNotTheGreetings() {
        // The month was the 34 greeting; the mockup's 40 px at 1280 is about 28 at the Fold's scale.
        assertEquals(28f, BoardDenseHeadRules.MONTH_SP)
        assertTrue(BoardDenseHeadRules.WEEK_SP < BoardDenseHeadRules.LABEL_SP)
        assertTrue(BoardDenseHeadRules.NUDGE_SP <= BoardDenseHeadRules.LABEL_SP)
        // The buttons and pills shrink from the 40 round buttons, the orb with them.
        assertTrue(BoardDenseHeadRules.BUTTON < BoardMatchRules.BUTTON)
        assertTrue(BoardDenseHeadRules.NUDGE_ORB < BoardMatchRules.NUDGE_ORB)
    }

    @Test
    fun theGoalsFoldIntoTheHeaderUnfoldedWithoutTheirHint() {
        assertTrue(BoardDenseHeadRules.goalsInHeader(wide = true))
        assertFalse(BoardDenseHeadRules.goalsInHeader(wide = false))
        // In the header "+ Goal" is a small pill alone; on the cover screen's own row the hint stays while there are none.
        assertFalse(BoardDenseHeadRules.goalHint(empty = true, inHeader = true))
        assertTrue(BoardDenseHeadRules.goalHint(empty = true, inHeader = false))
        assertFalse(BoardDenseHeadRules.goalHint(empty = false, inHeader = false))
    }

    @Test
    fun addIsASlimDashedRow() {
        assertTrue(BoardDenseHeadRules.ADD_HEIGHT < 34)
        assertTrue(BoardDenseHeadRules.ADD_SP <= BoardDenseRules.FOOT_SP)
    }

    @Test
    fun aNarrowPillKeepsItsFiguresOnOneLine() {
        // "nothing yet / 4h" wrapped to a third line in a 116 dp lane; there it is "– / 4h" under "planned".
        assertEquals("– / 4h", BoardDenseHeadRules.pillValue(budget(0, 240), narrow = true))
        assertEquals("nothing yet / 4h", BoardDenseHeadRules.pillValue(budget(0, 240), narrow = false))
        // Once anything is planned the figures are the mockup's as they are.
        assertEquals("4h 05 / 3h", BoardDenseHeadRules.pillValue(budget(245, 180), narrow = true))
        assertEquals(2, BoardDenseHeadRules.pillLines(narrow = true))
        assertEquals(1, BoardDenseHeadRules.pillLines(narrow = false))
    }

    @Test
    fun theFiguresFitANarrowLaneAtTheMockupsMono() {
        // At 10.5 mono (about 6.3 dp a character) inside 8 dp either side, a 116 dp lane holds about 15 characters.
        val perChar = BoardDenseRules.CHIP_SP * 0.6f
        val room = ((116f - 16f) / perChar).toInt()
        listOf(budget(0, 240), budget(0, 405), budget(245, 180), budget(75, 375)).forEach { b ->
            val v = BoardDenseHeadRules.pillValue(b, narrow = true)
            assertTrue(v.length <= room, "\"$v\" fits $room characters")
        }
    }
}
