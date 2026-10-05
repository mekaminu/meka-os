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
}
