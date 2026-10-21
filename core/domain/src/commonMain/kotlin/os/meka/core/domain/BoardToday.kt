package os.meka.core.domain

/**
 * The morning brief's plan of the day (Week board, slice 5, item (7)): [carried] open tasks carried over from earlier
 * days, [planned] the rest of today's open tasks, and [minutes] about how long they take, or null when too few have a
 * time estimate or a guess to say.
 */
data class DayPlan(val carried: Int, val planned: Int, val minutes: Int?) {
    val isEmpty: Boolean get() = carried == 0 && planned == 0

    /**
     * Worth saying: something carried over, or a time to give. Just "4 planned" would repeat the day's count ("4 tasks")
     * and the focus, so the brief leaves it out.
     */
    val worthSaying: Boolean get() = !isEmpty && (carried > 0 || minutes != null)

    /** "3 carried over · 5 planned · about 4h"; null when not [worthSaying]. */
    val line: String? get() = if (!worthSaying) null else listOfNotNull(
        carried.takeIf { it > 0 }?.let { "$it carried over" },
        planned.takeIf { it > 0 }?.let { "$it planned" },
        minutes?.let { "about ${TaskEstimateRules.label(it)}" },
    ).joinToString(" · ")

    /** The spoken brief: "Three carried over from earlier days and five planned, about 4 hours in all."; null when not [worthSaying]. */
    val sentence: String? get() {
        if (!worthSaying) return null
        val parts = listOfNotNull(
            carried.takeIf { it > 0 }?.let { if (it == 1) "One task carried over from an earlier day" else "${BoardTodayRules.spokenCount(it)} carried over from earlier days" },
            planned.takeIf { it > 0 }?.let { if (carried > 0) "${BoardTodayRules.spokenCount(it)} planned" else if (it == 1) "One task planned" else "${BoardTodayRules.spokenCount(it)} tasks planned" },
        )
        val head = parts.joinToString(" and ").replaceFirstChar { it.uppercase() }
        return minutes?.let { "$head, about ${BoardTodayRules.spokenMinutes(it)} in all." } ?: "$head."
    }
}

/**
 * Today follows the Board (Week board and roll-over, slice 5; Meka approved 2026-10-19 20:36), non-AI and pure.
 *
 * - [anytimeOrder]: Today's Anytime tasks (and so Up next, when nothing is planned at a time) follow the order he set on
 *   the Board's Today column ([TaskFields.BOARD_RANK]): the cards he placed first, by place; then the rest as before,
 *   by priority, then as added.
 * - [plan]: the morning brief's "3 carried over · 5 planned · about 4h" from today's open tasks: carried are those
 *   [RollOverRules.carries]; the time is each task's estimate, else Navilife's guess ([TaskEstimateRules.suggest]),
 *   said only when at least half the tasks have one, rounded to the quarter hour under an hour and the half hour above.
 *   With nothing carried and no time to give, the brief says nothing more than its day's count already does.
 */
object BoardTodayRules {
    /** Placed cards first by place; then by priority, then as added. */
    val anytimeOrder: Comparator<Task> = compareBy<Task> { it.boardRank ?: Long.MAX_VALUE }
        .thenByDescending { it.priority }
        .thenBy { it.createdAtMs }
        .thenBy { it.id }

    fun plan(dayTasks: List<Task>, all: List<Task>, today: DayWindow): DayPlan {
        val open = dayTasks.filter { it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX }
        val carried = open.count { RollOverRules.carries(it, today) }
        val sized = TaskEstimateRules.Sized.of(all)
        val times = open.mapNotNull { it.estimateMinutes ?: TaskEstimateRules.suggest(it, sized) }
        val minutes = if (open.isNotEmpty() && times.size * 2 >= open.size) round(times.sum()) else null
        return DayPlan(carried, open.size - carried, minutes?.takeIf { it > 0 })
    }

    /** To the quarter hour under an hour, the half hour from an hour (never below 15m). */
    fun round(minutes: Int): Int = when {
        minutes <= 0 -> 0
        minutes < 60 -> maxOf(15, (minutes + 7) / 15 * 15)
        else -> (minutes + 15) / 30 * 30
    }

    /** "two", "five", "12". */
    fun spokenCount(n: Int): String = NUMBERS.getOrNull(n) ?: n.toString()

    /** "45 minutes", "an hour", "an hour and a half", "4 hours", "2 and a half hours". */
    fun spokenMinutes(m: Int): String {
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "$r minutes"
            h == 1 && r == 0 -> "an hour"
            h == 1 && r == 30 -> "an hour and a half"
            r == 0 -> "$h hours"
            r == 30 -> "$h and a half hours"
            h == 1 -> "an hour and $r minutes"
            else -> "$h hours $r minutes"
        }
    }

    private val NUMBERS = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
}
