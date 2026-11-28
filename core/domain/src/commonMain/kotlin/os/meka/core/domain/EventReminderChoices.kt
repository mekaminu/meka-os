package os.meka.core.domain

/**
 * An event's Remind me and Leave by as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). The
 * event's detail unfolded two open chip rows, "REMIND ME" 5 min · 10 min · 15 min · 30 min and "LEAVE BY · HOW LONG TO
 * GET THERE" 10 min away · … · 1 h away, with a lit chip tapped again to turn it off. Each is now one card row,
 * "Remind me" · "10 min before" › and "Leave by" · "30 min away" ›, opening a sheet of the same choices
 * ([ReminderRules.remindChoices] / [ReminderRules.travelChoices], one set but no longer offered still among them) with
 * Off first, the one in force lit, each saying when its heads-up comes ("Heads-up at 13:50") or when to leave ("Leave
 * at 13:30"). A choice saves as the chip did and closes the sheet.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing
 * stored. [start] is the event's start as a minute of the day.
 */
object EventReminderChoiceRules {
    const val REMIND_HEAD = "Remind me"
    const val REMIND_TITLE = "When to remind you"
    const val REMIND_NOTE = "A heads-up on your phone and the Mac, held back in quiet hours. It stays in Navilife; " +
        "your calendar doesn't change."
    const val REMIND_OFF_LINE = "No heads-up for this event"

    const val LEAVE_HEAD = "Leave by"
    const val LEAVE_TITLE = "How long to get there"
    const val LEAVE_NOTE = "Navilife says when it's time to go, held back in quiet hours. It stays in Navilife; " +
        "your calendar doesn't change."
    const val LEAVE_OFF_LINE = "Nothing says when to leave"

    const val OFF = "Off"

    /** "Remind me" · "10 min before" (or "Off"), spoken as one line that says it opens its choices. */
    fun remindRow(current: Int): NotifyChoiceRow {
        val value = if (current == 0) OFF else ReminderRules.choiceLabel(current)
        return NotifyChoiceRow(REMIND_HEAD, value, "$REMIND_HEAD, $value. Opens its choices")
    }

    /** "Heads-up at 13:50" for [minutes] before [start]; [REMIND_OFF_LINE] for Off. */
    fun remindLine(start: Int, minutes: Int): String =
        if (minutes == 0) REMIND_OFF_LINE else "Heads-up at ${clock(start - minutes)}"

    /** Off, then the reminders still ahead (and the one set, even when no longer offered), the one in force lit. */
    fun remindSheet(start: Int, choices: List<Int>, current: Int): NotifyChoiceSheet =
        NotifyChoiceSheet(REMIND_TITLE, REMIND_NOTE, (listOf(0) + offered(choices, current)).map { m ->
            choice(m, if (m == 0) OFF else ReminderRules.choiceLabel(m), remindLine(start, m), m == current)
        })

    /** "Leave by" · "30 min away" (or "Off"). */
    fun leaveRow(current: Int): NotifyChoiceRow {
        val value = if (current == 0) OFF else ReminderRules.travelLabel(current)
        return NotifyChoiceRow(LEAVE_HEAD, value, "$LEAVE_HEAD, $value. Opens its choices")
    }

    /** "Leave at 13:30" for [minutes] to get there by [start] ("· rings as an alarm" when it does); Off's line for 0. */
    fun leaveLine(start: Int, minutes: Int, alarm: Boolean = false): String =
        if (minutes == 0) LEAVE_OFF_LINE else "Leave at ${clock(start - minutes)}" + if (alarm) " · rings as an alarm" else ""

    /** Off, then the travel times still ahead (and the one set), the one in force lit; [alarm] said on the set one. */
    fun leaveSheet(start: Int, choices: List<Int>, current: Int, alarm: Boolean = false): NotifyChoiceSheet =
        NotifyChoiceSheet(LEAVE_TITLE, LEAVE_NOTE, (listOf(0) + offered(choices, current)).map { m ->
            choice(m, if (m == 0) OFF else ReminderRules.travelLabel(m), leaveLine(start, m, alarm && m == current), m == current)
        })

    /** The minutes a choice sets (0 for Off); null when it isn't offered or is already in force. */
    fun named(choices: List<Int>, current: Int, name: String): Int? {
        val m = name.toIntOrNull() ?: return null
        return m.takeIf { (it == 0 || it in offered(choices, current)) && it != current }
    }

    private fun offered(choices: List<Int>, current: Int): List<Int> =
        if (current != 0 && current !in choices) (choices + current).sorted() else choices

    private fun choice(m: Int, label: String, line: String, lit: Boolean) =
        NotifyChoice(m.toString(), label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    private fun clock(minute: Int): String = LocalClock.formatMinute(((minute % DAY) + DAY) % DAY)

    private const val DAY = 24 * 60
}
