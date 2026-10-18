package os.meka.core.domain

/**
 * Edit your calendars from MEKA (build plan M1), completeness slice 4: **"move the dentist to 4pm" as a confirm card**.
 * Non-AI, pure.
 *
 * Ask and Talk name the events Navilife could move with a short handle ("e1", "e2"), like tasks' "t1": today's events
 * and, when the question asks to move something, the coming fortnight's events whose title shares a word with it. Only
 * an event Navilife may move by itself gets a handle ([movable]): on an account that allows editing, not view-only, not
 * a fixture or one still waiting for Google, timed, not started, no longer than a day, and with no guests (moving a
 * meeting changes it for other people, so that stays with the Edit sheet). The model may then propose
 * `{"kind":"move_event","ref":"e1","date":"YYYY-MM-DD","time":"HH:MM"}`; it becomes a card ("Move “Dentist” to
 * Fri 23 Oct · 16:00 · Google") only for a handle MEKA sent, at a time not gone and not the time it already has.
 * Nothing moves until Meka taps Move; then it is an ordinary edit with the five-second Undo (a repeating event moves
 * this one only). It never runs on its own ([TrustRules.kindOf] is null): event titles can be other people's words.
 */

/** An event as Ask named it: what the card needs, kept on the device beside its handle. */
data class AskEventRef(val eventId: String, val title: String, val day: Long, val minute: Int, val lengthMin: Int, val provider: String)

object EventAskRules {
    private const val MINUTE_MS = 60_000L

    /** Coming events looked at for a move question. */
    const val AHEAD_DAYS = 14
    /** Coming events a move question carries at most (today's are already in the context). */
    const val MAX_AHEAD = 4

    private val MOVE_WORDS = setOf("move", "moving", "reschedule", "rearrange", "shift", "push", "bring", "change", "put", "make")
    private val STOP = setOf(
        "the", "a", "an", "my", "to", "at", "on", "in", "for", "of", "and", "it", "this", "that", "from", "till", "until",
        "pm", "am", "please", "can", "could", "you", "me", "move", "moving", "reschedule", "rearrange", "shift", "push",
        "bring", "change", "put", "make", "forward", "back", "later", "earlier", "instead", "tomorrow", "today", "tonight",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "next", "week", "o'clock", "oclock",
        "event", "appointment", "meeting", "calendar", "half", "past", "quarter",
    )

    /** Whether [question] asks to move something ("move the dentist to 4pm", "push my haircut to Friday"). */
    fun isMove(question: String): Boolean = words(question).any { it in MOVE_WORDS }

    /** Whether Ask may offer to move [e] by itself; [canEdit] says whether its account allows editing. */
    fun movable(e: CalendarEvent, nowMs: Long, calendar: LocalCalendar, canEdit: (String, String) -> Boolean): Boolean {
        val account = e.account ?: return false
        return e.provider in CalendarEditRules.WRITABLE && !e.isFixture && !e.readOnly && !e.isProvisional &&
            e.guests.isEmpty() && !e.allDay && EditEventRules.timeEditable(e, nowMs, calendar) && canEdit(e.provider, account)
    }

    /** [e] as the card needs it. */
    fun ref(e: CalendarEvent, calendar: LocalCalendar): AskEventRef = AskEventRef(
        e.id, e.title.replace(Regex("""\s+"""), " ").trim(), calendar.epochDayOf(e.startAtMs), calendar.minuteOfDay(e.startAtMs),
        ((e.endAtMs - e.startAtMs) / MINUTE_MS).toInt(), e.provider,
    )

    /**
     * For a move question: the movable events after today in the next [AHEAD_DAYS] days whose title shares a word with
     * it ("dentist" for "move the dentist to 4pm"), soonest first, at most [MAX_AHEAD]; none for any other question.
     */
    fun ahead(question: String, events: List<CalendarEvent>, nowMs: Long, calendar: LocalCalendar, canEdit: (String, String) -> Boolean): List<CalendarEvent> {
        if (!isMove(question)) return emptyList()
        val wanted = words(question).filter { it.length >= 3 && it !in STOP && it.any(Char::isLetter) }.toSet()
        if (wanted.isEmpty()) return emptyList()
        val today = calendar.epochDayOf(nowMs)
        return events.asSequence()
            .filter { val d = calendar.epochDayOf(it.startAtMs); d > today && d <= today + AHEAD_DAYS }
            .filter { movable(it, nowMs, calendar, canEdit) }
            .filter { e -> words(e.title).any { w -> w in wanted || wanted.any { q -> stem(q) == stem(w) } } }
            .sortedBy { it.startAtMs }
            .distinctBy { it.id }
            .take(MAX_AHEAD)
            .toList()
    }

    /** A coming event's context line: "Fri 23 Oct 15:00–16:00 · Dentist · Google". */
    fun aheadLine(e: CalendarEvent, calendar: LocalCalendar): String =
        "${CivilDate.shortLabel(calendar.epochDayOf(e.startAtMs))} ${LocalClock.formatMinute(calendar.minuteOfDay(e.startAtMs))}–${LocalClock.formatMinute(calendar.minuteOfDay(e.endAtMs))}" +
            " · ${e.title.replace(Regex("""\s+"""), " ").trim()} · ${CalendarRules.name(e)}"

    /**
     * The model's move as a proposal, or null: a handle MEKA didn't send, no time, a day in the past or more than two
     * years ahead, a time already gone, or the time it already has. No date means the event's own day.
     */
    fun proposal(ref: AskEventRef?, day: Long?, minute: Int?, nowMs: Long, calendar: LocalCalendar): AskProposal.MoveEvent? {
        ref ?: return null
        val m = minute ?: return null
        val today = calendar.epochDayOf(nowMs)
        val d = day ?: ref.day
        if (d < today || d > today + AskRules.MAX_DAYS_AHEAD) return null
        if (calendar.toEpochMs(d, m) <= nowMs) return null
        if (d == ref.day && m == ref.minute) return null
        return AskProposal.MoveEvent(ref.eventId, ref.title, d, m, ref.provider)
    }

    /** The Edit form [form] (opened on the event) moved to [p]'s day and time, keeping its length. */
    fun moved(form: AddEventForm, p: AskProposal.MoveEvent): AddEventForm = form.copy(day = p.day, minute = p.minute)

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("""[^\p{L}\p{N}']+""")).map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** "dentist's" and "dentists" are "dentist". */
    private fun stem(w: String): String = w.removeSuffix("'s").let { if (it.length > 4 && it.endsWith("s")) it.dropLast(1) else it }
}
