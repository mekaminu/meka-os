package os.meka.core.domain

/**
 * Money review (Meka 2026-10-26 22:12, 16 screenshots: "Other" was 55% of the month, 52 payments). Under Spending's
 * category rows, "52 payments without a category · Sort them" opens a quick sorter: one payee at a time (the biggest
 * first), with the payment sheet's category chips; a pick puts every payment from that payee in it, past and future
 * (the rule [Money.setCategory] already keeps, with Undo), and the next payee comes up at once. Skip leaves one for
 * later. Both apps draw it from here.
 *
 * A payment is "without a category" when it is money out that the rules put in Other, he hasn't put its payee in Other
 * himself, and it isn't in one of his own categories.
 */
data class MoneySortItem(
    /** The payee's merchant key: what the sorter skips by. */
    val key: String,
    /** The payee's newest payment this month: the sheet opens on it, and a pick moves every payment from the payee. */
    val row: MoneyTxnRow,
    val count: Int,
    /** "−£84.20" out to this payee this month. */
    val total: String,
    /** "14 payments this month · −£84.20". */
    val line: String,
)

/** Spending's "N payments without a category" with the payees to sort, biggest first. */
data class MoneyUnsortedView(val count: Int, val line: String, val items: List<MoneySortItem>, val spoken: String)

/** One payee's month for [MoneySorter.view]: its newest payment's row, how many payments and the pence out. */
data class MoneySortPayee(val key: String, val row: MoneyTxnRow, val count: Int, val pence: Long)

object MoneySorter {
    const val ACTION = "Sort them"
    const val SKIP = "Skip"
    const val HEAD = "SORT YOUR PAYMENTS"
    const val ALL_DONE = "All sorted · every payment this month has a category"

    /** When the sorter runs out: [ALL_DONE], or "Sorted for now · 2 payees left for later" when he skipped some. */
    fun doneLine(skipped: Int): String =
        if (skipped <= 0) ALL_DONE else "Sorted for now · $skipped payee${if (skipped == 1) "" else "s"} left for later"

    /** "1 payment without a category", "52 payments without a category". */
    fun line(count: Int): String = "$count payment${if (count == 1) "" else "s"} without a category"

    /**
     * The view from the month's [payees] without a category; null when there are none. Biggest total first, then the
     * most payments, then the name.
     */
    fun view(payees: List<MoneySortPayee>): MoneyUnsortedView? {
        val live = payees.filter { it.count > 0 && it.pence > 0 }
        if (live.isEmpty()) return null
        val items = live.sortedWith(
            compareByDescending<MoneySortPayee> { it.pence }.thenByDescending { it.count }.thenBy { it.row.merchant.lowercase() },
        ).map { p ->
            val total = "−" + MoneyRules.fmt(p.pence)
            MoneySortItem(p.key, p.row, p.count, total, "${p.count} payment${if (p.count == 1) "" else "s"} this month · $total")
        }
        val n = items.sumOf { it.count }
        val l = line(n)
        return MoneyUnsortedView(n, l, items, "$l. $ACTION")
    }

    /** The payee the sorter shows: the first not yet [skipped] or [done] (picked and still on its way); null when none are left. */
    fun current(v: MoneyUnsortedView?, skipped: Set<String>, done: Set<String> = emptySet()): MoneySortItem? =
        v?.items?.firstOrNull { it.key !in skipped && it.key !in done }

    /** How many payees are left to sort, counting the one shown. */
    fun left(v: MoneyUnsortedView?, skipped: Set<String>, done: Set<String> = emptySet()): Int =
        v?.items?.count { it.key !in skipped && it.key !in done } ?: 0

    /** Over the sheet: "SORT YOUR PAYMENTS · 12 LEFT", or the head alone for the last one. */
    fun head(left: Int): String = if (left > 1) "$HEAD · $left LEFT" else HEAD

    /** Under the payee: "14 payments this month · −£84.20 · Mon 26 Oct · HSBC current". */
    fun sub(item: MoneySortItem): String = listOf(item.line, item.row.account).filter { it.isNotBlank() }.joinToString(" · ")

    /** What a screen reader says for the sorter's heading. */
    fun spoken(item: MoneySortItem, left: Int): String =
        "${item.row.merchant}, ${item.line}, $left to sort, pick a category"
}
