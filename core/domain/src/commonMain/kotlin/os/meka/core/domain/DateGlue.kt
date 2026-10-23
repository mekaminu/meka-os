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

/**
 * Durations held together on screen (the quality gate's Shut down the day shots, slice 47: on the cover screen the wake
 * alarm's line read "Suggested from Work at 09:00 · 1 h 20 / commute"). "1 h 20", "45 min" and "2 h" get no-break
 * spaces, and so does the word they measure when it follows ("1 h 20 commute", "2 h free"), so a line that must wrap
 * takes the whole phrase down. A short joining word after it ("1 h to get ready") stays free. Non-AI, pure; only for
 * words drawn on screen, and only where a screen asks for it (lines compared elsewhere keep plain spaces).
 */
object DurationGlue {
    private val SMALL = setOf("from", "then", "with", "into", "over", "till", "until", "before", "after")

    /** "1 h", "1 h 20", "45 min", "90 mins", with the following word when it's a word of four letters or more. */
    val DURATION = Regex("\\b\\d+ (?:h(?: \\d{1,2}(?! ?(?:h\\b|min|:)))?|mins?)\\b(?: ([a-z]{4,})\\b)?")

    /** The longest run a " · " beside a duration may make with the words either side of it. */
    const val DOT_RUN_MAX = 32

    /**
     * [line] with each duration (and the word it measures) made whole, and each " · " beside one held to the words on
     * both sides (CI run 1142: with "1 h 20 commute" one long run, [LoneWordGlue.dotsHeld] couldn't take the dot along,
     * so the cover screen's line ended "Suggested from Work at 09:00 ·").
     */
    fun kept(line: String): String {
        val glued = DURATION.replace(line) { m ->
            val word = m.groupValues[1]
            val keep = if (word.isNotEmpty() && word in SMALL) m.value.removeSuffix(" $word") else m.value
            keep.replace(' ', NBSP) + m.value.substring(keep.length)
        }
        val tokens = glued.split(' ')
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            val prev = out.lastOrNull()
            if (t == "·" && prev != null && next != null && prev.isNotEmpty() && next.isNotEmpty() && next != "·" &&
                (isDuration(prev) || isDuration(next)) && prev.length + next.length + 3 <= DOT_RUN_MAX
            ) {
                out[out.lastIndex] = prev + NBSP + "·" + NBSP + next
                i += 2
                continue
            }
            out += t
            i++
        }
        return out.joinToString(" ")
    }

    private val NBSP = LifeTileRules.GLUE[0]
    private fun isDuration(token: String) = DURATION.containsMatchIn(DateGlue.plain(token))

    /** The ranges in [line] (plain or kept) of each duration and its word, for a screen test that checks none wraps. */
    fun ranges(line: String): List<IntRange> = DURATION.findAll(DateGlue.plain(line)).map { m ->
        val word = m.groupValues[1]
        if (word.isNotEmpty() && word in SMALL) m.range.first until m.range.last + 1 - word.length - 1 else m.range
    }.toList()
}

/**
 * No word left alone on a line (the quality gate's Settings shots, slice 25: on the cover screen "When Ada says
 * something out loud on her / own" and "Gmail and Outlook · read, tidied, sent only on / your tap"). The last two
 * words of a line of three or more are joined by a no-break space, so a line that must wrap takes them down together;
 * a "·" or "–" just before them goes too, so no line starts with one. A pair longer than [PAIR_MAX] characters is
 * left free to wrap. Dates, numbers and counts are kept whole as [PhraseGlue] does. Non-AI, pure; only for words
 * drawn on screen (spoken and compared lines keep plain spaces).
 */
object LoneWordGlue {
    const val PAIR_MAX = 22
    private val NBSP = LifeTileRules.GLUE[0]
    private val JOINERS = setOf("·", "–", "-", "—")

    /**
     * [line] with its dates, numbers and counts whole ([PhraseGlue.kept]), each " · " held to the words beside it
     * ([dotsHeld]: no line starts with "· last push 16:55", Health's re-shot, quality gate slice 30) and its last words
     * held together while that leaves the line somewhere to break.
     */
    fun kept(line: String): String {
        val s = dotsHeld(PhraseGlue.kept(line.trimEnd()))
        val starts = wordStarts(s)
        if (starts.size < 3) return s
        var from = starts.size - 2
        if (s.substring(starts[from], starts[from + 1]).trim() in JOINERS && from > 0) from--
        val tail = s.substring(starts[from])
        if (tail.length > PAIR_MAX) return s
        val held = s.substring(0, starts[from]) + tail.replace(' ', NBSP)
        return if (held.length - held.lastIndexOf(' ') - 1 > RUN_MAX) s else held
    }

    /** The longest run a held dot may make with the word after it (beyond it, a line may end on the dot instead). */
    const val DOT_RUN_MAX = 20

    /** The longest unbreakable run the last words may make once held (a longer one would break mid-word). */
    const val RUN_MAX = 28

    /**
     * [s] with every spaced "·" held to the word before it, so no line starts with a dot, and to the word after it too
     * while that run stays within [DOT_RUN_MAX] ("expired · sign"), so a line ends on a dot only beside long words
     * ("jordan@example.com ·").
     */
    fun dotsHeld(s: String): String {
        val tokens = s.split(' ')
        val runs = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "·" && runs.isNotEmpty() && runs.last().isNotEmpty()) {
                runs[runs.lastIndex] = runs.last() + NBSP + "·"
                val next = tokens.getOrNull(i + 1)
                if (next != null && next.isNotEmpty() && next != "·" && runs.last().length + 1 + next.length <= DOT_RUN_MAX) {
                    runs[runs.lastIndex] = runs.last() + NBSP + next
                    i += 2
                    continue
                }
            } else {
                runs += t
            }
            i++
        }
        return runs.joinToString(" ")
    }

    /** True when a laid-out line (plain or kept) starts with a "·", for a screen test. */
    fun dotStartsLine(laidLine: String): Boolean = laidLine.trim { it.isWhitespace() || it == NBSP }.startsWith("·")

    /** The index where each word of [s] starts (words split on plain and no-break spaces). */
    private fun wordStarts(s: String): List<Int> =
        s.indices.filter { i -> !s[i].isSpace() && (i == 0 || s[i - 1].isSpace()) }

    private fun Char.isSpace() = this == ' ' || this == NBSP

    /** True when [lastLine] (one laid-out line's words, plain or kept) is a single word left alone, for a screen test. */
    fun alone(lastLine: String): Boolean = lastLine.trim().let { it.isNotEmpty() && it.none { c -> c.isSpace() } }
}

