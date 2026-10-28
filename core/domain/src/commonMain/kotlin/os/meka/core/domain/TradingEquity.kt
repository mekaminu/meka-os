package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Trading's equity card as the approved mockup (the Trading mockups in docs/design/money; README item 5, 2026-10-28): the
 * card leads the tab with its own label inside ("Hyperliquid · Main 0xab12…ab12"), the Read-only mark, the value large
 * with about what it is in the other currency beside it ("≈ £6,512", a tap shows Trading in that currency on both
 * devices), how it changed over the chosen range ("+$610 (7.8%) over the month", up in brass), the P&L line for that
 * range and the range chips 1D · 1W · 1M, then one grey line (all time and when it was read). The old separate P&L card,
 * the Dollars · Pounds row and the Today / This week / This month / All time rows fold into it.
 *
 * Only what the server already read is used: the day, week and month P&L Hyperliquid reports and the curve of day ends
 * (a month and a day, so no 3M chip: it would draw what isn't there). The range is this device's choice, not synced.
 */
data class TradingRange(
    val key: String, val label: String, val change: String?, val up: Boolean?, val points: List<MoneyWorthPoint>, val spoken: String,
)

data class TradingEquityCard(
    /** "Hyperliquid · Main 0xab12…ab12", or "Hyperliquid · 2 accounts". */
    val label: String,
    val total: String,
    /** "≈ £6,512" (or "≈ $8,420" while in pounds); null without the ECB's rate. */
    val other: String?,
    /** The currency key a tap on [other] switches to ([TradingCurrencyRules.GBP] / [TradingCurrencyRules.USD]). */
    val swapKey: String?,
    val otherSpoken: String?,
    val ranges: List<TradingRange>,
    /** "All time +$1,234 · updated 2 min ago". */
    val foot: String?,
    val spoken: String,
)

object TradingEquity {
    const val LABEL = "Hyperliquid"
    const val DAY = "1D"
    const val WEEK = "1W"
    const val MONTH = "1M"
    /** The mockup opens on the month. */
    const val DEFAULT = MONTH
    val KEYS = listOf(DAY, WEEK, MONTH)

    /** The range [key] picks (the month for anything else), with its chips' on state. */
    fun range(c: TradingEquityCard, key: String): TradingRange = c.ranges.firstOrNull { it.key == key } ?: c.ranges.first { it.key == DEFAULT }

    /** The chips under the line: label, whether chosen, what a screen reader says. */
    fun chips(c: TradingEquityCard, key: String): List<TradingRangeChip> {
        val on = range(c, key).key
        return c.ranges.map { r -> TradingRangeChip(r.key, r.label, r.key == on, r.spoken + if (r.key == on) ", chosen" else "") }
    }

    /** "+$610 (7.8%)": the change and its share of what the account was worth at the range's start (no share without one). */
    fun change(cents: Long, equityCents: Long, ccy: TradingCcy): String {
        val base = equityCents - cents
        val share = if (base > 0) " (" + pct1(cents.toDouble() / base) + ")" else ""
        return ccy.signed(cents) + share
    }

    /** "7.8%", "−2.1%", "0%" (one place under 100%). */
    fun pct1(share: Double): String {
        val tenths = (share * 1000).roundToLong()
        if (tenths == 0L) return "0%"
        val sign = if (tenths < 0) "−" else ""
        val a = abs(tenths)
        return if (a >= 1000) "$sign${(a + 5) / 10}%" else "$sign${a / 10}.${a % 10}%"
    }

    /** "≈ £6,512" from pence, "≈ $8,420" from cents: whole units, as the mockup. */
    fun about(minor: Long, pounds: Boolean): String {
        val whole = (abs(minor) + 50) / 100
        val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        return "≈ " + (if (minor < 0) "−" else "") + (if (pounds) "£" else "$") + grouped
    }

    /**
     * The card for [wallets] already read ([read]), [equity] their total; null until one is read. [curve] is the P&L
     * line in [ccy]; [updated] says when the newest read was.
     */
    fun card(
        wallets: List<TradingWallet>, read: List<TradingWallet>, equity: Long?, ccy: TradingCcy, curve: TradingCurveView?, updated: String?,
    ): TradingEquityCard? {
        if (equity == null || read.isEmpty()) return null
        fun sum(f: (TradingWallet) -> Long?): Long? = read.mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()
        val label = if (wallets.size == 1) wallets[0].let { "$LABEL · ${it.label} ${TradingTracker.short(it.address)}" }
        else "$LABEL · ${wallets.size} accounts"
        val total = ccy.amt(equity)
        val pence = read.map { it.equityPence }.takeIf { l -> l.all { it != null } }?.sumOf { it!! }
        val other = when {
            ccy.pounds -> about(equity, pounds = false)
            pence != null -> about(pence, pounds = true)
            else -> null
        }
        val swap = other?.let { if (ccy.pounds) TradingCurrencyRules.USD else TradingCurrencyRules.GBP }
        val otherSpoken = other?.let { "About ${it.removePrefix("≈ ")}. Shows trading in " + (if (ccy.pounds) "US dollars" else "pounds") }
        val points = curve?.points.orEmpty()
        val ranges = listOf(
            Triple(DAY, sum { it.pnlDayCents }, "today") to points.takeLast(2),
            Triple(WEEK, sum { it.pnlWeekCents }, "this week") to points.takeLast(8),
            Triple(MONTH, sum { it.pnlMonthCents }, "over the month") to points,
        ).map { (r, pts) ->
            val (key, cents, words) = r
            val change = cents?.let { change(it, equity, ccy) + " " + words }
            val up = cents?.let { when { it > 0 -> true; it < 0 -> false; else -> null } }
            TradingRange(key, key, change, up, pts.takeIf { it.size >= 2 }.orEmpty(),
                when (key) { DAY -> "One day"; WEEK -> "One week"; else -> "One month" } + (change?.let { ": $it" } ?: ""))
        }
        val all = sum { it.pnlAllCents }
        val foot = listOfNotNull(all?.let { "All time ${ccy.signed(it)}" }, updated?.let { "updated $it" })
            .joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
        val month = ranges.first { it.key == MONTH }
        val spoken = listOfNotNull("$label. Trading equity $total", other?.let { "about ${it.removePrefix("≈ ")}" }, month.change, foot)
            .joinToString(". ")
        return TradingEquityCard(label, total, other, swap, otherSpoken, ranges, foot, spoken)
    }
}

/** One range chip under the equity card's line. */
data class TradingRangeChip(val key: String, val label: String, val on: Boolean, val spoken: String)
