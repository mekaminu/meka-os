package os.meka.core.domain

/**
 * Settings → Speaking up's switches as rows opening sheets (walk-through week 5, A17: no open buttons or chip rows on a
 * page). Each moment's Turn on / Turn off pill and How often's pill (which named the other choice) are now one card row
 * under the label saying what's chosen ("Your train" · "On" ›, "How often" · "Up to 6 a day" ›), opening Notifications'
 * choice sheet with the chosen one lit. A choice saves as the pill did and closes the sheet; the status line and the
 * steps under the row stay. Mute for 2 hours / Speak again now is an action for the next two hours, not a standing
 * choice, and stays a button.
 *
 * [named] gives what a choice sets, or null when the name isn't one of that sheet's choices or is already in force.
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure.
 */
object SpeakUpChoiceRules {
    const val ON = "on"
    const val OFF = "off"
    const val ON_LABEL = "On"
    const val OFF_LABEL = "Off"
    const val ON_LINE = "Obi says it out loud, where only you can hear"
    const val OFF_LINE = "It stays a silent notification"
    const val SYNC_NOTE = "Syncs with your other devices."

    private val SWITCH_WORDS = setOf(SpeakUpRules.TURN_ON, SpeakUpRules.TURN_OFF)

    /** The moment a section is for, or null (How often, Mute). */
    fun moment(s: TalkSetupSection): SpeakMoment? =
        SpeakMoment.entries.firstOrNull { it.label == s.label }?.takeIf { s.action in SWITCH_WORDS }

    /** Whether [s] is a choice shown as a row (a moment's switch or How often), not a button. */
    fun isChoice(s: TalkSetupSection): Boolean = moment(s) != null || s.label == SpeakUpRules.OFTEN_LABEL

    /** The row for a choice section ("Your train" · "On"), or null for Mute. */
    fun row(s: TalkSetupSection): NotifyChoiceRow? {
        if (!isChoice(s)) return null
        return NotifyChoiceRow(s.label, s.status, "${s.label}, ${s.status}. Opens the choices")
    }

    /** The sheet a choice row opens: On · Off for a moment, the two speeds for How often; null for Mute. */
    fun sheet(s: TalkSetupSection): NotifyChoiceSheet? {
        moment(s)?.let { m ->
            val on = s.action == SpeakUpRules.TURN_OFF
            val note = listOfNotNull(m.line + ".",
                ObiInterruptRules.BREAKS_THROUGH_LINE.takeIf { ObiInterruptRules.breaksThrough(m) }?.let { dotted(it) },
                SYNC_NOTE).joinToString(" ")
            return NotifyChoiceSheet(m.label, note, listOf(
                choice(ON, ON_LABEL, ON_LINE, on),
                choice(OFF, OFF_LABEL, OFF_LINE, !on),
            ))
        }
        if (s.label != SpeakUpRules.OFTEN_LABEL) return null
        val now = SpeakOften.entries.firstOrNull { it.label == s.status } ?: SpeakOften.MORE
        return NotifyChoiceSheet(SpeakUpRules.OFTEN_LABEL, "The most moments Obi says out loud in a day. $SYNC_NOTE",
            SpeakOften.entries.map { o -> choice(o.name, o.label, oftenLine(o), o == now) })
    }

    /** For a moment: true for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun namedOn(s: TalkSetupSection, name: String): Boolean? {
        if (moment(s) == null) return null
        val on = s.action == SpeakUpRules.TURN_OFF
        return when (name) { ON -> true; OFF -> false; else -> null }?.takeIf { it != on }
    }

    /** For How often: the speed [name] picks; null when it isn't one or is already in force. */
    fun namedOften(s: TalkSetupSection, name: String): SpeakOften? {
        if (s.label != SpeakUpRules.OFTEN_LABEL) return null
        return SpeakOften.entries.firstOrNull { it.name == name }?.takeIf { it.label != s.status }
    }

    fun oftenLine(o: SpeakOften): String = when (o) {
        SpeakOften.MORE -> "Speak more: at most ${o.perDay} moments a day"
        SpeakOften.LESS -> "Speak less: at most ${o.perDay} moments a day"
    }

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    private fun dotted(line: String): String = if (line.endsWith(".")) line else "$line."
}
