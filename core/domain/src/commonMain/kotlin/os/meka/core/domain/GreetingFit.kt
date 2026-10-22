package os.meka.core.domain

/**
 * Today's greeting fits one line (quality gate, 2026-10-22: on the closed Fold "Good afternoon" wrapped to two lines
 * beside the watch face). The greeting is tried at its own size, then a step or two smaller; only when even the
 * smallest step is too wide (a long name) does it wrap, at that smallest size. Same steps on the Fold and the Mac.
 */
object GreetingFitRules {
    /** The sizes tried, as a share of the greeting style's size, largest first (34 sp → 30 → 26 on the Fold). */
    private val SCALES = listOf(1f, 0.88f, 0.76f)

    /** How many sizes there are (for the Mac, which tries each in turn). */
    val STEPS: Int get() = SCALES.size

    /** The share of the greeting's size at [step] (0 = its own size), clamped to the steps there are. */
    fun scaleAt(step: Int): Float = SCALES[step.coerceIn(0, SCALES.lastIndex)]

    /**
     * The greeting's size as a share of its style's, given its one-line width at that style ([widthAtBase]) and the
     * width it has ([available], same unit): the largest step at which one line fits, else the smallest step.
     */
    fun scale(widthAtBase: Float, available: Float): Float =
        if (widthAtBase <= 0f || available <= 0f) 1f
        else SCALES.firstOrNull { widthAtBase * it <= available } ?: SCALES.last()

    /** Whether the greeting still takes more than one line at its smallest step (then it wraps rather than cut). */
    fun wraps(widthAtBase: Float, available: Float): Boolean =
        widthAtBase > 0f && available > 0f && widthAtBase * SCALES.last() > available
}
