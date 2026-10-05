package os.meka.android.designsystem

import kotlin.math.roundToInt

/**
 * The arithmetic behind MEKA's choreography, kept free of Compose so it is unit-tested and matches the Mac
 * (macos/MekaOS/DesignSystem/MekaMotionKit.swift) number for number.
 */
object MotionMath {
    /** Delay before item [index] of a staggered group appears. Capped so long lists never feel slow; zero when reduced. */
    fun staggerDelayMs(index: Int, reduced: Boolean): Int =
        if (reduced || index <= 0) 0 else minOf(index, MekaChoreography.staggerMaxSteps) * MekaChoreography.staggerStepMs

    /** Total time a staggered group of [count] items takes to start appearing (used to end the intro). */
    fun staggerSpanMs(count: Int, reduced: Boolean): Int = if (count <= 0) 0 else staggerDelayMs(count - 1, reduced)

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
