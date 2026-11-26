package os.meka.core.domain

/**
 * Settings → Family → School emails' four switches as rows opening sheets (walk-through week 5, A17: no open buttons or
 * pills on a page). Each switch ([SchoolLeadRules.view]'s rows) is one card row saying what's chosen ("Task on the day"
 * · "On" ›) opening a sheet of On and Off, the chosen one lit, each saying what it makes for the next school events; a
 * choice saves as the pill did ([SchoolLeadRules.fields]) and closes the sheet.
 *
 * A reminder whose task is off, or every row while school events are off in What Navilife may do, can't be chosen: its
 * row is dimmed with no sheet, and the line under it says why ([reason]). Reuses Notifications' row and sheet shapes
 * ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing stored.
 */
object SchoolLeadChoiceRules {
    const val ON = "on"
    const val OFF = "off"
    const val ON_LABEL = "On"
    const val OFF_LABEL = "Off"

    /** The row for one switch ("Task on the day" · "On"); its value is Off while it can't be chosen. */
    fun row(r: SchoolLeadRow): NotifyChoiceRow {
        val value = if (r.on) ON_LABEL else OFF_LABEL
        val spoken = "${r.title}, $value. " + (if (r.enabled) "Opens the choices" else reason(r) ?: "Can't be changed now")
        return NotifyChoiceRow(r.title, value, spoken)
    }

    /** Why a dimmed row can't be chosen (the core's own line, "Needs the task on the day …"); null for a row that can. */
    fun reason(r: SchoolLeadRow): String? = when {
        r.enabled -> null
        r.line.startsWith("Needs ") -> r.line
        else -> "School events are off in What Navilife may do"
    }

    /**
     * The sheet for the switch [key] ([on]: chosen now), on the Fold ([mac] false) or the Mac; null for a key that isn't
     * one of [SchoolLeadRules.KEYS]. On says what it makes and when; Off says what goes (and, for a task, that its
     * reminder goes with it); the note says it's synced and applies to the next school events.
     */
    fun sheet(key: String, on: Boolean, mac: Boolean): NotifyChoiceSheet? {
        val (title, onLine, offLine) = words(key) ?: return null
        val picks = listOf(Triple(ON, ON_LABEL, onLine) to true, Triple(OFF, OFF_LABEL, offLine) to false)
        return NotifyChoiceSheet(title, note(mac), picks.map { (p, value) ->
            val lit = value == on
            NotifyChoice(p.first, p.second, p.third, lit, "${p.second}, ${p.third}" + if (lit) ", chosen" else "")
        })
    }

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun named(on: Boolean, name: String): Boolean? = when (name) {
        ON -> true
        OFF -> false
        else -> null
    }?.takeIf { it != on }

    fun note(mac: Boolean): String =
        "Synced, so the ${if (mac) "Fold" else "Mac"} has the same. A change applies to the next school events; " +
            "those already added keep theirs."

    private fun words(key: String): Triple<String, String, String>? = when (key) {
        SchoolLeadRules.DAY_TASK -> Triple(
            "Task on the day",
            "A task at the event's time, in that day's list",
            "No task on the day, and no reminder 3 days before (it rides on it). The calendar entry stays",
        )
        SchoolLeadRules.EARLY -> Triple(
            "Reminder 3 days before",
            "A reminder at 09:00 three days before, on the task on the day",
            "No reminder 3 days before. The task on the day stays",
        )
        SchoolLeadRules.PREP_TASK -> Triple(
            "Task the day before",
            "“Get ready for …” the day before, with what to bring or pay",
            "No task the day before, and no reminder that day (it rides on it)",
        )
        SchoolLeadRules.DAY_BEFORE -> Triple(
            "Reminder the day before",
            "A reminder at 09:00 the day before, on the task the day before",
            "No reminder the day before. The task the day before stays",
        )
        else -> null
    }
}
