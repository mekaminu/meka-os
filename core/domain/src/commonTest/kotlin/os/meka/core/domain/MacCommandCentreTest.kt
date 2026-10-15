package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacCommandCentreTest {
    private val r = MacCommandCentreRules

    @Test
    fun threeColumnsOnlyFromElevenHundredPoints() {
        assertEquals(CommandLayout.SINGLE, r.layout(599f))
        assertEquals(CommandLayout.TWO, r.layout(600f))
        // His 1000-pt window: two columns, not three cramped ones.
        assertEquals(CommandLayout.TWO, r.layout(1000f))
        assertEquals(CommandLayout.TWO, r.layout(1099.9f))
        assertEquals(CommandLayout.THREE, r.layout(1100f))
        assertEquals(CommandLayout.THREE, r.layout(1440f))
    }

    @Test
    fun twoColumnsPutNeedsYouBesideTodayAndComingUpUnderIt() {
        assertEquals(listOf(CommandColumn.TODAY, CommandColumn.NEEDS_YOU), r.columns(CommandLayout.TWO, taskOpen = false))
        assertEquals(listOf(CommandColumn.TODAY, CommandColumn.DETAIL), r.columns(CommandLayout.TWO, taskOpen = true))
        assertTrue(r.comingUpUnderToday(CommandLayout.TWO))
        assertFalse(r.comingUpUnderToday(CommandLayout.THREE))
        assertFalse(r.comingUpUnderToday(CommandLayout.SINGLE))
        assertEquals(
            listOf(CommandColumn.TODAY, CommandColumn.NEEDS_YOU, CommandColumn.COMING_UP),
            r.columns(CommandLayout.THREE, taskOpen = false),
        )
        assertEquals(
            listOf(CommandColumn.TODAY, CommandColumn.DETAIL, CommandColumn.COMING_UP),
            r.columns(CommandLayout.THREE, taskOpen = true),
        )
        assertEquals(listOf(CommandColumn.TODAY), r.columns(CommandLayout.SINGLE, taskOpen = true))
    }

    @Test
    fun needsYouIsListedInTodayOnlyWhenAlone() {
        assertTrue(r.todayListsNeedsYou(CommandLayout.SINGLE))
        assertFalse(r.todayListsNeedsYou(CommandLayout.TWO))
        assertFalse(r.todayListsNeedsYou(CommandLayout.THREE))
    }

    @Test
    fun wideWindowGivesNeedsYouTheMostRoom() {
        val w = r.widths(1440f, CommandLayout.THREE)
        assertEquals(3, w.size)
        assertEquals(1440f, w.sum())
        assertEquals(460f, w[0]) // 32%
        assertEquals(374f, w[2]) // 26%
        assertEquals(606f, w[1]) // the rest, ≈ 42%
        assertTrue(w[1] > w[0] && w[0] > w[2] - 100f)
    }

    @Test
    fun minimumsHoldAtTheThreeColumnBreakpoint() {
        val w = r.widths(1100f, CommandLayout.THREE)
        assertEquals(1100f, w.sum())
        assertEquals(352f, w[0])
        assertEquals(286f, w[2])
        assertTrue(w[0] >= r.TODAY_MIN && w[1] >= r.SIDE_MIN && w[2] >= r.COMING_UP_MIN)
    }

    @Test
    fun twoColumnsSplitRoughlyInHalf() {
        val w = r.widths(1000f, CommandLayout.TWO)
        assertEquals(listOf(520f, 480f), w)
        // At the narrowest two-column width Today keeps its minimum and Needs you its own.
        val narrow = r.widths(600f, CommandLayout.TWO)
        assertEquals(600f, narrow.sum())
        assertEquals(312f, narrow[0])
        assertTrue(narrow[1] >= r.SIDE_MIN)
    }

    @Test
    fun aloneTodayTakesTheWholeWidthAndNothingIsNegative() {
        assertEquals(listOf(540f), r.widths(540f, CommandLayout.SINGLE))
        assertEquals(listOf(0f), r.widths(-5f, CommandLayout.SINGLE))
        // Before the first measurement (width 0) nothing goes negative for Today.
        assertTrue(r.widths(0f, CommandLayout.THREE)[0] >= 0f)
    }

    @Test
    fun edgesFadeOnlyWhereThereIsMore() {
        // At the top with more below: only the foot fades.
        assertEquals(EdgeFades(top = false, bottom = true), r.edgeFades(0f, 1200f, 600f))
        // Half way: both.
        assertEquals(EdgeFades(top = true, bottom = true), r.edgeFades(300f, 1200f, 600f))
        // At the end: only the top.
        assertEquals(EdgeFades(top = true, bottom = false), r.edgeFades(600f, 1200f, 600f))
        // Content that fits: neither, and a trackpad bounce doesn't count.
        assertEquals(EdgeFades(top = false, bottom = false), r.edgeFades(0f, 400f, 600f))
        assertEquals(EdgeFades(top = false, bottom = false), r.edgeFades(0.5f, 600.5f, 600f))
        assertEquals(EdgeFades(top = false, bottom = false), r.edgeFades(-20f, 400f, 600f))
    }
}
