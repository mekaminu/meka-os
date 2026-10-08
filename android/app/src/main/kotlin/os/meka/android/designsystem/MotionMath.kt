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
}
