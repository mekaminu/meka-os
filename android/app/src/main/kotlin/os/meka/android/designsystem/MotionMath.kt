package os.meka.android.designsystem

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
}
