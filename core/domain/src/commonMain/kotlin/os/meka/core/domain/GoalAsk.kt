package os.meka.core.domain

/**
 * Goals, properly, slice 8 (Meka, 2026-10-10: "add a goal to run 5k by December", "how are my goals?"): the plain,
 * non-AI rules for goals in Ask and Talk. A question carries `goals` lines (each open goal with its progress, horizon,
 * done-by day, what counts towards it and its next step), and the model may propose `add_goal`
 * ({"title": "Run 5k", "date": "2026-12-31"}), read here into a card that does nothing until Meka taps it.
 */
object GoalAskRules {
    /** At most this many open goals go with a question (in the Goals screen's order: shortest horizon first). */
    const val ASK_GOALS = 8
    const val ALREADY = "That goal is already on your list"

    private fun tidy(s: String): String = s.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    /** How a goal's title is matched: lower case, spaces tidied, a trailing full stop off. */
    fun key(s: String): String = tidy(s).trimEnd('.', '!').trim().lowercase()

    /**
     * The goals as MEKA describes them to the model (Ask's `goals` lines): "Goals · 3 open · 1 finished", then each
     * "Goal “Run 5k” · 40% · This month · by Sat 31 Oct · 1 of 3 milestones · under “Run a marathon” · Next · Buy
     * running shoes" (a numeric goal's pace line last). Nothing at all while there are no goals.
     */
    fun askLines(view: GoalsView): List<String> {
        val open = view.goals
        if (open.isEmpty() && view.finishedGoals == 0) return emptyList()
        val head = "Goals · ${open.size} open" +
            (if (view.finishedGoals > 0) " · ${view.finishedGoals} finished" else "") +
            (if (open.size > ASK_GOALS) " · the first $ASK_GOALS below" else "")
        return listOf(head) + open.take(ASK_GOALS).map { g ->
            listOfNotNull(
                "Goal “${tidy(g.title)}”",
                "${g.progressPct}%",
                g.meta.takeIf { it.isNotBlank() },
                g.parentTitle?.let { "under “${tidy(it)}”" },
                g.nextLine,
                g.metric?.paceLine,
            ).joinToString(" · ")
        }
    }

    /**
     * The horizon a goal done by [due] belongs to: the shortest period (this week, month, quarter, year) whose end it
     * falls in, Someday beyond this year; This month when no day is given (as the Goals screen's own Add).
     */
    fun horizonFor(due: Long?, today: Long): GoalHorizon {
        if (due == null) return GoalHorizon.MONTH
        return listOf(GoalHorizon.WEEK, GoalHorizon.MONTH, GoalHorizon.QUARTER, GoalHorizon.YEAR)
            .firstOrNull { due <= GoalRules.horizonEnd(it, today)!! } ?: GoalHorizon.SOMEDAY
    }

    /**
     * A model's `add_goal` as a card: the title tidied (at most [AskRules.MAX_TITLE] characters), the done-by [due]
     * day if it gave one, and the horizon that day falls in. Null when there's no title or an open goal already has it.
     */
    fun proposal(goals: List<GoalItem>, title: String?, due: Long?, today: Long): AskProposal.AddGoal? {
        val t = title?.let { tidy(it).trimEnd('.').trim() }?.takeIf { it.isNotEmpty() && it.length <= AskRules.MAX_TITLE } ?: return null
        if (exists(goals, t)) return null
        return AskProposal.AddGoal(t, horizonFor(due, today), due)
    }

    /** An open goal is already called [title]. */
    fun exists(goals: List<GoalItem>, title: String): Boolean = goals.any { key(it.title) == key(title) }

    /** The card's words: "Add goal “Run 5k” · This quarter · by Thu 31 Dec". */
    fun cardLine(p: AskProposal.AddGoal, today: Long): String =
        "Add goal “${p.title}” · ${GoalRules.horizonLabel(p.horizon)}" + (p.dueDay?.let { " · " + GoalRules.dueLabel(it, today) } ?: "")

    /** The undo bar's line: "Added goal “Run 5k”". */
    fun doneLine(p: AskProposal.AddGoal): String = "Added goal “${p.title}”"

    /**
     * Undo takes the goal back only while nothing has been added to it since (no task, habit, milestone, number or
     * goal under it), so a goal Meka has started working on is never lost to a late Undo.
     */
    fun untouched(g: GoalItem): Boolean = !g.counted && g.childCount == 0 && !g.done
}
