package os.meka.core.domain

/**
 * Money: his own categories in Plan (the payment item's last slice, Meka 2026-10-26 23:18 (4): "used by Spending's
 * ring, budgets, Plan and Obi's ideas"). Underneath, a payee in one of his categories counts as
 * [MoneyCategory.OTHER], which Plan's older reckonings skip (Other says too little to halve or to be "over usual").
 * Here his categories are found again from the lines, so that:
 * - Obi's money check ([MoneyCheck]) can say "Vape 12× in 30 days" (a want he buys often) and "Vape over usual this
 *   month", each with Set a cap and the payments behind it;
 * - the forecast's what-ifs ([MoneyForecast]) offer "Halve Vape" beside his biggest everyday categories.
 *
 * Nothing else changes: totals, the forecast's pace and every built-in idea stay as they were.
 */
data class MoneyOwnPlan(
    val own: List<MoneyOwnCategory> = emptyList(),
    /** Merchant key → the id of his category it is in ([Money.ownRules]). */
    val ownRules: Map<String, String> = emptyMap(),
    /** Merchant key → his need (true) or want (false) mark ([MoneyNeeds]). */
    val needMerchants: Map<String, Boolean> = emptyMap(),
) {
    private val byId = own.associateBy { it.id }

    val any: Boolean get() = own.isNotEmpty() && ownRules.isNotEmpty()

    /** The category of his that [l] is in: only a line that is Other underneath, like Spending ([Money.view]). */
    fun of(l: MoneyLine): MoneyOwnCategory? =
        if (l.category != MoneyCategory.OTHER) null else ownRules[l.merchant]?.let { byId[it] }

    /** Whether [l] counts as a need: his mark on the payee, else a want (his own categories are wants by default). */
    fun need(l: MoneyLine): Boolean = MoneyNeeds.isNeed(l.category, needMerchants[l.merchant], own = true)

    /** His spending lines grouped by his category, in [own]'s order (money out only). */
    fun spendBy(lines: List<MoneyLine>): List<Pair<MoneyOwnCategory, List<MoneyLine>>> {
        if (!any) return emptyList()
        val g = lines.filter { it.pence < 0 }.groupBy { of(it)?.id }
        return own.mapNotNull { c -> g[c.id]?.takeIf { it.isNotEmpty() }?.let { c to it } }
    }

    companion object {
        /** The key an idea or a cut of his category carries ("own-k1a2…"; ids keep to [MoneyCheck.validId]). */
        fun ideaKey(c: MoneyOwnCategory): String = "own-" + c.id
    }
}
