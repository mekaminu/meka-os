package os.meka.android.designsystem

import os.meka.core.domain.DayRingPlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun pullToSyncFollowsTheFingerThenGivesAndNeverPassesItsLimit() {
        assertEquals(0f, MotionMath.pullOffsetDp(0f))
        assertEquals(0f, MotionMath.pullOffsetDp(-20f))
        var last = 0f
        for (drag in 1..600) {
            val o = MotionMath.pullOffsetDp(drag.toFloat())
            assertTrue(o > last, "pull went backwards at $drag")
            assertTrue(o < MekaChoreography.pullMaxDistanceDp, "pull passed its limit at $drag")
            assertTrue(o <= drag * MotionMath.PULL_RESISTANCE)
            last = o
        }
        // A thumb's length arms it: well under the screen's height.
        val armedAt = (1..600).first { MotionMath.pullArmed(MotionMath.pullOffsetDp(it.toFloat())) }
        assertTrue(armedAt in 80..200, "armed after $armedAt dp")
        assertFalse(MotionMath.pullArmed(MekaChoreography.pullThresholdDistanceDp - 1f))
    }

    @Test
    fun theBrassRingFillsWithThePullAndTurnsOncePerPeriodWhileSyncing() {
        assertEquals(0f, MotionMath.pullProgress(0f))
        assertEquals(0.5f, MotionMath.pullProgress(MekaChoreography.pullThresholdDistanceDp / 2f))
        assertEquals(1f, MotionMath.pullProgress(500f))
        assertEquals(0f, MotionMath.ringSweepDegrees(0f))
        assertEquals(MotionMath.RING_ARC_DEGREES, MotionMath.ringSweepDegrees(1f))
        assertTrue(MotionMath.RING_ARC_DEGREES < 360f) // the gap shows the turn
        val period = MekaChoreography.syncSpinPeriodMs.toLong()
        assertEquals(0f, MotionMath.ringSpinDegrees(0, reduced = false))
        assertEquals(180f, MotionMath.ringSpinDegrees(period / 2, reduced = false))
        assertEquals(90f, MotionMath.ringSpinDegrees(period + period / 4, reduced = false))
        assertEquals(0f, MotionMath.ringSpinDegrees(period / 2, reduced = true)) // Off: a still ring
        assertEquals(period, MotionMath.ringHoldMs(0))
        assertEquals(period - 300, MotionMath.ringHoldMs(300))
        assertEquals(0L, MotionMath.ringHoldMs(5_000))
    }

    @Test
    fun aPaneDimsTheScreenBehindItAsItSpringsUp() {
        assertEquals(0f, MotionMath.scrimAlpha(0f))
        assertEquals(MekaChoreography.sheetScrimOpacity, MotionMath.scrimAlpha(1f))
        assertEquals(MekaChoreography.sheetScrimOpacity, MotionMath.scrimAlpha(1.2f)) // a spring's overshoot never darkens more
        assertTrue(MekaChoreography.sheetScrimOpacity in 0.3f..0.6f)
    }

    @Test
    fun completingATaskSweepsTheRingThenFillsThenStrokesTheCheck() {
        assertEquals(420, MotionMath.checkDrawMs(reduced = false))
        assertEquals(0, MotionMath.checkDrawMs(reduced = true))
        // Start: nothing drawn.
        assertEquals(0f, MotionMath.checkRingDegrees(0f))
        assertEquals(0f, MotionMath.checkFill(0f))
        assertEquals(0f, MotionMath.checkStroke(0f))
        // The ring closes by half way; the fill floods in as it closes; the check hasn't started yet.
        assertEquals(360f, MotionMath.checkRingDegrees(0.5f))
        assertTrue(MotionMath.checkRingDegrees(0.25f) in 180f..360f) // eased out: fast start
        assertEquals(0f, MotionMath.checkFill(0.3f))
        assertTrue(MotionMath.checkFill(0.42f) in 0.1f..0.9f)
        assertEquals(1f, MotionMath.checkFill(0.5f))
        assertEquals(0f, MotionMath.checkStroke(0.5f))
        // Then the check strokes in and lands exactly drawn.
        assertTrue(MotionMath.checkStroke(0.75f) in 0.5f..1f)
        assertEquals(1f, MotionMath.checkStroke(1f))
        assertEquals(360f, MotionMath.checkRingDegrees(1f))
        // Overshoot never draws past done.
        assertEquals(1f, MotionMath.checkStroke(1.2f))
        assertEquals(360f, MotionMath.checkRingDegrees(1.2f))
    }

    @Test
    fun anEmptyStateRingBreathesSlowlyInAndOutAndHoldsStillWhenOff() {
        val period = MekaChoreography.emptyBreathPeriodMs.toLong()
        assertEquals(4200L, period)
        // Out at the start, fully in half way, out again after one breath; smooth and repeating.
        assertEquals(0f, MotionMath.breath(0, reduced = false), 1e-6f)
        assertEquals(1f, MotionMath.breath(period / 2, reduced = false), 1e-6f)
        assertEquals(0f, MotionMath.breath(period, reduced = false), 1e-6f)
        assertEquals(MotionMath.breath(period / 4, false), MotionMath.breath(period + period / 4, false), 1e-6f)
        assertEquals(0.5f, MotionMath.breath(period / 4, reduced = false), 1e-3f)
        assertEquals(0f, MotionMath.breath(-50, reduced = false), 1e-6f) // a clock before it appeared is "out"
        // Off: still and fully shown.
        assertEquals(1f, MotionMath.breath(0, reduced = true))
        assertEquals(1f, MotionMath.breath(period / 3, reduced = true))
        // Size and glow: from a little smaller and faint to full; never vanishes, never grows past full.
        assertEquals(MotionMath.BREATH_MIN_SCALE, MotionMath.breathScale(0f), 1e-6f)
        assertEquals(1f, MotionMath.breathScale(1f), 1e-6f)
        assertEquals(1f, MotionMath.breathScale(1.3f), 1e-6f)
        assertTrue(MotionMath.BREATH_MIN_SCALE in 0.85f..0.97f)
        assertEquals(MotionMath.BREATH_MIN_GLOW, MotionMath.breathGlow(0f), 1e-6f)
        assertEquals(1f, MotionMath.breathGlow(1f), 1e-6f)
        assertTrue(MotionMath.breathGlow(0f) > 0f)
    }

    @Test
    fun aStayingTickDrawsOnlyWhenItTurnsDoneHere() {
        // Ticked on this screen: the check draws.
        assertEquals(TickDraw.DRAW, MotionMath.tickDraw(wasDone = false, done = true, reduced = false))
        // Already done when it appears (or ticked elsewhere before the screen opened): done at once, no replay.
        assertEquals(TickDraw.DONE, MotionMath.tickDraw(wasDone = null, done = true, reduced = false))
        assertEquals(TickDraw.DONE, MotionMath.tickDraw(wasDone = true, done = true, reduced = false))
        // Unticked, or never ticked: back to the outline at once.
        assertEquals(TickDraw.REST, MotionMath.tickDraw(wasDone = true, done = false, reduced = false))
        assertEquals(TickDraw.REST, MotionMath.tickDraw(wasDone = null, done = false, reduced = false))
        // Off: never draws.
        assertEquals(TickDraw.DONE, MotionMath.tickDraw(wasDone = false, done = true, reduced = true))
        assertEquals(TickDraw.REST, MotionMath.tickDraw(wasDone = true, done = false, reduced = true))
    }

    @Test
    fun theDayRingDrawsItsMarkThenTheArcsAndNeedleThenCountsUp() {
        val full = DayRingPlay.FULL
        // Subtle: the mark draws round over 600 ms.
        assertEquals(0f, MotionMath.dayRingMark(0, full), 1e-6f)
        assertEquals(0.875f, MotionMath.dayRingMark(300, full), 1e-6f)
        assertEquals(1f, MotionMath.dayRingMark(600, full), 1e-6f)
        // Expressive runs it 1.4 times longer: 840 ms (Living Today, slice 2).
        assertEquals(0.875f, MotionMath.dayRingMark(420, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingMark(840, full, expressive = true), 1e-6f)
        assertEquals(840, MotionMath.dayRingMarkMs(true))
        assertEquals(504, MotionMath.dayRingArcMs(true))
        assertEquals(1008, MotionMath.dayRingNeedleMs(true))
        // Arcs start as the mark is halfway, one stagger step apart (Expressive 60 ms over 504 ms; Subtle 40 ms over 360 ms).
        assertEquals(0f, MotionMath.dayRingArc(420, 0, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingArc(924, 0, full, expressive = true), 1e-6f)
        assertEquals(0f, MotionMath.dayRingArc(480, 1, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingArc(984, 1, full, expressive = true), 1e-6f)
        assertEquals(0f, MotionMath.dayRingArc(340, 1, full, expressive = false), 1e-6f)
        assertEquals(1f, MotionMath.dayRingArc(700, 1, full, expressive = false), 1e-6f)
        // The needle sweeps from midnight to now from the same moment (Subtle 720 ms, Expressive 1008 ms).
        assertEquals(0f, MotionMath.dayRingNeedle(300, full), 1e-6f)
        assertEquals(1f, MotionMath.dayRingNeedle(1020, full), 1e-6f)
        assertEquals(0f, MotionMath.dayRingNeedle(420, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingNeedle(1428, full, expressive = true), 1e-6f)
        // The centre counts up once the mark has closed.
        assertEquals(0f, MotionMath.dayRingCount(840, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingCount(1740, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingCount(1300, full, expressive = false), 1e-6f)
        // It has landed when the last of them has: well over the 900 ms Meka asked for in Expressive.
        assertEquals(1740L, MotionMath.dayRingTotalMs(3, full, expressive = true))
        assertEquals(1300L, MotionMath.dayRingTotalMs(3, full, expressive = false))
        assertEquals(1740L, MotionMath.dayRingTotalMs(20, full, expressive = true))
    }

    @Test
    fun theHourMarksFadeInOneByOneBehindTheMarkOnTheFirstOpen() {
        val full = DayRingPlay.FULL
        // Halfway round (noon at the bottom): the morning's marks are up, 11 is fading in, noon hasn't begun.
        assertEquals(1f, MotionMath.dayRingHour(0.5f, 0, full), 1e-6f)
        assertEquals(1f, MotionMath.dayRingHour(0.5f, 10, full), 1e-6f)
        assertEquals(2f / 3f, MotionMath.dayRingHour(0.5f, 11, full), 1e-5f)
        assertEquals(0f, MotionMath.dayRingHour(0.5f, 12, full), 1e-6f)
        assertEquals(0f, MotionMath.dayRingHour(0.5f, 23, full), 1e-6f)
        assertEquals(1f, MotionMath.dayRingHour(1f, 23, full), 1e-6f)
        // The quick draw brings them up together with the mark; Off shows them.
        assertEquals(0.4f, MotionMath.dayRingHour(0.4f, 20, DayRingPlay.QUICK), 1e-6f)
        assertEquals(0.4f, MotionMath.dayRingHour(0.4f, 1, DayRingPlay.QUICK), 1e-6f)
        assertEquals(1f, MotionMath.dayRingHour(0f, 17, DayRingPlay.STILL), 1e-6f)
    }

    @Test
    fun theLiveTilesRiseInAsTheMarkClosesAndTheGreetingSpellsItself() {
        val full = DayRingPlay.FULL
        // Tiles: once the mark has closed (Expressive 840 ms), one stagger step apart, each over 504 ms.
        assertEquals(0f, MotionMath.dayTile(840, 0, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayTile(1344, 0, full, expressive = true), 1e-6f)
        assertEquals(0f, MotionMath.dayTile(1020, 3, full, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayTile(1524, 3, full, expressive = true), 1e-6f)
        assertEquals(0f, MotionMath.dayTile(720, 3, full, expressive = false), 1e-6f)
        assertEquals(0f, MotionMath.dayTile(225, 2, DayRingPlay.QUICK, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayTile(900, 2, DayRingPlay.QUICK, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayTile(0, 2, DayRingPlay.STILL, expressive = true), 1e-6f)
        // Four tiles land within the count-up, so the ring's total is unchanged.
        assertEquals(1740L, MotionMath.dayRingTotalMs(3, full, expressive = true, tiles = 4))
        assertEquals(1300L, MotionMath.dayRingTotalMs(3, full, expressive = false, tiles = 4))
        // Greeting letters: 28 ms apart, each over 260 ms; only on the first open of the day.
        assertEquals(0f, MotionMath.greetingLetter(0, 0, full), 1e-6f)
        assertEquals(1f, MotionMath.greetingLetter(260, 0, full), 1e-6f)
        assertEquals(0f, MotionMath.greetingLetter(56, 2, full), 1e-6f)
        assertEquals(1f, MotionMath.greetingLetter(316, 2, full), 1e-6f)
        assertEquals(736L, MotionMath.greetingTotalMs("Good morning, Meka".length, full))
        assertEquals(1f, MotionMath.greetingLetter(0, 5, DayRingPlay.QUICK), 1e-6f)
        assertEquals(1f, MotionMath.greetingLetter(0, 5, DayRingPlay.STILL), 1e-6f)
        assertEquals(0L, MotionMath.greetingTotalMs(18, DayRingPlay.QUICK))
        assertEquals(0L, MotionMath.greetingTotalMs(0, full))
    }

    @Test
    fun laterOpensDrawTheDayRingInAndOffDrawsItAtOnce() {
        val quick = DayRingPlay.QUICK
        // Expressive: the mark and needle draw round over 900 ms; arcs grow over its last three quarters; the centre counts.
        assertEquals(0f, MotionMath.dayRingMark(0, quick, expressive = true), 1e-6f)
        assertEquals(0.875f, MotionMath.dayRingMark(450, quick, expressive = true), 1e-6f)
        assertEquals(0.875f, MotionMath.dayRingNeedle(450, quick, expressive = true), 1e-6f)
        assertEquals(0f, MotionMath.dayRingArc(225, 5, quick, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingArc(900, 5, quick, expressive = true), 1e-6f)
        assertEquals(0.5f, MotionMath.dayRingCount(450, quick, expressive = true), 1e-6f)
        assertEquals(900L, MotionMath.dayRingTotalMs(9, quick, expressive = true))
        // Subtle: 500 ms.
        assertEquals(0.875f, MotionMath.dayRingMark(250, quick), 1e-6f)
        assertEquals(0f, MotionMath.dayRingArc(125, 0, quick, expressive = false), 1e-6f)
        assertEquals(1f, MotionMath.dayRingArc(500, 0, quick, expressive = false), 1e-6f)
        assertEquals(500L, MotionMath.dayRingTotalMs(9, quick, expressive = false))
        val still = DayRingPlay.STILL
        assertEquals(1f, MotionMath.dayRingMark(0, still), 1e-6f)
        assertEquals(1f, MotionMath.dayRingArc(0, 3, still, expressive = true), 1e-6f)
        assertEquals(1f, MotionMath.dayRingNeedle(0, still), 1e-6f)
        assertEquals(1f, MotionMath.dayRingCount(0, still, expressive = true), 1e-6f)
        assertEquals(0L, MotionMath.dayRingTotalMs(3, still, expressive = true))
    }

    @Test
    fun listFootFadesIntoTheBarAndTheLastRowStillScrollsClear() {
        // Fold review 2026-10-08 item 4: rows fade out over the last 24 dp instead of being cut by the capture bar.
        assertEquals(24, MotionMath.FOOT_FADE_DP)
        assertEquals(0f, MotionMath.footAlpha(0f))
        assertEquals(0.5f, MotionMath.footAlpha(12f))
        assertEquals(1f, MotionMath.footAlpha(24f))
        assertEquals(1f, MotionMath.footAlpha(400f))
        assertEquals(0f, MotionMath.footAlpha(-3f))
        // Today, Needs you and the Calendar agenda end with xl padding: more than the fade, so the last row is clear.
        assertTrue(MotionMath.footClear(MekaSpace.xl.value))
        assertFalse(MotionMath.footClear(MekaSpace.s.value))
    }

    @Test
    fun swipeColourDeepensAndIconPopsAtTheArmPoint() {
        assertEquals(96, MekaChoreography.swipeArmDistanceDp)
        assertEquals(1.25f, MekaChoreography.swipeIconPopScale)
        // Progress: 0 at rest, half way at 48 dp either way, capped at 1 past the arm point.
        assertEquals(0f, MotionMath.swipeProgress(0f))
        assertEquals(0.5f, MotionMath.swipeProgress(48f), 1e-6f)
        assertEquals(0.5f, MotionMath.swipeProgress(-48f), 1e-6f)
        assertEquals(1f, MotionMath.swipeProgress(300f))
        // Armed only at the arm distance, either way.
        assertEquals(0, MotionMath.swipeArmed(95.9f))
        assertEquals(1, MotionMath.swipeArmed(96f))
        assertEquals(-1, MotionMath.swipeArmed(-120f))
        // The colour: nothing at rest, a light wash once it moves, full at the arm point.
        assertEquals(0f, MotionMath.swipeTint(0f))
        assertEquals(0.3f, MotionMath.swipeTint(0.0001f), 1e-3f)
        assertEquals(0.65f, MotionMath.swipeTint(0.5f), 1e-6f)
        assertEquals(1f, MotionMath.swipeTint(1f), 1e-6f)
        // The icon grows 0.6 → 1 with the swipe and pops to 1.25 when armed; Off: always full size.
        assertEquals(0.6f, MotionMath.swipeIconScale(0f, armed = false, reduced = false), 1e-6f)
        assertEquals(0.8f, MotionMath.swipeIconScale(0.5f, armed = false, reduced = false), 1e-6f)
        assertEquals(1.25f, MotionMath.swipeIconScale(1f, armed = true, reduced = false), 1e-6f)
        assertEquals(1f, MotionMath.swipeIconScale(1f, armed = true, reduced = true))
        assertEquals(1f, MotionMath.swipeIconScale(0.2f, armed = false, reduced = true))
        // Icon and label fade in over the first 40 % of the way.
        assertEquals(0.5f, MotionMath.swipeIconAlpha(0.2f), 1e-6f)
        assertEquals(1f, MotionMath.swipeIconAlpha(0.6f))
        // Needs you's card takes on at most 18 % of the move's colour.
        assertEquals(0f, MotionMath.swipeWash(0f))
        assertEquals(0.09f, MotionMath.swipeWash(0.5f), 1e-6f)
        assertEquals(0.18f, MotionMath.swipeWash(2f), 1e-6f)
    }

    @Test
    fun theDetailGrowsOutOfTheTappedRow() {
        val pane = Bounds(0f, 100f, 400f, 900f)
        // The row, relative to the pane.
        assertEquals(Bounds(16f, 200f, 384f, 260f), MotionMath.containerOrigin(Bounds(16f, 300f, 384f, 360f), pane))
        // Half scrolled off the top: grows from the part that shows.
        assertEquals(Bounds(16f, 0f, 384f, 20f), MotionMath.containerOrigin(Bounds(16f, 80f, 384f, 120f), pane))
        // No row (search, a notification) or none of it shows: the sheet springs up as before.
        assertNull(MotionMath.containerOrigin(null, pane))
        assertNull(MotionMath.containerOrigin(Bounds(16f, 20f, 384f, 90f), pane))
        assertNull(MotionMath.containerOrigin(Bounds(16f, 300f, 16f, 360f), pane))
        // Edge by edge from the row to the pane; the spring's overshoot never grows it past the pane.
        val from = Bounds(16f, 200f, 384f, 260f)
        val to = Bounds(0f, 0f, 400f, 800f)
        assertEquals(from, MotionMath.containerBounds(from, to, 0f))
        assertEquals(Bounds(8f, 100f, 392f, 530f), MotionMath.containerBounds(from, to, 0.5f))
        assertEquals(to, MotionMath.containerBounds(from, to, 1.08f))
        assertEquals(from, MotionMath.containerBounds(from, to, -0.1f))
        // Corners ease from the row's rounding to the pane's.
        assertEquals(12f, MotionMath.containerCorner(8f, 16f, 0.5f), 1e-6f)
        assertEquals(0f, MotionMath.containerCorner(8f, 0f, 1f), 1e-6f)
        // The content stays hidden while it is row-sized, then fades in between 20 % and 60 %.
        assertEquals(0f, MotionMath.containerContentAlpha(0.2f))
        assertEquals(0.5f, MotionMath.containerContentAlpha(0.4f), 1e-6f)
        assertEquals(1f, MotionMath.containerContentAlpha(0.6f))
        assertEquals(1f, MotionMath.containerContentAlpha(1.05f))
    }

    @Test
    fun tabIconsFillAndSwellAsTheyMorph() {
        assertEquals(1.15f, MekaChoreography.tabIconPopScale)
        // Fill follows the morph, clamped against the spring's overshoot.
        assertEquals(0f, MotionMath.tabFill(-0.05f))
        assertEquals(0.4f, MotionMath.tabFill(0.4f), 1e-6f)
        assertEquals(1f, MotionMath.tabFill(1.1f))
        // Full size at both ends, the full swell halfway (lighting or unlighting); Off: never swells.
        assertEquals(1f, MotionMath.tabIconScale(0f, reduced = false), 1e-6f)
        assertEquals(1.15f, MotionMath.tabIconScale(0.5f, reduced = false), 1e-6f)
        assertEquals(1.1125f, MotionMath.tabIconScale(0.25f, reduced = false), 1e-6f)
        assertEquals(1f, MotionMath.tabIconScale(1.08f, reduced = false), 1e-6f)
        assertEquals(1f, MotionMath.tabIconScale(0.5f, reduced = true))
        // The flood grows from half the shape to all of it.
        assertEquals(0.5f, MotionMath.tabFillGrow(0f), 1e-6f)
        assertEquals(0.75f, MotionMath.tabFillGrow(0.5f), 1e-6f)
        assertEquals(1f, MotionMath.tabFillGrow(1.2f), 1e-6f)
    }
}
