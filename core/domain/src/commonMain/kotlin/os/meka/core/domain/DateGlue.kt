package os.meka.core.domain

/**
 * Dates held together on screen (the quality gate's Appointments shots, 2026-10-22: on the cover screen a row read
 * "last went 1 / Mar 2025" and "was due Mon / 12 Oct"). Each date phrase ("Mon 12 Oct", "1 Mar 2025", "Tue 3 Nov
 * 09:30", "Mar 2027") gets no-break spaces inside it, so a line that must wrap moves the whole date down. Non-AI, pure.
 * Only for words drawn on screen: spoken lines and lines compared elsewhere keep plain spaces.
 */
object DateGlue {
    private val MONTHS = Recurrence.MONTH_SHORT.joinToString("|")
    private val DAYS = LocalClock.DAY_SHORT.joinToString("|")

    /** Weekday? day month year? time?, or month year. */
    val DATE = Regex("\\b(?:(?:(?:$DAYS) )?\\d{1,2} (?:$MONTHS)\\b(?: \\d{4})?(?: \\d{1,2}:\\d{2})?|(?:$MONTHS) \\d{4})")

    /** [line] with every date phrase's spaces made no-break ([LifeTileRules.GLUE]). */
    fun kept(line: String): String = DATE.replace(line) { it.value.replace(' ', LifeTileRules.GLUE[0]) }

    /** [line] with the no-break spaces back to plain ones, for what Ask reads out or search lists. */
    fun plain(line: String): String = line.replace(LifeTileRules.GLUE[0], ' ')

    /** The character ranges of the date phrases in [line] (plain or already kept), for a test that checks none wraps. */
    fun ranges(line: String): List<IntRange> = DATE.findAll(plain(line)).map { it.range }.toList()
}
