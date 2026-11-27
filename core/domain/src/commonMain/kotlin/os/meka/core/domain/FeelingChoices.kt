package os.meka.core.domain

/**
 * Shut down the day's "How are you feeling?" as a row opening a sheet (walk-through week 5, A17: no open chip rows on a
 * page; Shut down had an open "Good · Tired · Stretched" row under its label). The row is "How are you feeling?" ·
 * "Not said" › (or the feeling picked today); the sheet lists the three, the one picked lit, each saying what it does to
 * tomorrow, from [FeelingRules]' own limits ("Offers to move up to 2 of tomorrow's tasks to lighter days"). Nothing moves
 * on a pick: the offer still waits for his tap, with Undo. Reuses Notifications' row and sheet shapes. Pure.
 */
object FeelingChoiceRules {
    const val NOT_SAID = "Not said"
    const val NOTE = "Kept on this device for a week, never synced or sent anywhere. Nothing moves until you tap."

    /** "How are you feeling?" · "Tired". */
    fun row(picked: Feeling?): NotifyChoiceRow {
        val value = picked?.label ?: NOT_SAID
        return NotifyChoiceRow(FeelingRules.QUESTION, value, "How are you feeling, $value. Opens the choices")
    }

    /** Each feeling, what it offers, the one picked lit. */
    fun sheet(picked: Feeling?): NotifyChoiceSheet = NotifyChoiceSheet(FeelingRules.QUESTION, NOTE, FeelingRules.CHOICES.map { f ->
        val line = line(f)
        NotifyChoice(f.name, f.label, line, f == picked, "${f.label}, $line" + if (f == picked) ", chosen" else "")
    })

    /** What picking [f] offers for tomorrow. */
    fun line(f: Feeling): String = when (f) {
        Feeling.GOOD -> "Tomorrow stays as it is"
        Feeling.TIRED -> "Offers to move up to ${FeelingRules.MAX_MOVES} of tomorrow's tasks to lighter days"
        Feeling.STRETCHED -> "Offers the same, names one task that could wait on Someday and can hold alerts that can wait"
    }

    /** The feeling a choice picks, or null when it isn't one or is the one already picked. */
    fun named(picked: Feeling?, name: String): Feeling? =
        Feeling.entries.firstOrNull { it.name == name && it != picked }
}
