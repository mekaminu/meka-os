package os.meka.core.domain

import kotlin.math.roundToLong

/**
 * Money redesign, Plan (mockup `docs/design/money/`, Plan tab, approved Meka 2026-10-25 09:30): **What if I save…**. He
 * slides a monthly amount (£25 steps), picks 3, 6, 12 or 24 months and no interest or a rate, and sees month-by-month bars,
 * the total and the month it's reached, what he'd put in and what interest adds, and Obi's line on whether it fits his
 * average month (what's left over after usual spending, from the forecast's whole months). Make it a goal turns it into
 * a £ goal in Goals with that month as its due day.
 *
 * Plain arithmetic with the inputs shown, monthly compounding (balance × (1 + rate/12) + amount, the first payment next
 * month); a sum, not a promise or advice. The rates are figures he picks to see the difference, not an offer: the line
 * says so. Nothing is stored or synced: the apps hold the slider and chips while Money is open and ask [view] on each
 * change, so dragging never rebuilds the money view. No AI, nothing sent.
 */
data class MoneySaveChip(val value: Int, val label: String, val on: Boolean, val spoken: String)

/** One month's bar: its height as a share of the last (0..1), the last one lit, and what a screen reader says. */
data class MoneySaveBar(val share: Double, val last: Boolean, val label: String)

/**
 * What if I save…: [amount] "£300" for [amountPence] on a slider from [MoneySave.MIN_PENCE] to [MoneySave.MAX_PENCE] in
 * [MoneySave.STEP_PENCE] steps; the [months] and [rates] chips; the [bars]; [total] "£1,812" reached [end] "by Apr 2027";
 * [split] (put in, interest, left each month); [obi] and [obiLit] (amber when it doesn't fit); [goalTitle] and [goalTarget]
 * for Make it a goal; [basis] the one small line under it.
 */
data class MoneySaveView(
    val head: String, val each: String, val amount: String, val amountPence: Long,
    val months: List<MoneySaveChip>, val rates: List<MoneySaveChip>, val bars: List<MoneySaveBar>,
    val total: String, val totalPence: Long, val end: String, val split: String, val obi: String, val obiLit: Boolean,
    val goalTitle: String, val goalTarget: String, val basis: String, val spoken: String,
)

/**
 * What the Plan tab needs from the money view to work out What if I save… on the device: [leftPence] his average month's
 * money left over (null before two whole months are in, or with no pay on the statements), [thisMonth] (year × 12 +
 * month − 1) and [startPence] where the slider starts.
 */
data class MoneySaveBase(val leftPence: Long?, val thisMonth: Int, val startPence: Long)

object MoneySave {
    const val HEAD = "WHAT IF I SAVE…"
    const val EACH = "Each month"
    const val MIN_PENCE = 2_500L
    const val MAX_PENCE = 200_000L
    const val STEP_PENCE = 2_500L
    const val DEFAULT_PENCE = 20_000L
    const val DEFAULT_MONTHS = 6
    const val MAKE_GOAL = "Make it a goal"
    val MONTHS: List<Int> = listOf(3, 6, 12, 24)
    /** Rates in hundredths of a percent a year: none, 2% and 4% (figures to compare, not offers). */
    val RATES_BP: List<Int> = listOf(0, 200, 400)
    const val BASIS = "Sums from your average month, worked out monthly · a rate here is one you pick to compare, not an offer · not financial advice"

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    private fun monthShort(m: Int): String = "${Recurrence.MONTH_SHORT[m % 12]} ${m / 12}"

    /** Pence snapped to the slider: £25 steps between £25 and £2,000. */
    fun snap(pence: Long): Long {
        val p = pence.coerceIn(MIN_PENCE, MAX_PENCE)
        return ((p.toDouble() / STEP_PENCE).roundToLong() * STEP_PENCE).coerceIn(MIN_PENCE, MAX_PENCE)
    }

    /** Where the slider starts: about half of what's left over in an average month, else £200. */
    fun start(leftPence: Long?): Long = if (leftPence == null || leftPence < 2 * MIN_PENCE) DEFAULT_PENCE else snap(leftPence / 2)

    /** The base for [MoneyView.save], from the forecast's left over and today. */
    fun base(leftPence: Long?, today: Long): MoneySaveBase = MoneySaveBase(leftPence, MoneyRules.monthOf(today), start(leftPence))

    fun months(m: Int): Int = if (m in MONTHS) m else DEFAULT_MONTHS
    fun rate(bp: Int): Int = if (bp in RATES_BP) bp else 0

    /** The balance after each month (pence, unrounded steps rounded at the end of each month): the first payment next month. */
    fun balances(amountPence: Long, months: Int, rateBp: Int): List<Long> {
        val r = rateBp / 10_000.0 / 12.0
        var b = 0.0
        return (1..months).map { b = b * (1 + r) + amountPence; b.roundToLong() }
    }

