package os.meka.core.domain

/**
 * Trading tracker, slice 6: a trading line on Today and in the morning brief — whether he's up or down over the last
 * day and how many positions are open, with **no amounts** (the figures stay behind Money's fingerprint, as the alerts
 * do). Read from what Navilife's server already writes for each public address ([TradingWallet]); nothing new is read or
 * sent and nothing here can trade.
 *
 * - Today's header: "Trading · up today · 2 positions open" (quiet), "Trading · ETH long 8% from liquidation" or
 *   "Trading · Main 22% below this month's high" (lit, at his alert levels). Nothing when no address is followed, the
 *   last read is older than [STALE_MS], or there's nothing to say (no open position and a flat day).
 * - The brief: one spoken sentence after habits and goals, "On Hyperliquid, you're up over the last day, and 2
 *   positions are open."; the Short brief says it only when it's lit.
 *
 * "Today" is Hyperliquid's own day figure (the last 24 hours). Figures, never advice.
 */
data class TradingTodayLine(val text: String, val spoken: String, val lit: Boolean)

object TradingTodayRules {
    /** A read older than this isn't put on Today or in the brief (the server reads at least hourly). */
    const val STALE_MS = 3 * 60 * 60_000L

    /** Today's line from [wallets] and his alert [setting]; null when there's nothing fresh to say. */
    fun todayLine(wallets: List<TradingWallet>, setting: TradingAlertSetting, nowMs: Long): TradingTodayLine? {
        val fresh = wallets.filter { w ->
            val at = w.readAtMs ?: return@filter false
            w.equityCents != null && nowMs - at <= STALE_MS && at <= nowMs + STALE_MS
        }
        if (fresh.isEmpty()) return null
        val many = fresh.size > 1

        // Lit first: the position nearest liquidation within his level, else an account at his drop level.
        val liqLevel = setting.liquidation.share.takeIf { it > 0 } ?: TradingTracker.LIQ_NEAR
        val near = fresh.flatMap { w -> w.positions.mapNotNull { p -> p.liquidationDistance?.takeIf { it < liqLevel }?.let { Triple(w, p, it) } } }
            .minByOrNull { it.third }
        if (near != null) {
            val (w, p, d) = near
            val side = if (p.long) "long" else "short"
            val whose = if (many) " · ${w.label}" else ""
            return TradingTodayLine(
                "Trading · ${p.coin} $side ${TradingTracker.pct(d)} from liquidation$whose",
                "Trading: your ${p.coin} $side is ${TradingTracker.pct(d)} from liquidation" + (if (many) ", in ${w.label}" else "") + ".",
                lit = true,
            )
        }
        val dropLevel = setting.drop.share
        val dropped = if (dropLevel <= 0) null else fresh.mapNotNull { w -> w.drawdownShare?.takeIf { it >= dropLevel }?.let { w to it } }.maxByOrNull { it.second }

        val day = fresh.mapNotNull { it.pnlDayCents }.takeIf { it.isNotEmpty() }?.sum()
        val open = fresh.sumOf { it.positions.size }
        val dayWord = when {
            day == null -> null
            day > 0 -> "up"
            day < 0 -> "down"
            else -> "flat"
        }
        if (dropped == null && open == 0 && (dayWord == null || dayWord == "flat")) return null

        val parts = mutableListOf("Trading")
        dayWord?.let { parts += "$it today" }
        dropped?.let { (w, s) -> parts += "${w.label} ${TradingTracker.pct(s)} below this month's high" }
            ?: run { parts += positions(open) }
        val first = when (dayWord) {
            "up" -> "you're up over the last day"
            "down" -> "you're down over the last day"
            "flat" -> "you're level over the last day"
            else -> null
        }
        val second = dropped?.let { (w, s) -> "${w.label} is ${TradingTracker.pct(s)} below this month's high" }
            ?: if (open == 0) "no positions are open" else if (open == 1) "1 position is open" else "$open positions are open"
        val spoken = "Trading: " + (if (first == null) second else "$first, and $second") + "."
        return TradingTodayLine(parts.joinToString(" · "), spoken, lit = dropped != null)
    }

    /** The brief's sentence for [line] (the same facts, said); a Short brief says it only when it's lit. */
    fun briefSentence(line: TradingTodayLine?, length: BriefLength): String? {
        val l = line ?: return null
        if (length == BriefLength.SHORT && !l.lit) return null
        return l.spoken.replaceFirst("Trading: ", "On Hyperliquid, ")
    }

    private fun positions(n: Int) = when (n) {
        0 -> "no positions open"
        1 -> "1 position open"
        else -> "$n positions open"
    }
}
