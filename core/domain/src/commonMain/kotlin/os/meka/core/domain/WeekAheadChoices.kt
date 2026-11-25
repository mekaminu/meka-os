package os.meka.core.domain

/**
 * The week ahead's Sunday heads-up as a row opening a sheet (walk-through week 5, A17: no open chip rows on a page). The
 * page had an "On" pill over a chip row "16:00 17:00 18:00 19:00 20:00" that wrapped 20:00 alone onto a second line on
 * the cover. It is now one card row ("Sunday heads-up" · "18:00", or · "Off") opening Notifications' choice sheet: Off,
 * then the times, each with a line on what it does, the one in use lit. Pure; nothing new stored.
 */
object WeekAheadChoiceRules {
    const val TITLE = "Sunday heads-up"
    const val OFF = "OFF"
    const val OFF_LABEL = "Off"
    const val NOTE = "Syncs with your other devices. The week ahead is always here in Ask, whatever you choose."

    /** "Sunday heads-up" · "18:00", or · "Off". */
    fun row(s: WeekAheadSettingView): NotifyChoiceRow {
        val value = if (s.on) s.timeLabel else OFF_LABEL
        return NotifyChoiceRow(TITLE, value, "$TITLE, $value. Opens the choices")
    }

    /** What a choice does: null is Off. */
    fun line(minute: Int?): String =
        if (minute == null) "No heads-up on Sundays"
        else "Your coming week on Sundays at ${LocalClock.formatMinute(minute)}, with Listen to hear it"

    /** Off, then the times offered (the set one among them), the one in use lit. */
    fun sheet(s: WeekAheadSettingView): NotifyChoiceSheet = NotifyChoiceSheet(
        TITLE, NOTE,
        listOf(choice(OFF, OFF_LABEL, line(null), !s.on)) +
            s.choices.map { m -> choice(m.toString(), LocalClock.formatMinute(m), line(m), s.on && s.minute == m) },
    )

    /** A choice back to the setting: Off keeps the time ([current]); a time turns it on; null if unknown or not a quarter hour from 06:00 to 22:00. */
    fun named(name: String, current: Int): WeekAheadSetting? {
        if (name == OFF) return WeekAheadSetting(false, current)
        val m = name.toIntOrNull()?.takeIf { WeekAheadRules.validMinute(it) } ?: return null
        return WeekAheadSetting(true, m)
    }

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
