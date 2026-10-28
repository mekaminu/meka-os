package os.meka.core.domain

/**
 * Money redesign, **Ask Obi**, slice 3: **the mic on the bar** (mockup `docs/design/money/`, v4: the bar's mic). A tap
 * on the bar's 🎙 opens Obi · money already listening (the sheet's own 🎙 listens again); what he says is tidied by
 * [question] and asked as if typed, so the device's rules and Obi's AI see the same words either way.
 *
 * Tidying is plain text work on the device: spoken amounts become pounds the rules read ("three hundred pounds" →
 * "£300", "two grand" → "£2,000", "1,200 quid" → "£1,200"), the first letter is a capital, and a question gets its "?".
 * Nothing is recorded or kept (the recogniser is the phone's, as in Capture; the Mac uses its own Dictation).
 */
object MoneyChatSpoken {
    /** The 🎙's spoken name on the bar and in the sheet. */
    const val MIC_SPOKEN = "Say your money question"
    /** The sheet's 🎙 while listening (a tap takes what he said so far). */
    const val MIC_DONE = "Done speaking"
    /** Nothing heard: the field stays for typing. */
    const val NOTHING = "Didn't catch a question. Tap the mic to try again, or type it."
    const val NOTHING_MAC = "Didn't catch a question. Click the mic to try again, or type it."

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90,
    )
    private val MONEY_WORDS = setOf("pound", "pounds", "quid", "grand", "k")
    private val QUESTION = setOf(
        "can", "could", "what", "what's", "whats", "how", "how's", "which", "when", "where", "why", "who", "is", "are",
        "am", "do", "does", "did", "should", "shall", "will", "would", "have", "has", "if",
    )
    private val POUNDS = Regex("""(?<![£\d])(\d[\d,]*(?:\.\d{1,2})?)\s?(?:pounds?|quid)\b""", RegexOption.IGNORE_CASE)
    private val GRAND = Regex("""(?<![£\d])(\d{1,3})\s?grand\b""", RegexOption.IGNORE_CASE)
    private val POUND_GRAND = Regex("""£(\d{1,3})\s?grand\b""", RegexOption.IGNORE_CASE)

    /** [words] as heard, tidied into the question to ask; null when nothing was said. */
    fun question(words: String): String? {
        var s = words.trim().replace(Regex("""\s+"""), " ")
        if (s.isEmpty()) return null
        s = numbers(s)
        s = POUND_GRAND.replace(s) { m -> "£" + grouped(m.groupValues[1].toLong() * 1000) }
        s = GRAND.replace(s) { m -> "£" + grouped(m.groupValues[1].toLong() * 1000) }
        s = POUNDS.replace(s) { m -> "£" + m.groupValues[1] }
        s = s.replaceFirstChar { it.uppercaseChar() }
        val first = s.substringBefore(' ').lowercase().trimEnd(',', '.')
        if (first in QUESTION && s.last() !in ".?!") s += "?"
        return s
    }

    /**
     * Spoken numbers to digits: a run of number words ("twelve hundred", "one thousand two hundred and fifty", "a
     * hundred") becomes digits when it is a sum of money (a money word follows) or at least 100. A lone "one" or "a"
     * stays a word ("which one", "a month"); "six months" stays for the month rules, which read words.
     */
    internal fun numbers(s: String): String {
        val tokens = s.split(' ')
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val run = run(tokens, i)
            if (run == null) { out += tokens[i]; i++; continue }
            val (end, value) = run
            val next = tokens.getOrNull(end)?.lowercase()?.trimEnd(',', '.', '?', '!')
            if (value >= 100 || (next != null && next in MONEY_WORDS)) {
                val trail = tokens[end - 1].takeLastWhile { it in ",.?!" }
                out += value.toString() + trail
                i = end
            } else {
                out += tokens[i]; i++
            }
        }
        return out.joinToString(" ")
    }

    /** The number words from [start]: (the index after them, their value), or null when none start there. */
    private fun run(tokens: List<String>, start: Int): Pair<Int, Long>? {
        var total = 0L
        var current = 0L
        var any = false
        var i = start
        while (i < tokens.size) {
            val raw = tokens[i]
            val w = raw.lowercase().trimEnd(',', '.', '?', '!')
            val ended = raw.length != w.length // a comma or full stop ends the number
            val nextW = tokens.getOrNull(i + 1)?.lowercase()?.trimEnd(',', '.', '?', '!')
            when {
                w in UNITS -> {
                    val v = UNITS.getValue(w).toLong()
                    // "twenty five" adds; "five twenty" isn't a number we read: stop before it.
                    if (any && current % 100 != 0L && current % 10 != 0L) break
                    if (any && v >= 10 && current % 100 != 0L) break
                    current += v; any = true
                }
                w == "hundred" && any -> current = (if (current == 0L) 1 else current) * 100
                w == "thousand" && any -> { total += (if (current == 0L) 1 else current) * 1000; current = 0 }
                w == "a" && !any && (nextW == "hundred" || nextW == "thousand") -> { current = 1; any = true }
                w == "and" && any && nextW != null && nextW in UNITS -> {}
                else -> break
            }
            i++
            if (ended) break
        }
        return if (any) i to (total + current) else null
    }

    private fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}
