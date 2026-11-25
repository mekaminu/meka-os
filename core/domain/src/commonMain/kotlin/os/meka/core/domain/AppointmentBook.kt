package os.meka.core.domain

/** One day on Booked…'s sheet: [label] is "Today", "Tomorrow" or "Fri 27". */
data class AppointmentBookDay(val day: Long, val label: String)

/** One time on Booked…'s sheet ([minute] of the day, [label] "09:30"). */
data class AppointmentBookTime(val minute: Int, val label: String)

/**
 * Booked…'s sheet (walk-through week 5, A17: no open input box on a page): the appointment's [title], [question], the
 * next week's [days] and the usual [times] as chips, and a field for a later day whose [typeHint] example is always
 * ahead of today. [note] says Navilife books nothing.
 */
data class AppointmentBookSheet(
    val title: String,
    val question: String,
    val dayLabel: String,
    val days: List<AppointmentBookDay>,
    val timeLabel: String,
    val times: List<AppointmentBookTime>,
    val typeHint: String,
    val saveLabel: String,
    val note: String,
    val today: Long,
)

/** Non-AI, pure: the sheet Booked… opens on Needs you's appointment card (both apps). */
object AppointmentBookRules {
    const val QUESTION = "When is it booked?"
    const val DAY = "DAY"
    const val TIME = "TIME"
    const val SAVE = "Save"
    const val NOTE = "This notes the day you booked. Navilife books nothing and calls no one."
    const val PICK_DAY = "Pick the day, or type a later one"
    const val NO_TIME = "no time yet"
    /** The chips: the next seven days, today first. */
    const val DAYS = 7
    /** How far ahead the typed example sits, so it is never a day already on the chips or in the past. */
    const val EXAMPLE_AHEAD = 10
    /** The example's time, 09:30. */
    const val EXAMPLE_MINUTE = 9 * 60 + 30
    val TIMES: List<Int> = listOf(8 * 60 + 30, 9 * 60, 9 * 60 + 30, 10 * 60 + 30, 12 * 60, 14 * 60, 15 * 60 + 30, 17 * 60)

    fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "Today"
        today + 1 -> "Tomorrow"
        else -> "${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]} ${CivilDate.fromEpochDay(day).day}"
    }

    fun typeHint(today: Long): String = "Or a later day: “${CivilDate.shortLabel(today + EXAMPLE_AHEAD)} ${clock(EXAMPLE_MINUTE)}”"

    /** "9:30" (no leading zero, as people type it and the booking reader reads it). */
    fun clock(minute: Int): String = "${minute / 60}:${(minute % 60).toString().padStart(2, '0')}"

    fun sheet(title: String, today: Long): AppointmentBookSheet = AppointmentBookSheet(
        title = title.trim().ifEmpty { "Appointment" },
        question = QUESTION,
        dayLabel = DAY,
        days = (0 until DAYS).map { AppointmentBookDay(today + it, dayLabel(today + it, today)) },
        timeLabel = TIME,
        times = TIMES.map { AppointmentBookTime(it, LocalClock.formatMinute(it)) },
        typeHint = typeHint(today),
        saveLabel = SAVE,
        note = NOTE,
        today = today,
    )

    /** What Save sends to the booking reader for the chosen chips: "Thu 26 Nov 9:30", or the day alone. */
    fun text(day: Long, minute: Int?): String = CivilDate.shortLabel(day) + (minute?.let { " ${clock(it)}" } ?: "")

    /** The line above Save: "Thu 26 Nov · 09:30", "Thu 26 Nov · no time yet", or how to start. */
    fun chosenLine(day: Long?, minute: Int?): String = when (day) {
        null -> PICK_DAY
        else -> "${CivilDate.shortLabel(day)} · ${minute?.let { LocalClock.formatMinute(it) } ?: NO_TIME}"
    }

    /** What Save sends: the typed words when there are any, else the chips; null when there is nothing to save. */
    fun toSave(day: Long?, minute: Int?, typed: String): String? {
        val t = typed.trim()
        return when {
            t.isNotEmpty() -> t
            day != null -> text(day, minute)
            else -> null
        }
    }

    /** Save is lit once a day is chosen or words are typed. */
    fun canSave(day: Long?, typed: String): Boolean = toSave(day, null, typed) != null
}
