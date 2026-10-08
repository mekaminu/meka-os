package os.meka.android.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionMathTest {
    @Test
    fun sectionsStagger40msApartAndCap() {
        assertEquals(0, MotionMath.staggerDelayMs(0, reduced = false))
        assertEquals(40, MotionMath.staggerDelayMs(1, reduced = false))
        assertEquals(120, MotionMath.staggerDelayMs(3, reduced = false))
        // Past the cap, everything shares the last delay.
        assertEquals(MotionMath.staggerDelayMs(8, false), MotionMath.staggerDelayMs(50, false))
        assertEquals(320, MotionMath.staggerSpanMs(30, reduced = false))
        assertEquals(0, MotionMath.staggerSpanMs(0, reduced = false))
    }

    @Test
    fun reducedMotionHasNoStaggerAndNoRise() {
        assertEquals(0, MotionMath.staggerDelayMs(5, reduced = true))
        assertEquals(0f, MotionMath.riseOffset(0f, 12f, reduced = true))
        assertEquals(12f, MotionMath.riseOffset(0f, 12f, reduced = false))
        assertEquals(0f, MotionMath.riseOffset(1.02f, 12f, reduced = false)) // spring overshoot never sinks below
    }

    @Test
    fun expressiveSpacesWiderRisesFurtherAndGrowsFromNinetySixPercent() {
        assertEquals(60, MotionMath.staggerDelayMs(1, reduced = false, expressive = true))
        assertEquals(180, MotionMath.staggerDelayMs(3, reduced = false, expressive = true))
        assertEquals(480, MotionMath.staggerSpanMs(30, reduced = false, expressive = true))
        assertEquals(0, MotionMath.staggerDelayMs(3, reduced = true, expressive = true))
        assertEquals(28, MotionMath.riseDistanceDp(expressive = true))
        assertEquals(12, MotionMath.riseDistanceDp(expressive = false))
        assertEquals(900, MotionMath.countUpMs(expressive = true))
        assertEquals(700, MotionMath.countUpMs(expressive = false))
        assertTrue(kotlin.math.abs(MotionMath.entryScale(0f, expressive = true, reduced = false) - 0.96f) < 1e-6f)
        assertTrue(kotlin.math.abs(MotionMath.entryScale(0.5f, expressive = true, reduced = false) - 0.98f) < 1e-6f)
        assertEquals(1f, MotionMath.entryScale(1.05f, expressive = true, reduced = false)) // overshoot never grows past full
        assertEquals(1f, MotionMath.entryScale(0f, expressive = false, reduced = false)) // Subtle never scales
        assertEquals(1f, MotionMath.entryScale(0f, expressive = true, reduced = true)) // Off never scales
    }

    @Test
    fun countUpStartsAtFromLandsOnToAndNeverGoesBackwards() {
        assertEquals(0, MotionMath.countUpValue(0, 135, 0f))
        assertEquals(135, MotionMath.countUpValue(0, 135, 1f))
        var last = -1
        for (i in 0..100) {
            val v = MotionMath.countUpValue(0, 135, i / 100f)
            assertTrue(v >= last, "count-up went backwards at $i")
            last = v
        }
        assertEquals(10, MotionMath.countUpValue(20, 10, 1f)) // counting down works too
    }

    @Test
    fun easingIsClampedAndFrontLoaded() {
        assertEquals(0f, MotionMath.easeOutCubic(-1f))
        assertEquals(1f, MotionMath.easeOutCubic(2f))
        assertTrue(MotionMath.easeOutCubic(0.5f) > 0.5f)
    }

    @Test
    fun anythingTappablePressesInToNinetySevenPercentAndOffOnlyDims() {
        assertEquals(1f, MotionMath.pressScale(pressed = false, reduced = false))
        assertEquals(0.97f, MotionMath.pressScale(pressed = true, reduced = false))
        assertEquals(1f, MotionMath.pressScale(pressed = true, reduced = true))
        assertEquals(0f, MotionMath.pressDim(pressed = false, reduced = true))
        assertTrue(MotionMath.pressDim(pressed = true, reduced = true) > MotionMath.pressDim(pressed = true, reduced = false))
        assertTrue(MotionMath.pressDim(pressed = true, reduced = false) > 0f)
    }

    @Test
    fun cardsLiftTwoDpUnderThePointerExceptWithMotionOff() {
        assertEquals(2, MotionMath.hoverLiftDp(hovering = true, reduced = false))
        assertEquals(0, MotionMath.hoverLiftDp(hovering = false, reduced = false))
        assertEquals(0, MotionMath.hoverLiftDp(hovering = true, reduced = true))
    }
}
