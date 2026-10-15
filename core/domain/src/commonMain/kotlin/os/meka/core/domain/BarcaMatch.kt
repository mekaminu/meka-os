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
        e.description.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.take(6)

    // ---- Slice 2: the live score, goal alerts and full time ----

    const val GOAL_PREFIX = "barca-goal:"
    const val FULL_TIME_PREFIX = "barca-ft:"
    /** A goal's heads-up stands this long after kick-off at most (a match long over never buzzes for a goal). */
    const val GOAL_STANDS_MIN = 180
    /** The full-time heads-up stands this long after kick-off (held through quiet hours, it's gone by morning). */
    const val FULL_TIME_STANDS_MIN = 360
    const val LIVE = "Live:"
    const val FULL_TIME = "Full time:"
    const val UNAVAILABLE = "Live score unavailable"

    data class Goal(val minute: String, val who: String, val team: String?) {
        /** "Lewandowski 23'". */
        val short: String get() = listOf(who, minute).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * The score the server wrote into a match's notes: "Live: Barça 2–1 Getafe · 67'" or "Full time: Barça 2–1 Getafe",
     * then "Goals: 23' Lewandowski (Barça) · 40' Mayoral (Getafe)".
     */
    data class Score(
        val live: Boolean, val home: String, val away: String, val homeGoals: Int, val awayGoals: Int,
        /** "67'", "half time" while live; "after extra time" at full time; null when the feed gave none. */
        val detail: String?, val goals: List<Goal>,
    ) {
        /** "Barça 2–1 Getafe". */
        val line: String get() = "$home $homeGoals–$awayGoals $away"
        val barcaGoals: Int get() = if (home == BARCA) homeGoals else awayGoals
        val otherGoals: Int get() = if (home == BARCA) awayGoals else homeGoals
        /** "Barça won", "Barça lost", "A draw". */
        val outcome: String get() = when {
            barcaGoals > otherGoals -> "Barça won"
            barcaGoals < otherGoals -> "Barça lost"
            else -> "A draw"
        }
    }

    private const val BARCA = "Barça"
    private val SCORE = Regex("""^(Live|Full time): (.+) (\d+)–(\d+) (.+?)(?: · (.+))?$""")
    private val GOAL = Regex("""^(\S+')?\s*(.+?)(?: \((.+)\))?$""")

    /** The score in the match's notes, or null before kick-off / when the server hasn't one. */
    fun score(e: CalendarEvent): Score? {
        val lines = notesLines(e)
        val m = lines.firstNotNullOfOrNull { SCORE.matchEntire(it) } ?: return null
        val goals = lines.firstOrNull { it.startsWith("Goals: ") }?.removePrefix("Goals: ")?.split(" · ").orEmpty()
            .mapNotNull { g -> GOAL.matchEntire(g.trim())?.let { Goal(it.groupValues[1], it.groupValues[2], it.groupValues[3].ifEmpty { null }) } }
        return Score(
            live = m.groupValues[1] == "Live", home = m.groupValues[2], away = m.groupValues[5],
            homeGoals = m.groupValues[3].toInt(), awayGoals = m.groupValues[4].toInt(),
            detail = m.groupValues[6].ifEmpty { null }, goals = goals,
        )
    }

    /** Whether the server said it couldn't read the score while the match is on. */
    fun scoreUnavailable(e: CalendarEvent): Boolean = notesLines(e).any { it == UNAVAILABLE }

    /**
     * The goal heads-up while a match is live: the latest goal only ("Goal · Barça 1–0 Getafe" · "Lewandowski 23' ·
     * LaLiga", or "Getafe score · Barça 1–1 Getafe" for theirs), keyed on the score so each goal is a fresh one and a
     * goal taken back doesn't buzz again. Posted in the match's one place, so each goal (and full time) replaces the
     * last ([NoticeSource.BARCA_GOALS], his to lower or turn off).
     */
    fun goalNotices(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long): List<Notice> =
        shown(events, marks).mapNotNull { e ->
            val s = score(e)?.takeIf { it.live && it.homeGoals + it.awayGoals > 0 } ?: return@mapNotNull null
            val expires = e.startAtMs + GOAL_STANDS_MIN * MIN_MS
            if (nowMs >= expires) return@mapNotNull null
            val last = s.goals.lastOrNull()
            val title = when {
                last?.team == null -> s.line
                last.team == BARCA -> "Goal · ${s.line}"
                else -> "${last.team} score · ${s.line}"
            }
            Notice(
                key = "$GOAL_PREFIX${e.id}:${s.homeGoals}-${s.awayGoals}", source = NoticeSource.BARCA_GOALS,
                tier = NoticeTier.HEADS_UP, title = title,
                text = listOfNotNull(last?.short, e.calendarName?.takeIf { it.isNotBlank() }, s.detail).joinToString(" · "),
                atMs = e.startAtMs, target = NoticeTarget.TODAY, expiresAtMs = expires, postAs = "barca-live:${e.id}",
            )
        }

    /**
     * The full-time heads-up: "Full time · Barça 2–1 Getafe" · "Barça won · Lewandowski 23', Yamal 51' · LaLiga"
     * ([NoticeSource.BARCA_RESULT]), in the same place as the goals.
     */
    fun fullTimeNotices(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long): List<Notice> =
        shown(events, marks).mapNotNull { e ->
            val s = score(e)?.takeIf { !it.live } ?: return@mapNotNull null
            val expires = e.startAtMs + FULL_TIME_STANDS_MIN * MIN_MS
            if (nowMs >= expires) return@mapNotNull null
            val barcaScorers = s.goals.filter { it.team == BARCA }.joinToString(", ") { it.short }.ifEmpty { null }
            Notice(
                key = "$FULL_TIME_PREFIX${e.id}", source = NoticeSource.BARCA_RESULT, tier = NoticeTier.HEADS_UP,
                title = "Full time · ${s.line}",
                text = listOfNotNull(s.outcome + (s.detail?.let { " $it" } ?: ""), barcaScorers, e.calendarName?.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                atMs = e.startAtMs, target = NoticeTarget.TODAY, expiresAtMs = expires, postAs = "barca-live:${e.id}",
            )
        }

    private fun shown(events: List<CalendarEvent>, marks: EventMarks) =
        events.filter { it.isFixture && !it.allDay && !marks.isHidden(it.id) && !marks.isCalendarHidden(it) }.distinctBy { it.id }

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
