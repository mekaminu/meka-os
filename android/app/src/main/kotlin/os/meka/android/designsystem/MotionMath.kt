package os.meka.android.designsystem

import os.meka.core.domain.DayRingPlay
import kotlin.math.roundToInt

/**
 * The arithmetic behind MEKA's choreography, kept free of Compose so it is unit-tested and matches the Mac
 * (macos/MekaOS/DesignSystem/MekaMotionKit.swift) number for number.
 */
object MotionMath {
    /**
     * Delay before item [index] of a staggered group appears. Capped so long lists never feel slow; zero when reduced.
     * Expressive (Appearance → Motion) spaces items wider apart.
     */
    fun staggerDelayMs(index: Int, reduced: Boolean, expressive: Boolean = false): Int =
        if (reduced || index <= 0) 0
        else minOf(index, MekaChoreography.staggerMaxSteps) *
            (if (expressive) MekaChoreography.expressiveStaggerStepMs else MekaChoreography.staggerStepMs)

    /** Total time a staggered group of [count] items takes to start appearing (used to end the intro). */
    fun staggerSpanMs(count: Int, reduced: Boolean, expressive: Boolean = false): Int =
        if (count <= 0) 0 else staggerDelayMs(count - 1, reduced, expressive)

    /** How far (dp) an appearing item rises from: further in Expressive. */
    fun riseDistanceDp(expressive: Boolean): Int =
        if (expressive) MekaChoreography.expressiveRiseDistanceDp else MekaChoreography.riseDistanceDp

    /** How long a count-up runs: longer in Expressive. */
    fun countUpMs(expressive: Boolean): Int = if (expressive) MekaChoreography.expressiveCountUpMs else MekaChoreography.countUpMs

    /**
     * Scale of an appearing item: Expressive grows it from [MekaChoreography.expressiveEntryScale] to full size as it
     * rises; Subtle and reduced motion never scale. A spring's overshoot never grows it past full size.
     */
    fun entryScale(progress: Float, expressive: Boolean, reduced: Boolean): Float {
        if (reduced || !expressive) return 1f
        val from = MekaChoreography.expressiveEntryScale
        return from + (1f - from) * progress.coerceIn(0f, 1f)
    }

    /** Ease-out cubic: fast start, gentle landing. The curve count-ups and rises use. */
    fun easeOutCubic(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val inv = 1f - f
        return 1f - inv * inv * inv
    }

    /** The number a count-up shows at [fraction] of the way from [from] to [to]. Lands exactly on [to]. */
    fun countUpValue(from: Int, to: Int, fraction: Float): Int {
        if (fraction >= 1f) return to
        return (from + (to - from) * easeOutCubic(fraction)).roundToInt()
    }

    /** Vertical offset of an appearing item: starts [distance] below, ends at 0. No movement when reduced. */
    fun riseOffset(progress: Float, distance: Float, reduced: Boolean): Float =
        if (reduced) 0f else (1f - progress.coerceIn(0f, 1f)) * distance

    /**
     * Scale of anything tappable (feedback motion, motion pass 2): presses in to [MekaChoreography.pressScale] while
     * held, in Subtle and Expressive alike; Off never scales (a brief dim instead, [pressDim]).
     */
    fun pressScale(pressed: Boolean, reduced: Boolean): Float =
        if (pressed && !reduced) MekaChoreography.pressScale else 1f

    /** Opacity of the dim drawn over a held item: a faint veil, a little stronger with Motion → Off (no scale then). */
    fun pressDim(pressed: Boolean, reduced: Boolean): Float = when {
        !pressed -> 0f
        reduced -> 0.12f
        else -> 0.06f
    }

    /** How far (dp) a card lifts while the pointer is over it (Mac; the Fold has no hover). None when reduced. */
    fun hoverLiftDp(hovering: Boolean, reduced: Boolean): Int =
        if (hovering && !reduced) MekaChoreography.hoverLiftDp else 0

    /**
     * Pull to sync (motion pass 2, slice 3): how far (dp) Today follows a finger dragged [dragDp] down past its top.
     * Rubber-banded: it starts at about the finger's pace and slows, never passing [MekaChoreography.pullMaxDistanceDp].
     */
    fun pullOffsetDp(dragDp: Float): Float {
        if (dragDp <= 0f) return 0f
        val max = MekaChoreography.pullMaxDistanceDp.toFloat()
        return max * (1f - 1f / (dragDp * PULL_RESISTANCE / max + 1f))
    }

