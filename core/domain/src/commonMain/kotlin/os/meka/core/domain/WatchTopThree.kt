package os.meka.core.domain

/**
 * Week board, the watch (item (1): "Watch: today's top three only"). Under the watch's now card, the day's next open
 * tasks as he ordered them: what carried from earlier days first (oldest first, as Today's From yesterday), then the
 * Board's Today column in his order (Today's Up next and Your day). The task the now card already shows is left out,
 * so the wrist never shows a task twice, and Needs you's tasks stay with the phone. At most [MAX] rows; the rest are
 * counted ("+4 more on your phone"). Each row ticks off with one tap (the watch's Done, with Undo). Pure, non-AI.
 */
data class WatchTopThree(
    /** "TODAY". */
    val label: String,
    /** "2 done · 5 to go" · "5 to go". */
    val countLine: String,
    val rows: List<WatchTopRow>,
    /** "+4 more on your phone"; null when every open task is on the wrist. */
    val moreLine: String?,
    val spoken: String,
)

data class WatchTopRow(
    val taskId: String,
    val title: String,
    /** "Carried 2 days" · "At 14:00 · 30m" · "1h"; null when there's nothing to say. */
    val line: String?,
    /** Carried from an earlier day: the line is in the accent. */
    val carried: Boolean,
)

object WatchTopThreeRules {
    const val MAX = 3
    const val LABEL = "TODAY"
    const val DONE_LINE = "Done"
    const val UNDO = "Undo"

    /**
     * The rows for [today] (as projected at [nowMs]), leaving out [shownTaskId] (the task on the now card); null when
     * no other task is open today. [cal] reads local times.
     */
    fun view(today: Today, shownTaskId: String?, cal: LocalCalendar): WatchTopThree? {
        val carriedIds = today.fromYesterday.map { it.task.id }.toSet()
        val ordered = (today.fromYesterday.map { it.task } + listOfNotNull(today.upNext) + today.yourDay)
            .distinctBy { it.id }
        val left = ordered.filter { it.id != shownTaskId }
        if (left.isEmpty()) return null
        val carriedLine = today.fromYesterday.associate { it.task.id to it.line }
        val rows = left.take(MAX).map { t ->
            WatchTopRow(
                taskId = t.id,
                title = TaskTitleRules.display(t.title),
                line = carriedLine[t.id] ?: line(t, cal),
                carried = t.id in carriedIds,
            )
        }
        val more = left.size - rows.size
        val done = today.doneToday.size
        val toGo = ordered.size
        val count = if (done > 0) "$done done · $toGo to go" else "$toGo to go"
        val spokenRows = rows.joinToString(". ") { it.title }
        return WatchTopThree(
            label = LABEL,
            countLine = count,
            rows = rows,
            moreLine = if (more > 0) "+$more more on your phone" else null,
            spoken = "Today, $toGo to go. Next: $spokenRows." + if (more > 0) " And $more more on your phone." else "",
        )
    }

    /** "At 14:00 · 30m" for a planned task, "30m" for an estimated anytime one, else null. */
    fun line(t: Task, cal: LocalCalendar): String? {
        val time = t.scheduledAtMs?.let { "At ${LocalClock.formatMinute(cal.minuteOfDay(it))}" }
        val size = t.estimateMinutes?.takeIf { it > 0 }?.let { TaskEstimateRules.label(it) }
        return listOfNotNull(time, size).joinToString(" · ").ifEmpty { null }
    }

    /** What the row says for a moment after its tick: "Done · Buy milk". */
    fun doneLine(title: String): String = "$DONE_LINE · ${TaskTitleRules.display(title)}"
}
