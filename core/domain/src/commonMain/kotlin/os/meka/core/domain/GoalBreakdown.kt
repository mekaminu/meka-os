package os.meka.core.domain

/**
 * Goals, properly, slice 4: **Break it down** (approval only). Meka asks MEKA to break a goal into milestones and first
 * steps; the AI proposes up to [MAX_MILESTONES] milestones and [MAX_STEPS] first steps, and Meka ticks which to keep.
 * Nothing is added until he taps Keep: a kept milestone is an ordinary milestone, a kept step an ordinary task linked to
 * the goal, and one Undo takes them all back.
 *
 * Non-AI and pure: what goes out (the goal in Meka's own words: title, horizon, done-by, why, what done looks like, the
 * milestones and open steps it already has, so nothing is proposed twice), how an answer is checked (cleaned, cut to
 * size, never a repeat of what's there, never more milestones than the goal has room for) and the lines both apps show.
 * The answer is the model's words, so it is only ever shown as proposals (ADR-006): it never adds anything by itself.
 */
object GoalBreakdownRules {
    const val ACTION = "Break it down"
    const val HINT = "MEKA suggests milestones and first steps · tick the ones to keep, nothing is added until you do"
    const val READING_LINE = "Thinking it through…"
    const val NOTHING_LINE = "Nothing new to suggest · it already looks broken down"
    const val FULL_LINE = "This goal has all the milestones it can keep · remove one to make room"
    const val MILESTONES_LABEL = "Milestones"
    const val STEPS_LABEL = "First steps"
    const val NOT_NOW = "Not now"
    const val NOT_CONNECTED_LINE = "Break it down works once this device is connected"

    /** At most this many milestones and first steps proposed at once. */
    const val MAX_MILESTONES = 7
    const val MAX_STEPS = 3
    /** The longest step title kept (a step is a task; its title stays short). */
    const val MAX_STEP_TITLE = 120
    /** At most this many of the goal's open steps go out, so the model doesn't repeat them. */
    const val MAX_KNOWN_STEPS = 10

    /** How many milestones the goal still has room for. */
    fun room(existingMilestones: Int): Int = (GoalRules.MAX_MILESTONES - existingMilestones).coerceIn(0, MAX_MILESTONES)

    /**
     * The model's [milestones] and [steps] as Meka will see them: each cleaned ([clean]), none already on the goal
     * ([existingMilestones], [openSteps]; case and full stops ignored), none twice, a step never the same as a proposed
     * milestone, at most [room] milestones and [MAX_STEPS] steps.
     */
    fun check(
        milestones: List<String?>,
        steps: List<String?>,
        existingMilestones: List<String>,
        openSteps: List<String>,
    ): GoalBreakdown {
        val seen = (existingMilestones + openSteps).map(::key).toMutableSet()
        val room = room(existingMilestones.size)
        val ms = milestones.mapNotNull { clean(it, GoalRules.MAX_MILESTONE_TITLE) }.filter { seen.add(key(it)) }.take(room)
        val ss = steps.mapNotNull { clean(it, MAX_STEP_TITLE) }.filter { seen.add(key(it)) }.take(MAX_STEPS)
        return GoalBreakdown(ms, ss)
    }

    /**
     * One proposal's words: trimmed, spaces collapsed, list marks and quotes off the front, a trailing full stop off,
     * the first letter capitalised; null when empty, too long or holding a link (MEKA never proposes opening one).
     */
    fun clean(raw: String?, max: Int): String? {
        var t = raw?.replace(Regex("""\s+"""), " ")?.trim() ?: return null
        t = t.trimStart('-', '•', '*', '·', ' ', '"', '“', '”', '\'').trimEnd('"', '“', '”', '\'', ' ')
        t = t.replace(Regex("""^\d+[.)]\s*"""), "")
        while (t.endsWith(".")) t = t.dropLast(1).trimEnd()
        if (t.isEmpty() || t.length > max) return null
        if (Regex("""(?i)https?://|www\.""").containsMatchIn(t)) return null
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    private fun key(s: String) = s.trim().trimEnd('.').replace(Regex("""\s+"""), " ").lowercase()

    /** "Keep 3" on the button; "Tick what to keep" while nothing is ticked. */
    fun keepLabel(ticked: Int): String = if (ticked <= 0) "Tick what to keep" else "Keep $ticked"

    /** What the bar says after Keep: "Added 3 milestones and 2 steps", "Added 1 milestone", "Added 2 steps". */
    fun keptLine(milestones: Int, steps: Int): String {
        val parts = listOfNotNull(
            if (milestones > 0) "$milestones ${if (milestones == 1) "milestone" else "milestones"}" else null,
            if (steps > 0) "$steps ${if (steps == 1) "step" else "steps"}" else null,
        )
        return if (parts.isEmpty()) "Nothing added" else "Added " + parts.joinToString(" and ")
    }

    /** "2 milestones and 1 first step to choose from". */
    fun readyLine(b: GoalBreakdown): String {
        val parts = listOfNotNull(
            if (b.milestones.isNotEmpty()) "${b.milestones.size} ${if (b.milestones.size == 1) "milestone" else "milestones"}" else null,
            if (b.steps.isNotEmpty()) "${b.steps.size} first ${if (b.steps.size == 1) "step" else "steps"}" else null,
        )
        return parts.joinToString(" and ") + " to choose from"
    }
}

/** What MEKA proposes for a goal (checked by [GoalBreakdownRules.check]); nothing of it exists until Meka keeps it. */
data class GoalBreakdown(val milestones: List<String>, val steps: List<String>) {
    val isEmpty: Boolean get() = milestones.isEmpty() && steps.isEmpty()
}
