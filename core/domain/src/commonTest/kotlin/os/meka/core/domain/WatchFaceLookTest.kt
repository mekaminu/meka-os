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
    fun theBezelIsACrispLineThatNeverFadesToNothing() {
        val wide = DayRingHeader.WIDE_DP
        near(1.5f, WatchFaceLook.bezelDp(wide))
        // Meka 12:13: 1.2–1.5 dp on Today's faces, never under 1.2 however small; heavier only on the bedside's.
        listOf(40, WatchFaceRules.COVER_DP, 96, wide).forEach {
            assertTrue(WatchFaceLook.bezelDp(it) in 1.2f..1.5f, "bezel ${WatchFaceLook.bezelDp(it)} at $it")
        }
        assertTrue(WatchFaceLook.bezelDp(260) > WatchFaceLook.bezelDp(wide))
        // Still thinner than the old 3 dp track it replaced.
        assertTrue(WatchFaceLook.bezelDp(wide) < DayRingLook.TRACK_STROKE_DP)
        // 0.9 alpha, on the opening and once landed, whatever the breath.
        near(0.9f, WatchFaceLook.BEZEL_DRAW_ALPHA)
        near(0.9f, WatchFaceLook.bezelAlpha(0.6f, 0f))
        near(0.9f, WatchFaceLook.bezelAlpha(1f, 1f))
    }

    @Test
    fun theArcsAreNoHeavierThanTheBezelSoTheRimReadsAsOneThinCircle() {
        listOf(WatchFaceRules.COVER_DP, 96, DayRingHeader.WIDE_DP, 260).forEach {
            val arc = WatchFaceLook.arcStrokeDp(it)
            assertTrue(arc <= 1.5f && arc >= 1.2f, "arc $arc at $it")
            assertTrue(arc <= WatchFaceLook.bezelDp(it) + 0.001f)
            // On their own track inside the bezel.
            assertTrue(WatchFaceRules.rimRadiusDp(it) + arc / 2f < WatchFaceLook.bezelRadiusDp(it) - WatchFaceLook.bezelDp(it) / 2f)
        }
        assertTrue(WatchFaceLook.WORK_ALPHA < WatchFaceRules.arcAlpha(DayArcKind.TASK))
        // What's on now glows faintly, not a thick sweep.
        assertTrue(WatchFaceLook.arcStrokeDp(WatchFaceRules.COVER_DP) + WatchFaceLook.NOW_GLOW_EXTRA_DP <= 4.5f)
        assertTrue(WatchFaceLook.NOW_GLOW_ALPHA <= 0.25f)
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
    fun theBezelsGlowIsVisibleAtTheCoverSizeAndNeverShrinksWithTheFace() {
        val cover = WatchFaceRules.COVER_DP
        // ~6 dp out, peak ~0.25, the same on the 78 dp face as on the 150 dp one (Meka 12:13: "it doesn't glow").
        near(6f, WatchFaceLook.blurOffsetDp(WatchFaceLook.BLUR_LAYERS - 1, cover))
        near(WatchFaceLook.blurOffsetDp(0, DayRingHeader.WIDE_DP), WatchFaceLook.blurOffsetDp(0, cover))
        assertTrue(WatchFaceLook.blurOffsetDp(0, 260) > WatchFaceLook.blurOffsetDp(0, DayRingHeader.WIDE_DP))
        near(0.25f, WatchFaceLook.blurAlpha(0, 1f))
        (1 until WatchFaceLook.BLUR_LAYERS).forEach {
            assertTrue(WatchFaceLook.blurAlpha(it, 1f) < WatchFaceLook.blurAlpha(it - 1, 1f))
        }
        assertEquals(0f, WatchFaceLook.blurAlpha(WatchFaceLook.BLUR_LAYERS, 1f))
        assertTrue(WatchFaceLook.blurAlpha(0, 0.6f) < WatchFaceLook.blurAlpha(0, 1f))
        // Even at the breath's low the inner glow is well past the old 0.11 peak.
        assertTrue(WatchFaceLook.blurAlpha(0, 0.6f) > 0.11f)
        // A canvas bleeding by bleedDp holds the whole glow.
        assertTrue(WatchFaceLook.bleedDp(cover) >= 6f)
        assertTrue(cover / 2f + WatchFaceLook.bleedDp(cover) >=
            WatchFaceLook.bezelRadiusDp(cover) + WatchFaceLook.blurOffsetDp(WatchFaceLook.BLUR_LAYERS - 1, cover))
    }

    @Test
    fun theSecondHandCrossesTheWholeDialWithAVisibleGlowAtEverySize() {
        listOf(40, WatchFaceRules.COVER_DP, 96, DayRingHeader.WIDE_DP, 260).forEach { size ->
            val needle = WatchFaceLook.needleDp(size)
            assertTrue(needle >= 1.2f, "needle $needle at $size")
            // Right across: past the markers and the arcs, to just inside the hairline and the bezel.
            val reach = WatchFaceLook.needleReachDp(size)
            val markersOuter = WatchFaceRules.rimRadiusDp(size) - WatchFaceRules.rimStrokeDp(size) / 2f - 2f
            assertTrue(reach > markersOuter, "reach $reach past the markers at $size")
            assertTrue(reach > WatchFaceRules.rimRadiusDp(size), "reach past the arcs at $size")
            assertTrue(reach < WatchFaceLook.hairlineRadiusDp(size), "inside the hairline at $size")
            assertTrue(reach > WatchFaceLook.bezelRadiusDp(size) - WatchFaceLook.bezelDp(size) - 2f, "just inside the bezel at $size")
            near(WatchFaceRules.rimRadiusDp(size) * 0.25f, WatchFaceLook.needleTailDp(size))
            // The glow: three blurred layers out to ~5 dp, 15 % → 50 % towards the needle, never collapsing.
            val halo = WatchFaceLook.halo(size)
            assertEquals(3, halo.size)
            assertTrue(halo.first().widthDp >= 5f - 0.001f, "glow ${halo.first().widthDp} at $size")
            assertTrue(halo.first().widthDp >= needle + 2f)
            near(0.15f, halo.first().alpha)
            near(0.5f, halo.last().alpha)
            halo.zipWithNext().forEach { (a, b) -> assertTrue(a.widthDp > b.widthDp && a.alpha < b.alpha) }
            assertTrue(halo.all { it.blurDp > 0f && it.widthDp > needle })
        }
        near(5f, WatchFaceLook.halo(WatchFaceRules.COVER_DP).first().widthDp)
        assertEquals(0xFFECD79EL, WatchFaceLook.NEEDLE_DARK_ARGB)
        val wide = DayRingHeader.WIDE_DP
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
