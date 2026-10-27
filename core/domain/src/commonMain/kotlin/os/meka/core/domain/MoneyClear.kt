package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Money redesign, Plan (mockup `docs/design/money/`, Plan tab, approved Meka 2026-10-25 09:30): **Clear my cards**. His
 * credit cards (marked on Accounts; charge cards are paid in full each month, so they're left out) with what each owes and
 * its rate; he slides one amount a month for all of them together and picks Highest interest first or Smallest balance
 * first, and sees when each card is clear, the month he's debt-free, the interest he'd still pay, what paying only the
 * minimums would mean, and Obi's line comparing the two orders and whether the amount fits his average month. Use this
 * plan puts "Clear my cards by …" in Goals with that month as its date.
 *
 * Where the numbers come from: what a card owes is its statement as he typed it, else its account's balance (as net worth
 * counts it). Its rate is the APR he typed in the card's sheet on Bills, else worked out from the last interest line on
 * the card's own statements over what it owes (× 12, "about"), else unknown (counted as 0% and said so). A 0% period
 * counts no interest until the month it ends. Each month: interest added, every card gets its minimum (2.5% of what it
 * owes, at least £25, as the mockup), and the rest goes to one card at a time in the chosen order. Plain arithmetic with
 * the inputs shown; a sum, not advice. Nothing stored or sent: the apps hold the slider and chips while Money is open and
 * ask [view] on each change, so dragging never rebuilds the money view. No AI.
 */
data class MoneyClearCard(
    val id: String, val name: String, val owedPence: Long,
    /** The rate in hundredths of a percent, or null when unknown. */
    val aprBp: Int?,
    /** Whether [aprBp] was worked out from an interest line rather than typed. */
    val estimated: Boolean,
    /** The month (year × 12 + month − 1) a 0% period ends, or null. */
    val promoEndMonth: Int? = null,
)

/**
 * What the Plan tab needs to work out Clear my cards on the device: the [cards] with a balance, [leftPence] his average
 * month's money left over (null before two whole months are in), [thisMonth] and the slider's range and start.
 */
data class MoneyClearBase(
    val cards: List<MoneyClearCard>, val leftPence: Long?, val thisMonth: Int,
    val minPence: Long, val maxPence: Long, val startPence: Long,
)

/** One card's row: [rate] "24.9% APR · clear by Mar 2027" ([rateLit] when its rate is unknown), [owed] "£4,200". */
data class MoneyClearRow(val id: String, val name: String, val rate: String, val rateLit: Boolean, val owed: String, val spoken: String)

/** A strategy chip: [MoneyClear.BY_RATE] or [MoneyClear.BY_SMALL]. */
data class MoneyClearChip(val key: String, val label: String, val on: Boolean, val spoken: String)

/**
 * Clear my cards for one amount and order: the [rows], [payLabel] "Pay each month, both cards" and [pay] "£600" on the
 * slider ([payPence]), the [orders] chips, the [free] "Mar 2027 · 17 mo" tile ([freeLit] when it never clears at this
 * pace) and [interest] tile, [vsMin] (paying only the minimums), [obi] and [obiLit], [goalTitle] for Use this plan, and
 * [basis] the small line under it.
 */
data class MoneyClearView(
    val head: String, val rows: List<MoneyClearRow>, val payLabel: String, val pay: String, val payPence: Long,
    val orders: List<MoneyClearChip>, val freeLabel: String, val free: String, val freeLit: Boolean,
    val interestLabel: String, val interest: String, val vsMin: String, val obi: String, val obiLit: Boolean,
    val goalTitle: String?, val basis: String, val spoken: String,
)

/** One run of the sums: the month each card clears (1-based, null when it doesn't within the cap), interest and months. */
data class MoneyClearRun(val clearMonth: List<Int?>, val interestPence: Long, val months: Int?)