    /** How full the brass ring is for a pull that has gone [offsetDp]: 0 at rest, 1 once letting go would sync. */
    fun pullProgress(offsetDp: Float): Float =
        (offsetDp / MekaChoreography.pullThresholdDistanceDp).coerceIn(0f, 1f)

    /** Whether letting go now syncs. */
    fun pullArmed(offsetDp: Float): Boolean = offsetDp >= MekaChoreography.pullThresholdDistanceDp

    /** The ring's arc (degrees) as it fills with the pull: up to [RING_ARC_DEGREES], leaving a gap that shows the spin. */
    fun ringSweepDegrees(progress: Float): Float = RING_ARC_DEGREES * progress.coerceIn(0f, 1f)

    /** The ring's turn (degrees) [elapsedMs] into a sync: one turn per [MekaChoreography.syncSpinPeriodMs]; still when reduced. */
    fun ringSpinDegrees(elapsedMs: Long, reduced: Boolean): Float =
        if (reduced || elapsedMs <= 0) 0f
        else (elapsedMs % MekaChoreography.syncSpinPeriodMs).toFloat() / MekaChoreography.syncSpinPeriodMs * 360f

    /** How much longer (ms) the ring keeps spinning after a sync that took [elapsedMs], so a quick sync is still seen. */
    fun ringHoldMs(elapsedMs: Long): Long = (MekaChoreography.syncSpinPeriodMs - elapsedMs).coerceAtLeast(0L)

    /** Opacity of the dim behind a pane springing up ([progress] 0 → 1); the same with Motion → Off (it fades either way). */
    fun scrimAlpha(progress: Float): Float = MekaChoreography.sheetScrimOpacity * progress.coerceIn(0f, 1f)

    /**
     * Completing a task (motion pass 2, slice 4): how long the ring-and-check draw runs before the row leaves.
     * Motion → Off: no draw, it is shown done at once.
     */
    fun checkDrawMs(reduced: Boolean): Int = if (reduced) 0 else MekaChoreography.checkDrawMs

    /** The accent ring's sweep (degrees) [fraction] of the way through the draw: once round in the first half. */
    fun checkRingDegrees(fraction: Float): Float = 360f * easeOutCubic(fraction / CHECK_RING_END)

    /** How solid the fill inside the ring is: it floods in as the ring closes. */
    fun checkFill(fraction: Float): Float =
        ((fraction - CHECK_FILL_START) / (CHECK_STROKE_START - CHECK_FILL_START)).coerceIn(0f, 1f)

    /** How much of the check's stroke is drawn (0 → 1): it strokes in over the second half, short leg first. */
    fun checkStroke(fraction: Float): Float =
        easeOutCubic((fraction - CHECK_STROKE_START) / (1f - CHECK_STROKE_START))

    /**
     * A tick that stays ticked (motion pass 2, slice 6: a habit, a routine step, Went on the Gym card): what the check
     * does when [done] is seen, given what it was before ([wasDone], null the first time it is shown). Only a change to
     * done draws ([checkDrawMs]); something already done when it appears (or ticked on the other device before this
     * screen opened) shows done at once, and unticking returns to rest at once. Motion → Off: never draws.
     */
    fun tickDraw(wasDone: Boolean?, done: Boolean, reduced: Boolean): TickDraw = when {
        !done -> TickDraw.REST
        wasDone != false || reduced -> TickDraw.DONE
        else -> TickDraw.DRAW
    }

    /**
     * Empty states (motion pass 2, slice 5; catalogue "Empty states"): how far into a breath the brass ring is
     * [elapsedMs] after it appeared, 0 (out) → 1 (in) → 0 over [MekaChoreography.emptyBreathPeriodMs], a smooth cosine.
     * Motion → Off: held still, fully in.
     */
    fun breath(elapsedMs: Long, reduced: Boolean): Float {
        if (reduced) return 1f
        val period = MekaChoreography.emptyBreathPeriodMs
        val phase = (elapsedMs.coerceAtLeast(0L) % period).toFloat() / period
        return (0.5f - 0.5f * kotlin.math.cos(2f * kotlin.math.PI.toFloat() * phase)).coerceIn(0f, 1f)
    }

