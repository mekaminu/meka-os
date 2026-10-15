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

/**
 * Barça match day, slice 3: the result in the next morning's brief and Ask/Talk "what's the score?" / "what channel is
 * Barça on?". Everything is read back from the lines the server wrote into each match's notes (slices 1 and 2): the
 * kick-off, where to watch it in the UK, the score and the goals. Those came from public web pages, so the Ask lines
 * make the context untrusted (ADR-006). Nothing is stored.
 */
object BarcaBriefRules {
    /** A finished match is said in the brief while its kick-off is at most this long ago. */
    const val RESULT_HOURS = 30
    private const val HOUR_MS = 3_600_000L
    private const val EVENING_MIN = 17 * 60
    private const val BARCA = "Barça"
    private val WORDS = listOf("nil", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    /** The latest finished Barça match whose kick-off was in the last [RESULT_HOURS], with its final score. */
    fun lastResult(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long): Pair<CalendarEvent, BarcaMatchRules.Score>? =
        shownMatches(events, marks)
            .filter { it.startAtMs <= nowMs && nowMs - it.startAtMs <= RESULT_HOURS * HOUR_MS }
            .mapNotNull { e -> BarcaMatchRules.score(e)?.takeIf { !it.live }?.let { e to it } }
            .maxByOrNull { it.first.startAtMs }

    /**
     * The brief's sentences: last night's result and Barça's scorers ("Last night Barça beat Getafe two–one, with goals
     * from Lewandowski twice and Yamal."), then, on a match day, where today's match is on ("Barça's match at 8
     * o'clock tonight is on TNT Sports 2 and HBO Max."). Empty when neither applies.
     */
    fun briefSentences(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, cal: LocalCalendar): List<String> {
        val out = mutableListOf<String>()
        val today = cal.epochDayOf(nowMs)
        lastResult(events, marks, nowMs)?.let { (e, s) ->
            val day = cal.epochDayOf(e.startAtMs)
            val whenSaid = when {
                day == today -> "Earlier today"
                day == today - 1 && cal.minuteOfDay(e.startAtMs) >= EVENING_MIN -> "Last night"
                day == today - 1 -> "Yesterday"
                else -> null
            }
            if (whenSaid != null) out += resultSentence(whenSaid, s)
        }
        shownMatches(events, marks)
            .filter { BarcaMatchRules.isMatch(it) && cal.epochDayOf(it.startAtMs) == today && it.startAtMs > nowMs }
            .minByOrNull { it.startAtMs }
            ?.let { e ->
                val watch = BarcaMatchRules.watchLine(e)
                val min = cal.minuteOfDay(e.startAtMs)
                val time = BriefScriptRules.clock(min) + if (min >= EVENING_MIN) " tonight" else ""
                when {
                    watch.startsWith("on ") -> out += "Barça's match at $time is on ${spokenChannels(watch.removePrefix("on "))}."
                    watch == "not on UK TV" -> out += "Barça's match at $time isn't on UK TV."
                }
            }
        return out
    }

    /** "Last night Barça beat Getafe two–one, with goals from Lewandowski twice and Yamal." */
    fun resultSentence(whenSaid: String, s: BarcaMatchRules.Score): String {
        val other = if (s.home == BARCA) s.away else s.home
        val hi = maxOf(s.barcaGoals, s.otherGoals)
        val lo = minOf(s.barcaGoals, s.otherGoals)
        val score = "${said(hi)}–${said(lo)}"
        val what = when {
            s.barcaGoals > s.otherGoals -> "Barça beat $other $score"
            s.barcaGoals < s.otherGoals -> "Barça lost $score to $other"
            else -> "Barça drew $score with $other"
        }
        val extra = s.detail?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""
        val scorers = scorers(s)
        val goals = when {
            scorers.isEmpty() -> ""
            scorers.size == 1 && s.barcaGoals == 1 -> ", with a goal from ${scorers[0]}"
            else -> ", with goals from ${listing(scorers)}"
        }
        return "$whenSaid $what$extra$goals."
    }

    /** Barça's scorers in order, each once: "Lewandowski twice", "Yamal", "an own goal". */
    fun scorers(s: BarcaMatchRules.Score): List<String> {
        val names = s.goals.filter { it.team == BARCA }.map { g ->
            val w = g.who.trim()
            when {
                OWN_GOAL.containsMatchIn(w) -> "an own goal"
                else -> w.replace(PENALTY, "").trim()
            }
        }.filter { it.isNotEmpty() }
        val counts = linkedMapOf<String, Int>()
        names.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return counts.map { (n, c) ->
            when {
                n == "an own goal" && c > 1 -> "$c own goals"
                c == 2 -> "$n twice"
                c >= 3 -> "$n with a hat-trick".takeIf { c == 3 } ?: "$n $c times"
                else -> n
            }
        }
    }

    private val OWN_GOAL = Regex("""\b(og|o\.g\.|own goal)$""", RegexOption.IGNORE_CASE)
    private val PENALTY = Regex("""\s+(pen|penalty|\(pen\))$""", RegexOption.IGNORE_CASE)

    private fun said(n: Int) = WORDS.getOrNull(n) ?: n.toString()

    /** "TNT Sports 2 · HBO Max" → "TNT Sports 2 and HBO Max". */
    internal fun spokenChannels(line: String): String = listing(line.split(" · ").map { it.trim() }.filter { it.isNotEmpty() })

    private fun listing(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** The Barça matches shown on his calendar (the Fixtures feed, not hidden, timed). */
    fun shownMatches(events: List<CalendarEvent>, marks: EventMarks): List<CalendarEvent> =
        events.filter { it.isFixture && !it.allDay && !marks.isHidden(it.id) && !marks.isCalendarHidden(it) }.distinctBy { it.id }
}

/** Barça match day, slice 3: Ask and Talk know the matches ("what's the score?", "what channel is Barça on?"). */
object BarcaAskRules {
    /** Matches carried: the latest finished one (within [BarcaBriefRules.RESULT_HOURS]), then the coming ones. */
    const val ASK_LINES = 4
    /** Coming matches looked at, days ahead. */
    const val AHEAD_DAYS = 14
    private const val DAY_MS = 86_400_000L

    private val ABOUT = Regex(
        """\b(bar[cç]a|barcelona|the score|score\b|kick[- ]?off|channel|on (the )?tv|watch (the )?(match|game|football)|the (match|game) (on|tonight|today|tomorrow)|football tonight|who scored|did .* win)""",
        RegexOption.IGNORE_CASE,
    )
    private val CHANNEL = Regex("""\b(channel|on (the )?tv|watch|which (station|app)|where.*(watch|on)|stream)""", RegexOption.IGNORE_CASE)
    private val SCORE = Regex("""\b(score|result|scored|win|won|lose|lost|draw|drew|goal)""", RegexOption.IGNORE_CASE)

    private val HOW_TO = Regex("""^\s*how\b|\b(turn (it |them )?(off|on)|notifications?|alerts?|heads-ups?|settings?|set (it )?up|help)\b""", RegexOption.IGNORE_CASE)

    /** A question about the matches themselves ("what's the score?"), not how Navilife's Barça features work. */
    fun isAbout(q: String): Boolean = ABOUT.containsMatchIn(q) && !HOW_TO.containsMatchIn(q)

    /** The matches Ask carries: the latest finished one, a live one, then the coming ones, nearest first. */
    fun matches(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long): List<CalendarEvent> {
        val shown = BarcaBriefRules.shownMatches(events, marks)
        val last = BarcaBriefRules.lastResult(events, marks, nowMs)?.first
        val live = shown.filter { BarcaMatchRules.score(it)?.live == true && it.startAtMs <= nowMs }
        val coming = shown.filter { it.startAtMs > nowMs && it.startAtMs - nowMs <= AHEAD_DAYS * DAY_MS }.sortedBy { it.startAtMs }
        return (listOfNotNull(last) + live + coming).distinctBy { it.id }.take(ASK_LINES)
    }

    /**
     * "Barça match · Sat 18 Oct 20:00 UK time · Barça v Getafe · LaLiga · on TNT Sports 2 · HBO Max · Live: Barça 2–1
     * Getafe · 67' · Goals: 23' Lewandowski (Barça)".
     */
    fun askLines(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, cal: LocalCalendar): List<String> =
        matches(events, marks, nowMs).map { e -> line(e, nowMs, cal) }

    private fun line(e: CalendarEvent, nowMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(e.startAtMs)
        val tbc = !BarcaMatchRules.isMatch(e)
        val title = e.title.removeSuffix("(kick-off TBC)").trim()
        val time = if (tbc) "kick-off to be confirmed" else LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs)) + " UK time"
        val s = BarcaMatchRules.score(e)
        val state = when {
            s != null && s.live -> "Live: ${s.line}" + (s.detail?.let { " · $it" } ?: "")
            s != null -> "Full time: ${s.line}" + (s.detail?.let { " · $it" } ?: "") + " · ${s.outcome}"
            BarcaMatchRules.scoreUnavailable(e) -> BarcaMatchRules.UNAVAILABLE
            e.startAtMs <= nowMs -> "on now"
            else -> null
        }
        val goals = s?.goals?.takeIf { it.isNotEmpty() }?.joinToString(", ") { g -> g.short + (g.team?.let { " ($it)" } ?: "") }?.let { "Goals: $it" }
        val watch = if (s != null && !s.live) null else BarcaMatchRules.watchLine(e)
        return listOfNotNull(
            "Barça match", "${CivilDate.shortLabel(day)} $time", title, e.calendarName?.takeIf { it.isNotBlank() }, watch, state, goals,
        ).joinToString(" · ")
    }

