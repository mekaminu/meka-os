package os.meka.core.domain

/**
 * "A morning brief worth listening to", slice 6: the closing one-liner, written by MEKA's AI with the brief (one call,
 * the same `ask.brief`) and remembered for 30 days, so neither device repeats one within 30 days. Non-AI, pure.
 *
 * The last [KEEP] lines are synced on the brief's `context_mode` entity as [BriefFields.LINES] (one LWW text field,
 * ADR-008 addendum): a line per day, "epochDay<TAB>text", oldest first. Once today has a line, both devices say that one
 * (re-listening, the pane, Talk's "read my brief"). The AI's line is checked like the brief ([BriefAiRules]: nothing
 * named that isn't in the facts), must be short, can't be a quote, and can't be like one said in the last 30 days;
 * otherwise MEKA's own ([pick]) closes, skipping any of its lines said in the last 30 days.
 */
data class BriefLineMemory(val day: Long, val text: String)

object BriefOneLinerRules {
    /** Days remembered (and lines sent to the AI to avoid). */
    const val KEEP = 30
    /** A one-liner at most, in characters and words. */
    const val MAX_CHARS = 140
    const val MAX_WORDS = 20
    /** Two lines sharing this much of their words (Jaccard) are the same line. */
    const val SIMILAR = 0.6

    /** The stored [raw] lines, oldest first, one per day (a later one for the same day wins). Never throws. */
    fun decode(raw: String?): List<BriefLineMemory> {
        if (raw.isNullOrBlank()) return emptyList()
        val byDay = LinkedHashMap<Long, String>()
        for (row in raw.split('\n')) {
            val tab = row.indexOf('\t')
            if (tab <= 0) continue
            val day = row.substring(0, tab).trim().toLongOrNull() ?: continue
            val text = row.substring(tab + 1).trim()
            if (text.isEmpty()) continue
            byDay.remove(day)
            byDay[day] = text.take(MAX_CHARS)
        }
        return byDay.entries.map { BriefLineMemory(it.key, it.value) }.sortedBy { it.day }.takeLast(KEEP)
    }

    fun encode(lines: List<BriefLineMemory>): String =
        lines.joinToString("\n") { "${it.day}\t${it.text.replace('\n', ' ').replace('\t', ' ').trim()}" }

    /** [lines] with [text] as [day]'s line, keeping only the [KEEP] days up to [day]. */
    fun remember(lines: List<BriefLineMemory>, day: Long, text: String): List<BriefLineMemory> =
        (lines.filter { it.day != day && it.day > day - KEEP && it.day < day } + BriefLineMemory(day, text.trim().take(MAX_CHARS)))
            .sortedBy { it.day }

    /** [day]'s line, if one was said. */
    fun today(lines: List<BriefLineMemory>, day: Long): String? = lines.firstOrNull { it.day == day }?.text

    /** The lines said in the 30 days before [day] (not today's), newest first. */
    fun recent(lines: List<BriefLineMemory>, day: Long): List<String> =
        lines.filter { it.day < day && it.day > day - KEEP }.sortedByDescending { it.day }.map { it.text }

    /**
     * MEKA's own line for [s]: [BriefScriptRules]'s line for the day, or the next of them not like one in [recent]
     * (the rotation alone never repeats within a month; an AI line or a changed day can still collide).
     */
    fun pick(s: BriefStory, recent: List<String>): String {
        val n = BriefScriptRules.lineCount
        for (step in 0 until n) {
            val line = BriefScriptRules.ownLine(s, s.epochDay + step)
            if (recent.none { similar(it, line) }) return line
        }
        return BriefScriptRules.ownLine(s, s.epochDay)
    }

    /**
     * The AI's [answer] as the one-liner, or null: one or two short sentences, every name, number and time in [facts]
     * ([BriefAiRules]; a sentence may start with an ordinary imperative), no quotation or attribution, at most
     * [MAX_CHARS] and [MAX_WORDS], and not like any line in [recent].
     */
    fun check(answer: String, facts: List<String>, recent: List<String>): String? {
        val bare = answer.trim().trim('"', '“', '”', '\'', '‘', '’').trim()
        if (bare.isEmpty() || bare.length > MAX_CHARS) return null
        if (bare.any { it in "\"“”«»" } || " - " in bare || " — " in bare || " – " in bare) return null
        val sentences = BriefAiRules.sentences(bare)
        if (sentences.isEmpty() || sentences.size > 2) return null
        val kept = BriefAiRules.checked(bare, facts, MAX_CHARS, OPENERS)
        if (kept != sentences) return null
        val line = kept.joinToString(" ")
        if (BriefAiRules.tokens(line).size > MAX_WORDS) return null
        if (recent.any { similar(it, line) }) return null
        return line
    }

    /** Two lines are the same when their words (lowercase, letters and digits) overlap by [SIMILAR] or more. */
    fun similar(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val shared = x.intersect(y).size.toDouble()
        return shared / (x.union(y).size) >= SIMILAR
    }

    private fun words(s: String): Set<String> = BriefAiRules.tokens(s).map { it.lowercase().replace('’', '\'') }.toSet()

    /** Ordinary words a one-liner may start with (imperatives; never a name). */
    private val OPENERS = setOf(
        "finish", "clear", "ship", "protect", "book", "call", "send", "close", "tackle", "knock", "crack", "go", "be",
        "enjoy", "win", "nail", "put", "block", "own", "use", "stay", "ask", "move", "drop", "say", "pick", "write",
        "reply", "plan", "rest", "celebrate", "walk", "head", "note", "small", "big", "steady", "progress", "momentum",
        "done", "less", "fewer", "earn", "build", "show", "guard", "trust", "chip", "pace", "breathe", "slow", "stop",
        "begin", "save", "spend", "choose", "decide", "keep", "make", "take", "give", "leave", "start", "get", "try",
    )
}
