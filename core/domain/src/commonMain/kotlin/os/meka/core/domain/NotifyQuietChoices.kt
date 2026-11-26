package os.meka.core.domain

/**
 * Settings → Notifications without open switches (walk-through week 5, A17). Quiet hours and the two digests were
 * switch rows with an On/Off pill; each is now one card row saying what's in force ("Quiet hours" · "22:00–07:00" ›,
 * "Midday · 12:30" · "Off" ›) opening Notifications' choice sheet of On · Off, the one in force lit, each saying what it
 * does; a choice writes what the switch wrote and closes the sheet. Quiet hours' From and Until steppers stay under
 * the row while it's on. Reuses [NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]. Pure; nothing new stored.
 */
object NotifyQuietChoiceRules {
    const val QUIET_TITLE = "Quiet hours"
    const val QUIET_NOTE = "Syncs with your other devices. With it on, set From and Until under the row."
    const val QUIET_OFF_LINE = "Notifications come at any hour, as they happen"
    const val DIGEST_SHEET_NOTE = "Syncs with your other devices. Critical things never wait for a digest."

    /** Quiet hours' row: "Quiet hours" · "22:00–07:00" while on ([summary] is the times), else "Off". */
    fun quietRow(enabled: Boolean, summary: String): NotifyChoiceRow =
        row(QUIET_TITLE, if (enabled) summary else CallChoiceRules.OFF_LABEL)

    /** What quiet hours' On does, true to the governor (the same rule as [NotifyRules.quietNote]). */
    fun quietOnLine(digestsOn: Boolean): String =
        if (digestsOn) "Heads-ups wait for the next digest, decisions until they end; only critical things come through"
        else "Heads-ups and decisions wait until they end; only critical things come through"

    /** Quiet hours' sheet: On · Off, the one in force lit. */
    fun quietSheet(enabled: Boolean, digestsOn: Boolean): NotifyChoiceSheet =
        NotifyChoiceSheet(QUIET_TITLE, QUIET_NOTE, onOff(enabled, quietOnLine(digestsOn), QUIET_OFF_LINE))

    /** A digest's title: "Midday · 12:30", "Evening · 18:00". */
    fun digestTitle(minute: Int): String = "${digestName(minute)} · ${LocalClock.formatMinute(minute)}"

    /** A digest's row: "Midday · 12:30" · "On". */
    fun digestRow(minute: Int, on: Boolean): NotifyChoiceRow =
        row(digestTitle(minute), if (on) CallChoiceRules.ON_LABEL else CallChoiceRules.OFF_LABEL)

    /** A digest's sheet: On says the round-up comes at its time, Off that it doesn't; the one in force lit. */
    fun digestSheet(minute: Int, on: Boolean): NotifyChoiceSheet {
        val time = LocalClock.formatMinute(minute)
        return NotifyChoiceSheet(
            digestTitle(minute), DIGEST_SHEET_NOTE,
            onOff(on, "One round-up of what's due at $time", "No round-up at $time"),
        )
    }

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun named(on: Boolean, name: String): Boolean? = CallChoiceRules.named(on, name)

    private fun digestName(minute: Int): String = if (minute < NotificationSettings.EVENING) "Midday" else "Evening"

    private fun row(title: String, value: String) = NotifyChoiceRow(title, value, "$title, $value. Opens the choices")

    private fun onOff(on: Boolean, onLine: String, offLine: String) = listOf(
        choice(CallChoiceRules.ON, CallChoiceRules.ON_LABEL, onLine, on),
        choice(CallChoiceRules.OFF, CallChoiceRules.OFF_LABEL, offLine, !on),
    )

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
