package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/** Fields of an `event_mark` (one per calendar event, id = the event's id). MEKA-only: the real calendar is untouched. */
object EventMarkFields {
    /** Hidden from my day: the timeline, the planner, the brief, the shutdown and the review leave it out. */
    const val HIDDEN = "hidden"
    const val HIDDEN_AT = "hiddenAtMs"
}

/** What MEKA knows about events beyond the provider's mirror: which are hidden and which have a prep task. */
data class EventMarks(
    val hidden: Set<String>,
    /** Event id → its prep task (open or done; deleted ones are gone). */
    val prepTasks: Map<String, Task>,
) {
    fun isHidden(eventId: String) = eventId in hidden

    /** The events that count for the day: everything not hidden. */
    fun visible(events: List<CalendarEvent>): List<CalendarEvent> =
        if (hidden.isEmpty()) events else events.filter { it.id !in hidden }

    companion object {
        val NONE = EventMarks(emptySet(), emptyMap())
    }
}

/**
 * Calendar actions (Meka, 2026-10-07: "are they just there so I am aware?"), non-AI. Nothing here changes Meka's
 * real calendars; the events stay a read-only mirror.
 *
 * - **Prep task:** a task linked to the event ([TaskFields.EVENT_ID]), "Prepare for Call with Tunde", due at the
 *   event's start and planned [PREP_LEAD_MIN] minutes before it for [PREP_MINUTES] minutes (left unplanned when that
 *   time has already gone). Its id comes from the event, so tapping twice, or on both devices offline, makes one task;
 *   adding it again after deleting or finishing it brings the same task back.
 * - **Hide from my day:** an `event_mark` with `hidden = true`. The day's timeline, Plan my day, the brief, the
 *   shutdown and the review leave it out; the Calendar tab lists it under its day so it can be shown again. Last tap
 *   wins across devices.
 */
class EventActions(
    private val replica: Replica,
    private val tasks: Tasks,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun marks(all: List<Task> = tasks.all()): EventMarks {
        val hidden = replica.entities(EntityTypes.EVENT_MARK)
            .filter { it[EventMarkFields.HIDDEN].boolOrNull == true }
            .map { it.ref.entityId }.toSet()
        val prep = all.filter { it.eventId != null && it.lifecycle != Lifecycle.CANCELLED }.associateBy { it.eventId!! }
        return EventMarks(hidden, prep)
    }

    /** Adds (or brings back) the prep task for [event]; returns its id. An open one is left as it is. */
    fun addPrep(event: CalendarEvent): String {
        val id = prepTaskId(event.id)
        val existing = tasks.get(id)
        if (existing != null && !existing.lifecycle.isTerminal) return id
        val p = PrepRules.plan(event, nowMs(), calendar)
        tasks.createWithId(
            id,
            NewTask(p.title, dueAtMs = p.dueAtMs, scheduledAtMs = p.scheduledAtMs, estimateMinutes = PREP_MINUTES),
            mapOf(TaskFields.EVENT_ID to event.id.fv()),
        )
        return id
    }

    fun hide(eventId: String) = setHidden(eventId, true)
    fun show(eventId: String) = setHidden(eventId, false)

    private fun setHidden(eventId: String, hidden: Boolean) {
        val current = replica.entity(EntityTypes.EVENT_MARK, eventId)?.get(EventMarkFields.HIDDEN)?.boolOrNull ?: false
        if (current == hidden) return
        replica.commitLocal(
            EntityTypes.EVENT_MARK, eventId,
            mapOf(EventMarkFields.HIDDEN to hidden.fv(), EventMarkFields.HIDDEN_AT to (if (hidden) nowMs().fv() else FieldValue.Null)),
        )
    }

    companion object {
        const val PREP_MINUTES = 15
        const val PREP_LEAD_MIN = 30

        /** The prep task's id for an event: the same on every device. */
        fun prepTaskId(eventId: String) = "p$eventId"
    }
}

/** Where a prep task goes. Pure. */
object PrepRules {
    const val MAX_TITLE = 200
    private const val MIN_MS = 60_000L

    data class Plan(val title: String, val dueAtMs: Long, val scheduledAtMs: Long?)

    fun plan(event: CalendarEvent, nowMs: Long, calendar: LocalCalendar): Plan {
        val title = cut("Prepare for ${event.title}")
        if (event.allDay) {
            // All-day bounds are UTC midnights; prepare by 09:00 local on its first day, no planned time.
            val firstDay = event.startAtMs.floorDiv(CivilDate.DAY_MS)
            return Plan(title, calendar.toEpochMs(firstDay, 9 * 60), null)
        }
        val at = event.startAtMs - EventActions.PREP_LEAD_MIN * MIN_MS
        return Plan(title, event.startAtMs, at.takeIf { it > nowMs })
    }

    private fun cut(s: String): String {
        if (s.length <= MAX_TITLE) return s
        val head = s.take(MAX_TITLE - 1)
        val space = head.lastIndexOf(' ')
        return (if (space > MAX_TITLE / 2) head.take(space) else head).trimEnd() + "…"
    }
}
