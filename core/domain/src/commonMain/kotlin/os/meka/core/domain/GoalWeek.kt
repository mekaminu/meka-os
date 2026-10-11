package os.meka.core.domain

/**
 * One of this week's goals as an arc on the watch face's goal ring (Goals, properly, slice 7): the ring is split into
 * one segment per goal (up to [GoalWeekRules.MAX_ARCS]), clockwise from 12, and each segment fills as far as the
 * goal's progress. A goal moving this week is drawn in the full accent; one not moving yet a quieter shade.
 */
data class WeekGoalArc(
    val goalId: String,
    val title: String,
    val pct: Int,
    val moving: Boolean,
    val done: Boolean,
    /** Where its segment starts, clockwise from 12 at the top. */
    val startDegrees: Float,
    /** The whole segment (the faint track); the fill is [fillDegrees]. */
    val sweepDegrees: Float,
) {
    val fillDegrees: Float get() = sweepDegrees * pct.coerceIn(0, 100) / 100f
}

/** This week's goals: the line on Today and the arcs on the watch face's goal ring. */
data class WeekGoals(
    val arcs: List<WeekGoalArc>,
    /** This week's goals (open and finished this week), all of them, not only those with an arc. */
    val total: Int,
    val moving: Int,
    val done: Int,
    /** "This week: 2 of 3 goals moving"; null with no goals for the week. */
    val line: String?,
) {
    /** What a screen reader says (also added to the watch face's spoken line); empty with no goals for the week. */
    val spoken: String get() = line?.let { "$it." }.orEmpty()

    companion object {
        val EMPTY = WeekGoals(emptyList(), 0, 0, 0, null)
    }
}

/**
 * This week's goals (Goals, properly, item 7; non-AI, pure, unit-tested).
 *
 * - This week's goals are the open goals with the horizon This week (in the Goals order), then those finished this
 *   week. (The Sunday week ahead's pick of up to three, item 6, will choose them when it lands.)
 * - Moving: finished, or anything moved it on since Monday ([GoalItem.lastMovedDay]: a linked task done, a milestone
 *   ticked, a number update, a linked habit ticked, a goal under it moving).
 * - The line: "This week: 2 of 3 goals moving", "This week: all 3 goals moving", "This week: 3 goals, none moving yet";
 *   one goal: "This week: “Run 5k” is moving" / "… not moving yet" / "… done"; all done: "This week: all 3 goals done".
 * - The ring: the watch face's outermost ring, just outside the rim ([ringRadiusDp]), one segment per goal for the
 *   first [MAX_ARCS], [GAP_DEGREES] apart, each filled by its progress; it comes up with the opening as the drawing rim
 *   passes it ([shown]), so reduced motion (the opening already landed) shows it at once. Today's faces only (closed and
 *   open Fold, Mac): the bedside clock's large face has no goal ring.
 */
object GoalWeekRules {
    const val MAX_ARCS = 3
    const val GAP_DEGREES = 12f
    /** The ring's stroke, and how far its outer edge sits inside the face's edge. */
    const val RING_DP = 1.5f
    const val EDGE_DP = 0.5f
    /** Of the accent: the segment's track, a goal moving this week, and one not moving yet. */
    const val TRACK_ALPHA = 0.2f
    const val MOVING_ALPHA = 0.95f
    const val WAITING_ALPHA = 0.5f

    fun moving(goal: GoalItem, today: Long): Boolean =
        goal.done || (goal.lastMovedDay ?: Long.MIN_VALUE) >= GoalRules.weekStart(today)

    fun build(open: List<GoalItem>, finishedThisWeek: List<GoalItem>, today: Long): WeekGoals {
        val goals = open.filter { it.horizon == GoalHorizon.WEEK && !it.done } + finishedThisWeek.filter { it.done }
        if (goals.isEmpty()) return WeekGoals.EMPTY
        val shown = goals.take(MAX_ARCS)
        val span = 360f / shown.size
        val gap = if (shown.size == 1) 0f else GAP_DEGREES
        val arcs = shown.mapIndexed { i, g ->
            WeekGoalArc(
                goalId = g.id, title = g.title, pct = if (g.done) 100 else g.progressPct.coerceIn(0, 100),
                moving = moving(g, today), done = g.done,
                startDegrees = i * span + gap / 2f, sweepDegrees = span - gap,
            )
        }
        val moving = goals.count { moving(it, today) }
        val done = goals.count { it.done }
        return WeekGoals(arcs, goals.size, moving, done, line(goals, moving, done, today))
    }

    private fun line(goals: List<GoalItem>, moving: Int, done: Int, today: Long): String {
        val n = goals.size
        if (n == 1) {
            val g = goals.single()
            val state = when {
                g.done -> "done"
                moving(g, today) -> "is moving"
                else -> "not moving yet"
            }
            return "This week: “${shorten(g.title)}” $state"
        }
        return when {
            done == n -> "This week: all $n goals done"
            moving == n -> "This week: all $n goals moving"
            moving == 0 -> "This week: $n goals, none moving yet"
            else -> "This week: $moving of $n goals moving"
        }
    }

    private fun shorten(title: String): String = title.trim().let { if (it.length <= 32) it else it.take(31).trimEnd() + "…" }

    /** The radius (dp) of the goal ring's centre line on a face of [sizeDp]: hugging the face's edge, outside the rim. */
    fun ringRadiusDp(sizeDp: Int): Float = sizeDp / 2f - EDGE_DP - RING_DP / 2f

    /** The ring's stroke and the colour's alpha for an arc's fill. */
    fun fillAlpha(arc: WeekGoalArc): Float = if (arc.moving) MOVING_ALPHA else WAITING_ALPHA

    /**
     * How much of an arc is shown as the face draws in: none until the drawing rim ([mark], 0..1 round from 12) reaches
     * its start, then it follows the rim round its segment; all of it once the opening has landed.
     */
    fun shown(mark: Float, arc: WeekGoalArc): Float {
        if (mark >= 1f) return 1f
        if (arc.sweepDegrees <= 0f) return 0f
        return ((mark * 360f - arc.startDegrees) / arc.sweepDegrees).coerceIn(0f, 1f)
    }
}
