package os.meka.core.domain

/**
 * Shut down's "Time to get ready" as a row opening a sheet (walk-through week 5, A17: no open chip rows on a page; the
 * open row "30 min · 45 min · 1 h · 1 h 15 · …" ran off the cover's right edge). The row is "Time to get ready" ·
 * "1 h" ›; the sheet lists each buffer, the one in use lit, each saying the wake time it would suggest for tomorrow's
 * first commitment ("Suggests 06:40 · for Work at 09:00"). A buffer moves the suggestion only, never an alarm already
 * set, and the sheet's note says so. Reuses Notifications' row and sheet shapes. Pure; nothing stored.
 */
object WakeBufferChoiceRules {
    const val TITLE = "Time to get ready"
    const val NOTE = "Moves the suggested time, not an alarm you've set. Synced, so the Mac has the same."

    /** "Time to get ready" · "1 h". */
    fun row(v: WakeView): NotifyChoiceRow {
        val value = AlarmRules.duration(v.bufferMin)
        return NotifyChoiceRow(TITLE, value, "$TITLE, $value. Opens the choices")
    }

    /** Each buffer on offer, what it would suggest, the one in use lit. */
    fun sheet(v: WakeView): NotifyChoiceSheet = NotifyChoiceSheet(TITLE, NOTE, v.bufferChoices.map { b ->
        val label = AlarmRules.duration(b)
        val line = line(v, b)
        val lit = b == v.bufferMin
        NotifyChoice(b.toString(), label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
    })

    /**
     * "Suggests 06:40 · for Work at 09:00" with something early tomorrow (and "· your alarm stays at 06:45" when one is
     * set at another time); with nothing early, how long before the first thing it would wake him.
     */
    fun line(v: WakeView, bufferMin: Int): String {
        val c = v.suggestion?.commitment
            ?: return "${AlarmRules.duration(bufferMin)} before the first thing, on a day that starts early"
        val at = AlarmRules.suggest(c, bufferMin)?.minute ?: return c.line
        val base = "Suggests ${LocalClock.formatMinute(at)} · for ${c.line}"
        val set = v.setMinute
        return if (set != null && set != at) "$base · your alarm stays at ${LocalClock.formatMinute(set)}" else base
    }

    /** The buffer a choice picks, or null when it isn't on offer or is the one in use. */
    fun named(v: WakeView, name: String): Int? =
        name.toIntOrNull()?.takeIf { it in v.bufferChoices && it != v.bufferMin }
}
