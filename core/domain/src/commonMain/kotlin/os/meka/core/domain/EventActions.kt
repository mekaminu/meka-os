package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/** Fields of an `event_mark` (one per calendar event, id = the event's id). MEKA-only: the real calendar is untouched. */
object EventMarkFields {
    /** Hidden from my day: the timeline, the planner, the brief, the shutdown and the review leave it out. */
    const val HIDDEN = "hidden"
    const val HIDDEN_AT = "hiddenAtMs"
    /** Remind me this many minutes before the start (Int; null: no reminder). */
    const val REMIND_MIN = "remindMin"
    /** Leave by: how many minutes it takes to get there (Int; null: no leave-by reminder). */
    const val TRAVEL_MIN = "travelMin"
    /** Leave by rings as an alarm instead of a heads-up (Bool; Alarms, slice 3, [LeaveAlarmRules]). */
    const val LEAVE_ALARM = "leaveAlarm"
}

/** What MEKA knows about events beyond the provider's mirror: which are hidden and which have a prep task. */
data class EventMarks(
    val hidden: Set<String>,
    /** Event id → its prep task (open or done; deleted ones are gone). */
    val prepTasks: Map<String, Task>,
    /** Event id → remind me this many minutes before. */
    val reminders: Map<String, Int> = emptyMap(),
    /** Event id → minutes it takes to get there (a leave-by reminder). */
    val travel: Map<String, Int> = emptyMap(),
    /** Calendars hidden from Today: key ([CalendarRules.key]) → name (see [CalendarRules]). */
    val hiddenCalendars: Map<String, String> = emptyMap(),
    /** Events whose leave-by rings as an alarm ([LeaveAlarmRules]). */
    val leaveAlarms: Set<String> = emptySet(),
    /** Calendars Meka turned on in Calendars (a mark with `hiddenFromToday = false`): a holiday calendar shows then. */
    val shownCalendars: Set<String> = emptySet(),
) {
    fun isHidden(eventId: String) = eventId in hidden

    /** Whether [e]'s calendar is hidden from Today. */
    fun isCalendarHidden(e: CalendarEvent): Boolean = isCalendarKeyHidden(CalendarRules.key(e))

    /**
     * For Swift too: whether the calendar with [key] is hidden from Today — turned off, or a holiday calendar
     * ([HolidayCalendars]) Meka hasn't turned on.
     */
    fun isCalendarKeyHidden(key: String): Boolean =
        key in hiddenCalendars || (key !in shownCalendars && HolidayCalendars.isHolidayKey(key))

    /**
     * The events the Calendar tab shows: all of them but a holiday calendar's ([HolidayCalendars]) while Meka hasn't
     * turned it on (Fold review 2026-10-09 07:26, item 9). A calendar Meka turned off himself stays in the tab.
     */
    fun forCalendarTab(events: List<CalendarEvent>): List<CalendarEvent> =
        events.filter { e -> !HolidayCalendars.isHoliday(e) || CalendarRules.key(e) in shownCalendars }

    /** For Swift: the reminder's minutes, or 0 for none. */
    fun reminderOf(eventId: String): Int = reminders[eventId] ?: 0

    /** For Swift: the travel minutes, or 0 for none. */
    fun travelOf(eventId: String): Int = travel[eventId] ?: 0

    /** For Swift: whether the event's leave-by rings as an alarm. */
    fun leaveRingsOf(eventId: String): Boolean = eventId in leaveAlarms

    /**
     * The events that count for the day: everything not hidden, one by one or by its calendar, with the same event on
     * several calendars as one row ([DuplicateEvents]); hiding any one of a merged row's events hides the row.
     */
    fun visible(events: List<CalendarEvent>): List<CalendarEvent> {
        val byCalendar = HashMap<String, Boolean>()
        val shown = events.filter { e -> !byCalendar.getOrPut(CalendarRules.key(e)) { isCalendarKeyHidden(CalendarRules.key(e)) } }
        return DuplicateEvents.merge(shown).filter { !DuplicateEvents.hiddenIn(it, hidden) }
    }

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
        val entities = replica.entities(EntityTypes.EVENT_MARK)
        val hidden = entities
            .filter { it[EventMarkFields.HIDDEN].boolOrNull == true }
            .map { it.ref.entityId }.toSet()
        // Only prep tasks: an all-day entry made into a task is linked to its event too, but isn't its prep.
        val prep = all.filter { it.eventId != null && it.id == prepTaskId(it.eventId) && it.lifecycle != Lifecycle.CANCELLED }
            .associateBy { it.eventId!! }
        fun minutes(field: String) = entities.mapNotNull { e ->
            e[field].longOrNull?.toInt()?.takeIf { it in 1..ReminderRules.MAX_MIN }?.let { e.ref.entityId to it }
        }.toMap()
        val calendarMarks = replica.entities(EntityTypes.CALENDAR_MARK)
        val shown = calendarMarks.filter { it[CalendarMarkFields.HIDDEN_FROM_TODAY].boolOrNull == false }
            .mapNotNull { it[CalendarMarkFields.KEY].textOrNull }.toSet()
        val calendars = calendarMarks
            .filter { it[CalendarMarkFields.HIDDEN_FROM_TODAY].boolOrNull == true }
            .mapNotNull { c ->
                val key = c[CalendarMarkFields.KEY].textOrNull ?: return@mapNotNull null
                key to (c[CalendarMarkFields.LABEL].textOrNull ?: "")
            }.toMap()
        val leaveAlarms = entities.filter { it[EventMarkFields.LEAVE_ALARM].boolOrNull == true }.map { it.ref.entityId }.toSet()
        return EventMarks(hidden, prep, minutes(EventMarkFields.REMIND_MIN), minutes(EventMarkFields.TRAVEL_MIN), calendars, leaveAlarms, shown)
    }

    /**
     * "Hide <calendar> from Today" (all-day polish): every event of the calendar with [key] leaves my day; the Calendar
     * tab and Search keep them. Last switch on any device wins; [showCalendar] is the undo and the Calendars switch.
     */
    fun hideCalendar(key: String, label: String) = setCalendarHidden(key, label, true)

    /**
     * Meka's own name for every calendar he renamed: key → name ([CalendarMarkFields.NAME]). Read on its own (no
     * tasks needed) because every event list is named with it.
     */
    fun calendarNames(): Map<String, String> = replica.entities(EntityTypes.CALENDAR_MARK).mapNotNull { c ->
        val key = c[CalendarMarkFields.KEY].textOrNull ?: return@mapNotNull null
        val name = c[CalendarMarkFields.NAME].textOrNull?.let { CalendarRules.cleanName(it) } ?: return@mapNotNull null
        key to name
    }.toMap()

    /**
     * Renames the calendar with [key] in MEKA only (Fold review 2026-10-09 07:26, item 9): the real calendar keeps its
     * name. Blank goes back to the default ([CalendarRules.defaultName]). Synced, last rename wins. Returns the name
     * stored, or null for the default.
     */
    fun renameCalendar(key: String, typed: String): String? {
        require(key.isNotEmpty()) { "calendar key is empty" }
        val name = CalendarRules.cleanName(typed)
        val id = CalendarRules.markId(key)
        val current = replica.entity(EntityTypes.CALENDAR_MARK, id)?.get(CalendarMarkFields.NAME)?.textOrNull
            ?.let { CalendarRules.cleanName(it) }
        if (current == name) return name
        replica.commitLocal(EntityTypes.CALENDAR_MARK, id, mapOf(CalendarMarkFields.KEY to key.fv(), CalendarMarkFields.NAME to name.fv()))
        return name
    }
    fun showCalendar(key: String) = setCalendarHidden(key, null, false)

    private fun setCalendarHidden(key: String, label: String?, hidden: Boolean) {
        require(key.isNotEmpty()) { "calendar key is empty" }
        val id = CalendarRules.markId(key)
        val current = replica.entity(EntityTypes.CALENDAR_MARK, id)
        // No mark yet and showing: still written when it's a holiday calendar, which starts hidden ([HolidayCalendars]).
        val was = current?.get(CalendarMarkFields.HIDDEN_FROM_TODAY)?.boolOrNull ?: HolidayCalendars.isHolidayKey(key)
        if (was == hidden) return
        val fields = buildMap {
            put(CalendarMarkFields.KEY, key.fv())
            put(CalendarMarkFields.HIDDEN_FROM_TODAY, hidden.fv())
            put(CalendarMarkFields.HIDDEN_AT, if (hidden) nowMs().fv() else FieldValue.Null)
            label?.takeIf { it.isNotBlank() }?.let { put(CalendarMarkFields.LABEL, it.trim().take(200).fv()) }
        }
        replica.commitLocal(EntityTypes.CALENDAR_MARK, id, fields)
    }

    /** Remind me [minutes] before [eventId] starts; null (or 0) turns the reminder off. */
    fun setReminder(eventId: String, minutes: Int?) = setMinutes(eventId, EventMarkFields.REMIND_MIN, minutes)

    /** A leave-by reminder [travelMinutes] before [eventId] starts (how long it takes to get there); null (or 0) turns it off. */
    fun setLeaveBy(eventId: String, travelMinutes: Int?) = setMinutes(eventId, EventMarkFields.TRAVEL_MIN, travelMinutes)

    /**
     * Ring as an alarm (Alarms, slice 3): [eventId]'s leave-by rings like the wake alarm instead of posting a heads-up.
     * It only rings while a travel time is set and the event has a place; the switch is kept either way. Last tap wins.
     */
    fun setLeaveAlarm(eventId: String, on: Boolean) {
        // An event added in MEKA that isn't in the mirror yet gets no marks (its id goes when the real one lands).
        if (PendingEditRules.isProvisional(eventId)) return
        val current = replica.entity(EntityTypes.EVENT_MARK, eventId)?.get(EventMarkFields.LEAVE_ALARM)?.boolOrNull ?: false
        if (current == on) return
        replica.commitLocal(EntityTypes.EVENT_MARK, eventId, mapOf(EventMarkFields.LEAVE_ALARM to on.fv()))
    }

    private fun setMinutes(eventId: String, field: String, minutes: Int?) {
        // An event added in MEKA that isn't in the mirror yet gets no marks (its id goes when the real one lands).
        if (PendingEditRules.isProvisional(eventId)) return
        val m = minutes?.takeIf { it != 0 }
        require(m == null || m in 1..ReminderRules.MAX_MIN) { "minutes must be 1..${ReminderRules.MAX_MIN}" }
        val current = replica.entity(EntityTypes.EVENT_MARK, eventId)?.get(field)?.longOrNull?.toInt()
        if (current == m) return
        replica.commitLocal(EntityTypes.EVENT_MARK, eventId, mapOf(field to m.fv()))
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

    /**
     * "Make it a task" for an all-day entry that reads like a to-do ([AllDayRules]): a task with the entry's title, no
     * date (so it sits in Today's Anytime list until done), linked to the event; the entry then leaves Today (hidden,
     * like "Hide from my day") so it isn't shown twice. Its id comes from the event, so a double tap or both devices
     * offline make one task; an open one is left as it is. Returns the task's id.
     */
    fun makeTask(event: CalendarEvent): String {
        val id = allDayTaskId(event.id)
        val existing = tasks.get(id)
        if (existing == null || existing.lifecycle.isTerminal) {
            tasks.createWithId(id, NewTask(cutTitle(event.title)), mapOf(TaskFields.EVENT_ID to event.id.fv()))
        }
        hide(event.id)
        return id
    }

    /** Undo for [makeTask]: deletes the task (if still there) and shows the entry again. */
    fun unmakeTask(eventId: String) {
        val id = allDayTaskId(eventId)
        if (tasks.get(id) != null) tasks.delete(id)
        show(eventId)
    }

    fun hide(eventId: String) = setHidden(eventId, true)
    fun show(eventId: String) = setHidden(eventId, false)

    private fun setHidden(eventId: String, hidden: Boolean) {
        // An event added in MEKA that isn't in the mirror yet gets no marks (its id goes when the real one lands).
        if (PendingEditRules.isProvisional(eventId)) return
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

        /** The task made from an all-day entry: the same on every device. */
        fun allDayTaskId(eventId: String) = "a$eventId"

        private fun cutTitle(s: String) = s.trim().let { if (it.length <= PrepRules.MAX_TITLE) it else it.take(PrepRules.MAX_TITLE - 1).trimEnd() + "…" }
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

/**
 * Remind me and Leave by (calendar actions, slice 2), non-AI and pure. Both are notices for the notification governor
 * (source [NoticeSource.EVENT_REMINDER], Heads-up, CLOCK precision per ADR-007), so quiet hours and the device's
 * choice apply like everything else; a reminder that can't post before the event starts is dropped, never sent late.
 *
 * - Remind me: [REMIND_CHOICES] minutes before a timed event: "Call with Tunde" · "In 10 min · 14:00 · Room 4".
 * - Leave by: offered when the event has a place; "it takes 30 min to get there" posts at start − 30 min:
 *   "Leave now for Dentist" · "Starts 14:00 · 30 min away · High St Surgery". No maps lookup: the travel time is Meka's.
 *
 * A notice's key holds the event's start, so a moved event reminds again at its new time. Hidden events, all-day
 * events and events that have started don't remind.
 */
object ReminderRules {
    const val MAX_MIN = 240
    val REMIND_CHOICES = listOf(5, 10, 15, 30)
    val TRAVEL_CHOICES = listOf(10, 15, 20, 30, 45, 60)
    private const val MIN_MS = 60_000L

    fun notices(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, cal: LocalCalendar): List<Notice> {
        if (marks.reminders.isEmpty() && marks.travel.isEmpty()) return emptyList()
        val out = mutableListOf<Notice>()
        for (e in events) {
            if (e.allDay || e.startAtMs <= nowMs || marks.isHidden(e.id)) continue
            val start = LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))
            val place = e.location?.trim()?.takeIf { it.isNotEmpty() && !isLink(it) }
            marks.reminders[e.id]?.let { m ->
                out += Notice(
                    key = "event:${e.id}:${e.startAtMs}:remind:$m", source = NoticeSource.EVENT_REMINDER, tier = NoticeTier.HEADS_UP,
                    title = e.title, text = listOfNotNull("In ${EventDetails.durationLabel(m * MIN_MS)}", start, place).joinToString(" · "),
                    atMs = e.startAtMs - m * MIN_MS, target = NoticeTarget.TODAY, expiresAtMs = e.startAtMs,
                    precision = NoticePrecision.CLOCK,
                )
            }
            val travel = marks.travel[e.id]
            // Ring as an alarm: the alarm rings instead ([LeaveAlarmRules]), so no heads-up as well.
            if (travel != null && place != null && !LeaveAlarmRules.rings(e, marks)) {
                out += Notice(
                    key = "event:${e.id}:${e.startAtMs}:leave:$travel", source = NoticeSource.EVENT_REMINDER, tier = NoticeTier.HEADS_UP,
                    title = "Leave now for ${e.title}",
                    text = "Starts $start · ${EventDetails.durationLabel(travel * MIN_MS)} away · $place",
                    atMs = e.startAtMs - travel * MIN_MS, target = NoticeTarget.TODAY, expiresAtMs = e.startAtMs,
                    precision = NoticePrecision.CLOCK,
                )
            }
        }
        return out
    }

    /** The reminder choices still ahead of now for [e] (a reminder set to a time already gone would post at once). */
    fun remindChoices(e: CalendarEvent, nowMs: Long): List<Int> =
        if (e.allDay) emptyList() else REMIND_CHOICES.filter { e.startAtMs - it * MIN_MS > nowMs }

    /** The travel times still ahead of now; none when the event has no place to go to. */
    fun travelChoices(e: CalendarEvent, nowMs: Long): List<Int> {
        val place = e.location?.trim()?.takeIf { it.isNotEmpty() && !isLink(it) }
        if (e.allDay || place == null) return emptyList()
        return TRAVEL_CHOICES.filter { e.startAtMs - it * MIN_MS > nowMs }
    }

    /** "Reminder 10 min before" · "Leave by 13:30 · 30 min away"; null when neither is set. */
    fun line(e: CalendarEvent, marks: EventMarks, cal: LocalCalendar): String? {
        val parts = mutableListOf<String>()
        marks.reminders[e.id]?.let { parts += "Reminder ${EventDetails.durationLabel(it * MIN_MS)} before" }
        marks.travel[e.id]?.takeIf { !e.allDay }?.let {
            parts += "Leave by ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs - it * MIN_MS))} · ${EventDetails.durationLabel(it * MIN_MS)} away" +
                if (LeaveAlarmRules.rings(e, marks)) " · $ALARM_WORD" else ""
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** Added to the leave-by line when it rings as an alarm: "Leave by 13:30 · 30 min away · alarm". */
    const val ALARM_WORD = "alarm"

    /** "10 min before" for a menu item. */
    fun choiceLabel(minutes: Int): String = "${EventDetails.durationLabel(minutes * MIN_MS)} before"

    /** "30 min away" for a menu item. */
    fun travelLabel(minutes: Int): String = "${EventDetails.durationLabel(minutes * MIN_MS)} away"

    internal fun isLink(location: String) =
        (location.startsWith("https://") || location.startsWith("http://")) && !location.contains(' ')
}
