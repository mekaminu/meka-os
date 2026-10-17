package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Trading tracker, slice 9: the monthly trading review, facts only. Once a month has finished (UK time), Navilife's
 * server adds up that month's perp trades, fees and funding for each followed address from Hyperliquid's public data
 * (the same add-up as YOUR TRADES, over the calendar month), and the month before for comparison, and writes them on the
 * `trading_wallet` ([TradingWalletFields.REVIEW_MONTH], [TradingWalletFields.REVIEW], [TradingWalletFields.REVIEW_PREV]).
 * Money → TRADING shows "SEPTEMBER IN REVIEW" in plain sentences (how many trades closed and won, the result after fees
 * and funding, against the month before, the best and worst coin, the biggest win and loss, the average win and loss),
 * and one heads-up on the morning of the 1st (no amounts) says it's ready. No AI, nothing that reads as advice.
 */
data class TradingMonth(val year: Int, val month: Int) {
    /** "2026-09". */
    val key: String get() = "$year-${month.toString().padStart(2, '0')}"
    /** "September". */
    val name: String get() = CivilDate.MONTH_LONG[month - 1]
    val previous: TradingMonth get() = if (month == 1) TradingMonth(year - 1, 12) else TradingMonth(year, month - 1)
    val next: TradingMonth get() = if (month == 12) TradingMonth(year + 1, 1) else TradingMonth(year, month + 1)

    fun fromMs(cal: LocalCalendar): Long = cal.toEpochMs(CivilDate.toEpochDay(year, month, 1), 0)
    fun toMs(cal: LocalCalendar): Long = next.fromMs(cal)

    companion object {
        /** The last month that has finished at [nowMs] in [cal]'s time. */
        fun lastFinished(nowMs: Long, cal: LocalCalendar): TradingMonth =
            CivilDate.fromEpochDay(cal.epochDayOf(nowMs)).let { TradingMonth(it.year, it.month).previous }

        fun parse(key: String?): TradingMonth? {
            val m = Regex("(\\d{4})-(\\d{2})").matchEntire(key?.trim() ?: return null) ?: return null
            val y = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            return if (y in 2019..2100 && mo in 1..12) TradingMonth(y, mo) else null
        }
    }
}

/** Money → TRADING's monthly review: a head ("SEPTEMBER IN REVIEW"), the result line, plain sentences and a foot. */
data class TradingReviewView(
    val head: String, val summary: String, val amount: String?, val up: Boolean?, val sentences: List<String>, val foot: String, val spoken: String,
)

object TradingReviewRules {
    const val FOOT = "From Hyperliquid's public data · the month in UK time · perpetuals only · a trade's result is before fees · figures, not trading advice"
    /** The heads-up comes from this time on the 1st, and the review stays "new" for [NEW_DAYS] days. */
    const val NOTICE_MINUTE = 9 * 60
    const val NEW_DAYS = 7
    /** After a failed add-up the server tries again no sooner than this. */
    const val RETRY_MS = 60 * 60_000L

    /** Whether the server should add up [w]'s review for [month]: it holds another month's (or none). */
    fun due(w: TradingWallet, month: TradingMonth): Boolean = w.reviewMonth != month.key

    private fun up(c: Long): Boolean? = when { c > 0 -> true; c < 0 -> false; else -> null }

    private fun sum(stats: List<TradingCoinStats>): List<TradingCoinStats> =
        stats.groupBy { it.coin }.map { (_, l) -> l.reduce { a, b -> a + b } }.filter { it.trades > 0 || it.feesCents != 0L || it.fundingCents != 0L }

    private fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

