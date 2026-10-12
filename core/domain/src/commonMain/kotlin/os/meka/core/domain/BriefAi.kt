package os.meka.core.domain

/**
 * "A morning brief worth listening to", slice 2: the brief written by MEKA's AI from the facts only (ADR-006 §2–4).
 * Non-AI, pure: what is sent and how the answer is checked.
 *
 * The device sends [BriefScriptRules.body]'s sentences (the deterministic brief before the news: every name, time and
 * number the AI may use is in them) and nothing else. The answer is read sentence by sentence ([check]): a sentence that
 * names anything not in the facts (a capitalised word, a number, a number word) is dropped. Too little left, or no
 * answer within [WAIT_MS], and the deterministic brief is read instead. The news, the one-liner and the close are always
 * MEKA's own ([BriefScriptRules.tail]).
 */
object BriefAiRules {
    /** How long Listen waits for the AI's brief before reading the deterministic one. */
    const val WAIT_MS = 6_000L
    /** What is sent at most (the wire refuses more). */
    const val MAX_FACTS = 40
    const val MAX_FACT = 300
    /** A kept sentence at most. */
    const val MAX_SENTENCE = 300
    /** At least this many sentences must survive the check, else the deterministic brief is read. */
    const val MIN_KEPT = 3

    /** The facts as sent: the body's sentences, each cut to [MAX_FACT], at most [MAX_FACTS]. */
    fun facts(v: MorningBriefView, name: String = "Meka"): List<String> =
        BriefScriptRules.body(v, name).map { it.trim().take(MAX_FACT) }.filter { it.isNotEmpty() }.take(MAX_FACTS)

    /**
     * The AI's [answer] checked against [facts]: its sentences in order, each kept only when every number, number word
     * and capitalised word in it (a sentence's first word may also be an ordinary opener: "Then", "Your") is in the
     * facts. Fitted to [maxChars] (the chosen [BriefLength]'s).
     */
    fun check(answer: String, facts: List<String>, maxChars: Int = BriefScriptRules.MAX_CHARS): List<String> =
        checked(answer, facts, maxChars, emptySet())

    /** [check], letting a sentence also start with one of [openers] (the one-liner's imperatives, [BriefOneLinerRules]). */
    internal fun checked(answer: String, facts: List<String>, maxChars: Int, openers: Set<String>): List<String> {
        val vocab = facts.flatMap { tokens(it) }.map { norm(it) }.flatMap { listOf(it, stem(it)) }.toSet()
        val kept = sentences(answer).filter { it.length <= MAX_SENTENCE && it.any { c -> c.isLetter() } && grounded(it, vocab, openers) }
        return BriefScriptRules.fit(kept, maxChars)
    }

    /** The brief read aloud from [kept] ([check]), or null when too little survived (read the deterministic one). */
    fun script(kept: List<String>, v: MorningBriefView): String? {
        if (kept.size < MIN_KEPT) return null
        return (kept + BriefScriptRules.tail(v)).joinToString(" ")
    }

    private fun grounded(sentence: String, vocab: Set<String>, openers: Set<String>): Boolean {
        // Markup, JSON or a link is never said.
        if (sentence.any { it in "{}[]<>#*|\\/@" } || "http" in sentence.lowercase()) return false
        val words = tokens(sentence)
        words.forEachIndexed { i, w ->
            val l = norm(w)
            val known = l in vocab || stem(l) in vocab
            when {
                w.any { it.isDigit() } -> if (!known) return false
                l in NUMBER_WORDS -> if (!known && NUMBER_WORDS[l] !in vocab) return false
                w.first().isUpperCase() -> if (!known && !(i == 0 && (l in OPENERS || l in openers)) && l !in ALWAYS) return false
            }
        }
        return true
    }

    /** Sentences split after . ! ? or … followed by a space (by hand: no regex, ADR-011's Native rule). */
    internal fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val t = text.replace('\n', ' ').trim()
        var start = 0
        var i = 0
        while (i < t.length) {
            val c = t[i]
            val end = c == '.' || c == '!' || c == '?' || c == '…'
            if (end && (i + 1 == t.length || t[i + 1] == ' ')) {
                val s = t.substring(start, i + 1).trim()
                if (s.isNotEmpty()) out += s
                start = i + 1
            }
            i++
        }
        val rest = t.substring(start.coerceAtMost(t.length)).trim()
        if (rest.isNotEmpty()) out += if (rest.last() in ".!?…") rest else "$rest."
        return out
    }

    /** Words: runs of letters, digits and apostrophes, with outer apostrophes trimmed. */
    internal fun tokens(text: String): List<String> {
        val out = mutableListOf<String>()
        val b = StringBuilder()
        fun flush() {
            val w = b.toString().trim('\'', '’')
            if (w.isNotEmpty()) out += w
            b.clear()
        }
        for (c in text) if (c.isLetterOrDigit() || c == '\'' || c == '’') b.append(c) else flush()
        flush()
        return out
    }

    private fun norm(w: String) = w.lowercase().replace('’', '\'')
    private fun stem(l: String) = l.removeSuffix("'s")

    private val NUMBER_WORDS = mapOf(
        "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
        "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50", "hundred" to "100",
    )

    /** Capitalised words that are never a name. */
    private val ALWAYS = setOf("i", "i'm", "i'll", "i've", "ok", "okay")

    /** Ordinary words a sentence may start with. */
    private val OPENERS = setOf(
        "a", "an", "the", "then", "and", "but", "so", "also", "after", "afterwards", "before", "next", "later", "finally",
        "lastly", "plus", "today", "tonight", "this", "that", "these", "those", "there", "there's", "here", "here's", "it",
        "it's", "its", "your", "you", "you're", "you've", "you'll", "on", "at", "in", "with", "for", "from", "by", "to",
        "if", "when", "while", "once", "as", "one", "nothing", "no", "not", "all", "both", "each", "every", "some", "any",
        "good", "morning", "afternoon", "evening", "remember", "don't", "do", "keep", "make", "take", "try", "start",
        "get", "give", "leave", "aim", "worth", "time", "now", "meanwhile", "otherwise", "outside", "over", "around",
        "between", "busy", "free", "clear", "quiet", "light", "heavy", "two", "three", "four", "five", "first", "second",
        "third", "what", "where", "who", "how", "let's", "look", "watch", "mind", "heads", "just", "only", "still", "most",
        "more", "less", "habits", "habit", "focus", "home", "work", "weather", "trains", "calendar", "news", "family",
    )
}
