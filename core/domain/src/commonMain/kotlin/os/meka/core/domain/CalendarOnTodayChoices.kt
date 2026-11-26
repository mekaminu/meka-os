package os.meka.core.domain

/**
 * Calendars' ON TODAY without open switches (walk-through week 5, A17). Each calendar was a switch row with an On/Off
 * pill; it is now a card row (its name and line, "On" ›) opening the choice sheet "Kids on Today" of On · Off, the one
 * in force lit, each saying what it does to that calendar's events; a choice writes what the switch wrote (hide or show)
 * and closes the sheet. Rename stays beside the value. Reuses [NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet].
 * Pure; nothing new stored.
 */
object CalendarOnTodayChoiceRules {
    const val NOTE = "Syncs with your other devices. The Calendar tab and Search always show its events."
    const val ON_LINE = "Its events show on Today and in your day: Up next, the brief and Plan my day"
    const val OFF_LINE = "Its events stay in the Calendar tab only, off Today and your day"

    /** The row: "Kids" · "On", spoken "Kids on Today, On. Opens the choices". */
    fun row(c: CalendarChoice): NotifyChoiceRow {
        val value = valueOf(c.onToday)
        return NotifyChoiceRow(c.label, value, "${spokenTitle(c)}, $value. Opens the choices")
    }

    /** What the row says it is, for screen readers and tests: "Kids on Today". */
    fun spokenTitle(c: CalendarChoice): String = "${c.label} on Today"

    /** Its sheet: "Kids on Today", On shows its events in my day, Off keeps them in the Calendar tab only. */
    fun sheet(c: CalendarChoice): NotifyChoiceSheet = NotifyChoiceSheet(
        spokenTitle(c), NOTE,
        listOf(
            choice(CallChoiceRules.ON, CallChoiceRules.ON_LABEL, ON_LINE, c.onToday),
            choice(CallChoiceRules.OFF, CallChoiceRules.OFF_LABEL, OFF_LINE, !c.onToday),
        ),
    )

    /** True to show it on Today, false to hide it; null when [name] isn't a choice or is already in force. */
    fun named(onToday: Boolean, name: String): Boolean? = CallChoiceRules.named(onToday, name)

    private fun valueOf(on: Boolean) = if (on) CallChoiceRules.ON_LABEL else CallChoiceRules.OFF_LABEL

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
