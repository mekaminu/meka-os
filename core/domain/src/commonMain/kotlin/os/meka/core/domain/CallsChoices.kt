package os.meka.core.domain

/**
 * Obi on calls without open chip rows (walk-through week 5, A17). The page had three chip rows: the monthly cap's
 * "£10 £20 £30 £50" inside THIS MONTH, WHAT CALLERS MAY KNOW's five chips (wrapping) and RECORDINGS' "7 days" ·
 * "Don't keep". The cap and the recordings are now each one card row saying what's chosen ("Monthly cap" · "£20",
 * "Keep recordings" · "7 days") opening Notifications' choice sheet with what each choice does, the one in use lit;
 * what callers may know is a list of switch rows, one per thing, each saying what Obi may tell a caller. Pure; nothing
 * new stored (the same synced settings as before).
 */
object CallsChoiceRules {
    const val CAP_TITLE = "Monthly cap"
    const val KEEP_TITLE = "Keep recordings"

    /** "Monthly cap" · "£20". */
    fun capRow(v: CallsSettingsView): NotifyChoiceRow {
        val value = "£${v.capGbp}"
        return NotifyChoiceRow(CAP_TITLE, value, "$CAP_TITLE, $value. Opens the choices")
    }

    /** What a cap does. */
    fun capLine(gbp: Int): String = "Live conversation for up to £$gbp a month"

    /** The caps offered ([LiveCallRules.CAP_CHOICES]), the one in use lit; the note is the page's cap note. */
    fun capSheet(v: CallsSettingsView): NotifyChoiceSheet = NotifyChoiceSheet(
        CAP_TITLE, CallsSettingsRules.CAP_NOTE,
        v.caps.mapNotNull { c -> c.key.toIntOrNull()?.let { choice(c.key, c.label, capLine(it), c.on) } },
    )

    /** A cap choice back to pounds; null when it isn't one offered. */
    fun capNamed(name: String): Int? = name.toIntOrNull()?.takeIf { it in LiveCallRules.CAP_CHOICES }

    /** "Keep recordings" · "7 days" / "Don't keep". */
    fun keepRow(v: CallsSettingsView): NotifyChoiceRow {
        val value = v.keeps.firstOrNull { it.on }?.label ?: CallsSettingsRules.keepLabel(VoiceRecordingRules.KEEP_CHOICES.first())
        return NotifyChoiceRow(KEEP_TITLE, value, "$KEEP_TITLE, $value. Opens the choices")
    }

    /** What a keep choice does. */
    fun keepLine(days: Int): String =
        if (days == 0) "Deleted as soon as the message is written out"
        else "Kept encrypted for $days days so you can listen back, then deleted"

    /** Don't keep, then the days offered, the one in use lit; the note is the page's recordings note. */
    fun keepSheet(v: CallsSettingsView): NotifyChoiceSheet = NotifyChoiceSheet(
        KEEP_TITLE, CallsSettingsRules.RECORDINGS_NOTE,
        v.keeps.mapNotNull { c -> c.key.toIntOrNull()?.let { choice(c.key, c.label, keepLine(it), c.on) } },
    )

    /** A keep choice back to days; null when it isn't one offered. */
    fun keepNamed(name: String): Int? = name.toIntOrNull()?.takeIf { it in VoiceRecordingRules.KEEP_CHOICES }

    /** What Obi may tell a caller, per thing, for its switch row. */
    fun knowsLine(key: String): String = when (LiveCallRules.Knows.of(key)) {
        LiveCallRules.Knows.BUSY -> "Obi may say you can't take the call right now"
        LiveCallRules.Knows.BACK -> "Obi may say roughly when you'll ring back"
        LiveCallRules.Knows.NAME -> "Obi may call you by your first name"
        LiveCallRules.Knows.WORK -> "Obi may say where you work"
        LiveCallRules.Knows.EMAIL -> "Obi may give your work email"
        null -> ""
    }

    /** "Your first name, on. Obi may call you by your first name". */
    fun knowsSpoken(c: CallsChip): String = "${c.label}, ${if (c.on) "on" else "off"}. ${knowsLine(c.key)}"

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
