package os.meka.core.domain

/**
 * What Add and Change on a request card do (build plan V1, "Requests from my wife become tasks", slice 4). Non-AI and
 * pure: the facade carries the plan out (a task, an event through calendar editing, a work-from-home day) and the apps
 * show [RequestAcceptRules.doneLine] in the undo bar. Nothing here replies to anyone.
 *
 * | Card | Add | Change |
 * |---|---|---|
 * | Task | a task on its day (planned at its time) | the same task, then its detail opens |
 * | Reminder | a task on its day, reminding at its time (when still ahead) | the same, then its detail opens |
 * | Event | the event in the calendar MEKA may edit; a planned task when none allows editing | a planned task, then its detail opens |
 * | Work from home | that day's work band reads "Work from home" | none |
 */
sealed class RequestPlan {
    /** A task titled [title]; on [day] (null: Anytime today), at [minute]; reminding at [remindMinute] that day. */
    data class AddTask(val title: String, val day: Long?, val minute: Int?, val remindMinute: Int?) : RequestPlan()

    /** An event on [day] at [minute] (null: all day), [lengthMin] long. */
    data class AddEvent(val title: String, val day: Long, val minute: Int?, val lengthMin: Int) : RequestPlan()

    /** [day] becomes a work-from-home day. */
    data class HomeDay(val day: Long) : RequestPlan()
}

object RequestAcceptRules {
    /** How long an event from a message is when it says nothing about its length. */
    const val EVENT_MINUTES = 60

    /**
     * The plan for [p]. [canAddEvent]: an account allows editing (Calendars). [change]: Meka pressed Change, so the
     * result is a task he can edit. Null when Change has nothing to make (work from home).
     */
    fun plan(p: RequestProposal, canAddEvent: Boolean, change: Boolean = false): RequestPlan? = when (p.kind) {
        RequestKind.TASK -> RequestPlan.AddTask(p.title, p.day, p.minute, null)
        RequestKind.REMINDER -> RequestPlan.AddTask(p.title, p.day, p.minute, p.minute)
        RequestKind.EVENT -> if (canAddEvent && !change && p.day != null) {
            RequestPlan.AddEvent(p.title, p.day, p.minute, EVENT_MINUTES)
        } else RequestPlan.AddTask(p.title, p.day, p.minute, null)
        RequestKind.WORK_FROM_HOME -> if (change) null else p.day?.let { RequestPlan.HomeDay(it) }
    }

    /**
     * The undo bar's line once it's done: "Added “Pick up dry cleaning” · Tomorrow", "Adding “Parents' evening” to
     * Google · Tue 20 Oct · 18:00", "Thu 15 Oct: work from home" ("… isn't a work day" when the schedule has none),
     * "Added “Parents' evening” as a task · allow calendar editing to add events" for an event no calendar could take.
     */
    fun doneLine(
        plan: RequestPlan,
        kind: RequestKind,
        today: Long,
        provider: String? = null,
        isWorkDay: Boolean = true,
    ): String = when (plan) {
        is RequestPlan.AddTask -> {
            val whenLine = whenLine(plan.day, plan.minute, today)
            val head = "Added “${plan.title}”"
            when {
                kind == RequestKind.EVENT -> "$head as a task" + (whenLine?.let { " · $it" } ?: "")
                plan.remindMinute != null -> "$head · reminding ${whenLine ?: "today"}"
                else -> head + (whenLine?.let { " · $it" } ?: "")
            }
        }
        is RequestPlan.AddEvent ->
            "Adding “${plan.title}” to ${provider?.let(CalendarEditRules::providerName) ?: "your calendar"} · " +
                TaskWhenRules.label(plan.day, plan.minute, today)
        is RequestPlan.HomeDay -> {
            val d = when (plan.day) {
                today -> "Today"
                today + 1 -> "Tomorrow"
                else -> CivilDate.shortLabel(plan.day)
            }
            if (isWorkDay) "$d: work from home" else "$d: work from home (not a work day)"
        }
    }

    /** "Tomorrow", "Tue 20 Oct · 18:00", "Today · 18:00"; null with neither a day nor a time. */
    fun whenLine(day: Long?, minute: Int?, today: Long): String? = when {
        day != null -> TaskWhenRules.label(day, minute, today)
        minute != null -> TaskWhenRules.label(today, minute, today)
        else -> null
    }

    /** The task's day: its own, else today when it has a time, else none (Anytime). */
    fun taskDay(plan: RequestPlan.AddTask, today: Long): Long? = plan.day ?: if (plan.minute != null) today else null

    /**
     * When the reminder fires, in ms, or null: only with a time, and only while that time is still ahead of [nowMs]
     * (a reminder for 18:00 accepted at 19:00 just plans the task).
     */
    fun remindAtMs(plan: RequestPlan.AddTask, today: Long, nowMs: Long, calendar: LocalCalendar): Long? {
        val minute = plan.remindMinute ?: return null
        val at = calendar.toEpochMs(plan.day ?: today, minute)
        return at.takeIf { it > nowMs }
    }
}
