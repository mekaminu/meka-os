package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodayColumnsTest {
    @Test
    fun theClosedFoldStaysOneColumnAndTheOpenFoldSplits() {
        assertFalse(TodayColumnsRules.splits(344f))
        assertFalse(TodayColumnsRules.splits(599f))
        assertTrue(TodayColumnsRules.splits(600f))
        assertTrue(TodayColumnsRules.splits(829f))
        assertTrue(TodayColumnsRules.splits(1100f))
    }

    @Test
    fun whatToDoIsOnTheLeftAndTheShapeOfTheDayOnTheRight() {
        assertEquals(
            listOf(TodaySlot.CLEAR, TodaySlot.FROM_YESTERDAY, TodaySlot.UP_NEXT, TodaySlot.EVENING, TodaySlot.NEEDS_YOU, TodaySlot.ANYTIME, TodaySlot.DONE),
            TodayColumnsRules.slots(TodayColumn.LEFT),
        )
        assertEquals(listOf(TodaySlot.TIMELINE, TodaySlot.CARDS), TodayColumnsRules.slots(TodayColumn.RIGHT))
    }

    @Test
    fun everyPartSitsInExactlyOneColumn() {
        val both = TodayColumnsRules.slots(TodayColumn.LEFT) + TodayColumnsRules.slots(TodayColumn.RIGHT)
        assertEquals(TodayOrderRules.ORDER.toSet(), both.toSet())
        assertEquals(TodayOrderRules.ORDER.size, both.size)
    }

    @Test
    fun theColumnsShareTheWidthAndTheHeaderLeavesThemMostOfTheHeight() {
        assertEquals(0.5f, TodayColumnsRules.LEFT_SHARE)
        assertTrue(TodayColumnsRules.HEADER_MAX_SHARE < 0.5f)
    }
}
