package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Watch face item (Meka 2026-10-12 18:04 and 2026-10-13 07:01): a smaller face on the cover screen, a thinner bezel with
 * a fine hairline and half the glow, and a thin glowing second hand instead of the comet tail.
 */
class WatchFaceLookTest {
    private fun near(expected: Float, actual: Float) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "expected $expected, was $actual")

    @Test
    fun theCoverScreenFaceIsAboutAFifthSmallerAndTheOpenFoldKeepsItsSize() {
        val cover = WatchFaceRules.headerDp(cover = true)
        assertEquals(WatchFaceRules.COVER_DP, cover)
        val shrink = 1f - cover.toFloat() / DayRingHeader.COMPACT_DP
        assertTrue(shrink in 0.15f..0.25f, "shrink $shrink")
        assertEquals(DayRingHeader.WIDE_DP, WatchFaceRules.headerDp(cover = false))
        // Habit dots still fit inside the markers on the smaller face.
        assertTrue(HabitDotRules.ringRadiusDp(cover) > HabitDotRules.dotDp(cover))
    }

    @Test
    fun theBezelIsAboutSixtyPercentOfTheOldTrackAndScalesWithTheFace() {
        val wide = DayRingHeader.WIDE_DP
        val ratio = WatchFaceLook.bezelDp(wide) / DayRingLook.TRACK_STROKE_DP
        assertTrue(ratio in 0.55f..0.6f, "ratio $ratio")
        // Scaled with the face: the cover face's bezel is thinner, the bedside's heavier, never vanishing.
        assertTrue(WatchFaceLook.bezelDp(WatchFaceRules.COVER_DP) < WatchFaceLook.bezelDp(wide))
        assertTrue(WatchFaceLook.bezelDp(260) > WatchFaceLook.bezelDp(wide))
        near(WatchFaceLook.BEZEL_MIN_DP, WatchFaceLook.bezelDp(40))
        // Relative to its face, the small one is no heavier than the 150 dp one.
        assertTrue(WatchFaceLook.bezelDp(WatchFaceRules.COVER_DP) / WatchFaceRules.COVER_DP <= WatchFaceLook.bezelDp(wide) / wide + 0.0001f)
    }

    @Test
    fun theHairlineSitsJustInsideTheBezelAndTheArcsKeepTheirOwnTrackInsideBoth() {
        listOf(WatchFaceRules.COVER_DP, DayRingHeader.WIDE_DP, 260).forEach { size ->
            val bezel = WatchFaceLook.bezelRadiusDp(size)
            val hair = WatchFaceLook.hairlineRadiusDp(size)
            assertTrue(hair < bezel - WatchFaceLook.bezelDp(size) / 2f, "hairline inside the bezel at $size")
            assertTrue(hair > bezel - WatchFaceLook.bezelDp(size) / 2f - 1f, "hairline close to the bezel at $size")
            // The arcs' track is inside the bezel, not on it.
            val arcsOuter = WatchFaceRules.rimRadiusDp(size) + WatchFaceRules.rimStrokeDp(size) / 2f
            assertTrue(arcsOuter < bezel - WatchFaceLook.bezelDp(size) / 2f, "arcs inside the bezel at $size")
            // The bezel stays on the face, clear of the week's goal ring that hugs the edge outside it.
            assertTrue(bezel + WatchFaceLook.bezelDp(size) / 2f < size / 2f)
            if (size <= DayRingHeader.WIDE_DP) {
                assertTrue(bezel + WatchFaceLook.bezelDp(size) / 2f <= GoalWeekRules.ringRadiusDp(size) - GoalWeekRules.RING_DP / 2f + 0.001f,
                    "bezel clear of the goal ring at $size")
            }
        }
        assertTrue(WatchFaceLook.HAIRLINE_ALPHA < WatchFaceLook.BEZEL_DRAW_ALPHA)
    }

    @Test
    fun theGlowIsHalfTheDayRings() {
        near(DayRingLook.EDGE_BLUR_DP / 2f, WatchFaceLook.BLUR_DP)
        near(DayRingLook.EDGE_BLUR_PEAK_ALPHA / 2f, WatchFaceLook.BLUR_PEAK_ALPHA)
        near(WatchFaceLook.BLUR_DP, WatchFaceLook.blurOffsetDp(WatchFaceLook.BLUR_LAYERS - 1, DayRingHeader.WIDE_DP))
        (1 until WatchFaceLook.BLUR_LAYERS).forEach {
            assertTrue(WatchFaceLook.blurAlpha(it, 1f) < WatchFaceLook.blurAlpha(it - 1, 1f))
        }
        assertEquals(0f, WatchFaceLook.blurAlpha(WatchFaceLook.BLUR_LAYERS, 1f))
        assertTrue(WatchFaceLook.blurAlpha(0, 0.6f) < WatchFaceLook.blurAlpha(0, 1f))
        // The live bezel starts where the opening left it and blends into the breath.
        near(WatchFaceLook.BEZEL_DRAW_ALPHA, WatchFaceLook.bezelAlpha(0.6f, 0f))
        near(0.6f, WatchFaceLook.bezelAlpha(0.6f, 1f))
        near(1f, WatchFaceLook.bezelAlpha(1.2f, 1f))
    }

    @Test
    fun theSecondHandIsAThinNeedleThroughTheCentreWithASoftGlowUnderIt() {
        val wide = DayRingHeader.WIDE_DP
        near(1f, WatchFaceLook.needleDp(wide))
        assertTrue(WatchFaceLook.needleDp(260) > 1.5f)
        near(WatchFaceLook.NEEDLE_MIN_DP, WatchFaceLook.needleDp(40))
        // Reaches the markers' outer ends, not the arcs; a counterweight of 18 % of the radius behind the centre.
        near(WatchFaceRules.rimRadiusDp(wide) - WatchFaceRules.rimStrokeDp(wide) / 2f - 2f, WatchFaceLook.needleReachDp(wide))
        near(WatchFaceRules.rimRadiusDp(wide) * 0.18f, WatchFaceLook.needleTailDp(wide))
        assertTrue(WatchFaceLook.needleReachDp(wide) > WatchFaceRules.rimRadiusDp(wide) * WatchFaceRules.MINUTE_HAND_LENGTH)
        // The glow: widest 3.5× the needle, the innermost at 35 %, fading as it widens.
        val halo = WatchFaceLook.halo(wide)
        assertEquals(3, halo.size)
        near(WatchFaceLook.needleDp(wide) * 3.5f, halo.first().widthDp)
        near(0.35f, halo.last().alpha)
        halo.zipWithNext().forEach { (a, b) -> assertTrue(a.widthDp > b.widthDp && a.alpha < b.alpha) }
        // The cap covers the needle and is smaller than the old hub.
        assertTrue(WatchFaceLook.capDp(wide) > WatchFaceLook.needleDp(wide))
        assertTrue(WatchFaceLook.capDp(wide) < WatchFaceRules.HUB_DP)
    }

    @Test
    fun quietHoursNightAndReducedMotionStillStopTheSecondHand() {
        assertEquals(DayRingLiveMode.SWEEP, WatchFaceRules.liveMode(reduced = false, powerSave = false, bedside = false, quiet = false))
        assertTrue(WatchFaceRules.liveMode(reduced = true, powerSave = false, bedside = false, quiet = false) != DayRingLiveMode.SWEEP)
        assertTrue(WatchFaceRules.liveMode(reduced = false, powerSave = false, bedside = true, quiet = true) != DayRingLiveMode.SWEEP)
        assertTrue(WatchFaceRules.liveMode(reduced = false, powerSave = false, bedside = false, quiet = false, night = true) != DayRingLiveMode.SWEEP)
    }
}
