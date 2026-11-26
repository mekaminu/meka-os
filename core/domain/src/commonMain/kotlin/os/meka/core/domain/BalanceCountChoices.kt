package os.meka.core.domain

/**
 * Balance's "Count as …" as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). Under the
 * weekly review's Balance each event with no clear area had its title, when, "Count as" and five open chips (Family,
 * Health, Self, Work, Don't count). Each is now one card row ("Swim club" · "Tue, Thu · 2 h" ›) that opens a sheet of
 * the five choices, each with a line on what it does to this week ("Adds 2 h · Family 6 h 30 → 8 h 30"). A choice
 * counts every event with that title, as the chip did, and closes the sheet; the line after it keeps its Undo.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]); a choice's name
 * is the chip's code ([BalanceRules.COUNT_AS]), so the apps send it back exactly as the chips did. Pure; nothing stored.
 */
object BalanceCountChoiceRules {
    const val CAPTION = "Not counted yet · tap one to say where it goes"
    const val NOTE = "Every event with this title counts this way from now on, past weeks too, on both devices. Undo puts it back."
    const val DONT_COUNT_LINE = "Left out of Balance, now and later"

    /** "Swim club" · "Tue, Thu · 2 h", spoken with what a tap does. */
    fun row(u: BalanceUnclear): NotifyChoiceRow =
        NotifyChoiceRow(u.title, u.line, "${u.title}, ${u.line}, not counted. Opens Count as")

    /** The sheet's title: "Count “Swim club” as". */
    fun title(u: BalanceUnclear): String = "Count “${u.title.take(40)}” as"

    /** What counting [u] as [area] does to the reviewed week: "Adds 2 h · Family 6 h 30 → 8 h 30". */
    fun line(u: BalanceUnclear, area: BalanceArea, rows: List<BalanceRow>): String {
        val now = rows.firstOrNull { it.area == area }?.minutes ?: 0
        val adds = if (u.minutes > 0) "Adds ${BalanceRules.hours(u.minutes)}" else "Counts from now on"
        return "$adds · ${area.label} ${BalanceRules.hours(now)} → ${BalanceRules.hours(now + u.minutes)}"
    }

    /** The five choices for [u] in [BalanceRules.COUNT_AS]'s order; none lit (the event has no area yet). */
    fun sheet(u: BalanceUnclear, rows: List<BalanceRow>): NotifyChoiceSheet = NotifyChoiceSheet(
        title(u), NOTE,
        BalanceRules.COUNT_AS.map { (code, label) ->
            val area = BalanceArea.named(code)
            val line = if (area == null) DONT_COUNT_LINE else line(u, area, rows)
            NotifyChoice(code, label, line, false, "$label, $line")
        },
    )

    /** A choice back to its code, as the chips sent it; null if it isn't one. */
    fun named(name: String): String? = name.takeIf { it in BalanceRules.countAsCodes() }
}
