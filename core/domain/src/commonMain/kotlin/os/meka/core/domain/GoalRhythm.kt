package os.meka.core.domain

/**
 * Last month's goals, looked back on from the 1st (Goals, properly, item 6): on Goals for the first week of the month
 * and in a heads-up on the 1st at 09:00.
 */
data class GoalMonth(
    /** The first day (local epoch day) of the month looked back on. */
    val monthStart: Long,
    /** "October". */
    val monthName: String,
    /** Goals finished in that month, oldest first. */
    val finished: List<String>,
    /** Open goals that moved on since that month began. */
    val moved: Int,
    /** Open goals quiet for [GoalRhythmRules.QUIET_DAYS] or more (Someday aside). */
    val quiet: List<String>,
    /** "October: 2 goals finished · 3 moved on · 1 quiet". */
    val line: String,
)

/**
 * The goals' rhythm (Goals, properly, item 6; non-AI, pure, unit-tested). The Sunday pick of the week's goals waits on
 * the Sunday week ahead.
 *
 * - **Quiet:** an open goal (not Someday) that nothing has moved on for [QUIET_DAYS] days — no linked task done, no
 *   milestone ticked, no number update, no linked habit ticked, no goal under it moving ([GoalItem.lastMovedDay]; a goal
 *   nothing has ever moved counts from the day it was added). Its meta says "quiet for 2 weeks".
 * - **One gentle nudge:** on the day a goal turns quiet, one heads-up at 09:00 ([NoticeSource.GOALS], opening Goals):
 *   "“Run 5k” · quiet for 2 weeks" · "Next · Buy running shoes. One small step this week?"; several turning quiet the
 *   same day share one. Never again for that stretch: only moving it on starts the count again. Gone by the next day.
 * - **The month's review:** on the 1st at 09:00 a heads-up looks back on last month — "Goals in October" · "2 finished ·
 *   3 moved on · 1 quiet: Save £2,000. Pick this month's goals?" — and the same line heads Goals for the first
 *   [MONTH_SHOWN_DAYS] days. Nothing when there were no goals to look back on.
 */
object GoalRhythmRules {
    const val QUIET_DAYS = 14
    const val NOTICE_MIN = 9 * 60
    const val MONTH_SHOWN_DAYS = 7
    /** The month notice and line name at most this many quiet goals. */
    const val MAX_NAMED = 3

    /** Days since anything moved [goal] on (or since it was added); null for a finished or Someday goal. */
    fun quietDays(goal: GoalItem, today: Long): Long? = quietDays(goal.horizon, goal.done, goal.lastMovedDay, goal.createdDay, today)

    fun quietDays(horizon: GoalHorizon, done: Boolean, lastMovedDay: Long?, createdDay: Long?, today: Long): Long? {
        if (done || horizon == GoalHorizon.SOMEDAY) return null
        val from = lastMovedDay ?: createdDay ?: return null
        return (today - from).coerceAtLeast(0)
    }

    fun isQuiet(goal: GoalItem, today: Long): Boolean = (quietDays(goal, today) ?: 0) >= QUIET_DAYS

    /** "quiet for 2 weeks", "quiet for 3 weeks"; null while it isn't quiet. */
    fun quietLabel(days: Long?): String? {
        if (days == null || days < QUIET_DAYS) return null
        val weeks = days / 7
        return if (weeks >= 9) "quiet for 2 months+" else "quiet for $weeks weeks"
    }

    /** The day [goal] turns quiet (the nudge's day); null for a finished or Someday goal. */
    fun nudgeDay(goal: GoalItem): Long? {
        if (goal.done || goal.horizon == GoalHorizon.SOMEDAY) return null
        return (goal.lastMovedDay ?: goal.createdDay ?: return null) + QUIET_DAYS
    }

    fun monthStart(day: Long): Long = CivilDate.fromEpochDay(day).let { CivilDate.toEpochDay(it.year, it.month, 1) }

    /** The first day of the month before [day]'s. */
    fun previousMonthStart(day: Long): Long = monthStart(monthStart(day) - 1)

