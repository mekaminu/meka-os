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
    /**
     * Hidden from Today (and the rest of my day); the Calendar tab and Search still show its events. `false` written
     * on a holiday calendar ([HolidayCalendars]) is Meka turning it on: without a mark one starts hidden.
     */
    const val HIDDEN_FROM_TODAY = "hiddenFromToday"
    const val HIDDEN_AT = "hiddenAtMs"
    /**
     * Meka's own name for the calendar ("Personal", "Kids"), shown wherever the calendar is named (the Calendar tab's
     * key, event rows and details, Calendars). Blank or absent: the default name ([CalendarRules.name]). LWW.
     */
    const val NAME = "displayName"
}

/** One calendar in Calendars' "On Today" list. */
data class CalendarChoice(
    val key: String,
    /** "Timestripe" · "Personal" · "Fixtures" · "Outlook" */
    val label: String,
    /** "Google · meka@gmail.com" · "FC Barcelona"; null when there's nothing to add. */
    val detail: String?,
    val onToday: Boolean,
    /** The name MEKA would use without Meka's own ([CalendarRules.defaultName]): the rename field's hint. */
    val defaultLabel: String = label,
    /** Whether Meka can rename it (any calendar with events; not the fixtures feed, not a hidden one with none now). */
    val canRename: Boolean = false,
) {
    /** Whether Meka gave it his own name. */
    val renamed: Boolean get() = canRename && label != defaultLabel
}

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
        else -> name(e) ?: if (e.provider == "microsoft") "Outlook" else "Google Calendar"
    }

    /** Shown for an account's own main calendar, which Google names after the address (Fold review 2026-10-09 07:26, item 9). */
    const val PERSONAL = "Personal"

    /** Longest name Meka can give a calendar. */
    const val MAX_NAME = 40

    /**
     * The calendar's name, or null when it has none: Meka's own ([CalendarEvent.calendarTitle]), else [defaultName].
     * Fixtures have none here ([label] says "Fixtures").
     */
    fun name(e: CalendarEvent): String? = when {
        e.isFixture -> null
        !e.calendarTitle.isNullOrBlank() -> e.calendarTitle.trim()
        else -> defaultName(e)
    }

    /**
     * The name without Meka's own: Google names an account's main calendar after its address ("meka@gmail.com"),
     * which reads as noise in the Calendar key and on rows, so that one is "Personal"; any other the provider's name.
     */
    fun defaultName(e: CalendarEvent): String? {
        val n = e.calendarName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val account = e.account?.trim().orEmpty()
        return if ('@' in n && n.equals(account, ignoreCase = true)) PERSONAL else n
    }

    /** What a rename stores: trimmed, inner spaces collapsed, at most [MAX_NAME]; null (back to the default) when blank. */
    fun cleanName(typed: String): String? =
        typed.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim().takeIf { it.isNotEmpty() }

    /** The undo bar's line after a rename: "Calendar renamed Kids" / "Calendar name back to Personal". */
    fun renamedLine(name: String?, defaultLabel: String): String =
        if (name == null) "Calendar name back to $defaultLabel" else "Calendar renamed $name"

    /** The `calendar_mark` entity id for [key]: the same on every device. */
    fun markId(key: String): String = "c" + ActivityRules.fnv64("calendar:$key")

    fun detail(e: CalendarEvent): String? = when {
        e.isFixture -> "FC Barcelona"
        else -> listOfNotNull(
            when (e.provider) { "google" -> "Google"; "microsoft" -> "Outlook"; else -> null },
            e.account?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ").ifEmpty { null }
    }

    /**
     * Every calendar in [events] plus hidden ones with no events now, by name; each says whether it's on Today. A
     * holiday calendar ([HolidayCalendars]) is off unless Meka turned it on ([shownCalendars]), and says so.
     */
    fun choices(
        events: List<CalendarEvent>,
        hiddenCalendars: Map<String, String>,
        shownCalendars: Set<String> = emptySet(),
    ): List<CalendarChoice> {
        val seen = LinkedHashMap<String, CalendarChoice>()
        for (e in events) {
            val k = key(e)
            if (k in seen) continue
            val holiday = HolidayCalendars.isHolidayKey(k)
            val off = k in hiddenCalendars || (holiday && k !in shownCalendars)
            val detail = if (holiday) listOfNotNull(detail(e), HolidayCalendars.DETAIL).joinToString(" · ") else detail(e)
            seen[k] = CalendarChoice(k, label(e), detail, !off, label(e.copy(calendarTitle = null)), canRename = !e.isFixture)
        }
        for ((k, label) in hiddenCalendars) {
            if (k !in seen) seen[k] = CalendarChoice(k, label.ifBlank { "Calendar" }, null, false)
        }
        return seen.values.sortedWith(compareBy<CalendarChoice>({ it.label.lowercase() }, { it.key }))
    }

    /** The undo bar's line after hiding: "Timestripe hidden from Today". */
    fun hiddenLine(label: String): String = "$label hidden from Today"
}

