package os.meka.core.domain

/*
 * Scheduling assistant, slice 1 (build plan; non-AI, pure): **a "can we meet?" email gets a reply with your free
 * times**. An email on "From your mail" whose subject or preview asks to meet ("Can we meet next week?", "Are you free
 * for a quick call?", "When works for you?") says so on its row ("Asks to meet") and offers **Suggest times**: the
 * reply editor opens with three free times from his own calendars — work days only, inside that day's work hours (so
 * the commute, evenings and protected time stay clear), not on a holiday, booked leave or an all-day "off"/"leave"
 * event, at least [BUFFER_MIN] minutes clear of every event, each on a different day, a morning and an afternoon in
 * turn. Nothing is booked and nothing is sent: he can change every word, and it goes only when he taps Send (Level 3,
 * the editor's usual 20 s Undo). Booking the time they pick is a later slice.
 *
 * The email's words are someone else's (ADR-006): they only decide whether the pill shows and how long the meeting
 * is (a "quick call" or "coffee" is half an hour, "15 min" is 15); they never reach the calendar or the times offered.
 */

/**
 * One free time offered in a reply. Slice 3: [theirStartMin], the start on their clock as his reply wrote it
 * ("(08:00–09:00 New York time)"), so "8am works" picks it too; null when the reply gave only his time.
 */
data class FreeSlot(val startMs: Long, val endMs: Long, val theirStartMin: Int? = null)

object MeetingRequestRules {
    const val SUGGEST = "Suggest times"
    const val ASKS = "Asks to meet"
    const val FINDING = "Finding times…"
    const val SUGGESTED_LINE = "Free times from your calendars · change anything · it goes only when you tap Send"
    const val NONE_FREE_LINE = "No free time in your work hours in the next two weeks · write your own times"
    const val UNKNOWN = "Couldn't find that email"
    /** Days looked at, from today. */
    const val DAYS_AHEAD = 14
    const val SHOWN = 3
    /** Kept clear either side of every event. */
    const val BUFFER_MIN = 15
    /** Times start on the hour or half hour. */
    const val STEP_MIN = 30
    /** Nothing offered sooner than this from now. */
    const val MIN_LEAD_MIN = 120
    const val DEFAULT_MIN = 60
    const val SHORT_MIN = 30
    /** The afternoon pick starts at or after this (local minute). */
    const val AFTERNOON_MIN = 13 * 60

    private const val MIN_MS = 60_000L

    /** What asks to meet: someone wanting a time with him. */
    private val ASK = Regex(
        """\b(?:can|could|shall|should)\s+we\s+(?:meet|have\s+a\s+(?:call|chat|meeting)|catch\s+up|get\s+together|speak|talk|chat|schedule|arrange|set\s+up|book|find)\b|""" +
            """\b(?:meet\s+up|get\s+together|grab(?:bing)?\s+(?:a\s+)?(?:coffee|lunch|a\s+drink)|catch[\s-]?up(?!\s+on\b))\b|""" +
            """\bfind\s+(?:a|some)\s+time\b|\b(?:set\s+up|schedule|arrange|book|organi[sz]e)\s+(?:a|some)\s+(?:time|call|meeting|chat|catch[\s-]?up|session|slot)\b|""" +
            """\b(?:hop|jump)\s+on\s+a\s+call\b|\bwhen\s+(?:are|would)\s+you\s+(?:be\s+)?(?:free|available)\b|""" +
            """\bare\s+you\s+(?:free|available)\b|\byour\s+availability\b|\bwhen\s+(?:works|suits|is\s+good|is\s+convenient)\s+for\s+you\b|""" +
            """\bwhat\s+(?:times?|days?)\s+(?:work|suit|are\s+good)\b|\bwould\s+you\s+be\s+(?:free|available|up\s+for)\s+(?:a|to)\b|\b(?:a|some)\s+convenient\s+time\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Not a request from a person: invites, their answers, events to sign up for, notes of a meeting. */
    private val NOT_ASKING = Regex(
        """\b(?:invitation|invited\s+you|accepted|declined|tentative|updated\s+invitation|webinar|register|registration|unsubscribe|newsletter|""" +
            """meeting\s+notes|minutes\s+of|recording|rsvp|ticket|livestream|event\s+reminder)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MINUTES = Regex("""\b(15|20|30|45|60|90)\s*(?:-\s*)?(?:min(?:ute)?s?|mins)\b""", RegexOption.IGNORE_CASE)
    private val SHORT = Regex("""\b(?:quick|short|brief)\s+(?:call|chat|catch[\s-]?up|word)\b|\bhalf\s+an\s+hour\b|\bcoffee\b""", RegexOption.IGNORE_CASE)
    private val HOUR = Regex("""\b(?:an|one|1)\s+hour\b""", RegexOption.IGNORE_CASE)
    /** An all-day event with these words takes the day off the list. */
    private val DAY_OFF = Regex("""\b(?:holiday|annual\s+leave|leave|day\s+off|off\s+work|out\s+of\s+(?:the\s+)?office|ooo|away|vacation|sick)\b""", RegexOption.IGNORE_CASE)

    /** Whether the email asks to meet (subject first, then the preview). */
    fun asks(subject: String?, preview: String?): Boolean {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.any { NOT_ASKING.containsMatchIn(it) }) return false
        return texts.any { ASK.containsMatchIn(it) }
    }

