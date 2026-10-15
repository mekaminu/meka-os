package os.meka.core.domain

/**
 * Barça match day, slice 4: the match on the Galaxy Watch. The watch has its own copy of the day (ADR-005 amendment
 * 2026-10-10), so it reads the same lines the server writes into each match's notes (slices 1 and 2): from the hour
 * before kick-off the screen and the tile show the match, where it's on, then the live score and the last goal, then
 * full time. Each goal's buzz is the phone's heads-up, which Wear OS brings to the wrist; this is what you see when you
 * look. Pure; nothing is stored; a hidden match or a hidden Fixtures calendar shows nothing.
 */
data class WatchMatch(
    val eventId: String,
    /** "BARÇA · 20:00" · "BARÇA · ON NOW" (no score yet) · "BARÇA · LIVE · 67'" · "BARÇA · HALF TIME" · "BARÇA · FULL TIME". */
    val label: String,
    /** Within the hour before kick-off and while it's on: the accent. */
    val lit: Boolean,
    /** "PSG v Barça" before kick-off and when no score has come; "Barça 2–1 Getafe" once there is one. */
    val line: String,
    /** "On TNT Sports 2 · HBO Max" · "Last goal · Yamal 51'" · "Barça won · Lewandowski 23', Yamal 51'". */
    val note: String?,
    /** The tile's one line: "PSG v Barça · 20:00" · "Barça 2–1 Getafe · 67'" · "FT · Barça 2–1 Getafe". */
    val short: String,
    val live: Boolean,
    /** For the complication while live: "2–1" (home first, as the score line). */
    val score: String?,
    /** For the complication's ring while live: how far through 90 minutes (half time 0.5). */
    val played: Float,
    val spoken: String,
)

object WatchMatchRules {
    /** It shows from this long before kick-off (when the phone's heads-up comes). */
    const val BEFORE_MIN = BarcaMatchRules.LEAD_MIN
    /** A full-time score stays this long after kick-off (the evening, not the next morning). */
    const val FULL_TIME_SHOWS_MIN = 240
    private const val MIN_MS = 60_000L
    private const val LABEL = "BARÇA"
    private val MINUTE = Regex("""^(\d+)(?:\+(\d+))?'$""")

    /** The fixtures worth keeping on the watch at [nowMs] (a few hours either side), so the screen can redraw cheaply. */
    fun near(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long): List<CalendarEvent> =
        events.filter {
            it.isFixture && !it.allDay && !marks.isHidden(it.id) && !marks.isCalendarHidden(it) &&
                it.startAtMs - BEFORE_MIN * MIN_MS <= nowMs + KEEP_AHEAD_MS && it.startAtMs + FULL_TIME_SHOWS_MIN * MIN_MS > nowMs
        }.distinctBy { it.id }.sortedBy { it.startAtMs }

    /** Looks a day ahead, so a sync before a match carries it to the moment it shows. */
    private const val KEEP_AHEAD_MS = 24 * 60 * MIN_MS

    /** The match to show at [nowMs] (the earliest that is due, on or just over), or null. */
    fun view(matches: List<CalendarEvent>, nowMs: Long, cal: LocalCalendar): WatchMatch? =
        matches.asSequence().sortedBy { it.startAtMs }.mapNotNull { one(it, nowMs, cal) }.firstOrNull()

    private fun one(e: CalendarEvent, nowMs: Long, cal: LocalCalendar): WatchMatch? {
        val start = e.startAtMs
        if (nowMs < start - BEFORE_MIN * MIN_MS) return null
        val kickOff = LocalClock.formatMinute(cal.minuteOfDay(start))
        val title = e.title.removeSuffix("(kick-off TBC)").trim()
        val s = BarcaMatchRules.score(e)
        if (s != null && !s.live) {
            if (nowMs >= start + FULL_TIME_SHOWS_MIN * MIN_MS) return null
            val scorers = s.goals.filter { it.team == "Barça" }.joinToString(", ") { it.short }.ifEmpty { null }
            val outcome = s.outcome + (s.detail?.let { " $it" } ?: "")
            val note = listOfNotNull(outcome, scorers).joinToString(" · ")
            return WatchMatch(
                eventId = e.id, label = "$LABEL · FULL TIME", lit = false, line = s.line, note = note,
                short = "FT · ${s.line}", live = false, score = null, played = 1f,
                spoken = "Full time. ${s.line}. $note.",
            )
        }
        // Not over: it stands as long as a goal's heads-up would (a match long gone never sticks).
        if (nowMs >= start + BarcaMatchRules.GOAL_STANDS_MIN * MIN_MS) return null
        if (s != null) {
            val half = s.detail.equals("half time", ignoreCase = true)
            val label = when {
                half -> "$LABEL · HALF TIME"
                s.detail != null -> "$LABEL · LIVE · ${s.detail.uppercase()}"
                else -> "$LABEL · LIVE"
            }
            val last = s.goals.lastOrNull()
            val note = last?.let { g -> "Last goal · ${g.short}" + (g.team?.takeIf { it != "Barça" }?.let { " ($it)" } ?: "") }
            return WatchMatch(
                eventId = e.id, label = label, lit = true, line = s.line, note = note,
                short = listOfNotNull(s.line, s.detail).joinToString(" · "), live = true,
                score = "${s.homeGoals}–${s.awayGoals}", played = if (half) 0.5f else played(s.detail),
                spoken = listOfNotNull(if (half) "Half time" else "Live", s.line, s.detail?.let(::spokenMinute), note).joinToString(". ") + ".",
            )
        }
        val started = nowMs >= start
        val note = when {
            started && BarcaMatchRules.scoreUnavailable(e) -> BarcaMatchRules.UNAVAILABLE
            else -> BarcaMatchRules.watchLine(e).replaceFirstChar { it.uppercase() }
        }
        return WatchMatch(
            eventId = e.id, label = if (started) "$LABEL · ON NOW" else "$LABEL · $kickOff", lit = true,
            line = title, note = note, short = "$title · $kickOff", live = false, score = null, played = 0f,
            spoken = (if (started) "Kicked off at $kickOff. " else "Kick-off at $kickOff. ") + "$title. $note.",
        )
    }

    /** "67'" → 67/90, "90+3'" → 1, extra time stays full; anything else 0. */
    fun played(detail: String?): Float {
        val m = detail?.trim()?.let { MINUTE.matchEntire(it) } ?: return 0f
        return (m.groupValues[1].toInt() / 90f).coerceIn(0f, 1f)
    }

    private fun spokenMinute(detail: String): String =
        MINUTE.matchEntire(detail.trim())?.let { m ->
            val base = m.groupValues[1]
            val added = m.groupValues[2]
            if (added.isNotEmpty()) "$base plus $added minutes" else "$base minutes"
        } ?: detail
}
