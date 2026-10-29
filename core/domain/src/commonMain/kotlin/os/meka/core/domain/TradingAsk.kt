package os.meka.core.domain

/**
 * Obi sees everything, slice 5 (the board's "Trading summary (read-only, behind the same unlock)"): **"how's my trading
 * going?"** on the orb (Live's money look-up, [LiveToolRules.MONEY_TOOL]) and typed Ask, answered on the device from
 * what Navilife's server already writes for each followed public address ([TradingWallet]): the account's value, the
 * day's, week's and month's P&L, the open positions with their distance to liquidation, the last
 * [TradingTracker.TRADES_DAYS] days' trades (as Money → Trading's tiles say them) and a drop from this month's high.
 *
 * The same rules as Money's answers: only while Money is unlocked (behind his fingerprint); never the AI's own figures
 * (the sentence is built here and Obi says it without changing a number); figures only, never advice; and nothing here
 * can trade (the hard constraint: Navilife never holds trading credentials, it reads public addresses).
 */
object TradingAskRules {
    const val EMPTY = "No trading account is followed yet · add your Hyperliquid public address (never a key) in Money → Trading."
    const val NOT_READ = "Navilife hasn't read your Hyperliquid account yet · it reads it at least hourly; look again soon."
    const val LOCKED = "Trading's figures are in Money, behind your fingerprint. Unlock it and I'll answer from your Hyperliquid account, on this device only."
    const val NOT_ADVICE = " Figures only · not financial advice."
    /** Positions named at most (the rest are counted). */
    const val POSITIONS_SAID = 3
    /** A drop from this month's high smaller than this isn't worth a sentence. */
    const val DROP_SAID = 0.01

    private val HOW_TO = Regex("""^\s*(how (do|should|can) i|how to|where (do|can|is)|what does|explain|help)\b""", RegexOption.IGNORE_CASE)
    private val ABOUT = Regex(
        """\b(trading|my trades|hyperliquid|perps?|liquidation|leverage|(open|my|what) positions?|positions? (are |is )?(open|i have)|p ?& ?l|pnl|funding (rate|paid|cost|fees?)|(my|the) (btc|eth|sol|bitcoin|ether) (long|short|position)|(am i|i'?m) (up|down) on (crypto|trading|hyperliquid))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "trading standards", "trading hours" aren't his trading. */
    private val NOT_TRADING = Regex("""\btrading (standards|hours|estate|name)\b""", RegexOption.IGNORE_CASE)

    /** Whether [question] asks about his trading (a how-to about Money → Trading stays with the help). */
    fun isAbout(question: String): Boolean {
        val q = question.trim()
        if (q.isEmpty() || HOW_TO.containsMatchIn(q) || NOT_TRADING.containsMatchIn(q)) return false
        return ABOUT.containsMatchIn(q)
    }

    /**
     * The answer from [wallets] (read figures only), amounts in [ccy] (his dollars or pounds choice); [nowMs] says how
     * fresh the read is. Never empty.
     */
    fun answer(wallets: List<TradingWallet>, ccy: TradingCcy, nowMs: Long): String {
        if (wallets.isEmpty()) return EMPTY
        val read = wallets.filter { it.equityCents != null }
        if (read.isEmpty()) return NOT_READ
        val parts = mutableListOf<String>()

        // 1. The account's value, and how fresh it is.
        val equity = read.sumOf { it.equityCents ?: 0L }
        val where = if (read.size > 1) " across ${read.size} accounts" else ""
        val at = read.mapNotNull { it.readAtMs }.maxOrNull()
        val fresh = at?.let { " (read ${ago(nowMs - it)})" } ?: ""
        parts += "On Hyperliquid$fresh you hold ${ccy.amt(equity)}$where."

        // 2. P&L over the day, week and month (Hyperliquid's own figures), each only when read.
        fun sum(f: (TradingWallet) -> Long?): Long? = read.mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()
        val pnl = listOfNotNull(
            sum { it.pnlDayCents }?.let { "${ccy.signed(it)} today" },
            sum { it.pnlWeekCents }?.let { "${ccy.signed(it)} this week" },
            sum { it.pnlMonthCents }?.let { "${ccy.signed(it)} this month" },
        )
        if (pnl.isNotEmpty()) parts += "P&L: ${pnl.joinToString(", ")}."

        // 3. Open positions, nearest liquidation first.
        val positions = read.flatMap { it.positions }.sortedBy { it.liquidationDistance ?: Double.MAX_VALUE }
        if (positions.isEmpty()) parts += "No positions are open."
        else {
            val named = positions.take(POSITIONS_SAID).joinToString("; ") { p ->
                val side = if (p.long) "long" else "short"
                val lev = p.leverage?.takeIf { it > 0 }?.let { " ${it}×" } ?: ""
                val liq = p.liquidationDistance?.let { d -> if (d < 0.01) ", less than 1% from liquidation" else ", ${TradingTracker.pct(d)} from liquidation" } ?: ""
                "${p.coin} $side$lev$liq, ${ccy.signed(p.unrealisedCents)} unrealised"
            }
            val more = (positions.size - POSITIONS_SAID).takeIf { it > 0 }?.let { " and $it more" } ?: ""
            val n = if (positions.size == 1) "1 position is open" else "${positions.size} positions are open"
            parts += "$n: $named$more."
        }

        // 4. A drop from this month's high, at a level worth saying.
        read.mapNotNull { w -> w.drawdownShare?.takeIf { it >= DROP_SAID }?.let { w to it } }.maxByOrNull { it.second }?.let { (w, s) ->
            val whose = if (read.size > 1) w.label else "The account"
            parts += "$whose is ${TradingTracker.pct(s)} below this month's high."
        }

        // 5. The window's trades, as Money → Trading's tiles say them.
        TradingTiles.view(read, ccy)?.let { t ->
            val tiles = t.tiles.associate { it.label to it.value }
            val wins = tiles[TradingTiles.WINS]
            val won = when {
                wins == null -> null
                wins == TradingTiles.NONE_CLOSED -> "no trades closed"
                else -> "$wins trades won"
            }
            val rest = t.tiles.filter { it.label != TradingTiles.WINS }.joinToString(", ") { "${it.label.lowercase()} ${it.value}" }
            parts += "Last ${TradingTracker.TRADES_DAYS} days: " + listOfNotNull(won, rest.ifEmpty { null }).joinToString(", ") + "."
        }

        return parts.joinToString(" ") + NOT_ADVICE
    }

    /** "just now", "12 min ago", "3 h ago". */
    fun ago(ms: Long): String {
        val min = (ms.coerceAtLeast(0) / 60_000L)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            else -> "${min / 60} h ago"
        }
    }
}
