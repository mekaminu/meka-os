package os.meka.core.domain

/**
 * The Week board's (j) (Meka 2026-10-29 02:12: "the study sessions put in his phone's calendar appear nowhere"): the
 * Fold also reads the phone's own calendars (Samsung's "My calendar" and any other calendar kept on the phone rather
 * than in Google or Outlook), so what he puts there shows on Today, the Week board, the timeline and Study's sessions.
 *
 * - Read on the Fold only, through Android's calendar provider, once he allows it (Calendars → THIS PHONE'S
 *   CALENDARS); kept in memory on that device, never stored, never synced, never sent to the server (no new sharing).
 * - Calendars the phone holds for a Google account are skipped (the server already reads those, ticked or not in
 *   Google), and any phone event that is the same as a server event (same title, same start) is dropped, so nothing
 *   shows twice.
 * - View only: Navilife never writes to the phone's calendars ([EDIT_LINE] says where to change one).
 * - Never an address as a name ([CalendarRules.looksLikeAddress]): a calendar named after one reads [NAME].
 */
data class PhoneCalendarRow(
    /** The event's id in the phone's calendar provider. */
    val eventId: Long,
    /** The calendar's display name on the phone ("My calendar"). */
    val calendarName: String?,
    /** The calendar's account type ("com.google", "LOCAL", "com.osp.app.signin" for Samsung's). */
    val accountType: String?,
    val title: String?,
    /** This instance's start and end (ms); for all-day, UTC midnights as the provider stores them. */
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String? = null,
    /** The IANA zone it was set in, or null. */
    val timeZone: String? = null,
)

/** One calendar on the phone and how many events it holds in the window, for Calendars. */
data class PhoneCalendarLine(val name: String, val events: Int) {
    /** "My calendar · 4 events in the next 5 weeks". */
    val line: String get() = "$name · " + when (events) {
        0 -> "no events in the next 5 weeks"
        1 -> "1 event in the next 5 weeks"
        else -> "$events events in the next 5 weeks"
    }
}

object PhoneCalendarRules {
    /** [CalendarEvent.provider] for an event read from the phone. */
    const val PROVIDER = "phone"

    /** What a phone calendar is called when it has no name, or only an address. */
    const val NAME = "This phone"

    /** Days read back and ahead of today. */
    const val DAYS_BACK = 7
    const val DAYS_AHEAD = 35

    /** At most this many events are taken from the phone (a calendar of years of birthdays stays small). */
    const val MAX_EVENTS = 600

    /** The account types whose calendars the server already reads (Google's, on the phone too). */
    val SERVER_ACCOUNT_TYPES = setOf("com.google")

    /** Calendars' section title and its line before he allows it. */
    const val SECTION = "THIS PHONE'S CALENDARS"
    const val ASK_LINE = "Show events from calendars kept on this phone (like Samsung's My calendar) on Today and the Week board. Read on this phone only, never sent anywhere."
    const val ALLOW = "Allow"
    const val NONE_LINE = "No calendars on this phone besides Google's."
    const val DENIED_LINE = "Calendar access is off for Navilife. Turn it on in the phone's Settings → Apps → Navilife → Permissions."
    /** Under the list once he has some: the Mac reads Google and Outlook only. */
    const val ONLY_HERE = "These show on this phone only. To see them on the Mac too, keep them in Google Calendar."

    /** Why a phone event has no Edit. */
    const val EDIT_LINE = "On this phone's own calendar · change it in the phone's calendar app"

    /** Whether the Fold reads a calendar of [accountType] (not one the server already reads). */
    fun reads(accountType: String?): Boolean = accountType?.trim()?.lowercase() !in SERVER_ACCOUNT_TYPES

    /** A calendar's shown name: its own, else [NAME]; never an address. */
    fun name(calendarName: String?): String {
        val n = calendarName?.trim()?.takeIf { it.isNotEmpty() } ?: return NAME
        return if (CalendarRules.looksLikeAddress(n)) NAME else n.take(CalendarRules.MAX_NAME)
    }

    /** The id a phone instance gets: the event and its start, so each day of a repeating one is its own row. */
    fun id(row: PhoneCalendarRow): String = "$PROVIDER:${row.eventId}:${row.beginMs}"

    /**
     * The rows as events: calendars the server reads left out, events the server already has (same title, same start
     * within a minute) dropped, earliest first, at most [MAX_EVENTS]. Untrusted text only as text (ADR-006 as for any
     * event's title).
     */
    fun events(rows: List<PhoneCalendarRow>, serverEvents: List<CalendarEvent>): List<CalendarEvent> {
        val known = serverEvents.groupBy { key(it.title) }
        return rows.asSequence()
            .filter { reads(it.accountType) && it.endMs >= it.beginMs }
            .map { r ->
                CalendarEvent(
                    id = id(r),
                    title = r.title?.trim()?.takeIf { it.isNotEmpty() } ?: "(No title)",
                    startAtMs = r.beginMs,
                    endAtMs = r.endMs,
                    allDay = r.allDay,
                    location = r.location?.trim()?.takeIf { it.isNotEmpty() },
                    provider = PROVIDER,
                    account = null,
                    calendarName = name(r.calendarName),
                    readOnly = true,
                    timeZone = r.timeZone?.takeIf { !r.allDay && EventZoneRules.validId(it) },
                )
            }
            .filterNot { e -> known[key(e.title)].orEmpty().any { s -> s.allDay == e.allDay && kotlin.math.abs(s.startAtMs - e.startAtMs) < 60_000L } }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.startAtMs }, { it.id }))
            .take(MAX_EVENTS)
            .toList()
    }

    /** Each phone calendar he'd see (the server's left out) with its events from [fromMs], by name. */
    fun calendars(rows: List<PhoneCalendarRow>, names: List<Pair<String?, String?>>, fromMs: Long): List<PhoneCalendarLine> {
        val counts = rows.filter { reads(it.accountType) && it.endMs > fromMs }.groupingBy { name(it.calendarName) }.eachCount()
        val all = names.filter { reads(it.second) }.map { name(it.first) } + counts.keys
        return all.distinct().sortedBy { it.lowercase() }.map { PhoneCalendarLine(it, counts[it] ?: 0) }
    }

    private fun key(title: String) = title.trim().lowercase().replace(Regex("\\s+"), " ")
}
