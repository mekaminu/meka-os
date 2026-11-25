package os.meka.core.domain

/**
 * Protected time's choices as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). The page had
 * three chip rows: "Off 18:00 18:30 19:00 19:30 20:00", "Open · Protected" and "Waits till morning · Shows as it comes".
 * Each is now one card row saying what's chosen ("Evenings from" · "19:00") opening a sheet of the choices, each with a
 * line on what it does and the chosen one lit. They reuse Notifications' row and sheet shapes ([NotifyChoiceRow],
 * [NotifyChoice], [NotifyChoiceSheet]) so both pages draw the same thing. Pure; nothing new stored.
 */
/** An evening choice read back: [on] for evenings protected, from [fromMin] (local minutes). */
data class EveningPick(val on: Boolean, val fromMin: Int)

object ProtectedChoiceRules {
    /** The evening sheet's "Off" choice; the times are their local minute ("1140"). */
    const val OFF = "OFF"
    const val OFF_LABEL = "Off"
    const val OPEN = "OPEN"
    const val OPEN_LABEL = "Open"
    const val PROTECTED = "PROTECTED"
    const val PROTECTED_LABEL = "Protected"
    const val HOLD = "HOLD"
    const val SHOW = "SHOW"

    const val SETTING_NOTE = "Syncs with your other devices. Anything you plan yourself is left as it is."
    const val MAIL_NOTE = "Syncs with your other devices. Security alerts and replies you've started always show."

    private fun row(title: String, value: String) = NotifyChoiceRow(title, value, "$title, $value. Opens the choices")
    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    /** "Evenings from" · "19:00", or · "Off". */
    fun eveningsRow(v: ProtectedView): NotifyChoiceRow =
        row(ProtectedRules.EVENINGS_FROM_LABEL, if (v.evenings) ProtectedRules.fromChip(v.fromMin) else OFF_LABEL)

    /** What an evening choice does. */
    fun eveningLine(minute: Int?): String =
        if (minute == null) "The planner may use your evenings"
        else "Nothing planned from ${LocalClock.formatMinute(minute)}, or from when a later shift ends"

    /** Off, then 18:00 … 20:00, the one in use lit. */
    fun eveningsSheet(v: ProtectedView): NotifyChoiceSheet = NotifyChoiceSheet(
        ProtectedRules.EVENINGS_FROM_LABEL, SETTING_NOTE,
        listOf(choice(OFF, OFF_LABEL, eveningLine(null), !v.evenings)) +
            ProtectedRules.FROM_CHOICES.map { m ->
                choice(m.toString(), ProtectedRules.fromChip(m), eveningLine(m), v.evenings && v.fromMin == m)
            },
    )

    /** An evening choice back to the setting: evenings on or off, and from when ([current] kept for Off); null if unknown. */
    fun eveningNamed(name: String, current: Int): EveningPick? {
        if (name == OFF) return EveningPick(false, current)
        val m = name.toIntOrNull()?.takeIf { it in ProtectedRules.FROM_CHOICES } ?: return null
        return EveningPick(true, m)
    }

    /** "Weekends and bank holidays" · "Protected" / "Open". */
    fun weekendsRow(v: ProtectedView): NotifyChoiceRow =
        row(ProtectedRules.WEEKENDS_LABEL, if (v.weekends) PROTECTED_LABEL else OPEN_LABEL)

    fun weekendsSheet(v: ProtectedView): NotifyChoiceSheet = NotifyChoiceSheet(
        ProtectedRules.WEEKENDS_LABEL, SETTING_NOTE,
        listOf(
            choice(OPEN, OPEN_LABEL, "The planner may use Saturdays, Sundays and bank holidays", !v.weekends),
            choice(PROTECTED, PROTECTED_LABEL, "Kept free all day, unless Work mode has a shift that day", v.weekends),
        ),
    )

    /** A weekend choice back to protected or not; null if unknown. */
    fun weekendNamed(name: String): Boolean? = when (name) {
        OPEN -> false
        PROTECTED -> true
        else -> null
    }

    /** "Work mail" · "Waits till morning" / "Shows as it comes". */
    fun mailRow(v: ProtectedView): NotifyChoiceRow =
        row(ProtectedMailRules.LABEL, if (v.holdMail) ProtectedMailRules.ON else ProtectedMailRules.OFF)

    fun mailSheet(v: ProtectedView): NotifyChoiceSheet = NotifyChoiceSheet(
        ProtectedMailRules.LABEL, MAIL_NOTE,
        listOf(
            choice(HOLD, ProtectedMailRules.ON, "Business emails wait and come back at 07:00", v.holdMail),
            choice(SHOW, ProtectedMailRules.OFF, "Business emails show as they arrive", !v.holdMail),
        ),
    )

    /** A mail choice back to holding or not; null if unknown. */
    fun mailNamed(name: String): Boolean? = when (name) {
        HOLD -> true
        SHOW -> false
        else -> null
    }
}
