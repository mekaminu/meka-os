package os.meka.core.domain

/**
 * Balance's targets as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). Under the weekly
 * review's Balance, "Targets ›" unfolded a caption and an open row of chips per area ("Family · a week" over 4 h, 6 h,
 * 8 h, 10 h, 14 h). Each area is now one card row ("Family" · "8 h a week" ›) that opens a sheet of the same choices,
 * the chosen one lit, each with a line on what it means for the week shown ("6 h 30 counted · 1 h 30 short"). A choice
 * sets the target as the chip did and closes the sheet; the line the tap said stays under the rows.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]); a choice's name
 * is its minutes a week as text ("0" is work's "Your hours"), read back by [named]. Pure; nothing stored.
 */
object BalanceTargetChoiceRules {
    const val NOTE = "Balance measures every week against this, past weeks too, on both devices."
    const val YOUR_HOURS_LABEL = "Your hours"
    const val YOUR_HOURS_LINE = "The work hours set in Calendars, each week as it was"

    /** "Family" · "8 h a week" (work: "Your hours" or "40 h a week"), spoken with what a tap does. */
    fun row(r: BalanceRow): NotifyChoiceRow {
        val value = value(r)
        return NotifyChoiceRow(r.label, value, "${r.label} target, $value. Opens its choices")
    }

    /** The row's value: what is chosen now. */
    fun value(r: BalanceRow): String =
        if (r.area == BalanceArea.WORK && r.defaultTarget) YOUR_HOURS_LABEL else "${BalanceRules.hours(r.targetMin)} a week"

    /** The sheet's title: "Family target a week". */
    fun title(r: BalanceRow): String = "${r.label} target a week"

    /** Whether choice [c] is the one in force for [r] (as the chips lit it). */
    fun chosen(r: BalanceRow, c: Int): Boolean = when {
        c == BalanceRules.YOUR_HOURS -> r.defaultTarget
        r.area == BalanceArea.WORK -> !r.defaultTarget && c == r.targetMin
        else -> c == r.targetMin
    }

    /** What [c] means for the week shown: "6 h 30 counted · met", "· 1 h 30 short"; work "36 h counted · 4 h over". */
    fun line(r: BalanceRow, c: Int): String {
        if (c == BalanceRules.YOUR_HOURS) return YOUR_HOURS_LINE
        val n = r.minutes
        val counted = "${BalanceRules.hours(n)} counted"
        val said = when {
            r.area == BalanceArea.WORK && n > c -> "${BalanceRules.hours(n - c)} over"
            r.area == BalanceArea.WORK -> "within it"
            n >= c -> "met"
            else -> "${BalanceRules.hours(c - n)} short"
        }
        val usual = if (c == r.area.defaultMin) " · the usual" else ""
        return "$counted · $said$usual"
    }

    /** The area's choices in order, the chosen one lit. */
    fun sheet(r: BalanceRow): NotifyChoiceSheet = NotifyChoiceSheet(
        title(r), NOTE,
        r.choices.map { c ->
            val label = if (c == BalanceRules.YOUR_HOURS) YOUR_HOURS_LABEL else BalanceRules.hours(c)
            val line = line(r, c)
            NotifyChoice(c.toString(), label, line, chosen(r, c), "$label, $line")
        },
    )

    /** A choice's name back to its minutes for [r]; null if it isn't one of [r]'s choices or is already in force. */
    fun named(r: BalanceRow, name: String): Int? =
        name.toIntOrNull()?.takeIf { it in r.choices && !chosen(r, it) }
}