    /**
     * Last month's review, from [open] goals and [finished] ones (each with its [GoalItem.doneDay]); null outside the
     * first [MONTH_SHOWN_DAYS] days of a month, or with nothing to look back on.
     */
    fun month(open: List<GoalItem>, finished: List<GoalItem>, today: Long): GoalMonth? {
        val thisMonth = monthStart(today)
        if (today - thisMonth >= MONTH_SHOWN_DAYS) return null
        val from = previousMonthStart(today)
        val done = finished.filter { g -> g.done && g.doneDay != null && g.doneDay in from until thisMonth }
            .sortedWith(compareBy<GoalItem> { it.doneDay }.thenBy { it.id })
        val active = open.filter { !it.done && it.horizon != GoalHorizon.SOMEDAY && (it.createdDay ?: Long.MIN_VALUE) < thisMonth }
        if (done.isEmpty() && active.isEmpty()) return null
        val moved = active.count { (it.lastMovedDay ?: Long.MIN_VALUE) >= from }
        val quiet = active.filter { isQuiet(it, today) }
        val name = CivilDate.MONTH_LONG[CivilDate.fromEpochDay(from).month - 1]
        val parts = buildList {
            if (done.isNotEmpty()) add("${done.size} ${if (done.size == 1) "goal" else "goals"} finished")
            if (moved > 0) add("$moved moved on")
            if (quiet.isNotEmpty()) add("${quiet.size} quiet")
        }
        val line = "$name: " + parts.ifEmpty { listOf("nothing moved on") }.joinToString(" · ")
        return GoalMonth(from, name, done.map { it.title }, moved, quiet.map { it.title }, line)
    }

    private fun quote(title: String): String = "“" + title.trim().let { if (it.length <= 32) it else it.take(31).trimEnd() + "…" } + "”"

    private fun names(titles: List<String>): String {
        val shown = titles.take(MAX_NAMED).map { quote(it) }
        val more = titles.size - shown.size
        return shown.joinToString(", ") + if (more > 0) " and $more more" else ""
    }

    /** The day's gentle nudge (one notice for every goal turning quiet today) and, on the 1st, the month's review. */
    fun notices(open: List<GoalItem>, month: GoalMonth?, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        val out = mutableListOf<Notice>()
        val turning = open.filter { nudgeDay(it) == today }
        if (turning.isNotEmpty()) {
            val (title, text) = if (turning.size == 1) {
                val g = turning.single()
                val next = g.nextStep?.let { "${if (g.nextIsMilestone) "Next milestone" else "Next"} · $it. One small step this week?" }
                    ?: "No next step yet. Add one, or move it to Someday?"
                "${quote(g.title)} · quiet for 2 weeks" to next
            } else {
                "${turning.size} goals quiet for 2 weeks" to "${names(turning.map { it.title })}. One small step on one this week?"
            }
            out += Notice(
                key = "goals:quiet:$today", source = NoticeSource.GOALS, tier = NoticeTier.HEADS_UP,
                title = title, text = text, atMs = cal.toEpochMs(today, NOTICE_MIN), target = NoticeTarget.GOALS,
                expiresAtMs = cal.toEpochMs(today + 1, 0),
            )
        }
        if (month != null && today == monthStart(today)) {
            val parts = buildList {
                if (month.finished.isNotEmpty()) add("${month.finished.size} finished")
                if (month.moved > 0) add("${month.moved} moved on")
                if (month.quiet.isNotEmpty()) add("${month.quiet.size} quiet: ${names(month.quiet)}")
            }
            val text = parts.ifEmpty { listOf("Nothing moved on") }.joinToString(" · ") + ". Pick this month's goals?"
            out += Notice(
                key = "goals:month:${month.monthStart}", source = NoticeSource.GOALS, tier = NoticeTier.HEADS_UP,
                title = "Goals in ${month.monthName}", text = text, atMs = cal.toEpochMs(today, NOTICE_MIN),
                target = NoticeTarget.GOALS, expiresAtMs = cal.toEpochMs(today + 1, 0),
            )
        }
        return out
    }
}
