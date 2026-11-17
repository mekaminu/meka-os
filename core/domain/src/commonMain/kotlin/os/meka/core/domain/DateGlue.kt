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
    /** "5 work days", "1 day", "12 nights", "3 weeks", "2 months", "14 mo" (Clear my cards' Debt-free tile, A15), "4 of 5". */
    val COUNT = Regex("\\b\\d+ (?:(?:work |working )?(?:days?|nights?|weeks?|months?|mo|years?|hours?|mins?)|of \\d+)\\b")

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

    /** "0 h of 8 h", "45 min of 8 h": a share of a time goes down whole (A15 part 2: Week ahead's "0 h / of 8 h"). */
    private val OF_DURATION = Regex("(?<=\\b(?:h|min|mins|\\d))(?: of )(?=\\d+[ \u00A0](?:h|mins?)\\b)")

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
        }.let { OF_DURATION.replace(it) { m -> m.value.replace(' ', NBSP) } }
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
 * No word left alone on a line (the quality gate's Settings shots, slice 25: on the cover screen "When Obi says
 * something out loud on his / own" and "Gmail and Outlook · read, tidied, sent only on / your tap"). The last two
 * words of a line of three or more are joined by a no-break space, so a line that must wrap takes them down together;
 * a "·" or "–" just before them goes too, so no line starts with one. A pair longer than [PAIR_MAX] characters is
 * left free to wrap. Dates, numbers and counts are kept whole as [PhraseGlue] does. Non-AI, pure; only for words
 * drawn on screen (spoken and compared lines keep plain spaces).
 */
object LoneWordGlue {
    const val PAIR_MAX = 22
    private val NBSP = LifeTileRules.GLUE[0]
    private val JOINERS = setOf("·", "–", "-", "—", "→")

    /** The marks held to the word before them, so no line starts with one or holds one alone (A14: Calendars' "→"). */
    private val HELD = setOf("·", "→")

    /** The marks that end a sentence, so the word after one starts the next (A28 slice 5). */
    private val ENDS = setOf('.', '?', '!')

    /**
     * [line] with its dates, numbers and counts whole ([PhraseGlue.kept]), each " · " held to the words beside it
     * ([dotsHeld]: no line starts with "· last push 16:55", Health's re-shot, quality gate slice 30) and its last words
     * held together while that leaves the line somewhere to break.
     */
    fun kept(line: String): String {
        val s = dotsHeld(leadsHeld(figuresHeld(phrasesHeld(PhraseGlue.kept(line.trimEnd())))))
        val starts = wordStarts(s)
        if (starts.size < 3) return s
        val base = starts.size - 2
        var from = base
        // Walk-through 2026-11-15, A15: a date ending the line goes down whole with the word before it ("Card · Imported /
        // Wed 21 Oct" could break inside the held pair, before the date).
        val plain = DateGlue.plain(s)
        DateGlue.DATE.findAll(plain).lastOrNull()?.takeIf { it.range.last == plain.lastIndex }?.let { d ->
            val at = starts.indexOfFirst { it >= d.range.first }
            if (at in 1..from) from = at - 1
        }
        // A15: a short last pair ("or two", "just now") takes the word before it too ("within a minute / or two",
        // "updated / just now"), unless that word is a joiner or a figure.
        if (from == base && from >= 1 && word(s, starts, from).length <= SHORT && word(s, starts, from + 1).length <= SHORT &&
            word(s, starts, from - 1) !in JOINERS && word(s, starts, from - 1).none { it.isDigit() }
        ) from--
        // A15 part 4: a last pair led by "your", "the", "a" or the like takes the word before it too, so the pair never
        // reads as cut off from what leads it (Obi on calls' "Obi can ring businesses after / your tap").
        if (from == base && from >= 1 && word(s, starts, from).lowercase() in DETERMINERS &&
            word(s, starts, from - 1) !in JOINERS && word(s, starts, from - 1).none { it.isDigit() }
        ) from--
        // When the longer hold doesn't fit, the last two words still go down together as before.
        return (if (from != base) held(s, starts, from, freeDot = true) else null) ?: held(s, starts, base, freeDot = false) ?: s
    }

    /**
     * [s] with its words from [start] on held together (a joiner or a short last sentence before them too); null when too
     * long. With [freeDot] (A15's longer holds only) a dot held to the word after it may let it go to make room.
     */
    private fun held(s: String, starts: List<Int>, start: Int, freeDot: Boolean): String? {
        var from = start
        if (s.substring(starts[from], starts[from + 1]).trim() in JOINERS && from > 0) from--
        // A28 slice 5: a short last sentence goes down whole with its first word ("… balances. Not / financial advice."
        // left "Not" ending the line before), while the three words stay within PAIR_MAX.
        if (from >= 2 && s.substring(starts[from - 2], starts[from - 1]).trimEnd().let { it.isNotEmpty() && it.last() in ENDS } &&
            s.substring(starts[from - 1]).length <= PAIR_MAX
        ) from--
        val tail = s.substring(starts[from])
        if (tail.length > PAIR_MAX) return null
        val held = s.substring(0, starts[from]) + tail.replace(' ', NBSP)
        val run = held.lastIndexOf(' ') + 1
        if (held.length - run <= RUN_MAX) return held
        // A15: a dot held to the word after it lets that word go, so the last words can still go down together
        // ("payments · imported Wed 21 Oct" ends a line on the dot rather than breaking before the date).
        val dot = held.lastIndexOf("$NBSP·$NBSP", starts[from])
        if (!freeDot || dot < run) return null
        val freed = held.substring(0, dot + 2) + " " + held.substring(dot + 3)
        return if (freed.length - (dot + 3) <= RUN_MAX) freed else null
    }

    /**
     * [kept] after [DurationGlue.kept], for a screen whose lines carry durations (found and fixing A10, the Week
     * ahead's balance line "family 6 h of 8 h" leaving "h" alone on a line). A line with no duration reads as [kept].
     * A percentage also takes the word after it (A28: the card sheet's foot ended a line on "before 0%" over "ends ·").
     */
    fun measured(line: String): String = kept(percentsHeld(DurationGlue.kept(line)))

    /** "0% ends", "10% higher": a percentage and the word after it never part ("10% · a drop" stays free). */
    private val PERCENT = Regex("(?<=\\d%) (?=[A-Za-z])")

    /** [s] with each percentage held to the word after it. */
    fun percentsHeld(s: String): String = PERCENT.replace(s, NBSP.toString())

    /** The longest run a held dot may make with the word after it (beyond it, a line may end on the dot instead). */
    const val DOT_RUN_MAX = 20

    /**
     * The longest run one word, its dot and the next word may make when no other dot is in or after the run (A28, walk-through
     * 2026-11-08: at [DOT_RUN_MAX] Health's "can't see notifications · messages" ended a line on the dot); also the
     * longest run a small leading word makes with the word after it.
     */
    const val PAIR_RUN_MAX = 26

    /** Small words that lead into the word after them, so none ends a line ("Obi found … on" / "rosaskitchen.example"). */
    private val LEADS = setOf("on", "at", "by", "to", "in", "of", "for", "via")

    /**
     * [s] with each small leading word ([LEADS]) held to the word after it while that run stays within [PAIR_RUN_MAX]
     * (A28: "Obi found … on" ended the call card's line). Not before a joiner, just after a dot or at the end.
     */
    fun leadsHeld(s: String): String {
        val tokens = s.split(' ')
        val runs = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            val lead = t.lowercase().let { it in LEADS || it.substringAfterLast(NBSP) in LEADS }
            // Just after a dot the word stays free, for the dot to take (CI: "Sun 25 Oct · in 4 days" ended on the dot).
            val afterDot = tokens.getOrNull(i - 1) in HELD
            if (lead && !afterDot && next != null && next.isNotEmpty() && next !in JOINERS && t.length + 1 + next.length <= PAIR_RUN_MAX) {
                runs += t + NBSP + next
                i += 2
            } else {
                runs += t
                i++
            }
        }
        return runs.joinToString(" ")
    }

    /**
     * Phrases that read as one and go down whole (walk-through 2026-11-15, A15 part 3: Needs you's hint ended a line on
     * "‹ ›" over "for the next ·", now "‹ › for more", which goes down with its dot; the inbox lead read "one / at a time";
     * part 5: Setup's head read "… 1 / couldn't be checked", and a figure before a phrase takes it whole).
     */
    private val PHRASES = listOf("‹ › for more", "one at a time", "couldn't be checked")

    /** [s] with each of [PHRASES] held whole. */
    fun phrasesHeld(s: String): String = PHRASES.fold(s) { acc, p -> acc.replace(p, p.replace(' ', NBSP)) }

    /** A figure on its own: "2", "£10.99", "45%", "1,200" (a time like "17:30" isn't one: it reads whole alone). */
    private val FIGURE = Regex("^[£$€−-]?\\d[\\d,.]*%?$")

    /** "a month", "per week": the period a price or count is for, held to it ("£10.99 a / month", A15). */
    private val PERIOD = Regex("\\b(?:a|an|per|each|every) (?:day|night|week|fortnight|month|year)\\b")

    /**
     * [s] with each figure on its own held to the word after it, and a period ("a month") made whole, while that run stays
     * within [PAIR_RUN_MAX] (walk-through 2026-11-15, A15 part 2: "· 2 / EVENTS", "1 / bill due", "· 5 / lessons left",
     * "Say nothing for 6 / seconds", "This week: 1 / reminder", "£10.99 a / month"). Not before a joiner.
     */
    fun figuresHeld(s: String): String {
        // The period is held on its own, not to the figure: "£10.99 · a month · Subscription" as one run was too long for
        // the last words to go down together (CI run 43988773613, Needs you's mail card left "Subscription" alone).
        val tokens = PERIOD.replace(s) { it.value.replace(' ', NBSP) }.split(' ')
        val runs = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val next = tokens.getOrNull(i + 1)
            if (FIGURE.matches(t) && next != null && next.isNotEmpty() && next !in JOINERS && next.first() !in JOINERS.map { it[0] } &&
                !PERIOD.containsMatchIn(next.replace(NBSP, ' ')) &&
                t.length + 1 + next.length <= PAIR_RUN_MAX
            ) {
                runs += t + NBSP + next
                i += 2
            } else {
                runs += t
                i++
            }
        }
        return runs.joinToString(" ")
    }

    /** The longest unbreakable run the last words may make once held (a longer one would break mid-word). */
    const val RUN_MAX = 28

    /**
     * [s] with every spaced "·" (and "→", A14) held to the word before it, so no line starts with one, and to the word after it too
     * while that run stays within [DOT_RUN_MAX] ("expired · sign"), so a line ends on a dot only beside long words
     * ("jordan@example.com ·").
     */
    fun dotsHeld(s: String): String {
        val tokens = s.split(' ')
        val runs = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t in HELD && runs.isNotEmpty() && runs.last().isNotEmpty()) {
                runs[runs.lastIndex] = runs.last() + NBSP + t
                val next = tokens.getOrNull(i + 1)
                // The longer pair only when it starts no chain: the word after it isn't followed by another dot.
                val lone = runs.last().split(NBSP).count { it in HELD } == 1 && tokens.getOrNull(i + 2) !in HELD
                val max = if (lone) PAIR_RUN_MAX else DOT_RUN_MAX
                if (next != null && next.isNotEmpty() && next !in HELD && runs.last().length + 1 + next.length <= max) {
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
    fun dotStartsLine(laidLine: String): Boolean = laidLine.trim { it.isWhitespace() || it == NBSP }.let { l -> HELD.any { l.startsWith(it) } }

    /** The longest word of a "short last pair" ([kept], A15: "or two", "just now"). */
    const val SHORT = 4

    /** Words that lead the last word as a pair ("your tap", "the month"), which then takes the word before it ([kept]). */
    private val DETERMINERS = setOf("your", "my", "his", "her", "their", "our", "the", "a", "an", "this", "that")

    /** The [i]th word of [s] given its [starts], without the spaces around it. */
    private fun word(s: String, starts: List<Int>, i: Int): String =
        s.substring(starts[i], starts.getOrNull(i + 1) ?: s.length).trim { it.isSpace() }

    /** The index where each word of [s] starts (words split on plain and no-break spaces). */
    private fun wordStarts(s: String): List<Int> =
        s.indices.filter { i -> !s[i].isSpace() && (i == 0 || s[i - 1].isSpace()) }

    private fun Char.isSpace() = this == ' ' || this == NBSP

    /** True when [lastLine] (one laid-out line's words, plain or kept) is a single word left alone, for a screen test. */
    fun alone(lastLine: String): Boolean = lastLine.trim().let { it.isNotEmpty() && it.none { c -> c.isSpace() } }
}


/**
 * Chips laid in rows with none left alone (walk-through 2026-11-08, A28 slice 5: What if I save…'s month chips put
 * "3 months · 6 months · 12 months" on one row on the cover screen and "24 months" alone under them). Non-AI, pure.
 */
object EvenRows {
    /**
     * How many chips go on each row, given each chip's [widths], the [gap] between them and the row's [max] width: one
     * row when all fit; otherwise as many rows as wrapping needs, with the chips shared out evenly (the earlier rows
     * taking any extra) when every row then fits, else plain wrapping.
     */
    fun sizes(widths: List<Int>, gap: Int, max: Int): List<Int> {
        if (widths.isEmpty()) return emptyList()
        val wrapped = mutableListOf<Int>()
        var used = 0
        var count = 0
        for (w in widths) {
            if (count > 0 && used + gap + w > max) { wrapped += count; used = 0; count = 0 }
            used += if (count == 0) w else gap + w
            count++
        }
        wrapped += count
        if (wrapped.size == 1 || wrapped.last() > 1) return wrapped
        val rows = wrapped.size
        val base = widths.size / rows
        val extra = widths.size % rows
        val even = List(rows) { r -> base + if (r < extra) 1 else 0 }
        var at = 0
        for (n in even) {
            val row = widths.subList(at, at + n)
            if (row.sum() + gap * (n - 1) > max) return wrapped
            at += n
        }
        return even
    }
}