/**
 * Holiday calendars start off Today (Fold review 2026-10-09, item 4: Google's "Holidays in United States" put Columbus
 * Day on Today). UK bank holidays already come from GOV.UK for work mode, so a subscribed holiday calendar is noise
 * until Meka turns it on in Calendars (a `calendar_mark` with `hiddenFromToday = false`). Non-AI, pure, by name only:
 *
 * - Google's own: "Holidays in United Kingdom", "Public holidays in Spain", "Christian Holidays", "Jewish Holidays"…
 * - Outlook's: "United States holidays", "United Kingdom holidays" (a country from [COUNTRIES]).
 * - A short country code: "UK Holidays", "US Holidays".
 *
 * Off also keeps one out of the Calendar tab and its key (Fold review 2026-10-09 07:26, item 9: "US Holidays" sat in the
 * key); turned on, it shows everywhere. Search still finds its events.
 *
 * Meka's own calendars are never caught: "Holidays", "Family holidays" or "Holiday plans" are not holiday calendars.
 * The fixtures feed never is.
 */
object HolidayCalendars {
    /** Added to the row's line in Calendars. */
    const val DETAIL = "Holiday calendar · off until you turn it on, Calendar tab too (UK bank holidays come from GOV.UK)"

    private val FAITHS = setOf(
        "christian", "orthodox christian", "jewish", "muslim", "islamic", "hindu", "buddhist", "sikh", "religious",
    )

    /** Outlook's holiday calendars are named after the country ("United Kingdom holidays"). */
    val COUNTRIES = setOf(
        "united kingdom", "united states", "uk", "us", "usa", "england", "scotland", "wales", "northern ireland",
        "ireland", "nigeria", "ghana", "canada", "australia", "new zealand", "india", "south africa", "spain", "france",
        "germany", "italy", "portugal", "netherlands", "belgium", "poland", "kenya", "jamaica",
    )

    private val CODE = Regex("^[A-Z]{2,3} [Hh]olidays$")

    fun isHolidayName(name: String?): Boolean {
        val raw = name?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return false
        val n = raw.lowercase()
        if (n.startsWith("holidays in ") || n.startsWith("public holidays in ") || n.startsWith("bank holidays in ")) return true
        if (CODE.matches(raw)) return true
        val head = when {
            n.endsWith(" public holidays") -> n.removeSuffix(" public holidays")
            n.endsWith(" holidays") -> n.removeSuffix(" holidays")
            else -> return false
        }
        return head in FAITHS || head in COUNTRIES
    }

    fun isHoliday(e: CalendarEvent): Boolean = !e.isFixture && isHolidayName(e.calendarName)

    /** From a calendar key ([CalendarRules.key]: "google|meka@gmail.com|Holidays in United States"). */
    fun isHolidayKey(key: String): Boolean {
        val parts = key.split("|", limit = 3)
        return parts.size == 3 && parts[0] != "fixtures" && isHolidayName(parts[2])
    }
}
