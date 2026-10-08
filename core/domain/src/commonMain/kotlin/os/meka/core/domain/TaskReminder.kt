package os.meka.core.domain

/** One chip in the task detail's Remind me row: "At 14:30", "15 min before", "In 1 h", "18:00". */
data class ReminderChoice(val label: String, val atMs: Long, val selected: Boolean)

/**
 * The task detail's Remind me row as shown (Fold review 2026-10-08, item 8). [label] is the row's summary: "Off", or
 * when it will remind ("Today · 14:15", "Thu 15 Oct · 09:00"). [choices] are the chips still ahead of now.
 */
data class TaskReminderView(
    val label: String,
    val isSet: Boolean,
    val atMs: Long?,
    val choices: List<ReminderChoice>,
) {
    /** For Swift: the reminder's time, or -1 for none. */
    val atMsOrNone: Long get() = atMs ?: -1L
}

/**
 * Remind me on a task (non-AI, pure). A reminder is a time (`remindAtMs`, synced, last writer wins) that becomes a
 * heads-up through the notification governor ([NoticeSource.TASK_REMINDER], CLOCK precision per ADR-007), so quiet
 * hours, digests and each device's choice apply like everything else. It moves with the task's When (and Tomorrow and
 * its repeats), and Done, Someday or Delete silence it.
 *
 * Chips, only those still ahead of now:
 * - a task with a time: "At 14:30" · "15 min before" · "1 h before";
 * - a task without one: "In 1 h" (today, from the next quarter hour) · "09:00" · "13:00" · "18:00" on its day;
 * - a reminder set elsewhere that isn't one of these is listed too, selected.
 *
 * A reminder that couldn't post (the phone was off) still goes out within [STALE_MS]; after that it is dropped.
 */
object TaskReminderRules {
    const val STALE_MS = 2 * 60 * 60_000L
    val BEFORE_CHOICES = listOf(0, 15, 60)
    val TIMES_OF_DAY = listOf(9 * 60, 13 * 60, 18 * 60)
    private const val MIN_MS = 60_000L

    fun view(task: Task, nowMs: Long, cal: LocalCalendar): TaskReminderView {
        val today = cal.epochDayOf(nowMs)
        val day = TaskWhenRules.dayOf(task, today, cal)
        val minute = TaskWhenRules.minuteOf(task, today, cal)
        val at = task.remindAtMs?.takeIf { it > nowMs }
        val base = buildList {
            if (minute != null) {
                val start = cal.toEpochMs(day, minute)
                BEFORE_CHOICES.forEach { b -> add((if (b == 0) "At ${TaskWhenRules.timeLabel(minute)}" else beforeLabel(b)) to start - b * MIN_MS) }
            } else {
                if (day == today) add("In 1 h" to inAnHour(nowMs, cal))
                TIMES_OF_DAY.forEach { m -> add(TaskWhenRules.timeLabel(m) to cal.toEpochMs(day, m)) }
            }
        }.filter { it.second > nowMs }.distinctBy { it.second }
        val choices = base.map { (l, ms) -> ReminderChoice(l, ms, ms == at) }.toMutableList()
        if (at != null && choices.none { it.selected }) {
            val d = cal.epochDayOf(at)
            val t = TaskWhenRules.timeLabel(cal.minuteOfDay(at))
            choices += ReminderChoice(if (d == day) t else TaskWhenRules.label(d, cal.minuteOfDay(at), today), at, true)
        }
        return TaskReminderView(
            label = at?.let { TaskWhenRules.label(cal.epochDayOf(it), cal.minuteOfDay(it), today) } ?: "Off",
            isSet = at != null,
            atMs = at,
            choices = choices,
        )
    }

    /** "15 min before", "1 h before". */
    fun beforeLabel(minutes: Int): String = "${EventDetails.durationLabel(minutes * MIN_MS)} before"

    /** An hour from the next quarter hour: 09:52 → 11:00. */
    fun inAnHour(nowMs: Long, cal: LocalCalendar): Long {
        val day = cal.epochDayOf(nowMs)
        val m = cal.minuteOfDay(nowMs)
        val quarter = ((m + TaskWhenRules.TIME_STEP_MIN - 1) / TaskWhenRules.TIME_STEP_MIN) * TaskWhenRules.TIME_STEP_MIN
        return cal.toEpochMs(day, 0) + (quarter + 60) * MIN_MS
    }

    /** The heads-ups for open tasks with a reminder. The key holds the time, so a moved reminder reminds again. */
    fun notices(tasks: List<Task>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return tasks.mapNotNull { t ->
            val at = t.remindAtMs ?: return@mapNotNull null
            if (t.lifecycle.isTerminal || t.lifecycle == Lifecycle.SOMEDAY) return@mapNotNull null
            if (nowMs >= at + STALE_MS) return@mapNotNull null
            val minute = TaskWhenRules.minuteOf(t, today, cal)
            Notice(
                key = "task:${t.id}:remind:$at", source = NoticeSource.TASK_REMINDER, tier = NoticeTier.HEADS_UP,
                title = t.title, text = noticeText(t, minute, cal),
                atMs = at, target = NoticeTarget.TODAY, expiresAtMs = at + STALE_MS,
                precision = NoticePrecision.CLOCK,
            )
        }
    }

    /** "Planned for 14:30" · "Due Fri 9 Oct" · "On your list". */
    internal fun noticeText(t: Task, minute: Int?, cal: LocalCalendar): String = when {
        minute != null -> "Planned for ${TaskWhenRules.timeLabel(minute)}"
        t.dueAtMs != null -> "Due ${CivilDate.shortLabel(cal.epochDayOf(t.dueAtMs))}"
        else -> "On your list"
    }
}