    fun rateLabel(bp: Int): String = if (bp == 0) "No interest" else "At ${pct(bp)} a year"

    /** The chip's short label (the cover screen fits three): "No interest", "At 4%". */
    fun rateChip(bp: Int): String = if (bp == 0) "No interest" else "At ${pct(bp)}"

    private fun pct(bp: Int): String = if (bp % 100 == 0) "${bp / 100}%" else "${bp / 100}.${(bp % 100).toString().padStart(2, '0').trimEnd('0')}%"

    /** Obi's line: whether [amountPence] a month fits his average month, and whether it's lit (it doesn't fit). */
    fun obi(amountPence: Long, leftPence: Long?): Pair<String, Boolean> {
        val amt = fmt(amountPence)
        if (leftPence == null) return "Once two whole months of payments are in, I can say whether $amt a month fits your usual month." to false
        if (leftPence < 1_000) {
            val more = MoneyForecast.roundTen(-leftPence)
            val said = if (more >= 1_000) "About ${fmt(more)} more goes out than comes in in an average month" else "About what comes in goes out in an average month"
            return "$said, so $amt a month would come from savings or the cards. Clear my cards first may cost you less." to true
        }
        val spare = MoneyForecast.roundTen(leftPence - amountPence)
        val left = fmt(MoneyForecast.roundTen(leftPence))
        return when {
            spare >= 40_000 -> "$amt a month still leaves about ${fmt(spare)} spare in an average month." to false
            spare >= 15_000 -> "Doable: it leaves about ${fmt(spare)} in an average month, so a dearer month (December) would be tight." to false
            spare >= 0 -> "Tight: about ${fmt(spare)} would be left in an average month, so one dear month could undo it. A little less would leave room." to true
            else -> "That's more than you usually have left ($left a month), so some months you'd be dipping into savings or the cards." to true
        }
    }

    /** What if I save [amountPence] a month for [months] at [rateBp], from [base]. */
    fun view(amountPence: Long, months: Int, rateBp: Int, base: MoneySaveBase): MoneySaveView {
        val amt = snap(amountPence)
        val n = months(months)
        val bp = rate(rateBp)
        val bal = balances(amt, n, bp)
        val total = bal.last()
        val paidIn = amt * n
        val interest = total - paidIn
        val endMonth = base.thisMonth + n
        val end = "by ${monthShort(endMonth)}"
        val bars = bal.mapIndexed { i, b ->
            MoneySaveBar(if (total > 0) b.toDouble() / total else 0.0, i == bal.lastIndex, "${monthShort(base.thisMonth + 1 + i)}: ${fmt(b)}")
        }
        val split = buildList {
            add("You put in ${fmt(paidIn)}")
            if (interest >= 50) add("interest about ${fmt(MoneyForecast.roundTen(interest).coerceAtLeast(100))}")
            base.leftPence?.takeIf { it >= 1_000 }?.let { add("left each month after usual spending: about ${fmt(MoneyForecast.roundTen(it - amt).coerceAtLeast(0))}") }
        }.joinToString(" · ")
        val (obi, lit) = obi(amt, base.leftPence)
        val monthChips = MONTHS.map { m ->
            MoneySaveChip(m, "$m months", m == n, "$m months" + if (m == n) ", selected" else "")
        }
        val rateChips = RATES_BP.map { r ->
            MoneySaveChip(r, rateChip(r), r == bp, rateLabel(r) + if (r == bp) ", selected" else "")
        }
        val totalText = fmt(roundPound(total))
        return MoneySaveView(
            HEAD, EACH, fmt(amt), amt, monthChips, rateChips, bars, totalText, total, end, split, obi, lit,
            goalTitle = "Save $totalText ${end}", goalTarget = totalText, basis = BASIS,
            spoken = "Save ${fmt(amt)} a month for $n months, ${rateLabel(bp).lowercase()}: $totalText $end. $split. $obi",
        )
    }

    /** Pence to the nearest pound (the total's headline). */
    fun roundPound(p: Long): Long = (p / 100.0).roundToLong() * 100

    /** The goal's due day: the last day of the month the saving reaches its total. */
    fun goalDueDay(months: Int, base: MoneySaveBase): Long {
        val m = base.thisMonth + months(months)
        val y = m / 12
        val mo = m % 12 + 1
        return CivilDate.toEpochDay(y, mo, CivilDate.lengthOfMonth(y, mo))
    }

    /** The horizon Goals files it under. */
    fun horizon(months: Int): GoalHorizon = when {
        months(months) <= 3 -> GoalHorizon.QUARTER
        else -> GoalHorizon.YEAR
    }

    fun goalSaid(title: String): String = "Made a goal: $title · it's in Goals; link it to your savings account under MONEY GOALS"
}