    /** The ring's size at [breath] (0 → 1): from [BREATH_MIN_SCALE] out to full size in. */
    fun breathScale(breath: Float): Float = BREATH_MIN_SCALE + (1f - BREATH_MIN_SCALE) * breath.coerceIn(0f, 1f)

    /** How strongly the ring's glow shows at [breath]: never gone, so it reads as resting, not blinking. */
    fun breathGlow(breath: Float): Float = BREATH_MIN_GLOW + (1f - BREATH_MIN_GLOW) * breath.coerceIn(0f, 1f)

    /**
     * The opening moment's Day ring (motion pass 2, slice 7; catalogue "Opening moment"). [DayRingPlay.FULL] (the
     * first open of the day): the brass mark (the ring's track) draws round over [MekaChoreography.dayRingMarkMs];
     * halfway through, the arcs grow in one after another (the stagger apart, each over `dayRingArc`) and the now
     * needle sweeps from midnight to now (`dayRingNeedle`); once the mark has closed the centre counts up
     * ([countUpMs]). [DayRingPlay.QUICK]: everything within `dayRingQuick`. [DayRingPlay.STILL] (Off): drawn at once.
     * Each returns 0 → 1 for [elapsedMs] since Today appeared.
     */
    fun dayRingMark(elapsedMs: Long, play: DayRingPlay): Float = when (play) {
        DayRingPlay.STILL -> 1f
        DayRingPlay.QUICK -> 1f
        DayRingPlay.FULL -> easeOutCubic(elapsedMs.toFloat() / MekaChoreography.dayRingMarkMs)
    }

    /** How far arc [index] (clockwise order) has grown from its start. */
    fun dayRingArc(elapsedMs: Long, index: Int, play: DayRingPlay, expressive: Boolean): Float = when (play) {
        DayRingPlay.STILL -> 1f
        DayRingPlay.QUICK -> easeOutCubic(elapsedMs.toFloat() / MekaChoreography.dayRingQuickMs)
        DayRingPlay.FULL -> {
            val begin = MekaChoreography.dayRingMarkMs / 2 + staggerDelayMs(index, false, expressive)
            easeOutCubic((elapsedMs - begin).toFloat() / MekaChoreography.dayRingArcMs)
        }
    }

    /** How far the now needle has swept from midnight (the top) towards now. */
    fun dayRingNeedle(elapsedMs: Long, play: DayRingPlay): Float = when (play) {
        DayRingPlay.STILL -> 1f
        DayRingPlay.QUICK -> easeOutCubic(elapsedMs.toFloat() / MekaChoreography.dayRingQuickMs)
        DayRingPlay.FULL -> easeOutCubic((elapsedMs - MekaChoreography.dayRingMarkMs / 2).toFloat() / MekaChoreography.dayRingNeedleMs)
    }

    /** The centre's count-up fraction (linear; [countUpValue] eases it). */
    fun dayRingCount(elapsedMs: Long, play: DayRingPlay, expressive: Boolean): Float = when (play) {
        DayRingPlay.STILL -> 1f
        DayRingPlay.QUICK -> (elapsedMs.toFloat() / MekaChoreography.dayRingQuickMs).coerceIn(0f, 1f)
        DayRingPlay.FULL -> ((elapsedMs - MekaChoreography.dayRingMarkMs).toFloat() / countUpMs(expressive)).coerceIn(0f, 1f)
    }

    /** When the whole opening has landed for a ring of [arcs] arcs and [tiles] live tiles, so the frame clock can stop. */
    fun dayRingTotalMs(arcs: Int, play: DayRingPlay, expressive: Boolean, tiles: Int = 0): Long = when (play) {
        DayRingPlay.STILL -> 0L
        DayRingPlay.QUICK -> MekaChoreography.dayRingQuickMs.toLong()
        DayRingPlay.FULL -> maxOf(
            MekaChoreography.dayRingMarkMs / 2 + staggerSpanMs(arcs, false, expressive) + MekaChoreography.dayRingArcMs,
            MekaChoreography.dayRingMarkMs / 2 + MekaChoreography.dayRingNeedleMs,
            MekaChoreography.dayRingMarkMs + countUpMs(expressive),
            if (tiles > 0) MekaChoreography.dayRingMarkMs + staggerSpanMs(tiles, false, expressive) + MekaChoreography.dayRingArcMs else 0,
        ).toLong()
    }

