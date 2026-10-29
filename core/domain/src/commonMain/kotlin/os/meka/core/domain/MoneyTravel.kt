package os.meka.core.domain

/**
 * Money manager (17), slice 1 (2026-10-17): **paying abroad** — which of his accounts and cards charged foreign fees and
 * which didn't, from his own statements over the last year, and the one to use abroad. Rules only, no AI, nothing sent;
 * insight on his own numbers, never a product suggestion (not financial advice).
 *
 * - A **payment abroad** is money out whose statement line carries another currency ("EUR 25.00", "€25.00", "USD",
 *   "VISA RATE 1.1534", "exchange rate"), never a fee line itself.
 * - A **foreign fee** is a fees line saying so ("Non-sterling transaction fee", "foreign transaction fee", "FX fee"; the
 *   same words the watchdog's fees use, [MoneyWatch.isForeignFee]).
 * - Per account with either in the last [WINDOW_DAYS]: "No foreign fees · 6 payments abroad · £312" (good, brass) or
 *   "£9.32 in foreign fees on 6 payments · about 3% of £312".
 * - The **tip**: the account with no fees and the most payments abroad, against the one that charged the most.
 * - **Abroad now** (the device's time zone isn't UK time) or a trip on Trips that is on or starts within [TRIP_AHEAD_DAYS]:
 *   [MoneyTravelView.banner] leads the Money screen, lit ("You're abroad · pay with Monzo: no foreign fees there before").
 *
 * Nothing stored: the view is worked out from the lines each time (no entity, no migration).
 */
data class MoneyTravelLine(val account: String, val day: Long, val pence: Long, val text: String, val category: MoneyCategory)

/** One account's year abroad: [good] when it charged no foreign fees on payments abroad. */
data class MoneyTravelRow(val id: String, val name: String, val line: String, val good: Boolean, val spoken: String)

/**
 * PAYING ABROAD on the Money screen (this month only): [tip] which to use, [rows] each account, [banner] when he's abroad
 * or a trip is near (shown at the top, lit). Null view when there's nothing abroad in a year and he isn't going.
 */
data class MoneyTravelView(
    val head: String,
    val tip: String?,
    val tipLit: Boolean,
    val rows: List<MoneyTravelRow>,
    val banner: String?,
    val foot: String,
    val spoken: String,
) {
    fun rowAt(i: Int): MoneyTravelRow = rows[i]
}

object MoneyTravel {
    const val HEAD = "PAYING ABROAD"
    const val WINDOW_DAYS = 365
    const val TRIP_AHEAD_DAYS = 14L
    const val FOOT = "From your own statements over the last year: payments in another currency and the foreign fees charged for them. Your card's terms say what it charges · not financial advice."
    const val NOTHING = "Nothing paid abroad in your statements in the last year, so there's no card to compare yet · your card's terms say what it charges abroad."

    /** [FOOT] / [NOTHING] saying where the lines really came from (Money bugs A4, the last places). */
    fun foot(source: MoneySource): String =
        if (source == MoneySource.FILES) FOOT else FOOT.replaceFirst("From your own statements", source.from)

    fun nothing(source: MoneySource): String = NOTHING.replaceFirst("your statements", source.held)

