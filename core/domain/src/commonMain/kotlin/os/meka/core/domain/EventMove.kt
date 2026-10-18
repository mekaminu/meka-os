package os.meka.core.domain

/**
 * Edit your calendars from MEKA (build plan M1), completeness slice 7: **the Mac's drag to move and keyboard
 * shortcuts**. Non-AI, pure.
 *
 * On the Mac's Calendar an event can be dragged onto another day (a day in the week strip, or a day's heading in the
 * agenda) and keeps its time and length; in its detail sheet ⌥⌘← / ⌥⌘→ move it a day earlier or later and ⌥⌘↑ / ⌥⌘↓
 * fifteen minutes earlier or later. Each is Meka's own act on his own event, so it is an ordinary edit with the
 * five-second Undo (a repeating event moves this one only), exactly as Save on its Edit sheet would send it.
 *
 * Only what a quick gesture can't get wrong is offered ([movable]): an event on an account that allows editing, not
 * view-only, not a fixture or one still on its way to Google, not started (an all-day one: today or later, one day
 * long), and with no guests (moving a meeting changes it for other people, so that stays with its Edit sheet).
 * [whyNot] says why in words for the pointer's help.
 */
object EventMoveRules {
    private const val DAY_MS = 86_400_000L
    private const val DAY_MIN = 24 * 60

    /** The keyboard's step for ⌥⌘↑ / ⌥⌘↓. */
    const val NUDGE_MIN = 15

    const val GUESTS_NOTE = "It has guests · move it from its Edit so it's on purpose"
    const val SPAN_NOTE = "It runs over several days · change it from its Edit"
    const val ON_ITS_WAY_NOTE = "It's still on its way to your calendar · move it once it's there"
    const val SAME = "It's already then"
    const val GONE = "That time has gone"
    const val DAY_GONE = "That day has gone"
    const val TOO_FAR = "That's too far ahead"

    /** Whether [e] may be dragged or nudged; [canEdit] says whether its account allows editing. */
    fun movable(e: CalendarEvent, nowMs: Long, calendar: LocalCalendar, canEdit: (String, String) -> Boolean): Boolean =
        whyNot(e, nowMs, calendar, canEdit) == null

    /** Why [e] can't be dragged or nudged, in words, or null when it can. */
    fun whyNot(e: CalendarEvent, nowMs: Long, calendar: LocalCalendar, canEdit: (String, String) -> Boolean): String? {
        val account = e.account
        if (account == null || e.provider !in CalendarEditRules.WRITABLE || e.isFixture) return "Navilife can't change this event"
        if (e.isProvisional) return ON_ITS_WAY_NOTE
        CalendarEditRules.notEditableReason(e, canEdit)?.let { return it }
        if (e.readOnly || !canEdit(e.provider, account)) return "Navilife can't change this event"
        if (e.guests.isNotEmpty()) return GUESTS_NOTE
        if (e.allDay && e.endAtMs - e.startAtMs > DAY_MS) return SPAN_NOTE
        if (!EditEventRules.timeEditable(e, nowMs, calendar)) return EditEventRules.TIME_NOTE
        return null
    }

    /** [form] (opened on the event) moved to [day], keeping its time and length. */
    fun toDay(form: AddEventForm, day: Long): AddEventForm = form.copy(day = day)

    /**
     * [form] moved by [days] and [minutes] (across midnight when the minutes carry it there); an all-day event moves
     * by whole days only.
     */
    fun by(form: AddEventForm, days: Int, minutes: Int): AddEventForm {
        val m = form.minute ?: return form.copy(day = form.day + days)
        val total = m + minutes
        return form.copy(day = form.day + days + total.floorDiv(DAY_MIN), minute = total.mod(DAY_MIN))
    }

    /**
     * Why [moved] can't be sent, compared with [opened] (the event as it is), or null when it can: the time it already
     * has, a time (or, all day, a day) already gone, or more than two years ahead.
     */
    fun refusal(moved: AddEventForm, opened: AddEventForm, nowMs: Long, calendar: LocalCalendar): String? {
        if (moved.day == opened.day && moved.minute == opened.minute) return SAME
        val today = calendar.epochDayOf(nowMs)
        if (moved.day > today + AskRules.MAX_DAYS_AHEAD) return TOO_FAR
        val m = moved.minute
        if (m == null) return if (moved.day < today) DAY_GONE else null
        return if (calendar.toEpochMs(moved.day, m) <= nowMs) GONE else null
    }

    /** The drop's hint while an event is held over a day: "Move to Fri 23 Oct · 15:00". */
    fun dropHint(form: AddEventForm, day: Long, today: Long): String = "Move to " + TaskWhenRules.label(day, form.minute, today)
}
