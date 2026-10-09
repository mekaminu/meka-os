package os.meka.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fold review 2026-10-09, item 2: the brighter Day ring. */
class DayRingLookTest {
    private fun near(expected: Float, actual: Float) = assertTrue(abs(expected - actual) < 0.001f, "expected $expected, got $actual")

    @Test
    fun trackIsA3DpBrassLineAt55Percent() {
        assertEquals(3f, DayRingLook.TRACK_STROKE_DP)
        near(0.55f, DayRingLook.TRACK_ALPHA)
        near(0.5f, DayRingLook.HOUR_MARK_ALPHA)
    }

    @Test
    fun edgeBreathesFrom60To100PercentBrass() {
        near(0.6f, DayRingLook.edgeAlpha(DayRingLive.GLOW_LOW))
        near(1f, DayRingLook.edgeAlpha(DayRingLive.GLOW_HIGH))
        near(1f, DayRingLook.edgeAlpha(1.4f))
    }

    @Test
    fun softBlurReaches8DpOutAndFadesAway() {
        near(DayRingLook.EDGE_BLUR_DP, DayRingLook.blurOffsetDp(DayRingLook.EDGE_BLUR_LAYERS - 1))
        near(8f, DayRingLook.EDGE_BLUR_DP)
        (1 until DayRingLook.EDGE_BLUR_LAYERS).forEach {
            assertTrue(DayRingLook.blurAlpha(it, 1f) < DayRingLook.blurAlpha(it - 1, 1f))
            assertTrue(DayRingLook.blurOffsetDp(it) > DayRingLook.blurOffsetDp(it - 1))
        }
        near(DayRingLook.EDGE_BLUR_PEAK_ALPHA, DayRingLook.blurAlpha(0, 1f))
        assertTrue(DayRingLook.blurAlpha(0, 0.6f) < DayRingLook.blurAlpha(0, 1f))
        near(0f, DayRingLook.blurAlpha(DayRingLook.EDGE_BLUR_LAYERS, 1f))
        assertTrue(DayRingLook.blurStrokeDp() > DayRingLook.EDGE_BLUR_DP / DayRingLook.EDGE_BLUR_LAYERS)
    }

    @Test
    fun secondHandIsThickerWithALongerBrighterTail() {
        near(2.5f, DayRingLook.HAND_STROKE_DP)
        assertEquals(60f, DayRingLive.TAIL_DEGREES)
        near(DayRingLook.TAIL_PEAK_ALPHA, DayRingLive.tailAlpha(0))
        assertTrue(DayRingLive.tailAlpha(0) > 0.55f)
        assertTrue(DayRingLook.HAND_TIP_DP > 2.5f)
    }
}
