package os.meka.core.domain

/**
 * A planned task whose time has gone by (Fold review 2026-10-09 13:45, item 3: "add mutation to PM Autopilot", planned
 * 09:15, still sat unticked above the now line at 13:44 with no hint, while Up next showed an anytime task).
 *
 * - A planned task is **late** once its planned stretch is over (its time plus its estimate, or
 *   [TimelineRules.DEFAULT_TASK_MIN] minutes) and it isn't done. Its timeline row says "Since 09:15" in the accent
 *   colour ([TimelineRow.late]).
 * - Up next shows a planned task whose time has come first (the earliest), ahead of later planned and anytime tasks;
 *   when it's late its label is lit and its line reads "Since 09:15 · 30 min".
 * - "Move to later" re-plans it in one tap: the first free stretch from now (on the quarter hour) long enough for it,
 *   before the planner's day end, around today's events (with the planner's buffer), booked sessions and the other
 *   planned tasks. Work hours aren't in the way (a work task belongs there). No room left today: no "Move to later",
 *   Tomorrow is still there.
 */
object LateTaskRules {
    const val MOVE_LATER = "Move to later"
    private const val MIN = 60_000L

    /** The task's planned stretch, in ms: its estimate, or [TimelineRules.DEFAULT_TASK_MIN] minutes. */
    fun lengthMs(t: Task): Long = (t.estimateMinutes ?: TimelineRules.DEFAULT_TASK_MIN) * MIN

    /** Planned, open, and its planned stretch is over. */
    fun isLate(t: Task, nowMs: Long): Boolean {
        val at = t.scheduledAtMs ?: return false
        val open = t.lifecycle == Lifecycle.ACTIVE || t.lifecycle == Lifecycle.INBOX
        return open && at + lengthMs(t) <= nowMs
    }

    /** "Since 09:15" */
    fun sinceLabel(hhmm: String): String = "Since $hhmm"

    /** The undo bar's line once moved: "Moved “Send the invoice” to 15:30". */
    fun movedLine(title: String, hhmm: String): String = "Moved “$title” to $hhmm"

    /**
     * Where "Move to later" puts [task]: the first quarter-hour start from now with room for it before the planner's day
     * end, clear of [events] (with buffers), [sessions] and the other [planned] tasks; null when today has no room.
     */
    fun laterSlot(
        task: Task,
        planned: List<Task>,
        events: List<CalendarEvent>,
        sessions: List<BookedSession>,
        nowMs: Long,
        day: DayWindow,
        prefs: DayPlanner.Prefs = DayPlanner.Prefs(),
    ): Long? {
        val g = prefs.granularityMin * MIN
        val start = ceilTo(nowMs, day.startMs, g)
        val end = day.startMs + prefs.dayEndMin * MIN
        if (start >= end) return null
        val busy = buildList {
            events.filter { !it.allDay && it.overlaps(day) }.forEach {
                add(DayPlanner.Slot(it.startAtMs - prefs.bufferMin * MIN, it.endAtMs + prefs.bufferMin * MIN))
            }
            sessions.forEach { add(DayPlanner.Slot(it.startMs, it.endMs)) }
            planned.filter { it.id != task.id && it.scheduledAtMs != null && it.scheduledAtMs in day }.forEach {
                add(DayPlanner.Slot(it.scheduledAtMs!!, it.scheduledAtMs + lengthMs(it)))
            }
        }
        val need = maxOf(lengthMs(task), g)
        return DayPlanner.subtract(DayPlanner.Slot(start, end), busy)
            .map { ceilTo(it.startMs, day.startMs, g) to it }
            .firstOrNull { (s, slot) -> slot.endMs - s >= need }?.first
    }

    private fun ceilTo(t: Long, origin: Long, step: Long): Long {
        val r = ((t - origin) % step + step) % step
        return if (r == 0L) t else t + (step - r)
    }
}

/** What "Move to later" did, for the undo bar: the line, and where the task was so Undo can put it back. */
data class LaterMove(val taskId: String, val fromMs: Long, val toMs: Long, val line: String)
