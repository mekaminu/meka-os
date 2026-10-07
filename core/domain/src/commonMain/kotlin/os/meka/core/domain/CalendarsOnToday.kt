package os.meka.core.domain

/**
 * Fields of a `calendar_mark` (one per calendar, id [CalendarRules.markId]). MEKA-only: the real calendar is untouched.
 * All LWW: the latest switch on any device wins.
 */
object CalendarMarkFields {
    /** The calendar's key ([CalendarRules.key]), so the mark can be matched back to its events. */
    const val KEY = "calendarKey"
    /** Its name when it was hidden, so Calendars can list it while it has no events in the mirror. */
    const val LABEL = "calendarLabel"
    /** Hidden from Today (and the rest of my day); the Calendar tab and Search still show its events. */
    const val HIDDEN_FROM_TODAY = "hiddenFromToday"
    const val HIDDEN_AT = "hiddenAtMs"
}

/** One calendar in Calendars' "On Today" list. */
data class CalendarChoice(
    val key: String,
    /** "Timestripe" · "Personal" · "Fixtures" · "Outlook" */
    val label: String,
    /** "Google · meka@gmail.com" · "FC Barcelona"; null when there's nothing to add. */
    val detail: String?,
    val onToday: Boolean,
)

/**
 * Which calendar an event comes from, and "Hide from Today" per calendar (all-day polish, Meka 2026-10-07: eight
 * Timestripe entries crowded Today). Non-AI, pure, unit-tested.
 *
 * - A calendar is its provider, account and calendar name ([key]); the fixtures feed is one calendar.
 * - A hidden calendar's events leave my day like "Hide from my day" does for one event (Today's timeline and all-day
 *   group, Up next, Plan my day, the brief, the shutdown, the review); the Calendar tab and Search still show them, and
 *   a reminder set on one of its events still comes.
 * - [choices] lists every calendar in the mirror, plus hidden ones that have no events right now, by name.
 */
object CalendarRules {
    /** "google|meka@gmail.com|Timestripe" · "fixtures||" */
    fun key(e: CalendarEvent): String =
        listOf(e.provider, e.account.orEmpty().trim().lowercase(), if (e.isFixture) "" else e.calendarName.orEmpty().trim()).joinToString("|")

    /** The calendar's name in menus and Calendars. */
    fun label(e: CalendarEvent): String = when {
        e.isFixture -> "Fixtures"
        !e.calendarName.isNullOrBlank() -> e.calendarName.trim()
        e.provider == "microsoft" -> "Outlook"
        else -> "Google Calendar"
    }

    /** The `calendar_mark` entity id for [key]: the same on every device. */
    fun markId(key: String): String = "c" + ActivityRules.fnv64("calendar:$key")

    fun detail(e: CalendarEvent): String? = when {
        e.isFixture -> "FC Barcelona"
        else -> listOfNotNull(
            when (e.provider) { "google" -> "Google"; "microsoft" -> "Outlook"; else -> null },
            e.account?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ").ifEmpty { null }
    }

    /** Every calendar in [events] plus hidden ones with no events now, by name; each says whether it's on Today. */
    fun choices(events: List<CalendarEvent>, hiddenCalendars: Map<String, String>): List<CalendarChoice> {
        val seen = LinkedHashMap<String, CalendarChoice>()
        for (e in events) {
            val k = key(e)
            if (k !in seen) seen[k] = CalendarChoice(k, label(e), detail(e), k !in hiddenCalendars)
        }
        for ((k, label) in hiddenCalendars) {
            if (k !in seen) seen[k] = CalendarChoice(k, label.ifBlank { "Calendar" }, null, false)
        }
        return seen.values.sortedWith(compareBy<CalendarChoice>({ it.label.lowercase() }, { it.key }))
    }

    /** The undo bar's line after hiding: "Timestripe hidden from Today". */
    fun hiddenLine(label: String): String = "$label hidden from Today"
}
