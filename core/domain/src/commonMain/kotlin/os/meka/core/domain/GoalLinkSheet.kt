package os.meka.core.domain

/**
 * An open goal's Link a task as a row opening a sheet (walk-through week 5, A17: no open input boxes on a page). The open
 * goal showed an always-open "Link a task…" box whose matches unfolded under it as "+ <task>" links. It is now one card
 * row, "+ Link a task" over what linking does (or how many tasks the goal counts), opening a sheet with:
 *  - a field to narrow the list by a few words of the task;
 *  - your open tasks not already on this goal ([GoalRules.linkCandidates], soonest first), shown at once, not only
 *    after typing; one on another goal says so ("On Get fit · linking moves it here");
 *  - a line when nothing is offered (no match for the words, or no open tasks at all);
 *  - Close · Link. Tapping a task picks it (lit); Link stays dark until a task still in the list is picked.
 *
 * Pure; nothing new stored (linking sets the task's goal as before).
 */
object GoalLinkRules {
    const val LABEL = "+ Link a task"
    const val LINK = "Link"
    const val EMPTY_LINE = "Count one of your open tasks towards this goal"
    const val NO_TASKS = "No open tasks to link yet. + Add a step makes one for this goal."

    /** "+ Link a task" over what linking does, or, once the goal counts tasks, how many and that another can join. */
    fun row(g: GoalItem): GoalAddRow {
        val line = when (g.tasksTotal) {
            0 -> EMPTY_LINE
            1 -> "1 task counts towards it · link another"
            else -> "${g.tasksTotal} tasks count towards it · link another"
        }
        return GoalAddRow(LABEL, line)
    }

    val sheet: GoalAddSheet = GoalAddSheet(
        title = "Link a task",
        hint = "Type a few words of the task",
        spokenHint = "Words of the task to link",
        note = "The task stays in your list as it is and now counts towards this goal's progress. One on another goal moves here.",
        addLabel = LINK,
    )

    /** The line under a task in the list: where it is now when it sits on another goal; null otherwise. */
    fun taskLine(t: Task, goalId: String, goalTitles: Map<String, String>): String? {
        val other = t.goalId?.takeIf { it != goalId } ?: return null
        val title = goalTitles[other] ?: return "On another goal · linking moves it here"
        return "On $title · linking moves it here"
    }

    /** What the sheet says when the list is empty: no match for the words, or no open tasks at all. */
    fun emptyLine(query: String): String {
        val q = query.trim().replace(Regex("\\s+"), " ")
        return if (q.isEmpty()) NO_TASKS else "No open task has \"$q\" in its name."
    }

    /** Link lights once a task still offered is picked. */
    fun canLink(picked: String?, offered: List<Task>): Boolean = picked != null && offered.any { it.id == picked }

    /** What a screen reader says for a task in the list (its line too), and whether it's picked. */
    fun spoken(t: Task, line: String?, picked: Boolean): String =
        listOfNotNull(t.title, line, if (picked) "picked" else null).joinToString(", ")
}
