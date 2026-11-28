package os.meka.core.domain

/**
 * An open goal's Milestones add and Add a step as rows opening sheets (walk-through week 5, A17: no open input boxes on a
 * page). An open goal on Goals unfolded two always-open boxes, "Add a milestone…" under its milestones and "Add a step…"
 * under Break it down. Each is now one card row:
 *  - "+ Add a milestone" over what one is (or how many more fit), opening a sheet with the milestone's name, what ticking
 *    it does for this goal, Close · Add. Add stays dark until a name is typed that the goal doesn't already have. The row
 *    goes once the goal keeps [GoalRules.MAX_MILESTONES], as the box did.
 *  - "+ Add a step" over what a step is, opening a sheet with the step's name, where the task it makes shows, Close · Add.
 *
 * Reuses [GoalAddRow] and [GoalAddSheet]. Pure; nothing new stored (milestones and linked tasks as before).
 */
object GoalStepRules {
    const val MILESTONE_LABEL = "+ Add a milestone"
    const val STEP_LABEL = "+ Add a step"
    const val ADD = "Add"
    const val MILESTONE_EMPTY = "A point on the way, ticked off as you reach it"
    const val STEP_LINE = "A task for this goal; the first open one shows as its next step"
    const val MAX_MILESTONE = GoalRules.MAX_MILESTONE_TITLE
    const val MAX_STEP = Goals.MAX_TITLE

    /** Whether the goal has room for another milestone (the row shows only then). */
    fun showsMilestoneRow(g: GoalItem): Boolean = g.milestones.size < GoalRules.MAX_MILESTONES

    /** "+ Add a milestone" over what one is, or, once there are some, how many more fit. */
    fun milestoneRow(g: GoalItem): GoalAddRow {
        val left = (GoalRules.MAX_MILESTONES - g.milestones.size).coerceAtLeast(0)
        val line = if (g.milestones.isEmpty()) MILESTONE_EMPTY else if (left == 1) "Room for 1 more" else "Room for $left more"
        return GoalAddRow(MILESTONE_LABEL, line)
    }

    /** The milestone's sheet; its note says what ticking it does for this goal (a number goal's ring follows the number). */
    fun milestoneSheet(g: GoalItem): GoalAddSheet = GoalAddSheet(
        title = "Add a milestone",
        hint = "Run 5k without stopping",
        spokenHint = "A milestone for this goal",
        note = if (g.metric != null) "Shown under Milestones with its tick. This goal's ring follows its number, so ticking one marks the way without moving it."
        else "Shown under Milestones with its tick; ticking it counts towards the goal's ring. A goal keeps up to ${GoalRules.MAX_MILESTONES}.",
        addLabel = ADD,
    )

    /** The name as it's kept: trimmed, spaces squeezed, at most [MAX_MILESTONE]; null when blank. */
    fun cleaned(typed: String, max: Int): String? = typed.trim().replace(Regex("\\s+"), " ").take(max).trim().ifEmpty { null }

    /** Add lights once a name is typed that the goal doesn't already have (written any case) and there's room. */
    fun canAddMilestone(typed: String, g: GoalItem): Boolean {
        val t = cleaned(typed, MAX_MILESTONE) ?: return false
        return showsMilestoneRow(g) && g.milestones.none { it.title.equals(t, ignoreCase = true) }
    }

    val stepRow: GoalAddRow = GoalAddRow(STEP_LABEL, STEP_LINE)

    val stepSheet: GoalAddSheet = GoalAddSheet(
        title = "Add a step",
        hint = "Book a running coach",
        spokenHint = "A step towards this goal",
        note = "Made as a task linked to this goal, with your other tasks. The first open one shows under the goal's name as its next step.",
        addLabel = ADD,
    )

    /** Add lights once a name is typed. */
    fun canAddStep(typed: String): Boolean = cleaned(typed, MAX_STEP) != null
}
