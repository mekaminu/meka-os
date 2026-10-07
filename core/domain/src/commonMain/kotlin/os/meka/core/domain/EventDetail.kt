package os.meka.core.domain

/** A video-call link found on an event: "Join Google Meet" with its https address. */
data class JoinLink(val url: String, val label: String)

/** What the event detail pane shows (calendar redesign, slice 3). */
data class EventDetailView(
    val id: String,
    val title: String,
    /** "Tue 6 Oct · 14:00–15:00" · "Tue 6 Oct · All day" · "Tue 6 – Thu 8 Oct · All day" · "Tue 6 Oct 22:00 – Wed 7 Oct 01:00" */
    val whenLine: String,
    /** "1 h" · "45 min" · "1 h 30"; null for all-day events and events with no length. */
    val duration: String?,
    /** "In 25 min" · "Now · ends 15:00" · "Ended" · "Today" (all day) · "Tomorrow"; null further ahead. */
    val status: String?,
    /** The status is happening now or within the hour, so it is lit. */
    val statusLit: Boolean,
    /** "Personal · meka@gmail.com" · "Work · Outlook · meka@outlook.com" · "FC Barcelona · Fixture" */
    val calendarLine: String?,
    val location: String?,
    /** What to search for in Maps (the location), or null when there's no place or the location is just a call link. */
    val mapsQuery: String?,
    /** The event's notes as plain text (untrusted, ADR-006: shown as text only, never followed or acted on). */
    val notes: String?,
    val join: JoinLink?,
    val isFixture: Boolean,
    /** Hidden from my day (calendar actions): the pane offers "Show in my day". */
    val hidden: Boolean = false,
    /** The event's prep task, when there is one (open or done). */
    val prepTaskId: String? = null,
    /** "Prep task at 13:30" · "Prep task due 14:00" · "Prep task done"; null without one. */
    val prepLine: String? = null,
    /** A prep task can be added (the event hasn't ended and has none open). */
    val canPrep: Boolean = false,
    /** Remind me this many minutes before (0: none). */
    val remindMin: Int = 0,
    /** Leave by: minutes to get there (0: none). */
    val travelMin: Int = 0,
    /** The reminder choices still ahead (minutes before); empty when Remind me isn't offered. */
    val remindChoices: List<Int> = emptyList(),
    /** The travel times still ahead; empty when Leave by isn't offered (no place, all day, too late). */
    val travelChoices: List<Int> = emptyList(),
    /** "Reminder 10 min before" · "Leave by 13:30 · 30 min away"; null when neither is set. */
    val reminderLine: String? = null,
)

/**
 * Event detail (calendar redesign, slice 3), non-AI and pure.
 *
 * - When: the date and times in local time; all-day events by calendar date (their end is the day after the last).
 * - Status relative to now: "In 25 min" (within a day), "Now · ends 15:00", "Ended"; all-day: "Today" / "Tomorrow".
 * - Join: the provider's own conference link when it is https; otherwise the first https link to a known
 *   video-call service in the location or the notes. Anything else in the notes is only text: notes come from the
 *   event's organiser, so they are untrusted (ADR-006) and nothing in them is opened unless Meka taps Join.
 * - Maps: the location, unless the location is only a link.
 */
object EventDetails {
    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    const val MAX_NOTES = 2_000

    /** Video-call services whose links become a Join button, by host (the host or any subdomain of it). */
    private val CALL_HOSTS = listOf(
        "meet.google.com" to "Join Google Meet",
        "teams.microsoft.com" to "Join Teams",
        "teams.live.com" to "Join Teams",
        "zoom.us" to "Join Zoom",
        "webex.com" to "Join Webex",
        "whereby.com" to "Join Whereby",
        "facetime.apple.com" to "Join FaceTime",
    )

