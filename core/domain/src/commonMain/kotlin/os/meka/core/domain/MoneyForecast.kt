package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Money manager, slice 4 (2026-10-16): **the forecast** (board item 5) on the Money screen's current month, behind his
 * fingerprint like the rest. From the whole months of statements he imported (up to the last 6), his average month: what
 * comes in, what goes out, what's left over; then where that leaves him in 6 and 12 months at the same pace. Rules only,
 * no AI; an average, never a promise, and not financial advice.
 *
 * **What-ifs** (in memory on the device he's using, never stored or synced; cleared when Money locks): halve one or more
 * of his three biggest everyday categories ("Halve Eating out · +£90 a month"), and an extra amount a month he types
 * himself (a side hustle, trading: his own figure, "£200", "£50 a week", "£1,200 a year"). The 6- and 12-month lines then
 * say how much more the what-ifs would leave him.
 *
 * Whole months: a month counts when it has statements from its first days to its last (the first month of the
 * statements only if they start by the 3rd; the latest only if they reach the 25th), the current month never. Two whole
 * months are needed. Transfers between his own accounts (a card repayment, savings) are neither in nor out.
 */
data class MoneyCutChip(val key: String, val label: String, val line: String, val on: Boolean, val spoken: String)

/** One horizon: [label] "By Apr 2027 · 6 months", [amount] "+£1,920" (signed), [line] what it is; [lit] when negative. */
data class MoneyForecastRow(val label: String, val amount: String, val line: String, val lit: Boolean, val spoken: String)

/**
 * The forecast block: [head], then either [notReady] (why there's no forecast yet) or [pace] ("In about £2,500 a month ·
 * out about £2,180"), [left] ("About £320 a month left over", [leftLit] when more goes out), the [rows] for 6 and 12
 * months, the what-if [cuts] and [extraLine] ("+£200 a month coming in · your own figure"), and [basis].
 */
data class MoneyForecastView(
    val head: String, val notReady: String?, val pace: String?, val left: String?, val leftLit: Boolean,
    val rows: List<MoneyForecastRow>, val cuts: List<MoneyCutChip>, val extraLine: String?, val basis: String?,
    /** Trading tracker, slice 11: "Trading as it's gone · +£120 a month" from his Hyperliquid results, and where it's from. */
    val trading: MoneyCutChip? = null, val tradingLine: String? = null,
    /** Plan's What if I save… ([MoneySave]): his average month's money left over in pence, before any what-if; null with no pay in. */
    val leftPence: Long? = null,
)

/**
 * Trading tracker, slice 11: his actual trading result a month in pence (after fees and funding, at the ECB's dollar rate)
 * and where it comes from ("September and August", "the last 30 days").
 */
data class MoneyTradingPace(val pencePerMonth: Long, val from: String)

/** What-ifs held by the facade while Money is open: categories halved and an extra amount a month (pence). */
data class MoneyWhatIf(val cuts: Set<String> = emptySet(), val extraPence: Long = 0L) {
    val any: Boolean get() = cuts.isNotEmpty() || extraPence != 0L
}

object MoneyForecast {
    const val HEAD = "FORECAST AT YOUR PACE"
    const val WHAT_IF = "WHAT IF"
    const val EXTRA_HINT = "Extra a month (side hustle, trading): £200"
    const val NOT_READY = "A forecast needs two whole months of statements · import a few months from each account to see one."
    const val NOT_ADVICE = "your average month, not a promise · not financial advice"
    const val EXTRA_BAD = "Type an amount, e.g. £200, £50 a week or £1,200 a year."
    const val EXTRA_CLEARED = "What-if extra cleared"
    const val MAX_EXTRA_PENCE = 10_000_000L
    const val MAX_CUTS = 3
    const val CUT_MIN_PENCE = 2_000L
    /** Slice 11: the trading what-if's key, toggled like a cut (never a category's key). */
    const val TRADING_KEY = "trading"
    const val TRADING_LABEL = "Trading as it's gone"

    /** Spending that can't simply be halved (bills, fees) or says too little (other). */
    private val NOT_CUTTABLE = setOf(MoneyCategory.BILLS, MoneyCategory.FEES, MoneyCategory.OTHER, MoneyCategory.SUBSCRIPTIONS)

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /** Pence rounded to the nearest £10 (£1 under £10). */
    fun roundTen(p: Long): Long = if (abs(p) < 1_000) (p / 100.0).roundToLong() * 100 else (p / 1_000.0).roundToLong() * 1_000

    private fun signed(p: Long): String = if (p < 0) "−" + fmt(-p) else "+" + fmt(p)

    private fun monthShort(m: Int): String = "${Recurrence.MONTH_SHORT[m % 12]} ${m / 12}"

    /** The whole months (oldest first, up to 6) before [today]'s month that the [lines] cover end to end. */
    fun wholeMonths(lines: List<MoneyLine>, today: Long): List<Int> {
        if (lines.isEmpty()) return emptyList()
        val thisMonth = MoneyRules.monthOf(today)
        val first = CivilDate.fromEpochDay(lines.minOf { it.day })
        val last = CivilDate.fromEpochDay(lines.maxOf { it.day })
        val firstWhole = first.year * 12 + first.month - 1 + (if (first.day <= 3) 0 else 1)
        val lastWhole = (last.year * 12 + last.month - 1 - (if (last.day >= 25) 0 else 1)).coerceAtMost(thisMonth - 1)
        val withLines = lines.map { MoneyRules.monthOf(it.day) }.toSet()
        return (firstWhole..lastWhole).filter { it in withLines }.takeLast(6)
    }

    /**
     * "£200", "200", "£50 a week", "£1,200 a year", "£200 a month" → pence a month; blank → 0; null when it isn't an
     * amount (or over £100,000 a month).
     */
    fun parseExtra(text: String): Long? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return 0L
        val m = Regex("""^[+]?\s*£?\s*([\d,]+(?:\.\d{1,2})?)\s*(?:a|per|/|each)?\s*(week|wk|w|month|mo|m|year|yr|y|pa|annum)?$""").matchEntire(t) ?: return null
        val base = MoneyCsv.pence(m.groupValues[1]) ?: return null
        if (base <= 0) return null
        val monthly = when (m.groupValues[2]) {
            "week", "wk", "w" -> base * 52 / 12
            "year", "yr", "y", "pa", "annum" -> base / 12
            else -> base
        }
        return monthly.takeIf { it <= MAX_EXTRA_PENCE }
    }

    fun extraSaid(pence: Long): String = if (pence == 0L) EXTRA_CLEARED else "What if: ${fmt(pence)} a month more coming in"

    /**
     * Trading tracker, slice 11: what trading has actually made him a month, from the followed Hyperliquid accounts (every
     * one must have the ECB's rate): the last finished month's review, averaged with the month before's when every account
     * has both; else the last 30 days' trades. Null without accounts, before the server has read them, or with no trades,
     * fees or funding at all (nothing to go on).
     */
    fun tradingPace(wallets: List<TradingWallet>, nowMs: Long, cal: LocalCalendar): MoneyTradingPace? {
        if (wallets.isEmpty() || wallets.any { w -> w.gbpPerUsdMicros?.let { TradingTracker.rateOk(it) } != true }) return null
        fun pence(w: TradingWallet, stats: List<TradingCoinStats>) = TradingTracker.pence(stats.sumOf { it.netCents }, w.gbpPerUsdMicros!!)
        fun active(stats: List<TradingCoinStats>) = stats.any { it.trades > 0 || it.feesCents != 0L || it.fundingCents != 0L }
        val month = TradingMonth.lastFinished(nowMs, cal)
        if (wallets.all { it.reviewMonth == month.key }) {
            val cur = wallets.sumOf { pence(it, it.review) }
            val bothMonths = wallets.all { it.reviewPrev != null }
            val anything = wallets.any { active(it.review) || (bothMonths && active(it.reviewPrev.orEmpty())) }
            if (anything) {
                return if (bothMonths) {
                    val prev = wallets.sumOf { pence(it, it.reviewPrev.orEmpty()) }
                    MoneyTradingPace((cur + prev) / 2, "${month.name} and ${month.previous.name}")
                } else MoneyTradingPace(cur, month.name)
            }
        }
        if (wallets.all { it.tradesAtMs != null } && wallets.any { active(it.trades) }) {
            return MoneyTradingPace(wallets.sumOf { pence(it, it.trades) }, "the last 30 days")
        }
        return null
    }

    /** Slice 11: the line under the trading what-if. */
    fun tradingLine(pace: MoneyTradingPace): String =
        "Trading: your Hyperliquid result a month from ${pace.from}, after fees and funding, at today's dollar rate · on top of your statements · not a promise"

    /** The forecast for [today] from every line, with [whatIf] applied; null with no lines at all. */
    fun view(lines: List<MoneyLine>, today: Long, whatIf: MoneyWhatIf = MoneyWhatIf(), trading: MoneyTradingPace? = null): MoneyForecastView? {
        if (lines.isEmpty()) return null
        val months = wholeMonths(lines, today)
        if (months.size < 2) return MoneyForecastView(HEAD, NOT_READY, null, null, false, emptyList(), emptyList(), null, null)
        val n = months.size
        val inMonths = lines.filter { MoneyRules.monthOf(it.day) in months }
        val income = inMonths.filter { it.category == MoneyCategory.INCOME }.sumOf { it.pence } / n
        val spendBy = inMonths.filter { it.category.spend }.groupBy { it.category }.mapValues { (_, l) -> -l.sumOf { it.pence } / n }
        val out = spendBy.values.sum().coerceAtLeast(0)
        val net = income - out
        val pace = if (income > 0) "In about ${fmt(roundTen(income))} a month · out about ${fmt(roundTen(out))}"
            else "Out about ${fmt(roundTen(out))} a month · no money in on these statements"
        val leftRounded = roundTen(net)
        val left = when {
            income <= 0 -> "Import the account your pay goes into to see what's left over"
            abs(leftRounded) < 1_000 -> "About what comes in goes out each month"
            net > 0 -> "About ${fmt(leftRounded)} a month left over"
            else -> "About ${fmt(-leftRounded)} a month more going out than coming in"
        }
        // What-ifs: his three biggest everyday categories, each halved on a tap.
        val cuttable = spendBy.filter { (c, p) -> c !in NOT_CUTTABLE && p >= CUT_MIN_PENCE }.entries
            .sortedByDescending { it.value }.take(MAX_CUTS)
        val cuts = cuttable.map { (c, p) ->
            val save = roundTen(p / 2)
            val on = c.key in whatIf.cuts
            MoneyCutChip(c.key, "Halve ${c.label}", "+${fmt(save)} a month", on,
                "Halve ${c.label}, ${fmt(save)} a month more" + if (on) ", on" else "")
        }
        // Slice 11: trading at the pace it has gone, his tap (a loss counts too).
        val tradingOn = trading != null && TRADING_KEY in whatIf.cuts
        val tradingChip = trading?.let { t ->
            val shown = signed(roundTen(t.pencePerMonth)) + " a month"
            MoneyCutChip(TRADING_KEY, TRADING_LABEL, shown, tradingOn, "$TRADING_LABEL, $shown" + if (tradingOn) ", on" else "")
        }
        val saved = cuttable.filter { it.key.key in whatIf.cuts }.sumOf { it.value / 2 } + whatIf.extraPence +
            (if (tradingOn) trading!!.pencePerMonth else 0L)
        val thisMonth = MoneyRules.monthOf(today)
        val rows = listOf(6, 12).map { h ->
            val at = roundTen(net * h)
            val label = "By ${monthShort(thisMonth + h)} · $h months"
            val base = when {
                income <= 0 -> "spent at this pace"
                at >= 0 -> "put by at your pace"
                else -> "more out than in at your pace"
            }
            val shown = if (income <= 0) "−" + fmt(roundTen(out * h)) else signed(at)
            val more = if (saved != 0L) " · ${signed(roundTen(saved * h))} with your what-ifs" else ""
            MoneyForecastRow(label, shown, base + more, lit = income > 0 && at < 0, spoken = "$label, $shown, $base$more")
        }
        val span = if (n == 1) monthShort(months.first()) else "${Recurrence.MONTH_SHORT[months.first() % 12]} – ${monthShort(months.last())}"
        val basis = "From $span ($n whole months) · $NOT_ADVICE"
        val extraLine = if (whatIf.extraPence > 0) "${signed(whatIf.extraPence)} a month coming in · your own figure" else null
        return MoneyForecastView(HEAD, null, pace, left, income > 0 && leftRounded <= -1_000, rows, cuts, extraLine, basis,
            tradingChip, trading?.let { tradingLine(it) }, leftPence = net.takeIf { income > 0 })
    }
}
