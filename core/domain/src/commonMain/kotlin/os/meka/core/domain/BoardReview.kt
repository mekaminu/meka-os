package os.meka.core.domain

/** A task that kept slipping this week: its title and why ("Carried 4 days", "Parked since last week"). */
data class BoardSlip(val taskId: String, val title: String, val why: String) {
    val spoken: String get() = "$title, $why"
}

/**
 * The week on the Board, for the Friday review (Week board item (8)): what was planned against what got done, the best
 * day, and what kept slipping. Shown in the weekly reflection; nothing here is written.
 */
data class BoardWeekReview(
    val weekStart: Long,
    /** Tasks that were on the week's Board: done there, or still open on one of its days or in its lane. */
    val planned: Int,
    val done: Int,
    /** "5 of 8 planned done · best day Tue, 3 done"; "Nothing was planned on the Board this week". */
    val line: String,
    val slipping: List<BoardSlip>,
) {
    /** "Kept slipping: Book MOT (carried 4 days), Tax return (parked since last week)" or null. */
    val slippingLine: String?
        get() = slipping.takeIf { it.isNotEmpty() }?.let { s ->
            BoardReviewRules.SLIPPING + ": " + s.joinToString(", ") { "${it.title} (${it.why.replaceFirstChar { c -> c.lowercase() }})" }
        }

    val spoken: String get() = listOfNotNull(line, slippingLine).joinToString(". ")

    companion object {
        val EMPTY = BoardWeekReview(0, 0, 0, BoardReviewRules.NOTHING_PLANNED, emptyList())
    }
}

/**
 * Friday review (Week board and roll-over, item (8); Meka 2026-10-19 20:36): on the weekly reflection, the week as the
 * Board saw it. Non-AI and pure, read from the tasks as they are now (no history is kept):
 *
 * - **Planned**: tasks done in the week ([Task.completedAtMs] on one of its days), plus open tasks planned for one of
 *   its days ([Task.scheduledAtMs]) or parked in its lane ([Task.boardWeek]). For the week in progress, open tasks
 *   carried into it from earlier (they lead Today) and ones parked in an earlier week's lane count too, as the Board
 *   shows them this week. Someday, waiting, dropped and floating tasks with no day don't count.
 * - **Best day**: the day with the most done, once [BEST_DAY_MIN] were done that day.
 * - **Kept slipping**: open one-off tasks among the planned that have carried [SLIP_DAYS] days or more (counted to the
 *   week's end, or today for the week in progress), were asked about at three carries, or sit in the lane from an
 *   earlier week; longest first, at most [MAX_SLIPS].
 * - From Friday the Board offers [REVIEW] ([offersReview]) to open the reflection on this week.
 */
object BoardReviewRules {
    const val TITLE = "Your week on the Board"
    const val REVIEW = "Review the week"
    const val SLIPPING = "Kept slipping"
    const val NOTHING_PLANNED = "Nothing was planned on the Board this week"
    const val SLIP_DAYS = 2L
    const val BEST_DAY_MIN = 2
    const val MAX_SLIPS = 3
    const val CARRY_PREFIX = "From your review"

    /** Friday, Saturday and Sunday: the Board's header offers [REVIEW]. */
    fun offersReview(today: Long): Boolean = CivilDate.isoDayOfWeek(today) >= 5

    fun review(tasks: List<Task>, weekStart: Long, today: Long, calendar: LocalCalendar): BoardWeekReview {
        val last = weekStart + 6
        val current = today in weekStart..last
        val inWeek: (Long) -> Boolean = { it in weekStart..last }
        val done = tasks.filter { t -> t.lifecycle == Lifecycle.DONE && t.completedAtMs?.let { inWeek(calendar.epochDayOf(it)) } == true }
        val open = tasks.filter { t ->
            if (t.lifecycle != Lifecycle.ACTIVE && t.lifecycle != Lifecycle.INBOX) return@filter false
            if (WeekBoardRules.parked(t)) {
                val w = t.boardWeek!!
                return@filter w == weekStart || (current && w < weekStart)
            }
            val at = t.scheduledAtMs ?: return@filter false
            if (t.waitsForItsDay(today)) return@filter false
            val d = calendar.epochDayOf(at)
            inWeek(d) || (current && d < weekStart)
        }
        val planned = done.size + open.size
        if (planned == 0) return BoardWeekReview(weekStart, 0, 0, NOTHING_PLANNED, emptyList())

        val end = if (current) today else last + 1
        val slips = open.filter { !it.isRepeating }.mapNotNull { t ->
            if (WeekBoardRules.parked(t)) {
                val weeks = (weekStart - t.boardWeek!!) / 7
                return@mapNotNull if (weeks >= 1) Triple(t, weeks * 7, if (weeks == 1L) "Parked since last week" else "Parked $weeks weeks") else null
            }
            val days = end - calendar.epochDayOf(t.scheduledAtMs!!)
            if (days >= SLIP_DAYS || t.carryAskedDay != null) Triple(t, days, if (days <= 1) "Carried from yesterday" else "Carried $days days") else null
        }.sortedWith(compareByDescending<Triple<Task, Long, String>> { it.second }.thenBy { it.first.createdAtMs }.thenBy { it.first.id })
            .take(MAX_SLIPS)
            .map { (t, _, why) -> BoardSlip(t.id, TaskTitleRules.display(t.title), why) }

        val best = done.groupBy { calendar.epochDayOf(it.completedAtMs!!) }
            .maxWithOrNull(compareBy<Map.Entry<Long, List<Task>>> { it.value.size }.thenByDescending { it.key })
            ?.takeIf { it.value.size >= BEST_DAY_MIN && done.size > it.value.size }
        val line = listOfNotNull(
            "${done.size} of $planned planned done",
            best?.let { "best day ${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(it.key) - 1]}, ${it.value.size} done" },
        ).joinToString(" · ")
        return BoardWeekReview(weekStart, planned, done.size, line, slips)
    }

    /**
     * The one line to carry into a week's plan: the first line of what the reflection for the week before [planWeek]
     * said matters ("From your review · Run 5k"), or null.
     */
    fun carryLine(all: List<Reflection>, planWeek: Long): String? {
        val r = all.firstOrNull { it.weekStart == planWeek - 7 && it.written } ?: return null
        val thing = r.matters.split('\n').map { it.trim().trimEnd('.', '!', ';', ',') }.firstOrNull { it.isNotEmpty() } ?: return null
        return "$CARRY_PREFIX · $thing"
    }
}