    /**
     * The device's own answer when the AI can't be reached: where to watch for a channel question, the live or last score
     * for a score question, else the next match. Null when his calendar holds no Barça match at
     * all (no Fixtures calendar), so a question that only sounds like football gets the usual "offline" line.
     */
    fun localAnswer(q: String, events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, cal: LocalCalendar): String? {
        val shown = BarcaBriefRules.shownMatches(events, marks).takeIf { it.isNotEmpty() } ?: return null
        val live = shown.firstOrNull { BarcaMatchRules.score(it)?.live == true && it.startAtMs <= nowMs }
        val next = shown.filter { it.startAtMs > nowMs && it.startAtMs - nowMs <= AHEAD_DAYS * DAY_MS }.minByOrNull { it.startAtMs }
        val last = BarcaBriefRules.lastResult(events, marks, nowMs)
        fun whenOf(e: CalendarEvent): String {
            val day = cal.epochDayOf(e.startAtMs)
            val today = cal.epochDayOf(nowMs)
            val d = when (day) { today -> "today"; today + 1 -> "tomorrow"; else -> CivilDate.shortLabel(day) }
            return if (!BarcaMatchRules.isMatch(e)) "$d, kick-off to be confirmed" else "$d at ${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))}"
        }
        fun title(e: CalendarEvent) = e.title.removeSuffix("(kick-off TBC)").trim()
        if (CHANNEL.containsMatchIn(q) && !SCORE.containsMatchIn(q)) {
            val e = live ?: next ?: return NO_MATCH
            return "${title(e)}, ${whenOf(e)}: ${BarcaMatchRules.watchLine(e)}."
        }
        if (SCORE.containsMatchIn(q) || live != null) {
            live?.let { e ->
                val s = BarcaMatchRules.score(e)!!
                return "${s.line}" + (s.detail?.let { ", $it" } ?: "") + "." + goalsSaid(s)
            }
            last?.let { (e, s) ->
                return "Full time: ${s.line}" + (s.detail?.let { ", $it" } ?: "") + " (${whenOf(e).substringBefore(" at")}). ${s.outcome}." + goalsSaid(s)
            }
            val e = next ?: return NO_MATCH
            return "No score yet: ${title(e)} is ${whenOf(e)}."
        }
        val e = next ?: return NO_MATCH
        return "${title(e)}, ${whenOf(e)}" + (e.calendarName?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "") + ": ${BarcaMatchRules.watchLine(e)}."
    }

    private fun goalsSaid(s: BarcaMatchRules.Score): String =
        s.goals.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.short }?.let { " Goals: $it." } ?: ""

    const val NO_MATCH = "There's no Barça match in your calendar in the next two weeks."
}