object MoneyClear {
    const val HEAD = "CLEAR MY CARDS"
    const val BY_RATE = "rate"
    const val BY_SMALL = "small"
    const val USE = "Use this plan"
    const val FREE = "Debt-free"
    const val INTEREST = "Interest you'll pay"
    const val STEP_PENCE = 2_500L
    const val MIN_PAY_PENCE = 2_500L
    const val MIN_RATE_PERCENT = 2.5
    /** The sums stop after this many months ("not at this pace"). */
    const val CAP_MONTHS = 600
    const val BASIS = "What each card owes from its statement or balance; rates you typed in its sheet on Bills, else worked out from its last interest charge · minimums taken as 2.5% (at least £25) · sums, not advice: Navilife never pays anything"

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    private fun monthShort(m: Int): String = "${Recurrence.MONTH_SHORT[m % 12]} ${m / 12}"

    fun orderLabel(key: String): String = if (key == BY_SMALL) "Smallest balance first" else "Highest interest first"

    fun order(key: String): String = if (key == BY_SMALL) BY_SMALL else BY_RATE

    /** The minimum on a card owing [owed] pence: 2.5%, at least £25, never more than it owes. */
    fun minimum(owed: Double): Double = minOf(owed, maxOf(MIN_PAY_PENCE.toDouble(), owed * MIN_RATE_PERCENT / 100.0))

    /**
     * A card's rate from its statement lines: the latest interest charged on it within two months, over what it owes, × 12,
     * in hundredths of a percent (kept when between 1% and 60%), or null.
     */
    fun estimateApr(cardId: String, owedPence: Long, lines: List<MoneyCardLine>, today: Long): Int? {
        if (owedPence <= 0) return null
        val interest = lines.filter {
            it.account == cardId && it.pence < 0 && today - it.day in 0..MoneyCards.FEE_LOOK_BACK_DAYS &&
                INTEREST_LINE.containsMatchIn(it.text) && !REFUND.containsMatchIn(it.text)
        }.maxByOrNull { it.day } ?: return null
        val bp = ((-interest.pence).toDouble() * 12 / owedPence * 10_000).roundToInt()
        return bp.takeIf { it in 100..6_000 }?.let { (it / 10.0).roundToInt() * 10 }
    }

    private val INTEREST_LINE = Regex("""\binterest\b""", RegexOption.IGNORE_CASE)
    private val REFUND = Regex("""\b(refund|reversal|rebate)\b""", RegexOption.IGNORE_CASE)

    private fun snapUp(p: Long): Long = ceil(p.toDouble() / STEP_PENCE).toLong() * STEP_PENCE

    private fun snapNear(p: Long): Long = (p.toDouble() / STEP_PENCE).roundToLong() * STEP_PENCE

    /**
     * The base for [MoneyView.clear]: each credit card that owes something (its statement, else its account's balance as
     * owed) with its rate, from [cards] (marked on Accounts), [balances] (account id → pence) and the cards' statement
     * [lines]; null when no card owes anything.
     */
    fun base(
        cards: List<MoneyCard>, balances: Map<String, Long>, lines: List<MoneyCardLine>, leftPence: Long?, today: Long,
    ): MoneyClearBase? {
        val thisMonth = MoneyRules.monthOf(today)
        val list = cards.filter { !it.charge }.mapNotNull { c ->
            val owed = c.statementPence ?: balances[c.id]?.let { abs(it) } ?: return@mapNotNull null
            if (owed <= 0) return@mapNotNull null
            val est = if (c.aprBp == null) estimateApr(c.id, owed, lines, today) else null
            val promo = c.promoEndDay?.takeIf { it >= today }?.let(MoneyRules::monthOf)
            MoneyClearCard(c.id, c.name, owed, c.aprBp ?: est, estimated = c.aprBp == null && est != null, promoEndMonth = promo)
        }.sortedWith(compareByDescending<MoneyClearCard> { it.owedPence }.thenBy { it.name.lowercase() })
        if (list.isEmpty()) return null
        val total = list.sumOf { it.owedPence }
        val mins = snapUp(list.sumOf { minimum(it.owedPence.toDouble()).roundToLong() }).coerceAtLeast(MIN_PAY_PENCE)
        val max = maxOf(snapUp(total / 6).coerceIn(150_000L, 500_000L), mins + 10 * STEP_PENCE)
        val start = snapNear(total / 12).coerceIn(mins, max)
        return MoneyClearBase(list, leftPence, thisMonth, mins, max, start)
    }

