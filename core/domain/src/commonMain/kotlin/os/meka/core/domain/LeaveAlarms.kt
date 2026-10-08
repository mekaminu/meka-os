package os.meka.core.domain

/**
 * Alarms, slice 3: **leave-by alarms** (Meka, 2026-10-07). Non-AI, pure.
 *
 * Leave by on a calendar event (calendar actions) is a heads-up notice "Leave now for Dentist" at the start less the
 * travel time Meka set. With **Ring as an alarm** on (a synced `event_mark.leaveAlarm`), it rings instead, like the
 * wake alarm: full screen over the lock screen on the Fold (Snooze 9 min, slide to dismiss), a notification with
 * Snooze / Dismiss on the Mac; the heads-up isn't posted as well.
 *
 * Nothing is stored to make it ring: the alarm is worked out from the event and its marks on every device, with an id
 * holding the event, its start and the travel time ([id]), so moving the event or changing the travel time makes a
 * fresh alarm at the new time and the old one is gone. Snooze and Dismiss store it under that id (an `alarm` of kind
 * `LEAVE`), so answering on either device ends it on both.
 *
 * Same rules as the leave-by notice: the event needs a place (not just a call link), isn't all day or hidden from my
 * day, and the travel time is Meka's own (no maps lookup). An alarm nobody answers rings for 10 minutes like any other
 * and is never rung late after the phone was off.
 */
object LeaveAlarmRules {
    const val TITLE = "Time to leave"
    /** The words the event detail and long-press menu use for the switch. */
    const val SWITCH_LABEL = "Ring as an alarm"
    private const val MIN_MS = 60_000L
    private const val TITLE_MAX = 60

    /** The alarm's id: the same on every device, new whenever the event moves or the travel time changes. */
    fun id(eventId: String, startAtMs: Long, travelMin: Int): String = "leave.e$eventId.s$startAtMs.t$travelMin"

    /** The place to go to, or null when there is none (no location, or only a call link). */
    fun place(e: CalendarEvent): String? = e.location?.trim()?.takeIf { it.isNotEmpty() && !ReminderRules.isLink(it) }

    /** Whether [e]'s leave-by rings as an alarm (rather than a heads-up) right now. */
    fun rings(e: CalendarEvent, marks: EventMarks): Boolean =
        e.id in marks.leaveAlarms && marks.travel[e.id] != null && !e.allDay && place(e) != null && !marks.isHidden(e.id)

    /** "Dentist at 14:00 · High St Surgery": what the ringing screen and notification say under "Time to leave". */
    fun note(e: CalendarEvent, cal: LocalCalendar): String {
        val title = e.title.trim().ifEmpty { "Your event" }.let { if (it.length > TITLE_MAX) it.take(TITLE_MAX - 1).trimEnd() + "…" else it }
        return listOfNotNull("$title at ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))}", place(e)).joinToString(" · ")
            .take(Alarms.NOTE_MAX)
    }

    /** The leave-by alarms of events still to start: one per event that [rings], at its start less the travel time. */
    fun alarms(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, cal: LocalCalendar): List<Alarm> {
        if (marks.leaveAlarms.isEmpty()) return emptyList()
        return events.mapNotNull { e ->
            if (!rings(e, marks) || e.startAtMs <= nowMs) return@mapNotNull null
            val travel = marks.travel.getValue(e.id)
            // Kept until the event starts, so a snoozed one can still ring; [AlarmRules.next] leaves out one whose ten
            // minutes have gone (never rung late).
            val at = e.startAtMs - travel * MIN_MS
            Alarm(
                id = id(e.id, e.startAtMs, travel),
                kind = AlarmKind.LEAVE,
                epochDay = cal.epochDayOf(at),
                minute = cal.minuteOfDay(at),
                atMs = at,
                off = false,
                snoozedUntilMs = null,
                dismissedAtMs = null,
                note = note(e, cal),
            )
        }
    }
}
