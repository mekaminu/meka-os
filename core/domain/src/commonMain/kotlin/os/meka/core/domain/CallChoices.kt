package os.meka.core.domain

/**
 * Work mode → Call assistant as rows opening sheets (walk-through week 5, A17: no open pills or switches on a page). The
 * call assistant's "Turn the call assistant on / off" pill and the "Screen unknown callers all the time" switch are each
 * one card row saying what's in force ("Call assistant" · "On" ›, "Screen unknown callers" · "Off" ›) opening a sheet of
 * On · Off, the one in force lit, each saying what it does; a choice writes what the pill or switch wrote and closes the
 * sheet. The line under each row ([CallScreeningRules.statusLine], [ScreenUnknownRules.line]) stays as it was. Reuses
 * Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing stored.
 */
object CallChoiceRules {
    const val ON = "on"
    const val OFF = "off"
    const val ON_LABEL = "On"
    const val OFF_LABEL = "Off"
    const val ASSISTANT_TITLE = "Call assistant"
    const val UNKNOWN_TITLE = "Screen unknown callers"
    const val ASSISTANT_NOTE = "Synced, so the Mac has the same. The screening itself happens on the Fold."
    const val UNKNOWN_NOTE = "Emergency numbers are never screened. Synced, so the Mac has the same."
    const val ASSISTANT_ON_LINE = "At work, calls from anyone not on your lists go to the assistant, which takes a message"
    const val ASSISTANT_OFF_LINE = "Every call rings as usual; the assistant takes no messages"
    const val UNKNOWN_ON_LINE = "Numbers you don't know go to the assistant evenings and weekends too"
    const val UNKNOWN_OFF_LINE = "Outside work every call rings"
    const val UNKNOWN_NEEDS_ASSISTANT = "; needs the call assistant on"

    /** The assistant's row: "Call assistant" · "On". */
    fun assistantRow(on: Boolean): NotifyChoiceRow = row(ASSISTANT_TITLE, on)

    /** The assistant's sheet: On says what happens at work, Off that every call rings; the one in force lit. */
    fun assistantSheet(on: Boolean): NotifyChoiceSheet =
        NotifyChoiceSheet(ASSISTANT_TITLE, ASSISTANT_NOTE, onOff(on, ASSISTANT_ON_LINE, ASSISTANT_OFF_LINE))

    /** The "screen unknown callers" row: "Screen unknown callers" · "Off". */
    fun unknownRow(on: Boolean): NotifyChoiceRow = row(UNKNOWN_TITLE, on)

    /** Its sheet; On says it needs the call assistant while that is off. */
    fun unknownSheet(on: Boolean, assistantOn: Boolean): NotifyChoiceSheet = NotifyChoiceSheet(
        ScreenUnknownRules.TITLE, UNKNOWN_NOTE,
        onOff(on, UNKNOWN_ON_LINE + if (assistantOn) "" else UNKNOWN_NEEDS_ASSISTANT, UNKNOWN_OFF_LINE),
    )

    /** True for On, false for Off; null when [name] isn't a choice or is already in force ([on]). */
    fun named(on: Boolean, name: String): Boolean? = when (name) {
        ON -> true
        OFF -> false
        else -> null
    }?.takeIf { it != on }

    private fun row(title: String, on: Boolean): NotifyChoiceRow {
        val value = if (on) ON_LABEL else OFF_LABEL
        return NotifyChoiceRow(title, value, "$title, $value. Opens the choices")
    }

    private fun onOff(on: Boolean, onLine: String, offLine: String) = listOf(
        choice(ON, ON_LABEL, onLine, on),
        choice(OFF, OFF_LABEL, offLine, !on),
    )

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
