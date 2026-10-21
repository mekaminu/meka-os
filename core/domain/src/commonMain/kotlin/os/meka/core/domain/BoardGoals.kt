package os.meka.core.domain

/**
 * One of the week's goals across the top of the Board (Week board, slice 7: the goals lane): its title and how many
 * of the week's tasks tagged to it are done ("2 of 4 done"), counting the cards on that week's days (done ones too)
 * and in its "No day yet" lane.
 */
data class BoardGoal(
    val goalId: String,
    val title: String,
    val done: Int,
    val total: Int,
    /** The goal itself is finished. */
    val finished: Boolean,
    /** Picked for this week in the week ahead or on the Board; false for this week's This-week goals shown with no pick. */
    val picked: Boolean,
) {
    /** "2 of 4 done" · "No tasks yet" · "Goal done". */
    val line: String
        get() = when {
            finished -> BoardGoalRules.GOAL_DONE
            total == 0 -> BoardGoalRules.NO_TASKS
            else -> "$done of $total done"
        }

    /** How far the slim bar under it fills, 0..1. */
    val fill: Float
        get() = when {
            finished -> 1f
            total == 0 -> 0f
            else -> done.toFloat() / total
        }

    /** The title shortened for a chip ("Get the garden ready for…"). */
    val shortTitle: String get() = BoardGoalRules.shorten(title)

    /** Screen readers: "Goal Run 5k, 2 of 4 done". */
    val accessibilityLabel: String get() = "Goal $title, ${line.lowercase()}"
}

/** A row of a card's "For a goal" menu: one of its week's goals (ticked when it's the one), or No goal. */
data class GoalTagChoice(val goalId: String?, val label: String, val selected: Boolean)

/** A goal "+ Goal" offers to pick for the week: an open goal not picked for it yet. */
data class GoalPickChoice(val goalId: String, val title: String, val horizon: String)

/**
 * The Board's goals lane (Week board and roll-over, item 4; non-AI, pure, unit-tested).
 *
 * - A week's goals are those picked for it ([GoalItem.weekOf], the week ahead's pick or "+ Goal" here), at most
 *   [MAX] ([GoalWeekRules.MAX_PICKS]). This week with nothing picked shows what Today's "This week: …" line counts
 *   ([GoalWeekRules.build]: the open This-week goals, and those finished this week), so the two always agree.
 * - Each shows "2 of 4 done" from that week's cards tagged to it ([Task.goalId]); "No tasks yet" with none; a finished
 *   goal says "Goal done".
 * - A card's menu tags it to one of its week's goals (the week its day is in; a lane card's week; Someday cards this
 *   week's), or No goal; nothing else changes on the task. A card tagged to a goal shows "◎ Run 5k".
 * - "+ Goal" (while the week has fewer than [MAX]) offers open goals not yet picked for it, shortest horizon first, at
 *   most [MAX_OFFERS], or a new one typed (made as a This-week goal and picked).
 */
object BoardGoalRules {
    const val MAX = GoalWeekRules.MAX_PICKS
    const val MAX_OFFERS = 6
    const val TITLE = "Goals this week"
    const val ADD = "+ Goal"
    const val NEW_GOAL = "New goal…"
    const val NO_GOAL = "No goal"
    const val MENU_TITLE = "For a goal"
    const val NO_TASKS = "No tasks yet"
    const val GOAL_DONE = "Goal done"
    const val OFF_WEEK = "Off this week"
    const val EMPTY = "Pick up to three goals for the week"
    const val TAG_MARK = "◎"
    private const val SHORT = 24

