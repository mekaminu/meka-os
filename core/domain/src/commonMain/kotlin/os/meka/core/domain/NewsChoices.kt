package os.meka.core.domain

/**
 * News → Topics without an open switch (walk-through week 5, A17: no open chip rows on a page). Spanish sources was an
 * open "Off · On" chip row with "Off · English only" under it; it is now one card row ("Spanish sources" · "Off" ›)
 * opening the choice sheet of On · Off, the one in force lit, each saying what it does ("Off: English only, wherever
 * news shows"); a choice writes what the chips wrote and closes the sheet. Reuses [NotifyChoiceRow], [NotifyChoice],
 * [NotifyChoiceSheet]. Pure; nothing new stored.
 */
object NewsChoiceRules {
    const val SPANISH_TITLE = "Spanish sources"
    const val SPANISH_NOTE = "Synced, so the Mac has the same. English stories always show."
    const val SPANISH_ON_LINE = "Adds ${NewsRules.SPANISH_SOURCES} to News, the ticker and the brief"
    const val SPANISH_OFF_LINE = "English only, wherever news shows"

    /** The row: "Spanish sources" · "Off". */
    fun spanishRow(on: Boolean): NotifyChoiceRow {
        val value = if (on) CallChoiceRules.ON_LABEL else CallChoiceRules.OFF_LABEL
        return NotifyChoiceRow(SPANISH_TITLE, value, "$SPANISH_TITLE, $value. Opens the choices")
    }

    /** Its sheet: On names the Spanish sources it adds, Off says English only; the one in force lit. */
    fun spanishSheet(on: Boolean): NotifyChoiceSheet = NotifyChoiceSheet(SPANISH_TITLE, SPANISH_NOTE, listOf(
        choice(CallChoiceRules.ON, CallChoiceRules.ON_LABEL, SPANISH_ON_LINE, on),
        choice(CallChoiceRules.OFF, CallChoiceRules.OFF_LABEL, SPANISH_OFF_LINE, !on),
    ))

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun named(on: Boolean, name: String): Boolean? = CallChoiceRules.named(on, name)

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
