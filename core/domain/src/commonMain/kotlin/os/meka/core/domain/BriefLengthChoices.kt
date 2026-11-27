package os.meka.core.domain

/**
 * The morning brief's length as a row opening a sheet (walk-through week 5, A17: no open chip rows on a page; the brief
 * had an open "Short · Standard · Full" row under Listen). The row is "Length" · "Standard" ›; the sheet lists the
 * three, the one in use lit, each saying about how long it runs and what it names, from [BriefLength]'s own limits
 * ("About 90 seconds · your first 3 events, 3 tasks, 3 headlines and your week's goals"). Reuses Notifications' row and
 * sheet shapes. Pure; nothing stored.
 */
object BriefLengthChoiceRules {
    const val TITLE = "Length"
    const val SHEET_TITLE = "How long the brief is"
    const val NOTE = "How much the spoken brief says. Synced, so the Mac has the same."

    /** "Length" · "Standard". */
    fun row(lengths: List<BriefLengthChoice>): NotifyChoiceRow {
        val value = (lengths.firstOrNull { it.chosen } ?: lengths.firstOrNull { it.id == BriefLength.DEFAULT.id })?.label
            ?: BriefLength.DEFAULT.label
        return NotifyChoiceRow(TITLE, value, "Brief length, $value. Opens the choices")
    }

    /** Each length, what it says, the one in use lit. */
    fun sheet(lengths: List<BriefLengthChoice>): NotifyChoiceSheet = NotifyChoiceSheet(SHEET_TITLE, NOTE, lengths.map { c ->
        val line = line(BriefLength.of(c.id))
        NotifyChoice(c.id, c.label, line, c.chosen, "${c.label}, $line" + if (c.chosen) ", chosen" else "")
    })

    /** "About 45 seconds · your first event, 1 task, 1 headline". */
    fun line(l: BriefLength): String {
        val time = when (l) {
            BriefLength.SHORT -> "About 45 seconds"
            BriefLength.STANDARD -> "About 90 seconds"
            BriefLength.FULL -> "About 3 minutes"
        }
        val events = 1 + l.moreEvents
        val parts = listOf(
            if (events == 1) "your first event" else "your first $events events",
            count(l.maxFocus, "task"),
            count(l.maxHeadlines, "headline"),
        )
        val said = parts.dropLast(1).joinToString(", ") + (if (l.extras) ", " else " and ") + parts.last() +
            if (l.extras) " and your week's goals" else ""
        return "$time · $said"
    }

    /** The length a choice picks, or null when it isn't one or is the one in use. */
    fun named(lengths: List<BriefLengthChoice>, name: String): String? =
        lengths.firstOrNull { it.id == name && !it.chosen }?.id

    private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"
}
