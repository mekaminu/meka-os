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

    @Test
    fun theMacSplitsFromItsOwnBreakpointAndAnOpenTaskKeepsAColumnBeside() {
        val r = TodayColumnsRules
        assertFalse(r.macSplits(599f))
        assertTrue(r.macSplits(600f))
        assertTrue(r.macSplits(1440f))
        // No task open: Today is the whole width.
        assertEquals(listOf(1440f), r.macWidths(1440f, taskOpen = false))
        // A task open: about a third for it, the rest for Today, adding up to the window.
        val wide = r.macWidths(1440f, taskOpen = true)
        assertEquals(2, wide.size)
        assertEquals(1440f, wide.sum())
        assertEquals(518f, wide[1])
        assertTrue(r.macTodaySplits(wide[0]))
        // Narrower: the task's column keeps its minimum and Today goes back to one list beside it.
        val mid = r.macWidths(800f, taskOpen = true)
        assertEquals(listOf(480f, 320f), mid)
        assertFalse(r.macTodaySplits(mid[0]))
        // Below the split width nothing changes.
        assertEquals(listOf(500f), r.macWidths(500f, taskOpen = true))
        // Today never squeezed under its minimum.
        val tight = r.macWidths(610f, taskOpen = true)
        assertEquals(300f, tight[0])
        assertEquals(610f, tight.sum())
    }

    @Test
    fun aRunningFastLeadsTheLeftColumnWhenSplitAndStaysInTheHeaderWhenNot() {
        // Walk-through week 4, A6: the capped header crushed the card on the open Fold.
        assertFalse(TodayColumnsRules.fastInHeader(split = true))
        assertTrue(TodayColumnsRules.fastInHeader(split = false))
    }

    @Test
    fun theScheduleLeadsTheRightColumnWithHealthAndSetupStraightUnderIt() {
        // Walk-through week 4, A6 (3): heading the column, the health line and setup card pushed the day to 17:15.
        val r = TodayColumnsRules
        assertEquals(listOf(TodaySlot.TIMELINE, null, TodaySlot.CARDS), r.rightWithHousekeeping())
        assertTrue(r.aboveHousekeeping(TodaySlot.TIMELINE))
        assertFalse(r.aboveHousekeeping(TodaySlot.CARDS))
        // Nothing on the left is above them: they never push Now down (week 3, A1).
        assertTrue(r.slots(TodayColumn.LEFT).none { r.aboveHousekeeping(it) })
    }

    @Test
    fun aScrolledSplitColumnFadesItsTopOverAWholeRow() {
        // Found and fixing week 5, A12: a 24 dp fade left "Fix the gate latch" sliced under the habit chips.
        val r = TodayColumnsRules
        assertTrue(r.TOP_FADE_DP >= TodayBesideRules.ROW_WITH_LINE_MAX_DP)
        assertEquals(0f, r.topFadeAlpha(0f))
        assertEquals(1f, r.topFadeAlpha(r.TOP_FADE_DP.toFloat()))
        assertEquals(1f, r.topFadeAlpha(200f))
        // A title's first 20 dp under the header are under half strength, so no half line reads as cut.
        assertTrue(r.topFadeAlpha(20f) < 0.5f)
    }

    @Test
    fun aRowsStubAtRestUnderTheChipsIsAllButGone() {
        // Found and fixing week 5, A12 slice 44: the end shot's "60 min" stub, about 20 dp under the header, read dim at
        // about a third; the eased fade leaves it under a fifth, and the fade still comes up whole by its end.
        val r = TodayColumnsRules
        assertTrue(r.topFadeAlpha(24f) < 0.2f)
        assertTrue(r.topFadeAlpha(42f) > 0.5f)
        val stops = r.topFadeStops()
        assertEquals(r.TOP_FADE_STEPS + 1, stops.size)
        assertEquals(0f, stops.first())
        assertEquals(1f, stops.last())
        assertTrue(stops.zipWithNext().all { (a, b) -> b > a })
    }
}
