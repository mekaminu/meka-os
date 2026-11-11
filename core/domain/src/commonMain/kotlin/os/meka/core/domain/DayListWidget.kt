package os.meka.core.domain

/** One open task on the Day list widget: a tick, its own time, the title and its length. */
data class WidgetDayListRow(
    val id: String,
    val title: String,
    /** "09:00" when the task has its own time; null otherwise (the widget shows no projected starts). */
    val time: String?,
    /** "30m": his estimate or Navilife's guess; null when neither. */
    val length: String?,
    /** Screen readers: "Book MOT, 09:00, 30m long". */
    val spoken: String,
)

/** The Day list home-screen widget (the Day list, slice 6): today's open tasks with an add button. */
data class WidgetDayList(
    /** "Today" */
    val title: String,
    /** "1 of 4 done · ends about 11:15" · "All 4 done" · "Nothing planned yet" */
    val line: String,
    /** 0..100: the done share of the day's planned minutes (the gold done bar). */
    val progressPercent: Int,
    /** The first open tasks, in the Day list's order, at most [DayListWidgetRules.ROWS]. */
    val rows: List<WidgetDayListRow>,
    /** "+2 more" when more open tasks wait than fit; null otherwise. */
    val more: String?,
    /** No tasks at all today: the widget shows [DayListWidgetRules.EMPTY] over its add button. */
    val empty: Boolean,
)

/**
 * The Day list's home-screen widget (the approved `docs/design/day-list/Today.dc.html`: "Also: hold the app icon → Add
 * tasks · the home-screen widget"). Non-AI and pure: today's Day list as the app draws it, cut to what fits a widget.
 * Only open tasks are listed (a tick on the widget marks one done); the add button opens today's Day list ready to type.
 */
object DayListWidgetRules {
    const val ROWS = 4
    const val TITLE = "Today"
    const val ADD = "+ Add tasks"
    const val EMPTY = "Nothing planned yet"
    const val EMPTY_HINT = "Tap + Add tasks and type one after another"
    const val TICK = "Mark done"
    /** The Mac's desktop widget is clicked, not tapped (the Day list, slice 6b). */
    const val DESK_EMPTY_HINT = "Click + Add tasks and type one after another"
    /** The Mac's desktop widget looks again this often besides when Navilife writes (and so after midnight). */
    const val DESK_REFRESH_MS = 30 * 60_000L

    /** The Mac's empty line: the Fold's hint with "Click" for "Tap". */
    fun emptyHint(desk: Boolean): String = if (desk) DESK_EMPTY_HINT else EMPTY_HINT

    fun view(list: DayListView?, rows: Int = ROWS): WidgetDayList {
        val all = list?.rows.orEmpty()
        val open = all.filter { !it.done }
        val shown = open.take(rows.coerceAtLeast(0))
        val line = when {
            all.isEmpty() -> EMPTY
            open.isEmpty() -> if (all.size == 1) "Done" else "All ${all.size} done"
            else -> listOfNotNull(list?.doneLine, list?.endsLine).joinToString(" · ")
        }
        return WidgetDayList(
            title = TITLE,
            line = line,
            progressPercent = ((list?.progress ?: 0f) * 100).toInt().coerceIn(0, 100),
            rows = shown.map { r ->
                val time = r.time.takeIf { r.timeIsSet }
                WidgetDayListRow(
                    id = r.id, title = r.title, time = time, length = r.length,
                    spoken = listOfNotNull(r.title, time, r.length?.let { "$it long" }).joinToString(", "),
                )
            },
            more = (open.size - shown.size).takeIf { it > 0 }?.let { "+$it more" },
            empty = all.isEmpty(),
        )
    }

    /** What the whole widget says to a screen reader: "Today. 1 of 4 done · ends about 11:15. Book MOT, 09:00 …". */
    fun describe(w: WidgetDayList): String =
        (listOf(w.title, w.line) + w.rows.map { it.spoken } + listOfNotNull(w.more)).joinToString(". ")

    /** A row's tick for a screen reader: "Mark done: Book MOT". */
    fun tickLabel(r: WidgetDayListRow): String = "$TICK: ${r.title}"
}
