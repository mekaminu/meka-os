package os.meka.core.domain

/**
 * Money → Trading's last two parts of the approved mockup (`docs/design/money/`, the Trading tab): **Obi's risk line**
 * and the **Kestrel card**. Both only read: nothing on the Trading tab can trade (the hard constraint), and neither is
 * advice. Both apps draw from here, so the Fold and the Mac say the same words.
 */

/**
 * Obi's risk line under the equity card (mockup: "Trading is 9% of everything you hold. Your BTC long is 5x: a 17% drop
 * reaches its liquidation price. Funding has cost $38 this month."): at most three short sentences, each only when its
 * figures are there. [lit] when a position is nearer its liquidation than the near-liquidation alert's level.
 */
data class TradingRiskView(val head: String, val line: String, val lit: Boolean, val spoken: String)

object TradingRisk {
    const val HEAD = "OBI ON YOUR RISK"
    /** A share of what he holds smaller than this isn't worth a sentence. */
    const val SHARE_SHOWN = 0.005

    /**
     * The line, or null when there's nothing to say yet (no account read, no position, no funding). [worth] gives what
     * he holds (net worth's "You have", trading included, in pounds) and the trading part of it; [near] is the share
     * at which a position counts as near liquidation (his alert's level).
     */
    fun view(wallets: List<TradingWallet>, worth: MoneyWorthView?, ccy: TradingCcy, near: Double = TradingTracker.LIQ_NEAR): TradingRiskView? {
        val read = wallets.filter { it.equityCents != null }
        if (read.isEmpty()) return null
        val parts = mutableListOf<String>()
        // 1. How much of what he holds is trading, once net worth counts it in pounds.
        val have = worth?.havePence ?: 0L
        val trading = worth?.tradingPence ?: 0L
        if (have > 0 && trading > 0) {
            val share = trading.toDouble() / have
            if (share >= SHARE_SHOWN) parts += "Trading is ${TradingTracker.pct(share.coerceAtMost(1.0))} of everything you hold."
        }
        // 2. The position nearest its liquidation price: its leverage and how far the price can move before it.
        val nearest = read.flatMap { it.positions }.filter { it.liquidationDistance != null }.minByOrNull { it.liquidationDistance!! }
        var lit = false
        if (nearest != null) {
            val d = nearest.liquidationDistance!!
            lit = d < near
            val side = if (nearest.long) "long" else "short"
            val lev = nearest.leverage?.takeIf { it > 0 }?.let { " is ${it}×" } ?: ""
            val move = if (nearest.long) "drop" else "rise"
            val pct = if (d < 0.01) "less than 1%" else "a ${TradingTracker.pct(d)}"
            parts += "Your ${nearest.coin} $side$lev: $pct $move reaches its liquidation price."
        } else if (read.any { it.positions.isNotEmpty() }) {
            parts += "No liquidation price read for your open positions yet."
        }
        // 3. Funding over the trades' window (received +, paid −), once the server has read the trades.
        val withTrades = read.filter { it.trades.isNotEmpty() || it.tradesAtMs != null }
        if (withTrades.isNotEmpty()) {
            val funding = withTrades.sumOf { w -> w.trades.sumOf { it.fundingCents } }
            val days = TradingTracker.TRADES_DAYS
            when {
                funding <= -100 -> parts += "Funding has cost ${ccy.amt(-funding)} in the last $days days."
                funding >= 100 -> parts += "Funding has paid you ${ccy.amt(funding)} in the last $days days."
            }
        }
        if (parts.isEmpty()) return null
        val line = parts.joinToString(" ")
        return TradingRiskView(HEAD, line, lit, "Obi on your risk. $line")
    }
}

/**
 * The Kestrel card (mockup: "Kestrel · 1 trade waiting for your OK", what it wants to do, "Today: 3 trades"): what
 * Kestrel has asked through the approvals inbox. It never approves anything itself: [review] takes him to the request
 * in Needs you, where a yes needs his fingerprint (Touch ID on the Mac), exactly as before. Null until Kestrel (or any
 * trade asker) has asked something in the last [RECENT_DAYS] days, so the card doesn't sit empty before Kestrel's side
 * is connected.
 */
data class TradingKestrelView(
    /** "KESTREL". */
    val head: String,
    /** "1 trade waiting for your OK" (lit) or "Nothing waiting". */
    val status: String,
    val lit: Boolean,
    /** The newest waiting request: its title ("Buy 0.05 BTC at market"), null with none waiting. */
    val waitingTitle: String?,
    /** Its first two lines and when it expires: "About £2,410 · Stop loss 5% · expires in 14 min". */
    val waitingLine: String?,
    /** The waiting request's id (Needs you's card), null with none. */
    val approvalId: String?,
    /** "Review in Needs you" while one waits; null otherwise. */
    val review: String?,
    /** "Today: 3 asked · 2 approved · 1 declined", or "Last asked Mon 26 Oct". */
    val tally: String?,
    val spoken: String,
)

object TradingKestrel {
    const val RECENT_DAYS = 30
    const val REVIEW = "Review in Needs you"
    const val NOTHING = "Nothing waiting"
    /** Said once, behind Trading's foot ⓘ. */
    const val INFO = "Kestrel's card shows what Kestrel asked; you answer in Needs you, and a yes needs your fingerprint (Touch ID on the Mac). Nothing on this tab can trade."

    fun view(all: List<Approval>, nowMs: Long, cal: LocalCalendar): TradingKestrelView? {
        val trades = all.filter { it.kind == ApprovalKind.TRADE }
        if (trades.isEmpty()) return null
        val today = cal.epochDayOf(nowMs)
        val pending = ApprovalRules.pending(trades, nowMs).sortedByDescending { it.askedAtMs }
        val recent = trades.filter { today - cal.epochDayOf(it.askedAtMs) < RECENT_DAYS }
        if (pending.isEmpty() && recent.isEmpty()) return null
        val newest = (pending.firstOrNull() ?: recent.maxByOrNull { it.askedAtMs })!!
        val head = newest.source.trim().uppercase().ifEmpty { "KESTREL" }
        val n = pending.size
        val status = if (n == 0) NOTHING else "$n trade${if (n == 1) "" else "s"} waiting for your OK"
        val w = pending.firstOrNull()
        val card = w?.let { ApprovalRules.card(it, nowMs, cal) }
        val waitingLine = card?.let { c ->
            (c.lines.take(2) + c.why.substringAfter(" · ", "").takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" · ").ifEmpty { null }
        }
        val asked = trades.filter { cal.epochDayOf(it.askedAtMs) == today }
        val tally = if (asked.isNotEmpty()) {
            val yes = asked.count { it.state(nowMs) == ApprovalState.APPROVED }
            val no = asked.count { it.state(nowMs) == ApprovalState.DECLINED }
            listOfNotNull("Today: ${asked.size} asked", "$yes approved".takeIf { yes > 0 }, "$no declined".takeIf { no > 0 }).joinToString(" · ")
        } else {
            recent.maxByOrNull { it.askedAtMs }?.let { "Last asked " + CivilDate.shortLabel(cal.epochDayOf(it.askedAtMs)) }
        }
        val name = head.lowercase().replaceFirstChar { it.uppercase() }
        val spoken = listOfNotNull("$name: $status", w?.title, tally).joinToString(". ")
        return TradingKestrelView(head, status, n > 0, w?.title, waitingLine, w?.id, if (w != null) REVIEW else null, tally, spoken)
    }
}