    /**
     * The live tiles under the Day ring (the opening moment, part 2): [DayRingPlay.FULL] — once the mark has closed,
     * tile [index] fades and rises in (the stagger apart, each over `dayRingArc`) while its number counts up with the
     * centre ([dayRingCount]); [DayRingPlay.QUICK] within `dayRingQuick`; [DayRingPlay.STILL] shown at once.
     */
    fun dayTile(elapsedMs: Long, index: Int, play: DayRingPlay, expressive: Boolean): Float = when (play) {
        DayRingPlay.STILL -> 1f
        DayRingPlay.QUICK -> easeOutCubic(elapsedMs.toFloat() / MekaChoreography.dayRingQuickMs)
        DayRingPlay.FULL -> {
            val begin = MekaChoreography.dayRingMarkMs + staggerDelayMs(index, false, expressive)
            easeOutCubic((elapsedMs - begin).toFloat() / MekaChoreography.dayRingArcMs)
        }
    }

    /**
     * The greeting's letters on the first open of the day ([DayRingPlay.FULL]): letter [index] fades in
     * `greetingLetter` after the one before, over `greetingLetterFade`. Later opens and Off: shown at once (1).
     */
    fun greetingLetter(elapsedMs: Long, index: Int, play: DayRingPlay): Float = when (play) {
        DayRingPlay.FULL -> easeOutCubic(
            (elapsedMs - index.coerceAtLeast(0).toLong() * MekaChoreography.greetingLetterMs).toFloat() / MekaChoreography.greetingLetterFadeMs,
        )
        else -> 1f
    }

    /** When the last of [letters] letters has faded in, so the frame clock can stop. */
    fun greetingTotalMs(letters: Int, play: DayRingPlay): Long =
        if (play != DayRingPlay.FULL || letters <= 0) 0L
        else (letters - 1).toLong() * MekaChoreography.greetingLetterMs + MekaChoreography.greetingLetterFadeMs

    /** How far (dp) a letter rises as it fades in. */
    const val GREETING_LETTER_RISE_DP = 6f

    /** The breathing ring's smallest size and faintest glow. */
    const val BREATH_MIN_SCALE = 0.92f
    const val BREATH_MIN_GLOW = 0.35f

    /** Where the ring has closed (fraction of the draw). */
    const val CHECK_RING_END = 0.5f
    /** Where the fill starts flooding in, just before the ring closes. */
    const val CHECK_FILL_START = 0.35f
    /** Where the check starts to stroke in (the fill is solid by then). */
    const val CHECK_STROKE_START = 0.5f

    /** The pull's give: the finger travels about this much further than the content at first. */
    const val PULL_RESISTANCE = 1.2f

    /** The brass ring's longest arc, so the turning gap reads as motion. */
    const val RING_ARC_DEGREES = 300f

    /**
     * A list's foot above a bar (Today's capture bar, the tabs): rows fade out over the last [FOOT_FADE_DP] instead of
     * being sliced by the bar mid-row (Fold review 2026-10-08, item 4). The bar sits under the list, never over it.
     */
    const val FOOT_FADE_DP = 24

    /** Opacity of a row's pixel [fromFootDp] above the list's foot: 0 at the foot, fully shown from [FOOT_FADE_DP] up. */
    fun footAlpha(fromFootDp: Float): Float = (fromFootDp / FOOT_FADE_DP).coerceIn(0f, 1f)

    /**
     * True when the list's bottom padding keeps the last row out of the fade once scrolled to the end, so the fade
     * can stay on all the time and the last row still scrolls fully clear of the bar.
     */
    fun footClear(bottomPaddingDp: Float): Boolean = bottomPaddingDp >= FOOT_FADE_DP

    /**
     * Swipe actions (motion pass 2, feedback motion; catalogue "Swipe actions"): how far a row (or Needs you's card)
     * swiped [dxDp] sideways is towards arming, 0 → 1 at [MekaChoreography.swipeArmDistanceDp].
     */
    fun swipeProgress(dxDp: Float): Float = (kotlin.math.abs(dxDp) / MekaChoreography.swipeArmDistanceDp).coerceIn(0f, 1f)

    /** Which way a swipe of [dxDp] is armed: 1 (right), -1 (left), or 0 short of [MekaChoreography.swipeArmDistanceDp]. */
    fun swipeArmed(dxDp: Float): Int = when {
        dxDp >= MekaChoreography.swipeArmDistanceDp -> 1
        dxDp <= -MekaChoreography.swipeArmDistanceDp -> -1
        else -> 0
    }

