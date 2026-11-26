package os.meka.core.domain

/**
 * A habit's settings as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). Tapping a habit on
 * Habits unfolded its four weeks and three captioned chip rows: Reminder (Off · Every day), Do or avoid (Do · Avoid) and
 * List (Morning · All day · Night time · his own). Each is now one card row ("Reminder" · "Every day · 07:30" ›) that
 * opens a sheet of the same choices, the chosen one lit, each saying what it does. A choice saves as the chip did and
 * closes the sheet; the reminder's time stepper stays under the rows while a reminder is on.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing
 * stored.
 */
object HabitSettingChoiceRules {
    const val REMIND_OFF = "OFF"
    const val REMIND_ON = "ON"
    const val OFF = "Off"
    const val EVERY_DAY = "Every day"
    const val REMINDER_NOTE = "A nudge on this device and your others. Change the time under the row once it's on."
    const val KIND_NOTE = "Changing it keeps the days you've ticked."
    const val LIST_NOTE = "Lists group your habits on Habits. Add your own with + New list."

    // ---- Reminder ----

    /** "Off", or "Every day · 07:30". */
    fun reminderValue(r: HabitBoardRow): String = r.remindAt?.let { "$EVERY_DAY · ${LocalClock.formatMinute(it)}" } ?: OFF

    fun reminderRow(r: HabitBoardRow): NotifyChoiceRow {
        val v = reminderValue(r)
        return NotifyChoiceRow(HabitBoardRules.REMINDER_HEAD, v, "${r.name} reminder, $v. Opens its choices")
    }

    /** The minute Every day would start at: the time set, else the list's usual. */
    private fun onAt(r: HabitBoardRow): Int = r.remindAt ?: HabitBoardRules.defaultReminder(r.list)

    fun reminderSheet(r: HabitBoardRow): NotifyChoiceSheet {
        val at = LocalClock.formatMinute(onAt(r))
        val onLine = if (r.avoid) "A nudge at $at each day to tick it if you kept off it" else "A nudge at $at each day to do it and tick it"
        val offLine = "No nudge; it stays on Habits to tick"
        return NotifyChoiceSheet(
            "${r.name} reminder", REMINDER_NOTE,
            listOf(
                NotifyChoice(REMIND_OFF, OFF, offLine, r.remindAt == null, "$OFF, $offLine"),
                NotifyChoice(REMIND_ON, EVERY_DAY, onLine, r.remindAt != null, "$EVERY_DAY, $onLine"),
            ),
        )
    }

    /**
     * What a reminder choice sets: [HabitReminderPick.minute] null for Off, else the time it starts at. Null when the
     * name isn't a choice or is already in force (nothing to save).
     */
    fun reminderNamed(r: HabitBoardRow, name: String): HabitReminderPick? = when (name) {
        REMIND_OFF -> if (r.remindAt != null) HabitReminderPick(null) else null
        REMIND_ON -> if (r.remindAt == null) HabitReminderPick(onAt(r)) else null
        else -> null
    }

    // ---- Do or avoid ----

    fun kindValue(r: HabitBoardRow): String = if (r.avoid) "Avoid" else "Do"

    fun kindRow(r: HabitBoardRow): NotifyChoiceRow {
        val v = kindValue(r)
        return NotifyChoiceRow(HabitBoardRules.KIND_HEAD, v, "${r.name}, ${HabitBoardRules.KIND_HEAD.lowercase()}: $v. Opens its choices")
    }

    fun kindSheet(r: HabitBoardRow): NotifyChoiceSheet {
        val doLine = "Something to do: tick it on the days you do it"
        val avoidLine = "Something to keep off: tick it on the days you managed it"
        return NotifyChoiceSheet(
            "${r.name}: ${HabitBoardRules.KIND_HEAD.lowercase()}", KIND_NOTE,
            listOf(
                NotifyChoice(HabitBoardRules.DO, "Do", doLine, !r.avoid, "Do, $doLine"),
                NotifyChoice(HabitBoardRules.AVOID, "Avoid", avoidLine, r.avoid, "Avoid, $avoidLine"),
            ),
        )
    }

    /** The avoid flag a choice sets; null when the name isn't a choice or is already in force. */
    fun kindNamed(r: HabitBoardRow, name: String): Boolean? = when (name) {
        HabitBoardRules.DO -> if (r.avoid) false else null
        HabitBoardRules.AVOID -> if (!r.avoid) true else null
        else -> null
    }

    // ---- List ----

    fun listRow(r: HabitBoardRow): NotifyChoiceRow =
        NotifyChoiceRow(HabitBoardRules.LIST_HEAD, r.list, "${r.name} list, ${r.list}. Opens its choices")

    /** "Before work · reminders start at 07:30"; his own list "Your list · reminders start at 09:00". */
    fun listLine(list: String): String {
        val whenLine = HabitBoardRules.whenLine(list).ifEmpty { "your list" }.replaceFirstChar { it.uppercase() }
        return "$whenLine · reminders start at ${LocalClock.formatMinute(HabitBoardRules.defaultReminder(list))}"
    }

    /** The lists in [lists]' order (the board's: Morning, All day, Night time, his own), the habit's lit. */
    fun listSheet(r: HabitBoardRow, lists: List<String>): NotifyChoiceSheet = NotifyChoiceSheet(
        "Move ${r.name} to", LIST_NOTE,
        lists.distinct().map { l ->
            val line = listLine(l)
            NotifyChoice(l, l, line, l == r.list, "$l, $line")
        },
    )

    /** The list a choice moves the habit to; null when it isn't one of [lists] or is the habit's list already. */
    fun listNamed(r: HabitBoardRow, lists: List<String>, name: String): String? =
        name.takeIf { it in lists && it != r.list }
}

/** A reminder choice: [minute] the time it starts at, null for Off. */
data class HabitReminderPick(val minute: Int?)
