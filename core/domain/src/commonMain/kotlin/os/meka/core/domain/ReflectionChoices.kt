package os.meka.core.domain

/**
 * The weekly reflection's "when it comes" as a row opening a sheet (walk-through week 5, A17: no open chip rows on a
 * page). The card's foot said "Comes on Sundays at 18:00 · through Monday" with Change, which unfolded six time chips
 * under it. It is now one card row, "Comes on Sundays" · "18:00" ›, opening a sheet of the same times ([ReviewRules.
 * timeChoices], the set one lit), each saying when the heads-up comes and where "Later tonight" would put it. A choice
 * saves as the chip did (the review card, its heads-up and the reflection, on both apps) and closes the sheet.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing
 * stored.
 */
object ReflectionChoiceRules {
    const val HEAD = "Comes on Sundays"
    const val TITLE = "When your week's review comes"
    const val NOTE = "The review card, its heads-up and the reflection open then on Sunday and stay through Monday. " +
        "The Fold and the Mac follow."
    const val NO_LATER = "no Later tonight, it's already late"

    /** "Comes on Sundays" · "18:00", spoken as one line that says it opens its choices. */
    fun row(start: Int): NotifyChoiceRow {
        val t = LocalClock.formatMinute(start)
        return NotifyChoiceRow(HEAD, t, "Weekly review and reflection come on Sundays at $t. Opens its choices")
    }

    /**
     * "Heads-up at 19:00 · Later tonight puts it to 21:00": where pressing Later tonight at the time itself brings it
     * back ([ReviewRules.laterMinute] on a Sunday), or [NO_LATER] when that wouldn't be offered.
     */
    fun line(start: Int): String {
        val later = ReviewRules.laterMinute(SUNDAY_EPOCH_DAY, start)
        val tail = later?.let { "Later tonight puts it to ${LocalClock.formatMinute(it)}" } ?: NO_LATER
        return "Heads-up at ${LocalClock.formatMinute(start)} · $tail"
    }

    /** The times offered, the set one lit (and among them even when it was typed elsewhere). */
    fun sheet(start: Int): NotifyChoiceSheet = NotifyChoiceSheet(TITLE, NOTE,
        ReviewRules.timeChoices(start).map { m ->
            val label = LocalClock.formatMinute(m)
            val line = line(m)
            val lit = m == start
            NotifyChoice(m.toString(), label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
        })

    /** The minute a choice sets; null when it isn't one offered or is already in force. */
    fun named(start: Int, name: String): Int? {
        val m = name.toIntOrNull() ?: return null
        return m.takeIf { it in ReviewRules.timeChoices(start) && it != start }
    }

    /** 1970-01-04, a Sunday. */
    private const val SUNDAY_EPOCH_DAY = 3L
}
