package os.meka.core.domain

/** Where a row sits in a card drawn row by row: the first rounds the top, the last the bottom, only both. */
enum class CardEdge { ONLY, FIRST, MIDDLE, LAST;
    val roundsTop: Boolean get() = this == ONLY || this == FIRST
    val roundsBottom: Boolean get() = this == ONLY || this == LAST
}

/**
 * The Today rebuild, Meka's 08:09 point (e): Today's Schedule is the mockup's one compact card
 * (`docs/design/day-list/TodayNew.dc.html`), not the old open timeline. Non-AI and pure, shared by the Fold and the Mac.
 *
 * - **One card**: the all-day rows, the "1 earlier ›" fold, events, planned tasks, sessions, work, the commute and the
 *   now line sit inside one surface card, a hairline between rows ([edge] says which row rounds which corner).
 * - **A narrow time column** ([TIME_DP], the mockup's 52 px plus room for "09:00") with "All day" in it on the first
 *   all-day row ([allDayTime]), so the separate "All day" label line goes.
 * - **No free-time rows** ([rows]): "1 h 30 free" lines are dropped from the card; the now line still says what's free
 *   when the free stretch starts now ("1 h free until Work"), and the commute rows stay inside the card.
 * - **The start only** ([time]): "09:30–10:00" shows as "09:30" and "Until 10:00" (begun before today) as "–10:00", so
 *   the time fits the narrow column on one line; an event's end goes first on its line under the title ([until]:
 *   "until 10:00 · Camp Nou").
 */
object ScheduleCardRules {
    const val TIME_DP = 60
    const val ALL_DAY = "All day"

    /** The timed rows the card shows: everything but the free gaps. */
    fun rows(rows: List<TimelineRow>): List<TimelineRow> = rows.filter { it.kind != TimelineKind.GAP }

    /** The time column's words: the start of a range, "–10:00" for one that began before today. */
    fun time(t: String): String = when {
        t.startsWith(UNTIL) -> "–" + t.removePrefix(UNTIL)
        '–' in t -> t.substringBefore('–')
        else -> t
    }

    /** "until 10:00" for a range, null otherwise. */
    fun until(t: String): String? = when {
        t.startsWith(UNTIL) -> null // "–10:00" already says it
        '–' in t -> "until " + t.substringAfter('–')
        else -> null
    }

    private const val UNTIL = "Until "

    /** "All day" in the time column of the first all-day row, none on the rest. */
    fun allDayTime(index: Int): String? = if (index == 0) ALL_DAY else null

    /** How many rows the card holds, in its order: all-day rows, "+2 more", "1 earlier", the earlier rows when open, the rest. */
    fun count(allDay: Int, more: Boolean, earlier: Boolean, earlierRows: Int, rows: Int): Int =
        allDay + (if (more) 1 else 0) + (if (earlier) 1 + earlierRows else 0) + rows

    fun edge(index: Int, count: Int): CardEdge = when {
        count <= 1 -> CardEdge.ONLY
        index == 0 -> CardEdge.FIRST
        index >= count - 1 -> CardEdge.LAST
        else -> CardEdge.MIDDLE
    }
}
