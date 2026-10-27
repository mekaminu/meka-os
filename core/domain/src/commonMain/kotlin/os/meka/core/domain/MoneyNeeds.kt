package os.meka.core.domain

/** On `money_rule`: true when every payment to this payer is a need, false a want; null follows its category ([MoneyNeeds]). */
object MoneyNeedFields {
    const val MERCHANT = "need"
}

/**
 * Money: needs and wants (Meka 2026-10-26 23:18, the payment sheet's Need toggle; the mockup's "Needs vs wants" view on
 * Spending, docs/design/money/Main.dc.html). Each spend category is a need or a want by default (groceries, bills,
 * transport, fuel, home, health, the kids and fees are needs; the rest, his own categories included, are wants). The
 * Need toggle on a payment marks its payer one way or the other, past and future, on both devices. Spending's view
 * chips switch the ring and rows between the categories and two lines: Needs and Wants, each with its biggest
 * categories named. Non-AI and pure, so the Fold and the Mac draw the same.
 */
object MoneyNeeds {
    const val NEED = "Need"
    const val VIEW_CATEGORIES = "Categories"
    const val VIEW_NEEDS = "Needs vs wants"
    const val NEEDS_KEY = "needs"
    const val WANTS_KEY = "wants"
    const val NEEDS = "Needs"
    const val WANTS = "Wants"

    /** The ring's tones for the two lines: the groceries gold and the shopping copper (the mockup's). */
    const val NEEDS_TONE = 0
    const val WANTS_TONE = 2

    /** Categories that are needs unless he says otherwise. */
    val NEED_CATEGORIES: Set<MoneyCategory> = setOf(
        MoneyCategory.GROCERIES, MoneyCategory.TRANSPORT, MoneyCategory.FUEL, MoneyCategory.BILLS, MoneyCategory.HOME,
        MoneyCategory.HEALTH, MoneyCategory.KIDS, MoneyCategory.FEES,
    )

    /** Whether [category] is a need before he marks anything (his own categories and Other are wants). */
    fun byDefault(category: MoneyCategory, own: Boolean = false): Boolean = !own && category in NEED_CATEGORIES

    /** A payment's need: his mark on the payer ([mark]) when he made one, else its category's default. */
    fun isNeed(category: MoneyCategory, mark: Boolean?, own: Boolean = false): Boolean = mark ?: byDefault(category, own)

    /** The line after the toggle: "Tesco counts as a need" / "Netflix counts as a want". */
    fun said(merchant: String, need: Boolean): String = "$merchant counts as a ${if (need) "need" else "want"}"

    /** The toggle's spoken line. */
    fun spoken(merchant: String, need: Boolean): String =
        if (need) "Need, on: $merchant counts as a need" else "Need, off: count $merchant as a need"

    /** The view chips over the ring, [needs] picked or not. */
    fun views(needs: Boolean): List<MoneyViewChip> = listOf(
        MoneyViewChip(VIEW_CATEGORIES, !needs, needs = false, spoken = if (!needs) "$VIEW_CATEGORIES, showing" else "Show $VIEW_CATEGORIES"),
        MoneyViewChip(VIEW_NEEDS, needs, needs = true, spoken = if (needs) "$VIEW_NEEDS, showing" else "Show $VIEW_NEEDS"),
    )

    /**
     * The Needs vs wants view for the month: [lines] one per spend payment with its shown label (a category's, or his
     * own one's) and whether it's a need; [spent] the month's spending. Each line names its three biggest categories
     * ("Groceries, Bills, Transport"); null when nothing was spent.
     */
    fun view(lines: List<MoneyNeedLine>, spent: Long): MoneyNeedsView? {
        val out = lines.filter { it.pence > 0 }
        if (out.isEmpty() || spent <= 0) return null
        val need = out.filter { it.need }
        val want = out.filterNot { it.need }
        val needP = need.sumOf { it.pence }
        val wantP = want.sumOf { it.pence }
        val total = (needP + wantP).coerceAtLeast(1)
        val biggest = maxOf(needP, wantP).coerceAtLeast(1)
        fun row(key: String, label: String, pence: Long, ls: List<MoneyNeedLine>, tone: Int, icon: MoneyIcon): MoneySpendRow {
            val pct = MoneySpending.pct(pence.toDouble() / total)
            val names = ls.groupBy { it.label }.mapValues { (_, l) -> l.sumOf { it.pence } }.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map { it.key }
            val note = when {
                names.isEmpty() -> "none this month"
                names.size <= 3 -> names.joinToString(", ")
                else -> names.take(3).joinToString(", ") + " and ${names.size - 3} more"
            }
            return MoneySpendRow(
                key, label, MoneyRules.fmt(pence), pct, if (pence > 0) (pence.toFloat() / biggest).coerceIn(0.02f, 1f) else 0f,
                note, MoneyTone.QUIET, tone, icon, "$label, ${MoneyRules.fmt(pence)}, $pct of spending, $note",
            )
        }
        val rows = listOf(
            row(NEEDS_KEY, NEEDS, needP, need, NEEDS_TONE, MoneyIcon.CART),
            row(WANTS_KEY, WANTS, wantP, want, WANTS_TONE, MoneyIcon.BAG),
        )
        val needSweep = needP.toFloat() / total
        val slices = buildList {
            if (needP > 0) add(MoneyRingSlice(NEEDS_KEY, NEEDS, 0f, needSweep, NEEDS_TONE))
            if (wantP > 0) add(MoneyRingSlice(WANTS_KEY, WANTS, needSweep, 1f - needSweep, WANTS_TONE))
        }
        return MoneyNeedsView(slices, rows)
    }

    /** Spending as the screen draws it: [v] with its ring and rows swapped for the Needs vs wants ones when [needs]. */
    fun shown(v: MoneySpendingView, needs: Boolean): MoneySpendingView {
        val n = v.needs ?: return v
        return if (needs) v.copy(slices = n.slices, rows = n.rows) else v
    }
}

/** One spend payment for [MoneyNeeds.view]: its pence out (positive), the label it shows under and whether it's a need. */
data class MoneyNeedLine(val label: String, val pence: Long, val need: Boolean)

/** One view chip over Spending's ring: [needs] whether it picks the Needs vs wants view. */
data class MoneyViewChip(val label: String, val on: Boolean, val needs: Boolean, val spoken: String)

/** Spending's Needs vs wants view: two slices and two rows ([MoneyNeeds.view]). */
data class MoneyNeedsView(val slices: List<MoneyRingSlice>, val rows: List<MoneySpendRow>)
