package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Watch face item, part 2: Today's header collapses as it scrolls, the face gliding into a small clock. */
class HeaderCollapseTest {
    private fun near(expected: Float, actual: Float) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.001f, "expected $expected, was $actual")

    @Test
    fun onlyTheCoverScreenAndTheMacCollapse() {
        assertTrue(HeaderCollapseRules.collapses(cover = true))
        assertTrue(HeaderCollapseRules.collapses(cover = false, mac = true))
        assertFalse(HeaderCollapseRules.collapses(cover = false))
    }

    @Test
    fun progressFollowsTheScrollAndIsFullOnceTheHeaderHasGone() {
        near(0f, HeaderCollapseRules.progress(0, 0f, 200f))
        near(0.5f, HeaderCollapseRules.progress(0, 100f, 200f))
        near(1f, HeaderCollapseRules.progress(0, 400f, 200f))
        near(1f, HeaderCollapseRules.progress(3, 0f, 200f))
        near(0f, HeaderCollapseRules.progress(0, 0f, 0f))
        near(1f, HeaderCollapseRules.progress(0, 5f, 0f))
        near(1f, HeaderCollapseRules.rangePx(40f, 80f))
        near(120f, HeaderCollapseRules.rangePx(200f, 80f))
    }

    @Test
    fun theFaceShrinksSmoothlyToTheCompactClock() {
        val full = WatchFaceRules.COVER_DP
        near(full.toFloat(), HeaderCollapseRules.faceDp(0f, full))
        near(HeaderCollapseRules.COMPACT_DP.toFloat(), HeaderCollapseRules.faceDp(1f, full))
        near((full + HeaderCollapseRules.COMPACT_DP) / 2f, HeaderCollapseRules.faceDp(0.5f, full))
        near(1f, HeaderCollapseRules.faceScale(0f, full))
        near(HeaderCollapseRules.COMPACT_DP.toFloat() / full, HeaderCollapseRules.faceScale(1f, full))
        // The Mac's 150 face lands on the same 40 dp clock.
        near(HeaderCollapseRules.COMPACT_DP.toFloat(), HeaderCollapseRules.faceDp(1f, DayRingHeader.WIDE_DP))
        // Never smaller than compact, however far it scrolls.
        near(HeaderCollapseRules.COMPACT_DP.toFloat(), HeaderCollapseRules.faceDp(3f, full))
        // It rises from the header's top to the slim bar's.
        near(32f, HeaderCollapseRules.faceTopDp(0f, 32f, 8f))
        near(20f, HeaderCollapseRules.faceTopDp(0.5f, 32f, 8f))
        near(8f, HeaderCollapseRules.faceTopDp(1f, 32f, 8f))
    }

    @Test
    fun reducedMotionSwitchesStatesAtHalfWayWithoutTheGlide() {
        near(0.3f, HeaderCollapseRules.shown(0.3f, reduced = false))
        near(0f, HeaderCollapseRules.shown(0.3f, reduced = true))
        near(1f, HeaderCollapseRules.shown(0.5f, reduced = true))
        near(1f, HeaderCollapseRules.shown(0.9f, reduced = true))
    }

    @Test
    fun theBarFadesInOverTheLastPartAndTheDotsStopTakingTaps() {
        near(0f, HeaderCollapseRules.barAlpha(0f))
        near(0f, HeaderCollapseRules.barAlpha(HeaderCollapseRules.BAR_FROM))
        near(0.5f, HeaderCollapseRules.barAlpha(0.8f))
        near(1f, HeaderCollapseRules.barAlpha(1f))
        assertFalse(HeaderCollapseRules.barShown(0.2f))
        assertTrue(HeaderCollapseRules.barShown(0.9f))
        assertTrue(HeaderCollapseRules.dotsTappable(0f))
        assertFalse(HeaderCollapseRules.dotsTappable(0.6f))
    }

    @Test
    fun theLinesFoldIntoOne() {
        assertEquals("Tue 13 Oct · 14°", HeaderCollapseRules.compactLine("Tue 13 Oct", "14° · light rain from 16:00"))
        assertEquals("Tue 13 Oct", HeaderCollapseRules.compactLine("Tue 13 Oct", null))
        assertEquals("Tue 13 Oct · home 9° now",
            HeaderCollapseRules.compactLine("Tue 13 Oct", "home 9° now · work (London) 14°, light rain from 15:00"))
        assertEquals("", HeaderCollapseRules.compactLine("", ""))
    }
}
