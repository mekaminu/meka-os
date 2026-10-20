package os.meka.core.domain

/**
 * The companion (build plan, take first), slice 4: **the days he asks about**. Non-AI, pure.
 *
 * Ask, Talk and Hello always carry today. A question that names another day or a few days ("what's on Thursday?",
 * "am I free on Saturday?", "what have I got next week?", "anything on the 23rd?", "what did I do yesterday?") also
 * carries those days, looked up on the device from his own calendar and tasks: the companion's "server tools for his
 * own data", answered where the data lives, so nothing of his calendar is read on the server or kept there. The model
 * gets a days line per day (how many events and tasks, or that it's clear) and the days' events and tasks as event and
 * task lines with the day before the time; an event Navilife may move and an open task keep their handles (e1, t1), so
 * "move the dentist on Thursday to 4pm" or "push Thursday's MOT task to Friday" still comes back as a card.
 *
 * Understood anywhere in the question (one range, the first that fits in this order): the day after tomorrow · tomorrow
 * (tmrw) · yesterday · next weekend · this weekend / the weekend / weekend · next week · this week · next Thursday ·
 * Thursday / on Thurs (the coming one, today included) · 23 Oct / 23rd October / Oct 23 · the 23rd (this month's, or
 * next month's once it's gone) · 23/10 (day/month). Saturday and Sunday need their full names ("the sun", "sat down"
 * are not days). A range of today alone carries nothing (today is already there). At most [MAX_DAYS] days.
 *
 * The calendar is read from yesterday to [CALENDAR_AHEAD_DAYS] ahead (the server's window), so a day beyond it says
 * its events aren't known yet; tasks are known for any day.
 */
data class AskDays(val from: Long, val to: Long) {
    val days: List<Long> get() = (from..to).toList()
}

