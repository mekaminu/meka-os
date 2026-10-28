package os.meka.core.domain

/**
 * Money redesign, **Ask Obi, slice 2: Obi's own money chat** (data sharing approved by Meka 2026-10-24 21:13: monthly
 * category totals and payee names with amounts go to the AI; never account names or numbers, sort codes, IBANs or
 * balances). A question [MoneyChat] can't answer on the device ([needsObi]) goes to Obi with [facts]: the last three
 * months' totals in and out and by category, the biggest payees of the last 90 days, what's still to go out before
 * payday (payee, amount, day) and the payday's date. Nothing else of Money.
 *
 * Obi may only use figures already in what was sent (the facts, his question and the chat so far). [check] reads every
 * number in his answer against those, so a made-up or worked-out figure is never shown: the turn says so instead
 * ([UNCHECKED]). No links or markup; one short paragraph.
 */
object MoneyChatObi {
    const val FEATURE = "money.chat"
    /** Under an answer Obi wrote. */
    const val BY = "Obi's answer · from your monthly totals and payees"
    const val UNCHECKED = "Obi's answer used a figure I couldn't find in your numbers, so I've left it out. Try asking another way, or tap a question below."
    const val NOTHING = "Obi didn't have an answer for that. Try asking another way, or tap a question below."
    const val MAX_ANSWER = 600
    const val MAX_FACTS = 40
    const val MONTHS = 3
    const val PAYEES = 15
    const val CATEGORIES = 10
    const val UPCOMING = 10
    const val PAYEE_DAYS = 90
    /** Earlier turns sent with a question so a follow-up makes sense ("and next month?"). */
    const val HISTORY = 4

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** Whether [question] is one the device's rules can't answer, so Obi should (not refused, not empty). */
    fun needsObi(question: String, d: MoneyChatData): Boolean {
        val q = question.trim()
        if (q.length < 3) return false
        if (d.ask.lines.isEmpty() && d.ask.upcoming.isEmpty() && d.clear == null) return false
        return !MoneyChat.isAbout(q)
    }

    /** A payee name fit to send: no account or card number in it (else null), on one line, short. */
    fun payee(name: String): String? {
        val n = name.replace(Regex("""\s+"""), " ").trim()
        if (n.isEmpty() || MoneyAccountNames.revealsNumber(n) || Regex("""\d{6,}""").containsMatchIn(n.replace(" ", ""))) return null
        return n.take(40)
    }

    /** What goes to Obi with a question: plain lines, at most [MAX_FACTS]. See the object doc. */
    fun facts(d: MoneyChatData): List<String> {
        val a = d.ask
        val out = mutableListOf("Today: ${CivilDate.shortLabel(a.today)} ${CivilDate.fromEpochDay(a.today).year}")
        val now = MoneyRules.monthOf(a.today)
        val counted = a.lines.filter { it.category != MoneyCategory.TRANSFERS }
        for (m in now downTo now - (MONTHS - 1)) {
            val lines = counted.filter { MoneyRules.monthOf(it.day) == m }
            if (lines.isEmpty()) continue
            val spent = -lines.filter { it.pence < 0 && it.category.spend }.sumOf { it.pence }
            val came = lines.filter { it.pence > 0 && it.category != MoneyCategory.TRANSFERS }.sumOf { it.pence }
            val label = MoneyRules.monthLabel(m) + if (m == now) " so far" else ""
            out += "$label: spent ${fmt(spent)}, came in ${fmt(came)}"
            val cats = lines.filter { it.pence < 0 && it.category.spend }.groupBy { it.category }
                .mapValues { (_, l) -> -l.sumOf { it.pence } }.entries.sortedByDescending { it.value }.take(CATEGORIES)
            if (cats.isNotEmpty()) out += "  by category: " + cats.joinToString(", ") { "${it.key.label} ${fmt(it.value)}" }
        }
        val since = a.today - PAYEE_DAYS
        val payees = counted.filter { it.day > since && it.pence < 0 && it.category.spend }
            .groupBy { payee(it.merchant) }.filterKeys { it != null }
            .mapValues { (_, l) -> -l.sumOf { it.pence } to l.size }.entries.sortedByDescending { it.value.first }.take(PAYEES)
        if (payees.isNotEmpty()) out += "Biggest payees, last $PAYEE_DAYS days: " +
            payees.joinToString("; ") { "${it.key} ${fmt(it.value.first)} (${it.value.second} payment${if (it.value.second == 1) "" else "s"})" }
        val inPayees = counted.filter { it.day > since && it.pence > 0 && it.category == MoneyCategory.INCOME }
            .groupBy { payee(it.merchant) }.filterKeys { it != null }
            .mapValues { (_, l) -> l.sumOf { it.pence } }.entries.sortedByDescending { it.value }.take(5)
        if (inPayees.isNotEmpty()) out += "Came in from, last $PAYEE_DAYS days: " + inPayees.joinToString("; ") { "${it.key} ${fmt(it.value)}" }
        a.payday?.paydayDay?.let { out += "Next payday: ${CivilDate.shortLabel(it)}" }
        val upcoming = a.upcoming.filter { it.dueDay >= a.today && it.dueDay <= a.today + 31 }.sortedBy { it.dueDay }
            .mapNotNull { b -> payee(b.title)?.let { "$it ${fmt(b.pence)} ${CivilDate.shortLabel(b.dueDay)}" } }.take(UPCOMING)
        if (upcoming.isNotEmpty()) out += "Still to go out, next 31 days: " + upcoming.joinToString("; ")
        return out.take(MAX_FACTS)
    }

    private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")
    private val BANNED = Regex("""https?:|www\.|[<>{}@`]|\*\*|^#""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** "1,250.00" → "1250"; "4.50" stays "4.5". */
    private fun norm(n: String): String = n.replace(",", "").let { s -> if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s }

    /** Every number in [texts], with each one's whole part too, so "£1,187" may stand for "£1,187.40". */
    fun numbersIn(texts: List<String>): Set<String> = texts.flatMap { t ->
        NUMBER.findAll(t).flatMap { m -> val n = norm(m.value); listOf(n, n.substringBefore('.')) }
    }.toSet()

    /**
     * Obi's [answer] on one paragraph, or null when it doesn't pass: empty or over-long, a link or markup, or a number
     * that isn't in [sent] (the facts, the question and the chat so far). Small counts and days of the month (up to 31)
     * and years may stand on their own.
     */
    fun check(answer: String, sent: List<String>): String? {
        val t = answer.replace(Regex("""\s+"""), " ").trim()
        if (t.length !in 2..MAX_ANSWER) return null
        if (BANNED.containsMatchIn(answer)) return null
        val allowed = numbersIn(sent)
        val bad = NUMBER.findAll(t).any { m ->
            val n = norm(m.value)
            val small = n.toLongOrNull()?.let { it in 0..31 || it in 2020..2100 } == true && !t.substring(0, m.range.first).endsWith("£")
            !small && n !in allowed
        }
        return if (bad) null else t
    }

    /** The turn Obi's [answer] makes: his checked words with [BY], or [UNCHECKED] / [NOTHING]. */
    fun turn(question: String, answer: String?, sent: List<String>): MoneyChatTurn {
        if (answer.isNullOrBlank()) return MoneyChatTurn(question, NOTHING, refused = true)
        val ok = check(answer, sent) ?: return MoneyChatTurn(question, UNCHECKED, refused = true)
        return MoneyChatTurn(question, ok, byObi = true)
    }
}