    /** Pence snapped to [base]'s slider: £25 steps between its minimums and its top. */
    fun snap(pence: Long, base: MoneyClearBase): Long = snapNear(pence.coerceIn(base.minPence, base.maxPence)).coerceIn(base.minPence, base.maxPence)

    /**
     * The sums: [pay] pence a month across [cards] in [order]; [minimumsOnly] pays only each card's minimum. The first
     * payment is next month; interest is added each month first (none while a 0% period runs, from [thisMonth]).
     */
    fun run(cards: List<MoneyClearCard>, pay: Long, order: String, thisMonth: Int, minimumsOnly: Boolean = false): MoneyClearRun {
        val bal = cards.map { it.owedPence.toDouble() }.toMutableList()
        val done = MutableList<Int?>(cards.size) { null }
        var interest = 0.0
        for (mo in 1..CAP_MONTHS) {
            val month = thisMonth + mo
            for (i in cards.indices) {
                if (bal[i] <= 0.5) continue
                val c = cards[i]
                val rate = if (c.promoEndMonth != null && month <= c.promoEndMonth) 0 else (c.aprBp ?: 0)
                val add = bal[i] * rate / 10_000.0 / 12.0
                bal[i] += add
                interest += add
            }
            var left = pay.toDouble()
            val live = cards.indices.filter { bal[it] > 0.5 }
            for (i in live) {
                val m = minimum(bal[i])
                bal[i] -= m
                left -= m
            }
            if (!minimumsOnly) {
                val ordered = if (order == BY_SMALL) live.sortedWith(compareBy<Int>({ bal[it] }, { it }))
                else live.sortedWith(compareByDescending<Int> { cards[it].aprBp ?: 0 }.thenBy { bal[it] })
                for (i in ordered) {
                    if (left <= 0) break
                    val x = minOf(left, bal[i])
                    bal[i] -= x
                    left -= x
                }
            }
            for (i in cards.indices) if (done[i] == null && bal[i] <= 0.5) { bal[i] = 0.0; done[i] = mo }
            if (done.all { it != null }) break
        }
        val months = if (done.all { it != null }) done.maxOf { it!! } else null
        return MoneyClearRun(done, interest.roundToLong(), months)
    }

    private fun years(months: Int?): String = when {
        months == null -> "${CAP_MONTHS / 12}+ years"
        months < 24 -> "$months months"
        else -> "about ${(months / 12.0).roundToInt()} years"
    }

    /** Obi's line on the order and whether [pay] fits his average month, and whether it's lit. */
    fun obi(base: MoneyClearBase, pay: Long, order: String, plan: MoneyClearRun, other: MoneyClearRun): Pair<String, Boolean> {
        val parts = mutableListOf<String>()
        if (base.cards.size > 1) {
            val diff = MoneyForecast.roundTen(other.interestPence - plan.interestPence)
            if (abs(diff) < 1_000) parts += "Both orders cost about the same in interest here."
            else if (order == BY_RATE) {
                val first = base.cards.maxWith(compareBy<MoneyClearCard> { it.aprBp ?: 0 }.thenBy { -it.owedPence })
                parts += if (diff > 0) "Clearing ${first.name} first saves about ${fmt(diff)} in interest compared with smallest-first."
                else "Here smallest-first would cost about ${fmt(-diff)} less."
            } else {
                val first = base.cards.minBy { it.owedPence }
                parts += if (diff < 0) "Smallest-first clears ${first.name} sooner, but costs about ${fmt(-diff)} more in interest."
                else "Smallest-first clears ${first.name} sooner and costs about ${fmt(diff)} less here."
            }
        }
        var lit = plan.months == null
        if (plan.months == null) parts += "At ${fmt(pay)} a month the interest keeps up with the payments: slide it higher to see a date."
        val left = base.leftPence
        when {
            left == null -> parts += "Once two whole months of payments are in, I can say whether ${fmt(pay)} a month fits your usual month."
            left < pay -> { parts += "That's more than you usually have left (${fmt(MoneyForecast.roundTen(left.coerceAtLeast(0)))} a month), so some months it would come from savings."; lit = true }
            else -> parts += "${fmt(pay)} a month fits inside an average month, leaving about ${fmt(MoneyForecast.roundTen(left - pay))}."
        }
        base.cards.firstOrNull { it.aprBp == null }?.let { parts += "Type ${it.name}'s APR in its sheet on Bills for the true interest." }
        if (plan.interestPence >= 10_000) parts += "A 0% balance transfer could cut the interest; worth checking what you're offered."
        return parts.joinToString(" ") to lit
    }

