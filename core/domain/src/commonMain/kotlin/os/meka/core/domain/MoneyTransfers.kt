package os.meka.core.domain

/**
 * Money bugs (A1): money moving between his own accounts is a transfer, never spending or income.
 *
 * A card repayment shows twice: "HSBC −£767.72" on the bank and "PAYMENT THANK YOU +£767.72" on the card. Counted as
 * they read, the bank side was £767.72 of "Other" spending and the card side £767.72 "in". This finds such moves:
 *  - **a pair**: money out on one of his accounts and the same amount (±1p) in on another of his accounts within
 *    [PAIR_DAYS] days, closest days first, each line in at most one pair;
 *  - **a payee that is one of his own accounts**: money out or in whose words name another of his accounts' banks
 *    ("Hsbc" on the Revolut account when one of his accounts is "HSBC Credit card").
 * Only lines whose words don't already point to a shop or bill can be a transfer (a £50 Tesco shop and a £50 refund
 * elsewhere stay as they are), money in that reads as pay ("SALARY", "HMRC") never is, and his own correction for a
 * merchant always wins. Pure; no I/O.
 */
object MoneyTransfers {
    const val PAIR_DAYS = 5L
    const val TOLERANCE_PENCE = 1L

    /** Words in an account's name that say nothing about the bank ("Current account", "My credit card"). */
    private val GENERIC = setOf(
        "my", "the", "current", "account", "credit", "card", "cards", "debit", "joint", "personal", "business", "savings",
        "saver", "main", "bills", "bank", "gb", "uk", "isa", "easy", "access", "everyday", "premier", "plus", "club",
    )

    /** The words of his account names that name a bank or card ("hsbc" from "HSBC Credit card"), per account. */
    fun bankWords(accounts: List<MoneyAccount>): Map<String, Set<String>> = accounts.associate { a ->
        a.id to a.name.lowercase().split(Regex("[^a-z]+"))
            .filter { it.length >= 3 && it !in GENERIC }
            .take(2).toSet()
    }

    /** The ids of [txns] (his lines, a statement and its bank account already counted once) that are his own moves. */
    fun ids(accounts: List<MoneyAccount>, txns: List<MoneyTxn>, rules: Map<String, MoneyCategory>): Set<String> {
        if (txns.isEmpty()) return emptySet()
        val out = HashSet<String>()
        val words = bankWords(accounts)
        val candidates = txns.filter { t -> t.pence != 0L && neutral(t, rules) }
        // A payee that is another of his accounts.
        if (accounts.size > 1) for (t in candidates) {
            val text = " " + t.text.lowercase().replace(Regex("[^a-z]+"), " ") + " "
            if (words.any { (id, ws) -> id != t.account && ws.any { w -> text.contains(" $w ") } }) out += t.id
        }
        // Pairs: money out on one account, the same in on another, nearest in days.
        val ins = candidates.filter { it.pence > 0 }.groupBy { it.pence }
        val used = HashSet<String>()
        for (o in candidates.filter { it.pence < 0 }.sortedWith(compareBy<MoneyTxn> { it.day }.thenBy { it.id })) {
            val amount = -o.pence
            val match = (amount - TOLERANCE_PENCE..amount + TOLERANCE_PENCE).asSequence()
                .flatMap { ins[it].orEmpty().asSequence() }
                .filter { it.account != o.account && it.id !in used && kotlin.math.abs(it.day - o.day) <= PAIR_DAYS }
                .minWithOrNull(compareBy<MoneyTxn> { kotlin.math.abs(it.day - o.day) }.thenBy { kotlin.math.abs(it.pence - amount) }.thenBy { it.id })
                ?: continue
            used += match.id
            out += o.id
            out += match.id
        }
        return out
    }

    /** Whether [t]'s words leave it free to be a transfer: no correction of his to anything else, no shop, no pay. */
    private fun neutral(t: MoneyTxn, rules: Map<String, MoneyCategory>): Boolean {
        val key = MoneyCategoryRules.merchantKey(t.text)
        rules[key]?.let { return it == MoneyCategory.TRANSFERS }
        return when (MoneyCategoryRules.categoryOf(t.text, t.pence)) {
            MoneyCategory.TRANSFERS, MoneyCategory.OTHER -> true
            MoneyCategory.INCOME -> !MoneyCategoryRules.incomeWords(t.text)
            else -> false
        }
    }

    /** A line's category with his moves known: his correction, else a transfer if [moves] has it, else its words'. */
    fun categoryOf(t: MoneyTxn, key: String, rules: Map<String, MoneyCategory>, moves: Set<String>): MoneyCategory =
        rules[key] ?: if (t.id in moves) MoneyCategory.TRANSFERS else MoneyCategoryRules.categoryOf(t.text, t.pence)
}