    /** The review of the last finished month across [wallets]; null until the server has written it for any of them. */
    fun view(wallets: List<TradingWallet>, nowMs: Long, cal: LocalCalendar): TradingReviewView? {
        val month = TradingMonth.lastFinished(nowMs, cal)
        val read = wallets.filter { it.reviewMonth == month.key }
        if (read.isEmpty()) return null
        val coins = sum(read.flatMap { it.review })
        val head = "${month.name.uppercase()} IN REVIEW"
        val foot = FOOT + if (read.size < wallets.size) " · ${plural(wallets.size - read.size, "account")} still being read" else ""
        if (coins.isEmpty()) {
            val s = "No trades, fees or funding in ${month.name}."
            return TradingReviewView(head, s, null, null, emptyList(), foot, "${month.name} in review. $s")
        }
        val all = coins.reduce { a, b -> a + b }
        val net = all.netCents
        val sentences = mutableListOf<String>()
        sentences += if (all.trades == 0) "You closed no trades in ${month.name}."
        else if (all.wins == 0) "You closed ${plural(all.trades, "trade")} in ${month.name} and won none of them."
        else "You closed ${plural(all.trades, "trade")} in ${month.name} and won ${all.wins} of them (${(all.wins * 100.0 / all.trades).roundToLong()}%)."
        val parts = listOfNotNull(
            "closed trades ${TradingTracker.signedUsd(all.realisedCents)}",
            all.feesCents.takeIf { it != 0L }?.let { "fees ${TradingTracker.signedUsd(-it)}" },
            all.fundingCents.takeIf { it != 0L }?.let { "funding ${TradingTracker.signedUsd(it)}" },
        )
        sentences += parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ": ${TradingTracker.signedUsd(net)} after fees and funding."
        // Against the month before, only when every account counted has the month before read too.
        if (read.all { it.reviewPrev != null }) {
            val prev = sum(read.flatMap { it.reviewPrev.orEmpty() })
            val prevNet = prev.sumOf { it.netCents }
            val prevName = month.previous.name
            val diff = net - prevNet
            sentences += when {
                prev.isEmpty() -> "There were no trades in $prevName to compare with."
                diff == 0L -> "The same as $prevName (${TradingTracker.signedUsd(prevNet)})."
                else -> "${TradingTracker.usd(abs(diff))} ${if (diff > 0) "better" else "worse"} than $prevName (${TradingTracker.signedUsd(prevNet)})."
            }
        }
        val traded = coins.filter { it.trades > 0 }
        if (traded.size >= 2) {
            val best = traded.maxBy { it.netCents }
            val worst = traded.minBy { it.netCents }
            sentences += "${best.coin} did best (${TradingTracker.signedUsd(best.netCents)} over ${plural(best.trades, "trade")}); " +
                "${worst.coin} did worst (${TradingTracker.signedUsd(worst.netCents)} over ${plural(worst.trades, "trade")})."
        } else if (traded.size == 1) {
            sentences += "All of it on ${traded[0].coin}."
        }
        val bestTrade = traded.maxByOrNull { it.bestCents }?.takeIf { it.bestCents > 0 }
        val worstTrade = traded.minByOrNull { it.worstCents }?.takeIf { it.worstCents < 0 }
        listOfNotNull(
            bestTrade?.let { "your biggest win was ${TradingTracker.signedUsd(it.bestCents)} on ${it.coin}" },
            worstTrade?.let { "your biggest loss ${TradingTracker.signedUsd(it.worstCents)} on ${it.coin}" },
        ).takeIf { it.isNotEmpty() }?.let { sentences += it.joinToString(", ").replaceFirstChar { c -> c.uppercase() } + "." }
        if (all.wins > 0 && all.losses > 0) {
            sentences += "Wins averaged ${TradingTracker.signedUsd(all.winSumCents / all.wins)} and losses ${TradingTracker.signedUsd(all.lossSumCents / all.losses)}."
        }
        if (all.feesCents > 0 && all.realisedCents > 0) {
            sentences += "Fees came to ${TradingAlerts.share(all.feesCents.toDouble() / all.realisedCents)} of what the closed trades made."
        }
        val summary = "${month.name} · ${TradingTracker.signedUsd(net)} after fees and funding"
        return TradingReviewView(head, summary, TradingTracker.signedUsd(net), up(net), sentences, foot,
            "${month.name} in review. " + sentences.joinToString(" "))
    }

    /**
     * The heads-up that the last month's review is ready: from 09:00 on the 1st (or when it arrives, if later) for
     * [NEW_DAYS] days, once per month; never an amount.
     */
    fun notice(wallets: List<TradingWallet>, nowMs: Long, cal: LocalCalendar): Notice? {
        val month = TradingMonth.lastFinished(nowMs, cal)
        val read = wallets.filter { it.reviewMonth == month.key }
        if (read.isEmpty()) return null
        val first = CivilDate.toEpochDay(month.next.year, month.next.month, 1)
        val from = cal.toEpochMs(first, NOTICE_MINUTE)
        val until = cal.toEpochMs(first + NEW_DAYS, 0)
        if (nowMs >= until) return null
        val at = maxOf(from, read.mapNotNull { it.reviewAtMs }.maxOrNull() ?: from)
        val slot = TradingAlerts.NOTICE_PREFIX + "review"
        return Notice(
            key = "$slot:${month.key}", source = NoticeSource.TRADING, tier = NoticeTier.HEADS_UP,
            title = "Your ${month.name} trading review is ready",
            text = "Trades, fees and funding for the month · in Money → Trading · facts, not advice",
            atMs = at, target = NoticeTarget.MONEY, expiresAtMs = until, postAs = slot,
        )
    }
}