    /**
     * The lane for each of [weeks] (their Mondays) from [open] goals and this week's [thisWeek] (Today's week goals,
     * which also hold those finished this week). [cards] are each week's cards (its days' and its lane's).
     */
    fun lane(
        weekStart: Long,
        today: Long,
        open: List<GoalItem>,
        thisWeek: WeekGoals,
        cards: List<BoardCard>,
    ): List<BoardGoal> {
        val current = GoalRules.weekStart(today)
        val openById = open.associateBy { it.id }
        val goals: List<Triple<String, String, Pair<Boolean, Boolean>>> = if (weekStart == current) {
            // Today's own list: picks first (open, then finished this week), else the This-week goals. A finished one
            // can't be picked again, so it counts as picked.
            thisWeek.arcs.map { a -> Triple(a.goalId, a.title, a.done to (a.done || openById[a.goalId]?.weekOf == weekStart)) }
        } else {
            open.filter { it.weekOf == weekStart && !it.done }.map { Triple(it.id, it.title, false to true) }
        }
        return goals.take(MAX).map { (id, title, flags) ->
            val mine = cards.filter { it.task.goalId == id }
            BoardGoal(id, title, done = mine.count { it.done }, total = mine.size, finished = flags.first, picked = flags.second)
        }
    }

    /** "◎ Run 5k" for a card tagged to [goalTitle]; null for none. */
    fun tag(goalTitle: String?): String? = goalTitle?.let { "$TAG_MARK ${shorten(it, 18)}" }

    /** The week (its Monday) a card's goal menu offers goals from: its day's week, its lane's week, else this week. */
    fun weekOfCard(board: WeekBoard, taskId: String): Long? {
        val spot = board.find(taskId) ?: return null
        return when (spot.source) {
            BoardSource.DAY -> board.weeks.firstOrNull { spot.key in it.startEpochDay until it.startEpochDay + 7 }?.startEpochDay
            BoardSource.WEEK -> spot.key
            BoardSource.SOMEDAY -> board.weeks.firstOrNull()?.startEpochDay
        }
    }

    /** A card's "For a goal" rows: its week's unfinished goals (the one it has ticked), then No goal when it has one. */
    fun tagChoices(board: WeekBoard, taskId: String): List<GoalTagChoice> {
        val spot = board.find(taskId) ?: return emptyList()
        if (!spot.card.canMove) return emptyList()
        val week = weekOfCard(board, taskId)?.let(board::week) ?: return emptyList()
        val current = spot.card.task.goalId
        val rows = week.goals.filter { !it.finished }.map { g ->
            GoalTagChoice(g.goalId, if (g.goalId == current) "✓ ${g.shortTitle}" else g.shortTitle, g.goalId == current)
        }
        if (rows.isEmpty()) return emptyList()
        return if (current != null) rows + GoalTagChoice(null, NO_GOAL, false) else rows
    }

    /** "+ Goal"'s offers for the week starting [weekStart]: open goals not picked for it, shortest horizon first. */
    fun pickChoices(open: List<GoalItem>, week: BoardWeek): List<GoalPickChoice> {
        if (week.goals.size >= MAX) return emptyList()
        val shown = week.goals.map { it.goalId }.toSet()
        return open.filter { !it.done && it.id !in shown && it.weekOf != week.startEpochDay }
            .sortedBy { it.horizon.ordinal }
            .take(MAX_OFFERS)
            .map { GoalPickChoice(it.id, it.title, GoalRules.horizonLabel(it.horizon)) }
    }

    /** "“Book MOT” · for “Run 5k”" · "“Book MOT” · no goal". */
    fun tagLine(taskTitle: String, goalTitle: String?): String {
        val t = TaskTitleRules.display(taskTitle)
        return if (goalTitle == null) "“$t” · no goal" else "“$t” · for “${shorten(goalTitle)}”"
    }

    /** "“Run 5k” is one of this week's goals" · "… next week's goals" · "… goals for 2–8 Nov". */
    fun pickLine(goalTitle: String, week: BoardWeek, on: Boolean): String {
        val g = shorten(goalTitle)
        val whose = when (week.title) {
            "This week" -> "this week's goals"
            "Next week" -> "next week's goals"
            else -> "the goals for ${week.range}"
        }
        return if (on) "“$g” is one of $whose" else "“$g” is off $whose"
    }

    fun shorten(title: String, max: Int = SHORT): String = title.trim().let { if (it.length <= max) it else it.take(max - 1).trimEnd() + "…" }
}