    fun build(e: CalendarEvent, nowMs: Long, calendar: LocalCalendar, marks: EventMarks = EventMarks.NONE): EventDetailView {
        fun hhmm(ms: Long) = LocalClock.formatMinute(calendar.minuteOfDay(ms))
        val today = calendar.epochDayOf(nowMs)

        val whenLine: String
        val duration: String?
        val status: String?
        var lit = false
        if (e.allDay) {
            // All-day bounds are UTC midnights: the first day and the day after the last.
            val first = e.startAtMs.floorDiv(CivilDate.DAY_MS)
            val last = maxOf(first, e.endAtMs.floorDiv(CivilDate.DAY_MS) - 1)
            whenLine = "${CalendarAgenda.spanLabel(first, last)} · All day"
            duration = if (last > first) "${last - first + 1} days" else null
            status = when {
                today in first..last -> "Today".also { lit = true }
                today + 1 == first -> "Tomorrow"
                today > last -> "Ended"
                else -> null
            }
        } else {
            val startDay = calendar.epochDayOf(e.startAtMs)
            // An event ending at midnight belongs to the day it started.
            val endDay = if (e.endAtMs > e.startAtMs) calendar.epochDayOf(e.endAtMs - 1) else startDay
            whenLine = when {
                e.endAtMs <= e.startAtMs -> "${CivilDate.shortLabel(startDay)} · ${hhmm(e.startAtMs)}"
                startDay == endDay -> "${CivilDate.shortLabel(startDay)} · ${hhmm(e.startAtMs)}–${hhmm(e.endAtMs)}"
                else -> "${CivilDate.shortLabel(startDay)} ${hhmm(e.startAtMs)} – ${CivilDate.shortLabel(endDay)} ${hhmm(e.endAtMs)}"
            }
            duration = if (e.endAtMs > e.startAtMs) durationLabel(e.endAtMs - e.startAtMs) else null
            status = when {
                nowMs >= e.startAtMs && nowMs < e.endAtMs -> "Now · ends ${hhmm(e.endAtMs)}".also { lit = true }
                nowMs >= maxOf(e.endAtMs, e.startAtMs + 1) -> "Ended"
                e.startAtMs - nowMs < 24 * HOUR_MS -> {
                    val mins = ((e.startAtMs - nowMs + MIN_MS - 1) / MIN_MS).toInt()
                    if (mins <= 60) lit = true
                    "In ${durationLabel(mins * MIN_MS)}"
                }
                startDay == today + 1 -> "Tomorrow"
                else -> null
            }
        }

        val calendarLine = listOfNotNull(
            e.calendarName?.takeIf { it.isNotBlank() },
            when (e.provider) {
                "microsoft" -> "Outlook"
                "fixtures" -> "Fixture"
                else -> null
            },
            e.account?.takeIf { it.isNotBlank() && e.provider != "fixtures" },
        ).joinToString(" · ").ifEmpty { null }

        val location = e.location?.trim()?.takeIf { it.isNotEmpty() }
        val notes = e.description?.let { cleanNotes(it) }
        val join = e.joinUrl?.trim()?.takeIf { isHttps(it) }?.let { JoinLink(it, labelFor(it) ?: "Join call") }
            ?: findCallLink(location) ?: findCallLink(notes)
        val prep = marks.prepTasks[e.id]
        val locationIsLink = location != null && (location.startsWith("https://") || location.startsWith("http://")) && !location.contains(' ')

        return EventDetailView(
            id = e.id,
            title = e.title,
            whenLine = whenLine,
            duration = duration,
            status = status,
            statusLit = lit,
            calendarLine = calendarLine,
            location = location,
            mapsQuery = location?.takeUnless { locationIsLink },
            notes = notes,
            join = join,
            isFixture = e.isFixture,
            hidden = marks.isHidden(e.id),
            prepTaskId = prep?.id,
            prepLine = prep?.let { prepLine(it, today, calendar) },
            canPrep = status != "Ended" && (prep == null || prep.isDone),
            remindMin = marks.reminderOf(e.id),
            travelMin = marks.travelOf(e.id),
            remindChoices = ReminderRules.remindChoices(e, nowMs),
            travelChoices = ReminderRules.travelChoices(e, nowMs),
            reminderLine = if (e.startAtMs > nowMs) ReminderRules.line(e, marks, calendar) else null,
        )
    }

    /** "Prep task at 13:30" · "Prep task at Wed 7 Oct 13:30" · "Prep task due 14:00" · "Prep task done". */
    fun prepLine(t: Task, today: Long, calendar: LocalCalendar): String {
        if (t.isDone) return "Prep task done"
        fun at(ms: Long): String {
            val d = calendar.epochDayOf(ms)
            val time = LocalClock.formatMinute(calendar.minuteOfDay(ms))
            return if (d == today) time else "${CivilDate.shortLabel(d)} $time"
        }
        t.scheduledAtMs?.let { return "Prep task at ${at(it)}" }
        t.dueAtMs?.let { return "Prep task due ${at(it)}" }
        return "Prep task added"
    }

    /** "45 min" · "1 h" · "1 h 30" · "26 h" (a timed event over midnight keeps counting hours). */
    fun durationLabel(ms: Long): String {
        val mins = (ms / MIN_MS).toInt()
        val h = mins / 60
        val m = mins % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h ${m.toString().padStart(2, '0')}"
        }
    }

    /** The label for a known video-call link, or null. */
    fun labelFor(url: String): String? {
        val host = hostOf(url) ?: return null
        return CALL_HOSTS.firstOrNull { (h, _) -> host == h || host.endsWith(".$h") }?.second
    }

    /** The first https link to a known video-call service in [text], or null. */
    fun findCallLink(text: String?): JoinLink? {
        if (text == null) return null
        var i = text.indexOf("https://")
        while (i >= 0) {
            var end = i
            while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"'()[]{}") end++
            val url = text.substring(i, end).trimEnd('.', ',', ';', ':', '!', '?')
            labelFor(url)?.let { return JoinLink(url, it) }
            i = text.indexOf("https://", end)
        }
        return null
    }

    /** Trimmed, at most two blank-free line breaks in a row, capped at [MAX_NOTES]; null when empty. */
    fun cleanNotes(raw: String): String? {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines().map { it.trimEnd() }
        val out = StringBuilder()
        var blank = 0
        for (l in lines) {
            if (l.isBlank()) { blank++; continue }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            out.append(l)
            blank = 0
        }
        val s = out.toString().trim()
        if (s.isEmpty()) return null
        return if (s.length <= MAX_NOTES) s else s.take(MAX_NOTES).trimEnd() + "…"
    }

    private fun isHttps(url: String) = url.startsWith("https://") && hostOf(url) != null && url.none { it.isWhitespace() }

    private fun hostOf(url: String): String? {
        if (!url.startsWith("https://")) return null
        val rest = url.substring(8)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        // No user-info tricks ("https://meet.google.com@evil.example"), no ports.
        if (authority.isEmpty() || '@' in authority || ':' in authority) return null
        val host = authority.lowercase().trimEnd('.')
        return host.takeIf { h -> h.all { it.isLetterOrDigit() || it == '.' || it == '-' } && '.' in h }
    }
}
