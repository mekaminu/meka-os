package os.meka.core.domain

/**
 * Trading tracker, slice 12: TRADING in dollars or pounds, his choice (the build plan's completeness line: "a GBP/USD
 * display choice with a free rate source"). Hyperliquid counts in US dollars; in pounds every amount on TRADING (equity,
 * P&L, open positions' unrealised P&L, the curve, YOUR TRADES, the monthly review) is shown at the European Central
 * Bank's daily rate that the server already writes on each account ([TradingWallet.gbpPerUsdMicros], slice 5). Prices
 * (entry, now, liquidation) stay in dollars, as Hyperliquid quotes them; the tax export keeps both (slice 10).
 *
 * The choice is one more field on the synced trading-alerts `context_mode` entity ([TradingAlertFields.CURRENCY],
 * "gbp" / "usd", absent = dollars; ADR-008 addendum, no migration), set by [TradingAlerts.with] with kind [KIND], so both
 * apps follow. Pounds wait until every read account carries a believable rate: until then TRADING stays in dollars and
 * says why.
 */
class TradingCcy private constructor(val gbpPerUsdMicros: Long?) {
    val pounds: Boolean get() = gbpPerUsdMicros != null

    /** "$1,234.56" or "£975.30". */
    fun amt(cents: Long): String = gbpPerUsdMicros?.let { MoneyRules.signedPlain(TradingTracker.pence(cents, it)) } ?: TradingTracker.usd(cents)

    /** "+$120.50" / "−$40.00" / "$0.00", or the same in pounds. */
    fun signed(cents: Long): String = gbpPerUsdMicros?.let { r ->
        val p = TradingTracker.pence(cents, r)
        if (p > 0) MoneyRules.signed(p) else MoneyRules.signedPlain(p)
    } ?: TradingTracker.signedUsd(cents)

    override fun toString() = if (pounds) "TradingCcy(GBP)" else "TradingCcy(USD)"

    companion object {
        val USD = TradingCcy(null)

        /**
         * Pounds when [pounds] is chosen and every read account (or, before any is read, every account) carries a
         * believable rate: the newest day's rate (they come from the same ECB file). Otherwise dollars.
         */
        fun of(wallets: List<TradingWallet>, pounds: Boolean): TradingCcy = rate(wallets)?.takeIf { pounds }?.let { TradingCcy(it) } ?: USD

        /** The rate pounds would use, or null while an account has none. */
        fun rate(wallets: List<TradingWallet>): Long? {
            val read = wallets.filter { it.equityCents != null }.ifEmpty { wallets }
            if (read.isEmpty()) return null
            val rates = read.map { w -> w.gbpPerUsdMicros?.takeIf { TradingTracker.rateOk(it) }?.let { it to (w.fxDay ?: 0L) } }
            if (rates.any { it == null }) return null
            return rates.filterNotNull().maxByOrNull { it.second }!!.first
        }
    }
}

/** SHOW IN under TRADING's total: the label, a line saying what it means, and the Dollars · Pounds chips. */
data class TradingCurrencyView(val label: String, val line: String, val chips: List<TradingAlertChip>)

object TradingCurrencyRules {
    /** The chips' kind, for [TradingAlerts.with] and the facade's existing setTradingAlert. */
    const val KIND = "currency"
    const val USD = "usd"
    const val GBP = "gbp"
    const val LABEL = "Show in"

    fun switched(pounds: Boolean): String =
        if (pounds) "Trading in pounds · at the European Central Bank's dollar rate · on both devices"
        else "Trading in US dollars, as Hyperliquid counts it · on both devices"

    /** What SHOW IN says, given his choice ([pounds]) and what [ccy] the view could use. */
    fun line(wallets: List<TradingWallet>, pounds: Boolean, ccy: TradingCcy): String = when {
        !pounds -> "In US dollars, as Hyperliquid counts it · Pounds shows it at the European Central Bank's daily rate"
        ccy.gbpPerUsdMicros != null -> "In pounds at ${TradingTracker.rateLine(ccy.gbpPerUsdMicros)} · the European Central Bank's rate · prices stay in dollars"
        wallets.none { it.equityCents != null } -> "In pounds once Hyperliquid's figures and today's dollar rate are read · dollars until then"
        else -> "Showing dollars until today's dollar rate is read · then pounds"
    }

    /** Null with no accounts (nothing to show in either). */
    fun view(wallets: List<TradingWallet>, pounds: Boolean, ccy: TradingCcy): TradingCurrencyView? {
        if (wallets.isEmpty()) return null
        val chips = listOf(USD to "Dollars", GBP to "Pounds").map { (key, label) ->
            val on = (key == GBP) == pounds
            TradingAlertChip(KIND, key, label, on, (if (key == GBP) "Show trading in pounds" else "Show trading in US dollars") + if (on) ", chosen" else "")
        }
        return TradingCurrencyView(LABEL, line(wallets, pounds, ccy), chips)
    }
}
