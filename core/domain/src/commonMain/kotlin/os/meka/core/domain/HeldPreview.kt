package os.meka.core.domain

/**
 * "1 held for later" opens a preview (Fold review 2026-10-09 13:45, item 5). During work mode Needs you says how much
 * the Fold is holding; tapping that line now unfolds what's held — sender · first line · time, newest first — so Meka
 * can see whether anything matters without leaving work mode. Non-AI and pure.
 *
 * Captured content is untrusted (ADR-006): shown only, never interpreted. Nothing here marks anything read, clears
 * it or touches WhatsApp and Messages: the summary after work is unchanged and Done still clears it.
 */
data class HeldPreviewRow(
    val id: String,
    /** "Ada" or "Ada · Family chat" for a group message. */
    val who: String,
    /** The item's first line, cut at a word: "Can you pick up the kids at…", "Missed call", "Voice message". */
    val line: String,
    /** "14:05" today, "Yesterday 18:40", else "Thu 8 Oct 09:12". */
    val time: String,
    val app: CaptureApp,
    val urgent: Boolean,
)

data class HeldPreview(
    /** "At work · 3 held for later" (the line that opens the preview). */
    val label: String,
    /** Newest first, at most [HeldPreviewRules.MAX_ROWS]. */
    val rows: List<HeldPreviewRow>,
    /** How many more are held beyond [rows] ("+2 more — all of them after work"); 0 when everything shows. */
    val more: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
    val moreLine: String? get() = if (more > 0) HeldPreviewRules.moreLine(more) else null
    val caption: String get() = HeldPreviewRules.CAPTION
}

object HeldPreviewRules {
    const val MAX_ROWS = 5
    const val LINE_CHARS = 60
    const val CAPTION = "A preview only: nothing is marked read in WhatsApp or Messages."
    const val SHOW_HINT = "Shows what's held"
    const val HIDE_HINT = "Folds the preview away"

    /** "At work · 3 held for later" */
    fun label(workLine: String, count: Int): String = "$workLine · $count held for later"

    /** "+2 more — all of them after work" */
    fun moreLine(more: Int): String = "+$more more — all of them after work"

    /** The first line of [text], cut at a word to [LINE_CHARS] with "…". */
    fun firstLine(text: String): String {
        val first = text.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        val line = first.replace(Regex("\\s+"), " ")
        if (line.length <= LINE_CHARS) return line
        val cut = line.lastIndexOf(' ', LINE_CHARS)
        val end = if (cut >= LINE_CHARS / 2) cut else LINE_CHARS
        return line.substring(0, end).trimEnd(' ', ',', ';', ':', '-', '.') + "…"
    }

    /** "14:05" today, "Yesterday 18:40", otherwise "Thu 8 Oct 09:12". */
    fun timeLabel(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val hhmm = LocalClock.formatMinute(cal.minuteOfDay(atMs))
        val day = cal.epochDayOf(atMs)
        val today = cal.epochDayOf(nowMs)
        return when (day) {
            today -> hhmm
            today - 1 -> "Yesterday $hhmm"
            else -> "${CivilDate.shortLabel(day)} $hhmm"
        }
    }

    fun row(item: CapturedItem, displayName: String, nowMs: Long, cal: LocalCalendar): HeldPreviewRow {
        val who = item.conversation?.trim()?.takeIf { it.isNotEmpty() && People.key(it) != People.key(displayName) }
            ?.let { "$displayName · $it" } ?: displayName
        val line = when (item.kind) {
            CaptureKind.MESSAGE -> firstLine(item.text.orEmpty()).ifEmpty { "Message" }
            CaptureKind.MISSED_CALL -> "Missed call"
            CaptureKind.VOICE_MESSAGE -> item.text?.let(::firstLine)?.takeIf { it.isNotEmpty() }
                ?.let { "Voice message · “$it”" } ?: item.displayLine // "Transcribing…" until its words arrive
        }
        return HeldPreviewRow(item.id, who, line, timeLabel(item.atMs, nowMs, cal), item.app, item.isUrgent)
    }

    /**
     * Everything [summary] holds, newest first (each under its person's name as the summary shows it, so a caller known
     * by number reads as the listed name), at most [MAX_ROWS], with [workLine] heading the label.
     */
    fun build(summary: AfterWorkSummary, workLine: String, nowMs: Long, cal: LocalCalendar): HeldPreview {
        val items = summary.people.flatMap { p -> p.items.map { it to p.personName } }
            .sortedWith(compareByDescending<Pair<CapturedItem, String>> { it.first.atMs }.thenBy { it.first.id })
        val rows = items.take(MAX_ROWS).map { (item, name) -> row(item, name, nowMs, cal) }
        return HeldPreview(label(workLine, items.size), rows, (items.size - rows.size).coerceAtLeast(0))
    }
}
