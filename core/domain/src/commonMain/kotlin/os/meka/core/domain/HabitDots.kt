package os.meka.core.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One of today's habits as a small dot just inside the watch face's markers (Calm Today, slice 2: "habits move into
 * the watch face as small dots around the rim, tap to tick"). Hollow until done, then filled with the accent; a habit
 * behind for the week is a full-accent ring, an on-track one a quieter ring.
 */
data class HabitDot(
    val id: String,
    /** The habit's whole name (the undo bar and a screen reader say it; the dot itself has no words). */
    val title: String,
    val done: Boolean,
    val behind: Boolean,
    /** Where it sits, clockwise from 12 at the top. */
    val degrees: Float,
    /** "Tick Stretch for today" / "Untick Stretch for today". */
    val tickLabel: String,
)

/** Where a dot is drawn on a face of a given size: its centre (dp from the face's centre, y down) and radius. */
data class HabitDotPlace(val xDp: Float, val yDp: Float, val radiusDp: Float)

/**
 * Today's habits on the watch face (non-AI, pure, unit-tested). The habits are [HabitChipRules]'s (done today, due
 * today or behind for the week, in the Goals order).
 *
 * - Up to [MAX_DOTS] sit on the face, [STEP_DEGREES] apart and centred on 6 o'clock, so they read as a small row at
 *   the foot of the dial and stay clear of 12 (where the rim starts drawing) and of the header's text on the left.
 * - They sit on a ring just inside the markers ([ringRadiusDp]), so the rim's arcs, work band and second hand's tail
 *   never cover them; the hour and minute hands pass over them like over any dial print.
 * - The chips under the ticker show only when the face is hidden (no day yet) or there are more habits than dots
 *   ([chipsShown]); otherwise the face carries them and the row goes.
 * - A tap (Mac: click) ticks the nearest dot within [HIT_DP] of it ([hit]); anywhere else on the face opens the
 *   24-hour Day ring as before. Each tick raises the undo bar with the habit's name ([tickedLine]), since the dot
 *   itself has none.
 */
object HabitDotRules {
    const val MAX_DOTS = 8
    const val STEP_DEGREES = 24f
    /** 6 o'clock: the foot of the dial. */
    const val CENTRE_DEGREES = 180f
    /** The dot's diameter on the header's faces (open Fold and Mac 150 dp; closed Fold 96 dp). */
    const val DOT_DP = 7f
    const val COMPACT_DOT_DP = 5f
    /** A hollow dot's ring. */
    const val RING_STROKE_DP = 1.2f
    /** An on-track (not behind) hollow dot's ring, of the accent; behind is the full accent. */
    const val OPEN_ALPHA = 0.55f
    /** How far from a dot's centre a tap still ticks it. */
    const val HIT_DP = 12f
    /** The gap between the markers' inner ends and the dots' ring. */
    const val GAP_DP = 3f
    /** The tick's pop: the fill overshoots to this scale on the complete spring before settling. */
    const val POP_SCALE = 1.35f

    fun dotDp(sizeDp: Int): Float = if (sizeDp < DayRingHeader.CENTRE_MIN_DP) COMPACT_DOT_DP else DOT_DP

    /** Whether today's habits fit on the face as dots (one to [MAX_DOTS]). */
    fun fits(count: Int): Boolean = count in 1..MAX_DOTS

    /** The dots for [chips]; none when they don't fit (the chips row carries them then). */
    fun dots(chips: List<HabitChip>): List<HabitDot> {
        if (!fits(chips.size)) return emptyList()
        val first = CENTRE_DEGREES - (chips.size - 1) * STEP_DEGREES / 2f
        return chips.mapIndexed { i, c ->
            HabitDot(c.id, c.name, c.done, c.behind, first + i * STEP_DEGREES, c.tickLabel)
        }
    }

    /** The chips row shows only when the face is hidden or the habits don't fit on it. */
    fun chipsShown(faceShown: Boolean, chips: List<HabitChip>): Boolean = chips.isNotEmpty() && (!faceShown || !fits(chips.size))

    /**
     * The radius (dp) of the ring the dots sit on, on a face of [sizeDp]: inside the major markers by [GAP_DP] and
     * half a dot (the same geometry both apps draw the markers with).
     */
    fun ringRadiusDp(sizeDp: Int): Float {
        val radius = WatchFaceRules.rimRadiusDp(sizeDp)
        val markerOuter = radius - WatchFaceRules.rimStrokeDp(sizeDp) / 2f - 2f
        val markerInner = markerOuter - radius * WatchFaceRules.MAJOR_MARKER_LENGTH
        return markerInner - GAP_DP - dotDp(sizeDp) / 2f
    }

    fun place(dot: HabitDot, sizeDp: Int): HabitDotPlace {
        val r = ringRadiusDp(sizeDp)
        val a = (dot.degrees - 90f) * PI / 180.0
        return HabitDotPlace((cos(a) * r).toFloat(), (sin(a) * r).toFloat(), dotDp(sizeDp) / 2f)
    }

    /** The dot a tap at ([xDp], [yDp]) from the face's centre ticks: the nearest within [HIT_DP]; null opens the day. */
    fun hit(dots: List<HabitDot>, xDp: Float, yDp: Float, sizeDp: Int): HabitDot? = dots
        .map { it to place(it, sizeDp) }
        .map { (d, p) -> d to sqrt((p.xDp - xDp) * (p.xDp - xDp) + (p.yDp - yDp) * (p.yDp - yDp)) }
        .filter { it.second <= HIT_DP }
        .minByOrNull { it.second }?.first

    /**
     * How far a dot has come up as the face draws in: it fades in as the drawing rim passes its place ([mark] is the
     * rim's progress, 0..1), over the next 30°.
     */
    fun shown(mark: Float, degrees: Float): Float = if (mark >= 1f) 1f else ((mark * 360f - degrees) / 30f).coerceIn(0f, 1f)

    /** The undo bar after a tap: "Stretch · done today" / "Stretch · not done today". */
    fun tickedLine(dot: HabitDot, nowDone: Boolean): String = "${dot.title} · " + if (nowDone) "done today" else "not done today"

    /** Added to the face's spoken line: "Habits: Stretch, done; Read, not yet." Nothing without dots. */
    fun spokenLine(dots: List<HabitDot>): String =
        if (dots.isEmpty()) "" else " Habits: " + dots.joinToString("; ") { "${it.title}, " + if (it.done) "done" else "not yet" } + "."
}