    /** How long the meeting is: the minutes it names, half an hour for a quick call or coffee, else an hour. */
    fun minutes(subject: String?, preview: String?): Int {
        val all = listOfNotNull(subject, preview).joinToString(" ")
        MINUTES.find(all)?.let { return it.groupValues[1].toInt() }
        if (HOUR.containsMatchIn(all)) return DEFAULT_MIN
        if (SHORT.containsMatchIn(all)) return SHORT_MIN
        return DEFAULT_MIN
    }

    /**
     * Up to [SHOWN] free times of [minutes] in the next [DAYS_AHEAD] days, each on its own work day ([work]), inside
     * that day's hours, no sooner than [MIN_LEAD_MIN] from [nowMs], [BUFFER_MIN] clear of [events] and outside
     * [protectedOn]'s slots; a morning and an afternoon in turn where the day allows. Slice 3: never on a day of [avoid]
     * (times he offered before that they couldn't make), and inside [theirs]'s working day when their email named a
     * zone, unless no time fits it at all.
     */
    fun slots(
        nowMs: Long, work: WorkHours, events: List<CalendarEvent>, protectedOn: (Long) -> List<DayPlanner.Slot>,
        cal: LocalCalendar, minutes: Int = DEFAULT_MIN, avoid: List<FreeSlot> = emptyList(), theirs: LocalCalendar? = null,
    ): List<FreeSlot> {
        val inTheirDay = slotsFor(nowMs, work, events, protectedOn, cal, minutes, avoid, theirs)
        return if (inTheirDay.isEmpty() && theirs != null) slotsFor(nowMs, work, events, protectedOn, cal, minutes, avoid, null) else inTheirDay
    }

    private fun slotsFor(
        nowMs: Long, work: WorkHours, events: List<CalendarEvent>, protectedOn: (Long) -> List<DayPlanner.Slot>,
        cal: LocalCalendar, minutes: Int, avoid: List<FreeSlot>, theirs: LocalCalendar?,
    ): List<FreeSlot> {
        val avoidDays = avoid.map { cal.epochDayOf(it.startMs) }.toSet()
        val need = minutes.coerceIn(15, 4 * 60) * MIN_MS
        val buffer = BUFFER_MIN * MIN_MS
        val earliest = nowMs + MIN_LEAD_MIN * MIN_MS
        val today = cal.epochDayOf(nowMs)
        val out = mutableListOf<FreeSlot>()
        for (day in today until today + DAYS_AHEAD) {
            if (out.size >= SHOWN) break
            if (!work.isWorkDay(day) || day in avoidDays) continue
            val hours = work.hoursOn(day)
            if (hours.crossesMidnight || hours.endMinute <= hours.startMinute) continue
            val dayStart = cal.toEpochMs(day, 0)
            val dayEnd = cal.toEpochMs(day + 1, 0)
            val dayEvents = events.filter { it.startAtMs < dayEnd && it.endAtMs > dayStart }
            if (dayEvents.any { it.allDay && DAY_OFF.containsMatchIn(it.title) }) continue
            val open = DayPlanner.Slot(maxOf(cal.toEpochMs(day, hours.startMinute), earliest), cal.toEpochMs(day, hours.endMinute))
            if (open.endMs - open.startMs < need) continue
            val busy = dayEvents.filter { !it.allDay }.map { DayPlanner.Slot(it.startAtMs - buffer, it.endAtMs + buffer) } + protectedOn(day)
            val free = DayPlanner.subtract(open, busy.sortedBy { it.startMs })
            val starts = free.flatMap { f -> startsIn(f, need, day, cal) }
                .filter { theirs == null || MeetingZoneRules.inTheirDay(it, (need / MIN_MS).toInt(), theirs) }
            if (starts.isEmpty()) continue
            val wantAfternoon = out.size % 2 == 1
            val pick = if (wantAfternoon) starts.firstOrNull { cal.minuteOfDay(it) >= AFTERNOON_MIN } ?: starts.first()
            else starts.firstOrNull { cal.minuteOfDay(it) < AFTERNOON_MIN } ?: starts.first()
            out += FreeSlot(pick, pick + need)
        }
        return out
    }

