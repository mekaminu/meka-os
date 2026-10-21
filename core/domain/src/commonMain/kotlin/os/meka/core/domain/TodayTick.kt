package os.meka.core.domain

/**
 * The Week board by hand, slice 6 (Meka 2026-10-21 19:02, "tick stuff off as you finish them just like Sunsama"): the
 * same tick on Today as on the Board. A ticked row keeps its place, struck through, for a moment ([BoardHandRules.SETTLE_MS],
 * drawn and all), then settles into the day's done group at the foot of Today, where it stays ticked and struck
 * through; a tap on its circle un-ticks it (Undo ticks it again). The done group is what [Today.doneToday] holds, the
 * latest first, so the one just ticked sits straight under the open ones.
 */
object TodayTickRules {
    /** Done rows shown on Today before the rest fold into "+3 more done". */
    const val MAX_SHOWN = 8

    /** How long a ticked row stays where it was after its check is drawn ([drawMs]); Motion → Off: completed at once. */
    fun settleWaitMs(drawMs: Int, reduced: Boolean): Long =
        if (reduced) 0L else (BoardHandRules.SETTLE_MS - drawMs).coerceAtLeast(0L)

    /** The done rows to show, the latest done first. */
    fun shown(doneToday: List<Task>, open: Boolean = false): List<Task> =
        if (open) doneToday else doneToday.take(MAX_SHOWN)

    /** "+3 more done" under the shown rows, or null when they're all shown. */
    fun moreLine(doneToday: List<Task>, open: Boolean = false): String? {
        val more = doneToday.size - shown(doneToday, open).size
        return if (more > 0) "+$more more done" else null
    }

    /** The group's label: "1 done today" · "3 done today"; null when nothing is done yet (no empty heading). */
    fun label(doneToday: List<Task>): String? = if (doneToday.isEmpty()) null else "${doneToday.size} done today"

    /** A done row's circle, said: what a tap does. */
    fun untickLabel(title: String): String = BoardHandRules.tickLabel(TaskTitleRules.display(title), done = true)

    /** The bar after un-ticking, with Undo (ticks it again). */
    fun untickedLine(title: String): String = "Not done “${TaskTitleRules.display(title)}”"
}
