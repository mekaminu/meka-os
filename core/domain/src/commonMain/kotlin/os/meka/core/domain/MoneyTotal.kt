package os.meka.core.domain

/**
 * Money's mockup check, README item 7 (2026-10-28; mockup `docs/design/money/`, tab Accounts): Accounts opens on one
 * total card as the design draws it — "Across 6 accounts and trading" over the total, large, and one breakdown line
 * ("Cash £5,924 · trading £6,512 · owed on cards £6,686") — in place of the NET WORTH label and its "You have … · you owe
 * …" and set-up sentences. The month-by-month line, the change since last month, "Partial · …" and the Outside these
 * accounts row stay in the same card under it (his features the mockup predates).
 *
 * Built from the [MoneyWorthView] both apps already have, so the Fold and the Mac say the same. Words only; nothing new
 * is stored and no figure is worked out here that net worth doesn't already count.
 */
data class MoneyTotalCard(
    /** "Across 6 accounts and trading", "Across 1 account", "Your money". */
    val head: String,
    /** "£5,750", "−£640"; null while nothing is counted yet (then [empty] says what to do, in a line). */
    val total: String?,
    val negative: Boolean,
    /** "Cash £5,924 · trading £6,512 · owed on cards £6,686 · outside £38,800"; null with nothing counted. */
    val breakdown: String?,
    /** "Add each account's balance to see one total" before anything is counted. */
    val empty: String?,
    val spoken: String,
)

object MoneyTotal {
    const val EMPTY = "Add each account's balance to see one total"
    const val NONE = "Your money"

    /** "Across 6 accounts and trading", "Across 1 account", "Across trading" (only trading followed). */
    fun head(accounts: Int, trading: Int): String = when {
        accounts == 0 && trading == 0 -> NONE
        accounts == 0 -> "Across trading"
        else -> "Across $accounts account${if (accounts == 1) "" else "s"}" + if (trading > 0) " and trading" else ""
    }

    /** The breakdown's parts in the mockup's order; a part shows only when there's something in it. */
    fun breakdown(w: MoneyWorthView): String? {
        if (w.total == null) return null
        val parts = mutableListOf<String>()
        val accounts = w.rows.any { it.kind == MoneyWorth.ACCOUNT && it.amount.isNotEmpty() }
        if (accounts) parts += "Cash ${MoneyWorth.signed(w.cashPence)}"
        if (w.tradingCount > 0) parts += "trading ${MoneyWorth.signed(w.tradingPence)}"
        if (w.rows.any { it.kind == MoneyWorth.CARD && it.amount.isNotEmpty() }) parts += "owed on cards ${MoneyRules.fmt(w.cardsOwePence)}"
        if (w.rows.any { it.kind == MoneyWorth.TYPED }) parts += "outside ${MoneyWorth.signed(w.typedPence)}"
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
    }

    /** The card; null before Money has a net worth block at all. */
    fun card(w: MoneyWorthView?): MoneyTotalCard? {
        w ?: return null
        val head = head(w.accountCount, w.tradingCount)
        val line = breakdown(w)
        val spoken = if (w.total != null) {
            "Total ${w.total}, ${head.replaceFirstChar { it.lowercase() }}." + (line?.let { " $it." } ?: "") + (w.missingLine?.let { " $it." } ?: "")
        } else "$head. $EMPTY."
        return MoneyTotalCard(head, w.total, w.totalNegative, line, if (w.total == null) EMPTY else null, spoken)
    }
}