    /**
     * Opacity of the action's colour behind the row: nothing at rest, a [SWIPE_MIN_TINT] wash as soon as it moves,
     * deepening to the full colour at the arm point. The same with Motion → Off (colour, not movement).
     */
    fun swipeTint(progress: Float): Float =
        if (progress <= 0f) 0f else SWIPE_MIN_TINT + (1f - SWIPE_MIN_TINT) * progress.coerceIn(0f, 1f)

    /**
     * Scale of the action's icon: grows from [SWIPE_ICON_FROM] with the swipe, then pops to
     * [MekaChoreography.swipeIconPopScale] once armed (the UI springs between them). Motion → Off: full size, no pop.
     */
    fun swipeIconScale(progress: Float, armed: Boolean, reduced: Boolean): Float = when {
        reduced -> 1f
        armed -> MekaChoreography.swipeIconPopScale
        else -> SWIPE_ICON_FROM + (1f - SWIPE_ICON_FROM) * progress.coerceIn(0f, 1f)
    }

    /** Opacity of the action's icon and label: fades in over the first [SWIPE_ICON_FADE] of the way. */
    fun swipeIconAlpha(progress: Float): Float = (progress / SWIPE_ICON_FADE).coerceIn(0f, 1f)

    /** How strongly Needs you's card takes on the colour of the move it leans to: up to [SWIPE_WASH_MAX] at the arm point. */
    fun swipeWash(progress: Float): Float = SWIPE_WASH_MAX * progress.coerceIn(0f, 1f)

    const val SWIPE_MIN_TINT = 0.3f
    const val SWIPE_ICON_FROM = 0.6f
    const val SWIPE_ICON_FADE = 0.4f
    const val SWIPE_WASH_MAX = 0.18f

    /**
     * List → detail container transform (motion pass 2, screen-level motion; catalogue "Task detail"): where the
     * tapped row sits inside the pane, [row] and [pane] both in the same (root) coordinates. Clamped to the pane's
     * height, so a row half scrolled off grows from its visible part. Null when there is no row (opened from search,
     * a notification, the cover screen) or none of it shows: then the pane springs up as a sheet as before.
     */
    fun containerOrigin(row: Bounds?, pane: Bounds): Bounds? {
        if (row == null) return null
        val h = pane.bottom - pane.top
        val top = (row.top - pane.top).coerceIn(0f, h)
        val bottom = (row.bottom - pane.top).coerceIn(0f, h)
        if (bottom - top < 1f || row.right - row.left < 1f) return null
        return Bounds(row.left - pane.left, top, row.right - pane.left, bottom)
    }

    /**
     * The container's bounds at [progress] of the expand spring: from the row's bounds to the pane's, edge by edge.
     * Clamped to 0…1, so the spring's overshoot never grows the container past the pane.
     */
    fun containerBounds(from: Bounds, to: Bounds, progress: Float): Bounds {
        val p = progress.coerceIn(0f, 1f)
        fun lerp(a: Float, b: Float) = a + (b - a) * p
        return Bounds(lerp(from.left, to.left), lerp(from.top, to.top), lerp(from.right, to.right), lerp(from.bottom, to.bottom))
    }

    /** A corner of the container: the row's rounding ([fromDp]) easing into the pane's ([toDp]). */
    fun containerCorner(fromDp: Float, toDp: Float, progress: Float): Float = fromDp + (toDp - fromDp) * progress.coerceIn(0f, 1f)

    /**
     * The detail's content inside the growing container: hidden while it is still row-sized, fading in from
     * [CONTAINER_FADE_FROM] to [CONTAINER_FADE_TO] of the way (and out the same way as it shrinks back).
     */
    fun containerContentAlpha(progress: Float): Float =
        ((progress - CONTAINER_FADE_FROM) / (CONTAINER_FADE_TO - CONTAINER_FADE_FROM)).coerceIn(0f, 1f)

    const val CONTAINER_FADE_FROM = 0.2f
    const val CONTAINER_FADE_TO = 0.6f
}

/** A rectangle by its edges, in pixels (kept free of Compose's Rect so [MotionMath] stays plain). */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** What a staying tick does ([MotionMath.tickDraw]): at rest (an outline), draw the check in, or show it done at once. */
enum class TickDraw { REST, DRAW, DONE }
