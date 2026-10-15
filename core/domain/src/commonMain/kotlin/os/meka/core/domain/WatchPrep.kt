package os.meka.core.domain

/**
 * Meeting prep, slice 3: the prep on the Galaxy Watch. The watch runs its own copy of the day (ADR-005 amendment
 * 2026-10-10), so its Today already carries [Today.meetingPrep] (worked out each minute from the synced calendar, emails
 * and Lists, with his lead time). This turns it into what a wrist can read at a glance: when, what, who and the one or
 * two most useful lines; the heads-up's buzz is the phone's, which Wear OS brings to the wrist. Pure; nothing stored;
 * guests' names and email subjects are other people's words (ADR-006), shown as text only.
 */
data class WatchPrep(
    val eventId: String,
    /** "PREP · IN 12 MIN" · "PREP · NOW" · "PREP · STARTED 14:00". */
    val label: String,
    /** "Quote review · 14:00". */
    val title: String,
    /** "With Sam Taylor and Alex Green". */
    val who: String,
    /** At most [WatchPrepRules.MAX_LINES] of Today's lines ("Sam Taylor · last email Tue 13:02 · “Re: quote”"). */
    val lines: List<String>,
    /** The tile's one line: "Prep · Quote review · in 12 min" · "Prep · Quote review · now". */
    val short: String,
    val spoken: String,
)

object WatchPrepRules {
    const val LABEL = "PREP"
    /** A wrist reads two lines; the rest stays on the phone and the Mac. */
    const val MAX_LINES = 2

    /** The watch's prep at [nowMs], or null when there's none (or it's gone [MeetingPrepRules.AFTER_START_MIN] after the start). */
    fun view(prep: MeetingPrep?, nowMs: Long, cal: LocalCalendar): WatchPrep? {
        val p = prep ?: return null
        if (nowMs >= p.startAtMs + MeetingPrepRules.AFTER_START_MIN * 60_000L) return null
        val at = LocalClock.formatMinute(cal.minuteOfDay(p.startAtMs))
        val title = p.title.ifEmpty { p.headline.removePrefix("Prep · ").substringBefore(" at ").substringBefore(" · ") }
        val mins = ((p.startAtMs - nowMs + 59_999L) / 60_000L)
        val whenShort = when {
            nowMs >= p.startAtMs + 60_000L -> "started $at"
            mins <= 0L -> "now"
            else -> "in $mins min"
        }
        val lines = p.lines.let { all ->
            if (all.size <= MAX_LINES) all
            else all.take(MAX_LINES - 1) + "+${p.lines.size - (MAX_LINES - 1) + extra(all)} more"
        }
        val spoken = buildString {
            append("Prep. ").append(title).append(" at ").append(at).append(", ").append(whenShort).append('.')
            if (p.whoLine.isNotEmpty()) append(' ').append(p.whoLine).append('.')
            lines.forEach { append(' ').append(it.replace(" · ", ", ")).append('.') }
        }
        return WatchPrep(
            eventId = p.eventId,
            label = "$LABEL · ${whenShort.uppercase()}",
            title = "$title · $at",
            who = p.whoLine,
            lines = lines,
            short = WatchTileRules.shorten("Prep · $title", WatchTileRules.TITLE_MAX - 12) + " · $whenShort",
            spoken = spoken,
        )
    }

    /** Today's last line may already be "+N more": count what it hid too. */
    private fun extra(all: List<String>): Int =
        all.lastOrNull()?.let { Regex("""^\+(\d+) more$""").matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0
}
