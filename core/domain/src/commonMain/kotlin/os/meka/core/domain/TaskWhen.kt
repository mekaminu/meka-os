package os.meka.core.domain

/** One day chip in the task detail's When row: "Today", "Tomorrow", or a picked day ("Thu 15 Oct"). */
data class WhenChoice(val label: String, val day: Long, val selected: Boolean)

/**
 * The task detail's When row as shown (Fold review 2026-10-08, item 8): the day it's on, an optional time, and the
 * chips. [label] is the row's summary ("Today", "Today · 14:30", "Tomorrow · 09:00", "Thu 15 Oct"); [minute] is null
 * while it has no time; [suggestedMinute] is the time "Add a time" starts from.
 */
data class TaskWhenView(
    val label: String,
    val day: Long,
    val minute: Int?,
    val timeLabel: String?,
    val chips: List<WhenChoice>,
    val suggestedMinute: Int,
) {
    /** For Swift: the time as minutes past midnight, or -1 while there is none. */
    val minuteOrNone: Int get() = minute ?: -1
}

/**
 * When: which day a task is on and, optionally, at what time (non-AI, pure). A task with a time is planned on the
 * timeline that day; without one it sits under Anytime. A day after today keeps the task out of Today until then
 * (the same `deferredToDay` as Tomorrow), so the planner, the brief and the shutdown all agree.
 *
 * - The task's day: its planned day, else the day it shows from, else today; an earlier day (overdue) reads as today.
 * - Chips: Today · Tomorrow, plus the chosen day when it's later ("Thu 15 Oct"); the app offers "Pick a date" too.
 * - Time steps by [TIME_STEP_MIN] within the day; "Add a time" starts at the next quarter hour at least 15 minutes
 *   away (today), or 09:00 on another day.
 */
object TaskWhenRules {
    const val TIME_STEP_MIN = 15
    const val DEFAULT_MINUTE = 9 * 60
    /** How far ahead a day can be picked. */
    const val MAX_DAYS_AHEAD = 730
    private const val LAST_MINUTE = 24 * 60 - TIME_STEP_MIN

    fun dayOf(task: Task, today: Long, calendar: LocalCalendar): Long {
        val d = task.scheduledAtMs?.let(calendar::epochDayOf) ?: task.showsFromDay ?: today
        return maxOf(d, today)
    }

    fun minuteOf(task: Task, today: Long, calendar: LocalCalendar): Int? {
        val at = task.scheduledAtMs ?: return null
        // An overdue plan from an earlier day isn't a time today.
        return if (calendar.epochDayOf(at) < today) null else calendar.minuteOfDay(at)
    }

    fun view(task: Task, nowMs: Long, calendar: LocalCalendar): TaskWhenView {
        val today = calendar.epochDayOf(nowMs)
        val day = dayOf(task, today, calendar)
        val minute = minuteOf(task, today, calendar)
        val chips = buildList {
            add(WhenChoice("Today", today, day == today))
            add(WhenChoice("Tomorrow", today + 1, day == today + 1))
            if (day > today + 1) add(WhenChoice(CivilDate.shortLabel(day), day, true))
        }
        return TaskWhenView(
            label = label(day, minute, today),
            day = day,
            minute = minute,
            timeLabel = minute?.let(::timeLabel),
            chips = chips,
            suggestedMinute = suggestedMinute(day, today, calendar.minuteOfDay(nowMs)),
        )
    }

    /** "Today", "Today · 14:30", "Tomorrow", "Thu 15 Oct · 09:00". */
    fun label(day: Long, minute: Int?, today: Long): String {
        val d = when (day) {
            today -> "Today"
            today + 1 -> "Tomorrow"
            else -> CivilDate.shortLabel(day)
        }
        return if (minute == null) d else "$d · ${timeLabel(minute)}"
    }

    fun timeLabel(minute: Int): String {
        val m = minute.coerceIn(0, 24 * 60 - 1)
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** Next quarter hour at least 15 minutes from now (capped at 23:45) today; 09:00 on any other day. */
    fun suggestedMinute(day: Long, today: Long, nowMinute: Int): Int {
        if (day != today) return DEFAULT_MINUTE
        val next = ((nowMinute + TIME_STEP_MIN + TIME_STEP_MIN - 1) / TIME_STEP_MIN) * TIME_STEP_MIN
        return next.coerceAtMost(LAST_MINUTE)
    }

    /** ‹ › on the time: [steps] quarter hours on, kept within the day (no wrap past midnight). */
    fun step(minute: Int, steps: Int): Int {
        val snapped = (minute / TIME_STEP_MIN) * TIME_STEP_MIN
        return (snapped + steps * TIME_STEP_MIN).coerceIn(0, LAST_MINUTE)
    }

    /** The undo bar after Delete in the detail. */
    fun deletedLine(title: String): String = "Deleted “${title.take(40).trim()}${if (title.length > 40) "…" else ""}”"
}
