package os.meka.core.domain

/**
 * Edit your calendars, completeness slice 6: an event in its own time zone. A timed event set in a zone whose clock
 * differs from the device's at the event's start or end (a call with New York from home, or a home event seen while
 * abroad, when the phone has moved to the local zone) gets one more line on its detail: "New York time · 09:00–10:00",
 * with the day when it differs from the day here ("UK time · Wed 21 Oct · 01:00–02:00"). Nothing when the clocks
 * agree, the zone is unknown, or the event is all day (all-day events are by calendar date everywhere). Pure, no AI.
 */
object EventZoneRules {
    private val ID = Regex("^[A-Za-z][A-Za-z0-9_+\\-]*(/[A-Za-z0-9_+\\-]+){0,2}$")

    /** Whether [id] looks like an IANA zone id (the server checks it parses; the apps only show its label). */
    fun validId(id: String): Boolean = id.length in 2..64 && ID.matches(id)

    /** "New York time" · "UK time" · "UTC"; the IANA id's last part with spaces, "Etc/GMT+5" as "UTC−5". */
    fun label(id: String): String {
        when (id) {
            "Europe/London", "GB", "Europe/Belfast" -> return "UK time"
            "UTC", "Etc/UTC", "Etc/UCT", "UCT", "Etc/GMT", "GMT", "Etc/Zulu", "Zulu", "Universal", "Etc/Universal" -> return "UTC"
        }
        if (id.startsWith("Etc/GMT")) {
            // POSIX sign: Etc/GMT+5 is five hours behind UTC.
            val n = id.removePrefix("Etc/GMT").toIntOrNull() ?: return "UTC"
            return if (n > 0) "UTC−$n" else "UTC+${-n}"
        }
        val city = id.substringAfterLast('/').replace('_', ' ').trim()
        return if (city.isEmpty()) id else "$city time"
    }

    /**
     * The extra line for [event], with [here] the device's calendar and [theirs] the event's own zone's (null when it
     * has none or the zone isn't known on this device); null when there's nothing to add.
     */
    fun line(event: CalendarEvent, here: LocalCalendar, theirs: LocalCalendar?): String? {
        val zone = event.timeZone ?: return null
        if (event.allDay || theirs == null) return null
        val end = maxOf(event.startAtMs, event.endAtMs)
        fun same(ms: Long) = here.epochDayOf(ms) == theirs.epochDayOf(ms) && here.minuteOfDay(ms) == theirs.minuteOfDay(ms)
        if (same(event.startAtMs) && same(end)) return null
        fun hhmm(ms: Long) = LocalClock.formatMinute(theirs.minuteOfDay(ms))
        val startDay = theirs.epochDayOf(event.startAtMs)
        val endDay = if (end > event.startAtMs) theirs.epochDayOf(end - 1) else startDay
        val times = when {
            end <= event.startAtMs -> hhmm(event.startAtMs)
            endDay == startDay -> "${hhmm(event.startAtMs)}–${hhmm(end)}"
            else -> "${hhmm(event.startAtMs)} – ${CivilDate.shortLabel(theirs.epochDayOf(end))} ${hhmm(end)}"
        }
        val day = if (startDay != here.epochDayOf(event.startAtMs)) "${CivilDate.shortLabel(startDay)} · " else ""
        return "${label(zone)} · $day$times"
    }
}
