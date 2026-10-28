package os.meka.core.domain

/**
 * Trading's month as four small tiles (approved Money mockup, README item 5): under the equity card, two by two, the
 * closed profit, the funding paid (or received), the fees and the winning trades ("14 of 23"). The mockup says
 * "OCTOBER SO FAR"; the server adds trades up over the last [TradingTracker.TRADES_DAYS] days (YOUR TRADES' window), so
 * the label says that, honestly. Once the tiles show, YOUR TRADES drops the summary and the rows they already say and
 * keeps "After fees and funding", the averages, the best and worst and each coin ([trades]). Read-only figures, no advice.
 *
 * Also README item 5: ALERTS folds into one row ([alertsRow]: how many are on over the short list of each) that opens a
 * sheet with the sentence and the choices as before. Words only; nothing new is stored.
 */
object TradingTiles {
    const val HEAD = "LAST ${TradingTracker.TRADES_DAYS} DAYS"
    const val CLOSED = "Closed profit"
    const val FUNDING_PAID = "Funding paid"
    const val FUNDING_RECEIVED = "Funding received"
    const val FEES = "Fees"
    const val WINS = "Winning trades"
    const val NONE_CLOSED = "None closed"

    /** The tiles across [wallets]' read trades; null until the server has read trades for any of them, or with none at all. */
    fun view(wallets: List<TradingWallet>, ccy: TradingCcy = TradingCcy.USD): TradingTilesView? {
        val read = wallets.filter { it.tradesAtMs != null }
        if (read.isEmpty()) return null
        val stats = read.flatMap { it.trades }
        if (stats.none { it.trades > 0 || it.feesCents != 0L || it.fundingCents != 0L }) return null
        val all = stats.reduce { a, b -> a + b }
        fun up(c: Long): Boolean? = when { c > 0 -> true; c < 0 -> false; else -> null }
        val tiles = listOf(
            TradingTile(CLOSED, ccy.signed(all.realisedCents), up(all.realisedCents)),
            TradingTile(if (all.fundingCents > 0) FUNDING_RECEIVED else FUNDING_PAID, ccy.signed(all.fundingCents), up(all.fundingCents)),
            TradingTile(FEES, ccy.signed(-all.feesCents), up(-all.feesCents)),
            TradingTile(WINS, if (all.trades == 0) NONE_CLOSED else "${all.wins} of ${all.trades}", null),
        )
        val spoken = "Last ${TradingTracker.TRADES_DAYS} days. " + tiles.joinToString(". ") { "${it.label} ${it.value}" }
        return TradingTilesView(HEAD, tiles, spoken)
    }

    /**
     * YOUR TRADES as it shows beside the tiles: the summary and the closed, fees and funding rows leave (the tiles say
     * them), "After fees and funding" stays. Without tiles it's YOUR TRADES as it was.
     */
    fun trades(t: TradingView): TradingTradesView? {
        val tr = t.trades ?: return null
        if (t.tiles == null) return tr
        return tr.copy(summary = null, lines = tr.lines.filter { it.label == AFTER })
    }

    private const val AFTER = "After fees and funding"

    // ALERTS as one row.
    const val ALERTS_SHEET = "Alerts"
    const val ALERTS_DONE = "Done"

    /** ALERTS' one row: "3 of 4 on" over "Near liquidation 10% · a drop 10% · a big order off · funding 0.5%". */
    fun alertsRow(a: TradingAlertsView): TradingAlertsRow {
        val groups = listOf(
            "Near liquidation" to a.liqChips, "a drop" to a.dropChips, "a big order" to a.fillChips, "funding" to a.fundingChips,
        ).filter { it.second.isNotEmpty() }
        val parts = groups.map { (name, chips) ->
            val on = chips.firstOrNull { it.on }
            name to (on?.takeIf { !isOff(it) }?.label)
        }
        val n = parts.count { it.second != null }
        val title = when (n) {
            0 -> "All alerts off"
            parts.size -> "All ${parts.size} alerts on"
            else -> "$n of ${parts.size} alerts on"
        }
        val line = parts.joinToString(" · ") { (name, v) -> "$name ${v ?: "off"}" }.replaceFirstChar { it.uppercase() }
        return TradingAlertsRow(title, line, "$title. $line. Opens a sheet")
    }

    private fun isOff(c: TradingAlertChip) = c.key == "off"
}

/** One of the four tiles: [label] over [value]; [up] lights a gain in the accent (a cost or a count stays plain). */
data class TradingTile(val label: String, val value: String, val up: Boolean?)

/** The month's tiles under their small caps [head]. */
data class TradingTilesView(val head: String, val tiles: List<TradingTile>, val spoken: String)

/** ALERTS' one row that opens the sheet with the choices. */
data class TradingAlertsRow(val title: String, val line: String, val spoken: String)
