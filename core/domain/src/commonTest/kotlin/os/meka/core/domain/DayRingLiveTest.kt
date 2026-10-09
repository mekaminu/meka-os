package os.meka.core.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayRingLiveTest {
    // Thu 8 Oct 2026 21:00:00 UTC (22:00 in London, BST).
    private val nine = 1_791_493_200_000L
    private val hour = 3_600_000L

    private fun near(expected: Float, actual: Float, tol: Float = 0.01f) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected, was $actual")

    @Test
    fun theSecondHandSweepsOnceAMinuteFromTheTopSmoothly() {
        near(0f, DayRingLive.handDegrees(nine))
        near(90f, DayRingLive.handDegrees(nine + 15_000))
        near(180f, DayRingLive.handDegrees(nine + 30_000))
        near(270f, DayRingLive.handDegrees(nine + 45_000))
        near(0f, DayRingLive.handDegrees(nine + 60_000))
        // Smooth, not ticking: it moves between seconds.
        near(1.5f, DayRingLive.handDegrees(nine + 250))
        assertTrue(DayRingLive.handDegrees(nine + 16) > DayRingLive.handDegrees(nine))
        // Before 1970 still lands on the dial.
        near(90f, DayRingLive.handDegrees(-45_000))
    }

    @Test
    fun theEdgeBreathesBetweenSixtyAndAHundredPercentOverFiveSeconds() {
        near(DayRingLive.GLOW_LOW, DayRingLive.glow(nine))
        near(DayRingLive.GLOW_HIGH, DayRingLive.glow(nine + 2_500))
        near(DayRingLive.GLOW_LOW, DayRingLive.glow(nine + 5_000))
        near(0.8f, DayRingLive.glow(nine + 1_250))
        (0L until 5_000L step 37).forEach { t ->
            val g = DayRingLive.glow(nine + t)
            assertTrue(g >= DayRingLive.GLOW_LOW - 0.001f && g <= DayRingLive.GLOW_HIGH + 0.001f)
        }
        // Calm: never more than a small change between two frames.
        (0L until 5_000L step 16).forEach { t ->
            assertTrue(abs(DayRingLive.glow(nine + t + 16) - DayRingLive.glow(nine + t)) < 0.01f)
        }
    }

    @Test
    fun theShimmerRunsOnceAtTheTopOfEachLocalHour() {
        assertEquals(0f, DayRingLive.shimmer(nine, hour))
        val mid = DayRingLive.shimmer(nine + 900, hour)!!
        assertTrue(mid > 0.5f && mid < 1f)
        assertNull(DayRingLive.shimmer(nine + DayRingLive.SHIMMER_MS, hour))
        assertNull(DayRingLive.shimmer(nine + 30 * 60_000L, hour))
        // A half-hour zone (India, +5:30): its hours start on UTC's half hours.
        assertNull(DayRingLive.shimmer(nine, 5 * hour + hour / 2))
        assertEquals(0f, DayRingLive.shimmer(nine + hour / 2, 5 * hour + hour / 2))
        // Its head only moves forward.
        var last = -1f
        (0L until DayRingLive.SHIMMER_MS step 50).forEach { t ->
            val s = DayRingLive.shimmer(nine + t, 0)!!
            assertTrue(s >= last)
            last = s
        }
    }

    @Test
    fun theNowDotPopsAsTheMinuteTurnsAndSettles() {
        near(1f, DayRingLive.nowPop(nine))
        val peak = (0L..DayRingLive.NOW_POP_MS).maxOf { DayRingLive.nowPop(nine + it) }
        assertTrue(peak > 1.35f && peak <= 1f + DayRingLive.NOW_POP_SCALE + 0.01f, "peak $peak")
        val dip = (0L..DayRingLive.NOW_POP_MS).minOf { DayRingLive.nowPop(nine + it) }
        assertTrue(dip < 1f && dip > 0.9f, "a small overshoot below rest, was $dip")
        assertEquals(1f, DayRingLive.nowPop(nine + DayRingLive.NOW_POP_MS))
        assertEquals(1f, DayRingLive.nowPop(nine + 30_000))
    }

    @Test
    fun theHandFadesInAndItsTailFadesAway() {
        assertEquals(0f, DayRingLive.handFade(0))
        near(0.5f, DayRingLive.handFade(DayRingLive.HAND_FADE_MS / 2))
        assertEquals(1f, DayRingLive.handFade(10_000))
        assertTrue(DayRingLive.tailAlpha(0) > DayRingLive.tailAlpha(1))
        (1 until DayRingLive.TAIL_SEGMENTS).forEach { assertTrue(DayRingLive.tailAlpha(it) < DayRingLive.tailAlpha(it - 1)) }
        near(0f, DayRingLive.tailAlpha(DayRingLive.TAIL_SEGMENTS))
    }

    @Test
    fun offIsStillPowerSavingRedrawsEachMinuteOtherwiseItSweeps() {
        assertEquals(DayRingLiveMode.SWEEP, DayRingLive.mode(reduced = false, powerSave = false))
        assertEquals(DayRingLiveMode.MINUTE, DayRingLive.mode(reduced = false, powerSave = true))
        assertEquals(DayRingLiveMode.STILL, DayRingLive.mode(reduced = true, powerSave = false))
        assertEquals(DayRingLiveMode.STILL, DayRingLive.mode(reduced = true, powerSave = true))
        assertEquals(16L, DayRingLive.nextDrawInMs(DayRingLiveMode.SWEEP, nine))
        assertEquals(45_000L, DayRingLive.nextDrawInMs(DayRingLiveMode.MINUTE, nine + 15_000))
        assertNull(DayRingLive.nextDrawInMs(DayRingLiveMode.STILL, nine))
    }

    @Test
    fun theBedsideRingBreathesSlowerAndKeepsStillInQuietHours() {
        // The same 60 % → 100 % → 60 %, over 8 s instead of 5.
        near(DayRingLive.GLOW_LOW, DayRingLive.bedsideGlow(nine))
        near(DayRingLive.GLOW_HIGH, DayRingLive.bedsideGlow(nine + 4_000))
        near(DayRingLive.GLOW_LOW, DayRingLive.bedsideGlow(nine + 8_000))
        assertTrue(DayRingLive.bedsideGlow(nine + 2_500) < DayRingLive.glow(nine + 2_500))
        (0L until 8_000L step 41).forEach { t ->
            val g = DayRingLive.bedsideGlow(nine + t)
            assertTrue(g >= DayRingLive.GLOW_LOW - 0.001f && g <= DayRingLive.GLOW_HIGH + 0.001f)
        }
        // Awake: sweeps like Today's; power saving once a minute; Off still.
        assertEquals(DayRingLiveMode.SWEEP, DayRingLive.bedsideMode(reduced = false, powerSave = false, quiet = false))
        assertEquals(DayRingLiveMode.MINUTE, DayRingLive.bedsideMode(reduced = false, powerSave = true, quiet = false))
        assertEquals(DayRingLiveMode.STILL, DayRingLive.bedsideMode(reduced = true, powerSave = false, quiet = false))
        // Quiet hours: no sweeping hand in a dark bedroom, the ring still redrawn each minute; Off stays still.
        assertEquals(DayRingLiveMode.MINUTE, DayRingLive.bedsideMode(reduced = false, powerSave = false, quiet = true))
        assertEquals(DayRingLiveMode.STILL, DayRingLive.bedsideMode(reduced = true, powerSave = false, quiet = true))
        assertTrue(DayRingLive.BEDSIDE_QUIET_ALPHA in 0.2f..0.6f)
    }
}
