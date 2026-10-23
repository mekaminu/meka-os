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

/**
 * Numbers held together on screen (the quality gate's re-shot of slice 23, 2026-10-23: on the cover screen the
 * Emergency card read "Sam two doors down, 07700 / 900123" and Annual leave "Mon 26 – Fri 30 Oct · 5 work / days").
 * A phone number ("07700 900123", "+44 7700 900123"), a count with its unit ("5 work days", "12 days", "3 weeks") and
 * "4 of 5" each get no-break spaces, as do dates ([DateGlue]). Non-AI, pure; only for words drawn on screen.
 */
object PhraseGlue {
    /** A run of digits with single spaces between them, led by 0 or +: a phone number when it holds 10–13 digits. */
    private val PHONE = Regex("(?<![\\d+])(?:\\+|0)\\d+(?: \\d+)+(?!\\d)")
    /** "5 work days", "1 day", "12 nights", "3 weeks", "2 months", "4 of 5". */
    val COUNT = Regex("\\b\\d+ (?:(?:work |working )?(?:days?|nights?|weeks?|months?|years?|hours?|mins?)|of \\d+)\\b")

    private fun glue(s: String) = s.replace(' ', LifeTileRules.GLUE[0])

    /** [line] with its dates, phone numbers and counts made whole. */
    fun kept(line: String): String {
        val dated = DateGlue.kept(line)
        val phoned = PHONE.replace(dated) { m -> if (m.value.count { it.isDigit() } in 10..13) glue(m.value) else m.value }
        return COUNT.replace(phoned) { glue(it.value) }
    }

    /** The ranges in [line] (plain or kept) that must not wrap: dates, phone numbers and counts, for a screen test. */
    fun ranges(line: String): List<IntRange> {
        val p = DateGlue.plain(line)
        return DateGlue.ranges(p) +
            PHONE.findAll(p).filter { m -> m.value.count { it.isDigit() } in 10..13 }.map { it.range } +
            COUNT.findAll(p).map { it.range }
    }
}
