package os.meka.core.domain

/**
 * Activity log completeness (2026-10-18): **Export** — everything the log holds, not just the 30 days the screen shows,
 * as a spreadsheet (CSV) Meka saves where he likes. Made on the device from his own synced data; nothing is sent or
 * stored. Message words never go in (see [ActivityRules.NO_TEXT_SOURCES]); the rows are the screen's own lines.
 *
 * Retention: entries stay in his encrypted, synced data (Delete all removes them); the screen shows the last
 * [ActivityRules.DAYS_SHOWN] days and Undo lasts [ActivityRules.UNDO_DAYS] days. Nothing is deleted on its own.
 */
data class ActivityExportFile(val line: String, val csv: String? = null, val fileName: String? = null) {
    override fun toString() = "ActivityExportFile(${line.take(40)}, ${csv?.length ?: 0} chars)"
}

object ActivityExportRules {
    const val LABEL = "Export"
    const val NOTHING = "Nothing in Activity to save yet"
    val COLUMNS = listOf("Date", "Time", "Kind", "What", "Detail", "Why", "Undo")

    /** The line under the days: how long things are kept and shown, and what Export saves. */
    fun keptLine(count: Int): String =
        "Kept in your data on your devices · this shows the last ${ActivityRules.DAYS_SHOWN} days and Undo lasts " +
            "${ActivityRules.UNDO_DAYS} · Export saves " + if (count == 1) "the 1 entry" else "all $count entries"

    fun kindLabel(k: ActivityKind): String = when (k) {
        ActivityKind.REMINDED -> "Reminder"
        ActivityKind.DIGEST -> "Digest"
        ActivityKind.CHANGED -> "Done for you"
        ActivityKind.PUBLISHED -> "Phone build"
        ActivityKind.CALENDAR -> "Calendar edit"
        ActivityKind.SCREENED -> "Call stopped"
        ActivityKind.CALL -> "Call"
        ActivityKind.FAMILY -> "Family"
        ActivityKind.DEVICE -> "Device"
        ActivityKind.APPROVAL -> "Approval"
    }

    fun fileName(today: Long): String = "navilife-activity-${date(today)}.csv"

    /** What the Undo column says for [i]. */
    fun undoCell(i: ActivityItem, nowMs: Long, cal: LocalCalendar): String = when {
        i.undoneAtMs != null -> "Undone ${date(cal.epochDayOf(i.undoneAtMs))} ${LocalClock.formatMinute(cal.minuteOfDay(i.undoneAtMs))}" +
            (i.undoNote?.let { " · $it" } ?: "")
        ActivityRules.undoExpired(i, nowMs) -> ActivityRules.TOO_LATE
        i.canUndo -> "Can be undone in Activity"
        else -> ""
    }

    /** Every entry, oldest first, one row each; message text left out. */
    fun csv(items: List<ActivityItem>, nowMs: Long, cal: LocalCalendar): String {
        val out = StringBuilder()
        fun add(cells: List<String>) { out.append(cells.joinToString(",") { TradingExportRules.field(it) }).append("\r\n") }
        add(COLUMNS)
        for (i in items.sortedWith(compareBy<ActivityItem> { it.atMs }.thenBy { it.id })) {
            add(listOf(
                date(cal.epochDayOf(i.atMs)),
                LocalClock.formatMinute(cal.minuteOfDay(i.atMs)),
                kindLabel(i.kind),
                i.summary,
                i.detail.takeIf { ActivityRules.keepsDetail(i.source) }.orEmpty(),
                i.why,
                undoCell(i, nowMs, cal),
            ))
        }
        return out.toString()
    }

    fun file(items: List<ActivityItem>, nowMs: Long, cal: LocalCalendar): ActivityExportFile {
        if (items.isEmpty()) return ActivityExportFile(NOTHING)
        val n = items.size
        val line = (if (n == 1) "1 entry" else "$n entries") + " · choose where to save it"
        return ActivityExportFile(line, csv(items, nowMs, cal), fileName(cal.epochDayOf(nowMs)))
    }

    private fun date(day: Long): String = CivilDate.fromEpochDay(day).let {
        "${it.year}-${it.month.toString().padStart(2, '0')}-${it.day.toString().padStart(2, '0')}"
    }
}
