package os.meka.core.domain

/**
 * Money: the payment sheet (Meka 2026-10-26 23:18, two screenshots: tapping a payment opened an unthemed white dropdown
 * with near-invisible light text). A tap on a payment now opens the mockup's sheet (docs/design/money, Main.dc.html):
 * the payee and amount, the day and account, category chips (the ones he uses most first, his own choices before the
 * rest, Income and Transfers last), then Shared · Always shared · Side hustle as toggles and Done. Picking a category
 * puts every payment from that payee in it, past and future (the rule [Money.setCategory] already keeps), and says
 * "Moved 14 payments from News ON The to Shopping" with Undo. Both apps draw the sheet from here.
 */
object MoneyPaymentSheet {
    const val CATEGORY_LABEL = "CATEGORY"
    const val MARKS_LABEL = "MARK IT"
    const val DONE = "Done"
    const val SHARED = "Shared"
    const val ALWAYS_SHARED = "Always shared"
    const val SIDE_HUSTLE = "Side hustle"

    /** Categories that aren't spending sit at the end of the chips, whatever their use. */
    private val LAST = setOf(MoneyCategory.INCOME.key, MoneyCategory.TRANSFERS.key)

    /**
     * Every category's key in chip order: the ones he has picked himself for a payee ([taught]) first, then the rest;
     * within each, the most used ([use]: how many of his payments are in it) first, ties in the usual order; Income and
     * Transfers always last.
     */
    fun order(use: Map<String, Int>, taught: Set<String>): List<String> {
        val all = MoneyCategory.entries.map { it.key }
        val index = all.withIndex().associate { (i, k) -> k to i }
        val spend = all.filter { it !in LAST }.sortedWith(
            compareBy<String> { it !in taught }
                .thenByDescending { use[it] ?: 0 }
                .thenBy { index[it] ?: 0 },
        )
        return spend + all.filter { it in LAST }
    }

    /** The chips for a payment in [current]: [order] (or the usual order before any view has one), its own one lit. */
    fun chips(order: List<String>, current: String): List<MoneySheetChip> {
        val keys = order.ifEmpty { MoneyCategory.entries.map { it.key } }
        return keys.mapNotNull { MoneyCategory.of(it) }.map { c ->
            val on = c.key == current
            MoneySheetChip(c.key, c.label, on, if (on) "${c.label}, chosen" else "Put it in ${c.label}")
        }
    }

    /** Under the payee: "Mon 26 Oct · HSBC current". */
    fun sub(row: MoneyTxnRow): String = listOf(row.day, row.account).filter { it.isNotBlank() }.joinToString(" · ")

    /** Under the chips: what picking one does. */
    fun ruleLine(row: MoneyTxnRow): String =
        if (row.taught) "${row.merchant} is always ${row.categoryLabel} · pick another to move every payment from them"
        else "Picking one moves every payment from ${row.merchant}, past and future"

    /** Shared (this payment), Always shared (every payment to this payee) and Side hustle (this payee). */
    fun toggles(row: MoneyTxnRow): List<MoneySheetToggle> = listOf(
        MoneySheetToggle(MoneySheetMark.PAYMENT_SHARED, SHARED, row.shared, if (row.shared) "Shared, on" else "Mark it shared"),
        MoneySheetToggle(MoneySheetMark.PAYEE_SHARED, ALWAYS_SHARED, row.sharedAlways,
            if (row.sharedAlways) "Always shared, on" else "Share every payment to ${row.merchant}"),
        MoneySheetToggle(MoneySheetMark.SIDE_HUSTLE, SIDE_HUSTLE, row.business,
            if (row.business) "Side hustle, on" else "Count ${row.merchant} as the side hustle's"),
    )

    /** The sheet's spoken heading. */
    fun spoken(row: MoneyTxnRow): String = "${row.merchant}, ${row.amount}, ${sub(row)}, ${row.categoryLabel}"

    /** The line after a pick: "Moved 14 payments from News ON The to Shopping", or "Costa Coffee is Eating out from now on". */
    fun movedLine(merchant: String, category: MoneyCategory, count: Int): String =
        if (count > 1) "Moved $count payments from $merchant to ${category.label}" else "$merchant is ${category.label} from now on"
}

/** One category chip on the payment sheet. */
data class MoneySheetChip(val key: String, val label: String, val on: Boolean, val spoken: String)

/** What a toggle on the payment sheet marks. */
enum class MoneySheetMark { PAYMENT_SHARED, PAYEE_SHARED, SIDE_HUSTLE }

/** One toggle on the payment sheet. */
data class MoneySheetToggle(val mark: MoneySheetMark, val label: String, val on: Boolean, val spoken: String)
