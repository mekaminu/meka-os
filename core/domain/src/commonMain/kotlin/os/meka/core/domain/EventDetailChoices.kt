package os.meka.core.domain

/**
 * An event's last open choices as rows opening sheets (walk-through week 5, A17: no open chip rows or switches on a
 * page). On a club session the detail unfolded "WHOSE IS IT? · EVERY SESSION" Alex · Sam, a lit name tapped again to
 * clear it; under Leave by, "Ring as an alarm" was an On/Off pill. Each is now one card row, "Whose is it?" · "Alex" ›
 * ("Not set" while nobody is) and "Ring as an alarm" · "Off" ›, opening the shared choice sheet: Whose is it? lists
 * Not set then the children, each saying what the series' sessions will read ("Every Wed 16:55 reads Alex"); Ring as an
 * alarm is On · Off, each saying what happens when it's time to go. A choice saves as the chip or pill did and closes.
 *
 * Reuses [NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]. Pure; nothing new stored.
 */
object EventDetailChoiceRules {
    const val WHOSE_HEAD = "Whose is it?"
    const val WHOSE_NOTE = "Set once for every session in the series. The Calendar and Today name them from then on; " +
        "your calendar doesn't change."
    const val NOT_SET = "Not set"
    /** [NotifyChoice.name] of Not set: no child's name can be empty. */
    const val NOT_SET_NAME = ""

    const val ALARM_NOTE = "Only this event's Leave by. Your other heads-ups stay as they are."
    const val ALARM_ON_LINE = "Rings full screen when it's time to go · Snooze or slide to dismiss"
    const val ALARM_OFF_LINE = "A heads-up when it's time to go"

    /** "Whose is it?" · "Alex" (or "Not set"). */
    fun whoseRow(current: String?): NotifyChoiceRow {
        val value = current ?: NOT_SET
        return NotifyChoiceRow(WHOSE_HEAD, value, "$WHOSE_HEAD, $value. Opens its choices")
    }

    /**
     * Not set, then [names] in age order, the one in force lit; [every] is the series' "Wed 16:55". Not set's line is
     * what the agenda asks meanwhile ("Asks Alex or Sam?").
     */
    fun whoseSheet(names: List<String>, current: String?, every: String?): NotifyChoiceSheet {
        val sessions = every?.let { "Every $it" } ?: "Every session"
        val unset = choice(NOT_SET_NAME, NOT_SET, "$sessions asks ${names.joinToString(" or ")}?", current == null)
        return NotifyChoiceSheet(WHOSE_HEAD, WHOSE_NOTE, listOf(unset) + names.map { n ->
            choice(n, n, "$sessions reads $n", n == current)
        })
    }

    /** The child a choice sets (null for Not set), wrapped so "nothing to do" differs; null when it's in force or unknown. */
    fun whoseNamed(names: List<String>, current: String?, name: String): Pick? {
        val picked = if (name == NOT_SET_NAME) null else name.takeIf { it in names } ?: return null
        return if (picked == current) null else Pick(picked)
    }

    /** What a Whose is it? choice sets: [child] null clears it. */
    data class Pick(val child: String?)

    /** "Ring as an alarm" · "On" / "Off". */
    fun alarmRow(on: Boolean): NotifyChoiceRow {
        val value = if (on) CallChoiceRules.ON_LABEL else CallChoiceRules.OFF_LABEL
        return NotifyChoiceRow(LeaveAlarmRules.SWITCH_LABEL, value, "${LeaveAlarmRules.SWITCH_LABEL}, $value. Opens its choices")
    }

    /** On · Off, the one in force lit, each saying what happens when it's time to go. */
    fun alarmSheet(on: Boolean): NotifyChoiceSheet = NotifyChoiceSheet(LeaveAlarmRules.SWITCH_LABEL, ALARM_NOTE, listOf(
        choice(CallChoiceRules.ON, CallChoiceRules.ON_LABEL, ALARM_ON_LINE, on),
        choice(CallChoiceRules.OFF, CallChoiceRules.OFF_LABEL, ALARM_OFF_LINE, !on),
    ))

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun alarmNamed(on: Boolean, name: String): Boolean? = CallChoiceRules.named(on, name)

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
