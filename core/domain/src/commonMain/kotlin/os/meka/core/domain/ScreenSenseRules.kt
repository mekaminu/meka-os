package os.meka.core.domain

/**
 * The quality gate's "no nonsense on screen" check (Meka 2026-10-21 20:21: "Why am I the one discovering what needs
 * improvement?"). Given every piece of text a screen shows, it names what a person would read as broken: a 0 m
 * average or longest, a lone dash where a figure belongs, "3 of 2 done", a negative count, a programming value
 * ("null", "NaN"), an unfilled template ("%s", "{name}"), or "0 of 0". Percentages and money may go over 100 or below
 * zero (over budget, spending down), so they are left alone. The screenshot tests run it over each screen
 * they draw and fail the slice when it finds anything, so these never reach the Fold.
 *
 * Pure text rules: it can't see boxes, so "a big empty bordered box" stays with the screenshots and the reviewer.
 */
object ScreenSenseRules {
    data class Problem(val text: String, val why: String)

    private val statWords = Regex("""\b(average|avg|longest|shortest|last|mean|typical)\b""", RegexOption.IGNORE_CASE)
    private val zeroLength = Regex("""(?<![\d.:])0 ?(m|min|mins|h|hrs?|s)\b""")
    // A count ("3 of 2 done"), never a duration: "family 4 h 15 of 8 h" is four and a quarter hours of eight (slice 45).
    // Grouped numbers read whole (quality gate slice 59): "12 of 1,000,000 characters" is twelve of a million, not "12 of 1".
    private val ofDone = Regex("""(?<!\d h )(?<![\d,])\b(\d{1,3}(?:,\d{3})+|\d+) of (\d{1,3}(?:,\d{3})+|\d+)(?!,\d)\b(?! ?(h|m|min|mins|hrs?)\b)""")
    private val negative = Regex("""(?<![\w\d:–-])-\d+(\.\d+)? ?(m|min|h|s|days?|tasks?|done|items?)\b""")
    private val codeValue = Regex("""\b(null|NaN|undefined|Infinity|lateinit)\b""")
    private val template = Regex("""%[sd]|%\d\$[sd]|\{[a-zA-Z_][a-zA-Z0-9_]*\}|\$\{""")
    private val loneDash = setOf("—", "–", "-", "--", "—:—", "--:--")
    private val digits = Regex("""\d+""")

    /** Every problem in [texts], one per text and reason, in the order found. Empty means the screen reads sensibly. */
    fun problems(texts: List<String>): List<Problem> = texts.flatMap { problemsIn(it) }

    fun problemsIn(text: String): List<Problem> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val found = mutableListOf<Problem>()
        if (t in loneDash) found += Problem(t, "a dash where a figure belongs")
        // "average 0 m", "Last fast 0 m · longest 0 m": a stat of nothing. A 0 m on its own (a timer at rest) is fine.
        t.split('·', '|', ',').forEach { part ->
            val allZero = digits.findAll(part).all { it.value.toLong() == 0L }
            if (statWords.containsMatchIn(part) && zeroLength.containsMatchIn(part) && allZero) {
                found += Problem(t, "a 0 length shown as a figure (“${part.trim()}”)")
            }
        }
        ofDone.findAll(t).forEach { m ->
            val (a, b) = m.destructured
            val done = a.replace(",", "").toLongOrNull() ?: return@forEach
            val total = b.replace(",", "").toLongOrNull() ?: return@forEach
            when {
                total == 0L -> found += Problem(t, "an empty count shown as a figure (“${m.value}”)")
                done > total -> found += Problem(t, "more done than there are (“${m.value}”)")
            }
        }
        if (negative.containsMatchIn(t)) found += Problem(t, "a negative amount")
        if (codeValue.containsMatchIn(t)) found += Problem(t, "a programming value on screen")
        if (template.containsMatchIn(t)) found += Problem(t, "an unfilled template")
        return found
    }

    /** One line per problem, for a failing test's message. */
    fun report(problems: List<Problem>): String = problems.joinToString("\n") { "• “${it.text}”: ${it.why}" }
}
