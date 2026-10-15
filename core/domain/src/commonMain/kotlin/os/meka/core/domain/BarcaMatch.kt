package os.meka.core.domain

/**
 * Barça match day, slice 1 (Meka approved 2026-10-12). For each Barça match in the Fixtures calendar (the server's
 * FC Barcelona feed), the server writes two lines into the match's notes: the kick-off in UK time ("Kick-off 20:00 UK
 * time") and where to watch it in the UK ("On UK TV: Premier Sports 1 · LaLigaTV", "Not on UK TV, per the listings"
 * or "UK TV not listed yet"), read from a public listings page. These rules read those lines back and make the
 * heads-up an hour before kick-off. Nothing is stored; a hidden match or a hidden Fixtures calendar gets nothing, and
 * quiet hours and the Notifications choice apply through the governor like any heads-up.
 */
object BarcaMatchRules {
    const val NOTICE_PREFIX = "barca:"
    /** The heads-up comes this long before kick-off. */
    const val LEAD_MIN = 60
    /** It stands until this long after kick-off. */
    const val STANDS_MIN = 15
    const val ON_TV = "On UK TV:"
    const val NOT_ON_TV = "Not on UK TV"
    const val NOT_LISTED = "UK TV not listed yet"
    private const val TBC_TITLE = "(kick-off TBC)"
    private const val MIN_MS = 60_000L

    /** A Barça match from the Fixtures feed with a set kick-off time. */
    fun isMatch(e: CalendarEvent): Boolean = e.isFixture && !e.allDay && !e.title.endsWith(TBC_TITLE)

    /** "Kick-off 20:00 UK time" from the match's notes, or null when the server hasn't written it. */
    fun kickOffLine(e: CalendarEvent): String? = notesLines(e).firstOrNull { it.startsWith("Kick-off ") }

    /**
     * Where to watch, as the heads-up says it: "on Premier Sports 1 · LaLigaTV", "not on UK TV" or "UK TV not listed
     * yet" (also when the notes say nothing yet).
     */
    fun watchLine(e: CalendarEvent): String {
        val line = notesLines(e).firstOrNull { it.startsWith("On UK TV") || it.startsWith("UK TV") || it.startsWith(NOT_ON_TV) }
        return when {
            line == null -> NOT_LISTED
            line.startsWith(ON_TV) -> "on " + line.removePrefix(ON_TV).trim()
            line.startsWith(NOT_ON_TV) -> "not on UK TV"
            line.startsWith("UK TV:") -> "UK TV " + line.removePrefix("UK TV:").trim()
            else -> line
        }
    }

    private fun notesLines(e: CalendarEvent): List<String> =
        e.description.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4)

    /**
     * The heads-up an hour before each coming match: "Barça v Getafe · 20:00" · "LaLiga · kick-off in an hour · on
     * Premier Sports 1". Keyed on the kick-off, so a moved match gets a fresh one.
     */
    fun notices(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, cal: LocalCalendar): List<Notice> =
        events.filter { isMatch(it) && !marks.isHidden(it.id) && !marks.isCalendarHidden(it) }
            .distinctBy { it.id }
            .mapNotNull { e ->
                val expires = e.startAtMs + STANDS_MIN * MIN_MS
                if (nowMs >= expires) return@mapNotNull null
                val at = e.startAtMs - LEAD_MIN * MIN_MS
                val competition = e.calendarName?.takeIf { it.isNotBlank() }
                Notice(
                    key = "$NOTICE_PREFIX${e.id}:${e.startAtMs}", source = NoticeSource.BARCA, tier = NoticeTier.HEADS_UP,
                    title = "${e.title} · ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))}",
                    text = listOfNotNull(competition, "kick-off in an hour", watchLine(e)).joinToString(" · ")
                        .replaceFirstChar { it.uppercase() },
                    atMs = at, target = NoticeTarget.TODAY, expiresAtMs = expires, precision = NoticePrecision.CLOCK,
                )
            }
}