    private val CURRENCY = Regex(
        """\b(EUR|USD|CHF|SEK|NOK|DKK|PLN|CZK|HUF|RON|BGN|ISK|TRY|AED|JPY|CNY|AUD|CAD|NZD|THB|MXN|ZAR|INR|SGD|HKD|MAD|EGP|BRL)\s?\d|\d\s?(EUR|USD|CHF|SEK|NOK|DKK|PLN|CZK|HUF|RON|BGN|ISK|TRY|AED|JPY|CNY|AUD|CAD|NZD|THB|MXN|ZAR|INR|SGD|HKD|MAD|EGP|BRL)\b|[€$¥]\s?\d|\d\s?[€$¥]|\b(visa|mc|mastercard|exchange|fx|conversion) rate\b|@\s?\d+\.\d{3,}""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [text] is a payment in another currency (and not a fee line). */
    fun isAbroad(text: String): Boolean = CURRENCY.containsMatchIn(text) && !MoneyWatch.isForeignFee(text)

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun n(k: Int, one: String, many: String = one + "s") = "$k ${if (k == 1) one else many}"

    /** "about 3%", "about 2.8%": the fee as a share of what was paid abroad. */
    fun rate(fee: Long, spent: Long): String? {
        if (spent <= 0 || fee <= 0) return null
        val tenths = (fee * 1000 + spent / 2) / spent
        return if (tenths % 10 == 0L) "about ${tenths / 10}%" else "about ${tenths / 10}.${tenths % 10}%"
    }

    private data class Stats(val id: String, val name: String, val n: Int, val spent: Long, val fees: Long, val feeN: Int)

    /**
     * The view for [today]: [names] each account's name; [abroadNow] when the device is outside UK time; [trip] a trip on
     * or starting within [TRIP_AHEAD_DAYS] (its name and first day).
     */
    fun view(
        lines: List<MoneyTravelLine>, names: Map<String, String>, today: Long,
        abroadNow: Boolean = false, trip: Pair<String, Long>? = null, source: MoneySource = MoneySource.FILES,
    ): MoneyTravelView? {
        val from = today - WINDOW_DAYS + 1
        val recent = lines.filter { it.day in from..today && it.pence < 0 && it.account in names }
        val stats = recent.groupBy { it.account }.mapNotNull { (acc, ls) ->
            val abroad = ls.filter { isAbroad(it.text) && it.category.spend && it.category != MoneyCategory.FEES }
            val fees = ls.filter { MoneyWatch.isForeignFee(it.text) }
            if (abroad.isEmpty() && fees.isEmpty()) null
            else Stats(acc, names.getValue(acc), abroad.size, -abroad.sumOf { it.pence }, -fees.sumOf { it.pence }, fees.size)
        }
        val going = abroadNow || trip?.let { it.second <= today + TRIP_AHEAD_DAYS } == true
        if (stats.isEmpty() && !going) return null

        val good = stats.filter { it.n > 0 && it.fees == 0L }.sortedWith(compareByDescending<Stats> { it.n }.thenBy { it.name.lowercase() })
        val bad = stats.filter { it.fees > 0 }.sortedWith(compareByDescending<Stats> { it.fees }.thenBy { it.name.lowercase() })
        val best = good.firstOrNull()
        val worst = bad.firstOrNull()

        val rows = (good + bad + stats.filter { it !in good && it !in bad }).map { s ->
            val line = if (s.fees == 0L) "No foreign fees · ${n(s.n, "payment")} abroad · ${fmt(s.spent)}"
            else listOfNotNull(
                "${fmt(s.fees)} in foreign fees" + (if (s.n > 0) " on ${n(s.n, "payment")}" else " · ${n(s.feeN, "fee")}"),
                rate(s.fees, s.spent)?.let { "$it of ${fmt(s.spent)}" },
            ).joinToString(" · ")
            MoneyTravelRow(s.id, s.name, line, s.fees == 0L && s.n > 0, "${s.name}, $line")
        }

        val worstSaid = worst?.let { w -> "${w.name} charged ${fmt(w.fees)} in fees" + (rate(w.fees, w.spent)?.let { " ($it)" } ?: "") }
        val tip = when {
            best != null && worst != null ->
                "Abroad, pay with ${best.name}: no foreign fees on your ${n(best.n, "payment")} abroad in the last year. $worstSaid."
            best != null -> "Abroad, pay with ${best.name}: no foreign fees on your ${n(best.n, "payment")} abroad in the last year."
            worst != null -> {
                val total = bad.sumOf { it.fees }
                "Every account you've paid with abroad charged foreign fees: ${fmt(total)} in the last year. A card with no foreign fees would have saved that."
            }
            else -> nothing(source)
        }
        val use = best?.let { "pay with ${it.name}: no foreign fees there before" }
            ?: worst?.let { "your cards charged foreign fees before" + (rate(it.fees, it.spent)?.let { r -> " ($r)" } ?: "") }
        val banner = when {
            !going -> null
            abroadNow -> "You're abroad" + (use?.let { " · $it" } ?: " · nothing paid abroad in ${source.held} yet")
            else -> {
                val (name, start) = trip!!
                val whenSaid = when (val d = start - today) {
                    in Long.MIN_VALUE..0L -> "$name now"
                    1L -> "$name tomorrow"
                    else -> "$name in $d days"
                }
                whenSaid + (use?.let { " · if it's abroad, $it" } ?: " · nothing paid abroad in ${source.held} yet")
            }
        }
        val spoken = listOfNotNull(banner, tip).joinToString(". ")
        return MoneyTravelView(HEAD, tip, best != null || going, rows, banner, foot(source), spoken)
    }

    /** Ask's answer to "which card should I use abroad?": the tip, then each account's line. */
    fun answer(v: MoneyTravelView?, source: MoneySource = MoneySource.FILES): String {
        if (v == null) return nothing(source)
        val lines = v.rows.take(MoneyAskRules.SHOWN).joinToString(" ") { "${it.name}: ${it.line}." }
        return listOfNotNull(v.banner?.let { "$it." }, v.tip, lines.ifEmpty { null }).joinToString(" ") + source.notAdvice
    }

    /** The trip that matters for [today]: on now, or the next one starting within [TRIP_AHEAD_DAYS] (name and first day). */
    fun tripNear(trips: List<TripItem>, today: Long): Pair<String, Long>? =
        trips.filter { it.endDay >= today && it.startDay <= today + TRIP_AHEAD_DAYS }.minByOrNull { it.startDay }?.let { it.name to it.startDay }
}
