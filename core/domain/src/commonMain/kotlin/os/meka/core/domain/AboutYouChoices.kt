package os.meka.core.domain

/**
 * Settings → About you without open switches (walk-through week 5, A17). The partner's Watch for requests and Handle
 * her requests for me were switch rows with an On/Off pill; each is now one card row ("Watch for requests" · "On" ›,
 * "Handle Morgan's requests for me" · "On" ›) opening the choice sheet of On · Off, the one in force lit, each saying
 * what it does by her name once she's named; a choice writes what the switch wrote and closes the sheet. Handle stays
 * shown only while her messages are watched. Reuses [NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]. Pure;
 * nothing new stored.
 */
object AboutYouChoiceRules {
    const val WATCH_TITLE = "Watch for requests"
    const val WATCH_NOTE = "Syncs with your other devices. Messages are read on the Fold, through its notifications."
    const val HANDLE_NOTE = "Syncs with your other devices. Each thing done for you can be undone from Needs you."

    /** Watch for requests' row: "Watch for requests" · "On". */
    fun watchRow(on: Boolean): NotifyChoiceRow = row(WATCH_TITLE, on)

    /** Its sheet: On flags what she asks for, Off treats her messages like anyone's; by her name once named. */
    fun watchSheet(partner: String?, on: Boolean): NotifyChoiceSheet {
        val p = nameOf(partner)
        val onLine = p?.let { AboutYouRules.watchLine(it, true) } ?: AboutYouRules.WATCH_ON
        val offLine = p?.let { AboutYouRules.watchLine(it, false) } ?: AboutYouRules.WATCH_OFF
        return NotifyChoiceSheet(WATCH_TITLE, WATCH_NOTE, onOff(on, said(onLine), said(offLine)))
    }

    /** Handle her requests' title, by her name once named: "Handle Morgan's requests for me". */
    fun handleTitle(partner: String?): String = nameOf(partner)?.let { AboutYouRules.handleTitle(it) } ?: HandleForMeRules.TITLE

    /** Its row: "Handle Morgan's requests for me" · "On". */
    fun handleRow(partner: String?, on: Boolean): NotifyChoiceRow = row(handleTitle(partner), on)

    /** Its sheet: On adds what she asks for at once, Off leaves her requests in Needs you; the one in force lit. */
    fun handleSheet(partner: String?, on: Boolean): NotifyChoiceSheet {
        val p = nameOf(partner)
        val onLine = p?.let { AboutYouRules.handleLine(it, true) } ?: HandleForMeRules.ON
        val offLine = p?.let { AboutYouRules.handleLine(it, false) } ?: HandleForMeRules.OFF
        return NotifyChoiceSheet(handleTitle(partner), HANDLE_NOTE, onOff(on, said(onLine), said(offLine)))
    }

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun named(on: Boolean, name: String): Boolean? = CallChoiceRules.named(on, name)

    private fun nameOf(partner: String?): String? = partner?.trim()?.takeIf { it.isNotEmpty() }

    /** "On · Navilife flags …" → "Navilife flags …": the sheet's choice already says On or Off. */
    private fun said(line: String): String = line.substringAfter(" · ").replaceFirstChar { it.uppercase() }

    private fun row(title: String, on: Boolean): NotifyChoiceRow {
        val value = if (on) CallChoiceRules.ON_LABEL else CallChoiceRules.OFF_LABEL
        return NotifyChoiceRow(title, value, "$title, $value. Opens the choices")
    }

    private fun onOff(on: Boolean, onLine: String, offLine: String) = listOf(
        choice(CallChoiceRules.ON, CallChoiceRules.ON_LABEL, onLine, on),
        choice(CallChoiceRules.OFF, CallChoiceRules.OFF_LABEL, offLine, !on),
    )

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