    /** The on-the-step starts in [f] that leave [need] before its end. */
    private fun startsIn(f: DayPlanner.Slot, need: Long, day: Long, cal: LocalCalendar): List<Long> {
        val startMin = cal.minuteOfDay(f.startMs).let { if (cal.epochDayOf(f.startMs) < day) 0 else it }
        var m = ((startMin + STEP_MIN - 1) / STEP_MIN) * STEP_MIN
        val out = mutableListOf<Long>()
        while (m < LocalClock.MINUTES_PER_DAY) {
            val at = cal.toEpochMs(day, m)
            if (at + need > f.endMs) break
            if (at >= f.startMs) out += at
            m += STEP_MIN
        }
        return out
    }

    /** "Tuesday 20 October, 10:00–11:00". */
    fun slotLabel(s: FreeSlot, cal: LocalCalendar): String =
        "${CivilDate.longLabel(cal.epochDayOf(s.startMs))}, ${LocalClock.formatMinute(cal.minuteOfDay(s.startMs))}–${LocalClock.formatMinute(cal.minuteOfDay(s.endMs))}"

    /** "Hi Sam," for a person's name; "Hi," for an address, a number or a company-looking name. */
    fun greeting(from: String): String {
        val t = from.trim()
        val first = t.substringBefore(' ')
        val person = t.isNotEmpty() && '@' !in t && t.none { it.isDigit() } && t.split(Regex("""\s+""")).size <= 3 &&
            first.length >= 2 && first.first().isUpperCase() && first.all { it.isLetter() || it == '-' || it == '\'' }
        return if (person) "Hi $first," else "Hi,"
    }

    /**
     * The reply Suggest times puts in the editor: a greeting, the free times as a list ([zone] after them, e.g. "UK
     * time"), an ask to pick one, and his name from About you when he gave one. Null when no time is free. Slice 3:
     * each time also in [theirs] when their email named a zone, and "No problem. How about…" when [again] (they couldn't
     * make the times he offered before).
     */
    fun draft(
        from: String, slots: List<FreeSlot>, cal: LocalCalendar, zone: String?, sign: String?,
        theirs: TheirZone? = null, again: Boolean = false,
    ): String? {
        if (slots.isEmpty()) return null
        val zonePart = zone?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return buildString {
            append(greeting(from)).append("\n\n")
            append(
                when {
                    again && slots.size == 1 -> "No problem. How about this time instead$zonePart:"
                    again -> "No problem. How about one of these instead$zonePart:"
                    slots.size == 1 -> "Happy to meet. I'm free at this time$zonePart:"
                    else -> "Happy to meet. I'm free at any of these times$zonePart:"
                },
            )
            append("\n\n")
            slots.forEach { append("• ").append(slotLabel(it, cal)).append(MeetingZoneRules.theirPart(it, cal, theirs)).append('\n') }
            append('\n')
            append(if (slots.size == 1) "Let me know if that suits you and I'll put it in the diary." else "Let me know which suits you and I'll put it in the diary.")
            sign?.trim()?.takeIf { it.isNotEmpty() }?.let { append("\n\n").append(it) }
        }
    }

    /** The zone named after the times: "UK time" for London, else the zone's own id; null when unknown. */
    fun zoneLabel(zoneId: String?): String? = when {
        zoneId.isNullOrBlank() -> null
        zoneId == "Europe/London" -> "UK time"
        else -> zoneId
    }
}