object DayAskRules {
    /** Days one question carries at most (a week). */
    const val MAX_DAYS = 7
    /** How far ahead a question's day may be (the furthest a "23 Jan" can sensibly mean). */
    const val MAX_AHEAD = 366
    /** The calendar Navilife reads: from yesterday to 30 days ahead (Integrations' window). */
    const val CALENDAR_AHEAD_DAYS = 30
    /** Event and task lines the days carry at most, soonest first. */
    const val MAX_EVENTS = 20
    const val MAX_TASKS = 12

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private const val WD = """(monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sunday)"""
    private const val MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
    private const val ORD = """(?:st|nd|rd|th)?"""
    private val dayAfter = Regex("""\bday after tomorrow\b""", opt)
    private val tomorrow = Regex("""\b(tomorrow|tmrw)\b""", opt)
    private val yesterday = Regex("""\byesterday\b""", opt)
    private val nextWeekend = Regex("""\bnext\s+weekend\b""", opt)
    private val weekend = Regex("""\b(?:this\s+|the\s+)?weekend\b""", opt)
    private val nextWeek = Regex("""\bnext\s+week\b""", opt)
    private val thisWeek = Regex("""\b(?:this|the rest of the)\s+week\b""", opt)
    private val weekday = Regex("""\b(?:(next|this|on)\s+)?$WD\b""", opt)
    private val dateDm = Regex("""\b(\d{1,2})$ORD\s+(?:of\s+)?$MONTH\b""", opt)
    private val dateMd = Regex("""\b$MONTH\s+(\d{1,2})$ORD\b""", opt)
    private val dateSlash = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})(?![\d/])""")
    private val nth = Regex("""\bthe\s+(\d{1,2})(st|nd|rd|th)\b""", opt)

    /** The days [question] names, or null when it names none (or only today). */
    fun range(question: String, today: Long): AskDays? {
        val q = question.replace(Regex("""\s+"""), " ").replace(Regex("""\b24/7\b"""), "")
        val dow = CivilDate.isoDayOfWeek(today)
        val monday = today - (dow - 1)
        val r: AskDays? = when {
            dayAfter.containsMatchIn(q) -> AskDays(today + 2, today + 2)
            tomorrow.containsMatchIn(q) -> AskDays(today + 1, today + 1)
            yesterday.containsMatchIn(q) -> AskDays(today - 1, today - 1)
            nextWeekend.containsMatchIn(q) -> AskDays(monday + 12, monday + 13)
            weekend.containsMatchIn(q) -> if (dow >= 6) AskDays(today, monday + 6) else AskDays(monday + 5, monday + 6)
            nextWeek.containsMatchIn(q) -> AskDays(monday + 7, monday + 13)
            thisWeek.containsMatchIn(q) -> AskDays(today, monday + 6)
            else -> dated(q, today) ?: weekday.find(q)?.let { m ->
                val w = weekdayOf(m.groupValues[2])
                val d = if (m.groupValues[1].equals("next", ignoreCase = true)) monday + 7 + (w - 1) else today + (w - dow).mod(7)
                AskDays(d, d)
            }
        }
        return r?.takeIf { it.from <= it.to && !(it.from == today && it.to == today) && it.from >= today - 1 && it.to <= today + MAX_AHEAD }
            ?.let { AskDays(it.from, minOf(it.to, it.from + MAX_DAYS - 1)) }
    }

    private fun dated(q: String, today: Long): AskDays? {
        val day = dateDm.find(q)?.let { m -> dateOf(m.groupValues[1].toInt(), monthOf(m.groupValues[2]), today) }
            ?: dateMd.find(q)?.let { m -> dateOf(m.groupValues[2].toInt(), monthOf(m.groupValues[1]), today) }
            ?: dateSlash.find(q)?.let { m -> m.groupValues[2].toInt().takeIf { it in 1..12 }?.let { mo -> dateOf(m.groupValues[1].toInt(), mo, today) } }
            ?: nth.find(q)?.let { m ->
                val n = m.groupValues[1].toInt()
                val now = CivilDate.fromEpochDay(today)
                if (n == now.day) today
                else if (n > now.day) dateIn(now.year, now.month, n)
                else if (now.month == 12) dateIn(now.year + 1, 1, n) else dateIn(now.year, now.month + 1, n)
            }
        return day?.let { AskDays(it, it) }
    }

    /** The coming [day] [month] (this year's, or next year's once it has gone; yesterday stays this year's). */
    private fun dateOf(day: Int, month: Int, today: Long): Long? {
        val year = CivilDate.fromEpochDay(today).year
        val d = dateIn(year, month, day) ?: return null
        return if (d >= today - 1) d else dateIn(year + 1, month, day)
    }

    private fun dateIn(year: Int, month: Int, day: Int): Long? =
        if (month in 1..12 && day in 1..CivilDate.lengthOfMonth(year, month)) CivilDate.toEpochDay(year, month, day) else null

    private fun monthOf(s: String): Int = when (s.lowercase().take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    private fun weekdayOf(s: String): Int = when (s.lowercase().take(3)) {
        "mon" -> 1; "tue" -> 2; "wed" -> 3; "thu" -> 4; "fri" -> 5; "sat" -> 6; else -> 7
    }

    /** The day [e] starts on, and every day it runs into (an all-day event's end is the next midnight). */
    private fun daysOf(e: CalendarEvent, calendar: LocalCalendar): LongRange {
        val start = calendar.epochDayOf(e.startAtMs)
        val last = maxOf(start, calendar.epochDayOf(e.endAtMs - 1))
        return start..last
    }

    /** [r]'s events (not today's: those are already there), soonest first, at most [MAX_EVENTS]. */
    fun events(r: AskDays, events: List<CalendarEvent>, today: Long, calendar: LocalCalendar): List<CalendarEvent> =
        events.asSequence()
            .filter { e -> val d = daysOf(e, calendar); d.first <= r.to && d.last >= r.from && !(d.first == today && d.last == today) }
            .sortedWith(compareBy<CalendarEvent> { maxOf(calendar.epochDayOf(it.startAtMs), r.from) }.thenBy { !it.allDay }.thenBy { it.startAtMs })
            .distinctBy { it.id }
            .take(MAX_EVENTS)
            .toList()

    /** The day a task belongs to for a days question: its plan, else its due day; null for neither. */
    fun dayOf(t: Task, calendar: LocalCalendar): Long? =
        (t.scheduledAtMs ?: t.dueAtMs)?.let(calendar::epochDayOf)

    /**
     * [r]'s tasks (not today's): the open ones planned or due on its days, and on a day gone by the ones he did that
     * day (as "done"), soonest first, at most [MAX_TASKS].
     */
    fun tasks(r: AskDays, tasks: List<Task>, today: Long, calendar: LocalCalendar): List<Task> =
        tasks.asSequence()
            .filter { t ->
                if (t.lifecycle == Lifecycle.DONE) {
                    val done = t.completedAtMs?.let(calendar::epochDayOf)
                    done != null && done < today && done in r.from..r.to
                } else {
                    val d = dayOf(t, calendar)
                    !t.lifecycle.isTerminal && t.lifecycle != Lifecycle.SOMEDAY && d != null && d != today && d in r.from..r.to
                }
            }
            .sortedWith(compareBy<Task> { if (it.lifecycle == Lifecycle.DONE) it.completedAtMs ?: 0L else it.scheduledAtMs ?: it.dueAtMs ?: 0L })
            .distinctBy { it.id }
            .take(MAX_TASKS)
            .toList()

    /** A day's event line: "Thu 22 Oct 15:00–16:00 · Dentist · Google" / "Sat 24 Oct · all day · Away · Family". */
    fun eventLine(e: CalendarEvent, r: AskDays, calendar: LocalCalendar): String {
        val title = e.title.replace(Regex("""\s+"""), " ").trim()
        val cal = if (e.isFixture) e.calendarName else CalendarRules.name(e)
        if (e.allDay) {
            val d = daysOf(e, calendar)
            val days = if (d.first == d.last) CivilDate.shortLabel(d.first) else "${CivilDate.shortLabel(maxOf(d.first, r.from))} – ${CivilDate.shortLabel(d.last)}"
            return listOfNotNull(days, "all day", title, cal, e.location).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val head = "${CivilDate.shortLabel(calendar.epochDayOf(e.startAtMs))} ${LocalClock.formatMinute(calendar.minuteOfDay(e.startAtMs))}–${LocalClock.formatMinute(calendar.minuteOfDay(e.endAtMs))}"
        return listOfNotNull(head, title, cal, e.location).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** A day's task line's words after the title: "planned Thu 22 Oct · 10:00" / "due Fri 23 Oct" / "done Mon 19 Oct". */
    fun taskWhen(t: Task, today: Long, calendar: LocalCalendar): String {
        if (t.lifecycle == Lifecycle.DONE) return "done " + CivilDate.shortLabel(calendar.epochDayOf(t.completedAtMs ?: 0L))
        val at = t.scheduledAtMs
        if (at != null) return "planned " + TaskWhenRules.label(calendar.epochDayOf(at), calendar.minuteOfDay(at), today)
        return "due " + TaskWhenRules.label(calendar.epochDayOf(t.dueAtMs ?: 0L), null, today)
    }

    /**
     * The days lines: first the days asked about with how many events and tasks ("Days asked about · Sat 24 – Sun 25 Oct
     * · 3 events · 1 task"), then each day ("Sat 24 Oct · 2 events · 1 task", "Sun 25 Oct · nothing in the calendar and
     * no tasks", "Fri 27 Nov · no tasks · the calendar isn't read this far ahead yet").
     */
    fun askLines(r: AskDays, events: List<CalendarEvent>, tasks: List<Task>, today: Long, calendar: LocalCalendar): List<String> {
        fun counts(e: Int, t: Int, done: Int): String = listOfNotNull(
            if (e > 0) plural(e, "event") else null,
            if (t > 0) plural(t, "task") else null,
            if (done > 0) "$done done" else null,
        ).joinToString(" · ")
        val span = if (r.from == r.to) CivilDate.shortLabel(r.from) else "${CivilDate.shortLabel(r.from)} – ${CivilDate.shortLabel(r.to)}"
        val open = tasks.filter { it.lifecycle != Lifecycle.DONE }
        val head = listOfNotNull("Days asked about · $span", if (today in r.from..r.to) "not counting today's" else null, counts(events.size, open.size, tasks.size - open.size).ifEmpty { "nothing in the calendar and no tasks" })
        val out = mutableListOf(head.joinToString(" · "))
        if (r.from == r.to && r.from <= today + CALENDAR_AHEAD_DAYS) return out
        r.days.forEach { d ->
            val e = events.count { daysOf(it, calendar).let { s -> d in s } }
            val dayTasks = tasks.filter { t -> if (t.lifecycle == Lifecycle.DONE) t.completedAtMs?.let(calendar::epochDayOf) == d else dayOf(t, calendar) == d }
            val t = dayTasks.count { it.lifecycle != Lifecycle.DONE }
            val done = dayTasks.size - t
            val beyond = d > today + CALENDAR_AHEAD_DAYS
            val line = when {
                d == today -> null
                beyond -> listOf(CivilDate.shortLabel(d), counts(0, t, done).ifEmpty { "no tasks" }, "the calendar isn't read this far ahead yet").joinToString(" · ")
                e + t + done == 0 -> "${CivilDate.shortLabel(d)} · nothing in the calendar and no tasks"
                else -> "${CivilDate.shortLabel(d)} · ${counts(e, t, done)}"
            }
            line?.let { out += it }
        }
        return out
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}