    /** Clear my cards at [payPence] a month in [order], from [base]. */
    fun view(payPence: Long, order: String, base: MoneyClearBase): MoneyClearView {
        val pay = snap(payPence, base)
        val ord = order(order)
        val plan = run(base.cards, pay, ord, base.thisMonth)
        val other = run(base.cards, pay, if (ord == BY_RATE) BY_SMALL else BY_RATE, base.thisMonth)
        val mins = run(base.cards, 0, ord, base.thisMonth, minimumsOnly = true)
        val rows = base.cards.mapIndexed { i, c ->
            val rate = when {
                c.aprBp == null -> "APR ? · tap it on Bills to add"
                c.estimated -> "about ${MoneyCards.aprText(c.aprBp)} APR"
                else -> "${MoneyCards.aprText(c.aprBp)} APR"
            } + (c.promoEndMonth?.let { " · 0% to ${monthShort(it)}" } ?: "")
            val clear = plan.clearMonth[i]?.let { "clear by ${monthShort(base.thisMonth + it)}" } ?: "not clear at this pace"
            MoneyClearRow(c.id, c.name, "$rate · $clear", c.aprBp == null, fmt(c.owedPence), "${c.name}, owes ${fmt(c.owedPence)}, $rate, $clear")
        }
        val payLabel = when (base.cards.size) {
            1 -> "Pay each month"
            2 -> "Pay each month, both cards"
            else -> "Pay each month, all ${base.cards.size} cards"
        }
        val free = plan.months?.let { "${monthShort(base.thisMonth + it)} · $it mo" } ?: "Not at this pace"
        val interest = fmt(MoneyForecast.roundTen(plan.interestPence).coerceAtLeast(0))
        val vsMin = "Paying only the minimums: ${years(mins.months)} and about ${fmt(MoneyForecast.roundTen(mins.interestPence))} in interest."
        val (obi, lit) = obi(base, pay, ord, plan, other)
        val orders = if (base.cards.size < 2) emptyList() else listOf(BY_RATE, BY_SMALL).map { k ->
            MoneyClearChip(k, orderLabel(k), k == ord, orderLabel(k) + if (k == ord) ", selected" else "")
        }
        val goal = plan.months?.let { "Clear my cards by ${monthShort(base.thisMonth + it)}" }
        return MoneyClearView(
            HEAD, rows, payLabel, fmt(pay), pay, orders, FREE, free, plan.months == null, INTEREST, interest, vsMin, obi, lit,
            goalTitle = goal, basis = BASIS,
            spoken = "$payLabel ${fmt(pay)}, ${orderLabel(ord).lowercase()}: debt-free $free, interest $interest. $vsMin $obi",
        )
    }

    /** The goal's due day: the last day of the month the cards clear, or null when they don't at [payPence]. */
    fun goalDueDay(payPence: Long, order: String, base: MoneyClearBase): Long? {
        val m = run(base.cards, snap(payPence, base), order(order), base.thisMonth).months ?: return null
        val month = base.thisMonth + m
        val y = month / 12
        val mo = month % 12 + 1
        return CivilDate.toEpochDay(y, mo, CivilDate.lengthOfMonth(y, mo))
    }

    fun horizon(dueDay: Long, today: Long): GoalHorizon = when {
        dueDay - today <= 92 -> GoalHorizon.QUARTER
        dueDay - today <= 366 -> GoalHorizon.YEAR
        else -> GoalHorizon.SOMEDAY
    }

    const val NOT_AT_PACE = "At this amount the cards don't clear: slide it higher first."

    fun goalSaid(title: String): String = "Made a goal: $title · it's in Goals"
}
