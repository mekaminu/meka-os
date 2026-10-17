package os.meka.core.domain

import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Trading tracker, slice 1 (Meka, 2026-10-12 21:23: "track whether I'm making money or not, all automated"): Money →
 * TRADING shows his Hyperliquid accounts read-only, from his **public address** only. The hard constraint holds: no
 * keys, no API wallet, no signing, nothing that can trade. Hyperliquid's account data is public, so Navilife's server
 * reads it from the public Info API (`clearinghouseState` and `portfolio`) with just the address.
 *
 * - He types an address (and a name) on the device: one `trading_wallet` entity, id [walletId], with [TradingWalletFields.ADDRESS]
 *   and [TradingWalletFields.LABEL]. The address lives only in his synced, encrypted data (never in the repo or a log).
 * - The server writes what it read onto the same entity (server ops, only the fields that changed): equity, P&L for
 *   the day, week, month and all time (Hyperliquid's own P&L, which leaves deposits and withdrawals out), unrealised
 *   P&L, and the open positions as [encode]d lines; every [OPEN_MS] while a position is open, [IDLE_MS] otherwise.
 * - Remove clears it on every device (Undo puts it back); Delete all money data takes the wallets too.
 *
 * All in US dollars (Hyperliquid's own unit), shown behind Money's fingerprint. Figures, never advice.
 */
object TradingWalletFields {
    const val ADDRESS = "address"
    const val LABEL = "label"
    /** Server: [TradingTracker.OK] or [TradingTracker.ERROR]. */
    const val STATE = "state"
    /** Server: when it last read the account successfully, and last tried. */
    const val READ_AT = "readAtMs"
    const val TRIED_AT = "triedAtMs"
    /** Server: account value in US cents. */
    const val EQUITY = "equityCents"
    const val PNL_DAY = "pnlDayCents"
    const val PNL_WEEK = "pnlWeekCents"
    const val PNL_MONTH = "pnlMonthCents"
    const val PNL_ALL = "pnlAllCents"
    const val UNREALISED = "unrealisedCents"
    /** Server: the open positions, [TradingTracker.encode]d. */
    const val POSITIONS = "positions"
    /** Server (slice 2): the last [TradingTracker.TRADES_DAYS] days' trades per coin, [TradingTracker.encodeTrades]d; when read, and from when. */
    const val TRADES = "trades"
    const val TRADES_AT = "tradesAtMs"
    const val TRADES_FROM = "tradesFromMs"
    /**
     * Server (slice 3): how far the account's P&L has fallen from its high this month (US cents, 0 at the high), and the
     * account value at that high, so the device can say "22% below this month's high" ([TradingWallet.drawdownShare]).
     */
    const val DRAWDOWN = "drawdownCents"
    const val DRAWDOWN_BASE = "drawdownBaseCents"

    val SERVER = listOf(STATE, READ_AT, TRIED_AT, EQUITY, PNL_DAY, PNL_WEEK, PNL_MONTH, PNL_ALL, UNREALISED, POSITIONS, TRADES, TRADES_AT, TRADES_FROM,
        DRAWDOWN, DRAWDOWN_BASE)
}

/** One open position as the server wrote it: prices as Hyperliquid's decimal text; [size] signed (short negative). */
data class TradingPosition(
    val coin: String, val size: String, val entry: String?, val mark: String?, val liquidation: String?,
    val unrealisedCents: Long, val leverage: Int?,
) {
    val long: Boolean get() = !size.trim().startsWith("-")

    /** How far the price can move before liquidation, as a share of the mark price; null without both. */
    val liquidationDistance: Double? get() {
        val m = mark?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val l = liquidation?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return abs(m - l) / m
    }
}

/**
 * Trading tracker, slice 2: one coin's perp trades over the window, as the server added them up from Hyperliquid's
 * public fills and funding. A **trade** is one closing order (its fills together); [realisedCents] is Hyperliquid's
 * closed P&L (before fees), [feesCents] what was paid in fees (a rebate is negative), [fundingCents] funding received
 * (paid is negative). [winSumCents]/[lossSumCents] add up the winning and losing trades; [bestCents]/[worstCents] are
 * the single best and worst (0 with no trades). All US cents.
 */
data class TradingCoinStats(
    val coin: String, val trades: Int, val wins: Int, val losses: Int, val realisedCents: Long, val feesCents: Long, val fundingCents: Long,
    val winSumCents: Long, val lossSumCents: Long, val bestCents: Long, val worstCents: Long,
) {
    /** What the coin made after fees and funding. */
    val netCents: Long get() = realisedCents - feesCents + fundingCents

    /** Two coins' (or two accounts') figures together. */
    operator fun plus(o: TradingCoinStats): TradingCoinStats = TradingCoinStats(
        coin, trades + o.trades, wins + o.wins, losses + o.losses, realisedCents + o.realisedCents, feesCents + o.feesCents,
        fundingCents + o.fundingCents, winSumCents + o.winSumCents, lossSumCents + o.lossSumCents,
        when { trades == 0 -> o.bestCents; o.trades == 0 -> bestCents; else -> maxOf(bestCents, o.bestCents) },
        when { trades == 0 -> o.worstCents; o.trades == 0 -> worstCents; else -> minOf(worstCents, o.worstCents) },
    )
}

data class TradingWallet(
    val id: String, val address: String, val label: String, val state: String?, val readAtMs: Long?, val triedAtMs: Long?,
    val equityCents: Long?, val pnlDayCents: Long?, val pnlWeekCents: Long?, val pnlMonthCents: Long?, val pnlAllCents: Long?,
    val unrealisedCents: Long?, val positions: List<TradingPosition>,
    /** Slice 2: the window's trades per coin (empty until the server has read them), when they were read and from when. */
    val trades: List<TradingCoinStats> = emptyList(), val tradesAtMs: Long? = null, val tradesFromMs: Long? = null,
    /** Slice 3: the fall from this month's P&L high and the account value at that high (US cents); null until read. */
    val drawdownCents: Long? = null, val drawdownBaseCents: Long? = null,
) {
    override fun toString() = "TradingWallet($id)"

    /** How far below this month's high, as a share of the account at that high (0 at the high); null without both. */
    val drawdownShare: Double? get() {
        val d = drawdownCents?.takeIf { it >= 0 } ?: return null
        val b = drawdownBaseCents?.takeIf { it > 0 } ?: return null
        return d.toDouble() / b
    }
}

/** One P&L line: "Today" · "+$120.50", [up] true/false, null when flat. */
data class TradingPnlRow(val label: String, val amount: String, val up: Boolean?)

data class TradingPositionRow(
    val id: String, val title: String, val line: String, val amount: String, val up: Boolean?, val liquidationLine: String?, val lit: Boolean,
    val spoken: String,
)

data class TradingWalletRow(val id: String, val name: String, val line: String, val lit: Boolean)

/** One coin under YOUR TRADES: "ETH" · "+$210.00" (after fees and funding) · "7 trades · 4 won · fees −$12.30 · funding +$3.10". */
data class TradingCoinRow(val id: String, val title: String, val amount: String, val up: Boolean?, val line: String, val spoken: String)

/**
 * Slice 2: YOUR TRADES · LAST 30 DAYS — how many trades closed and how many won, closed P&L, fees, funding and what's
 * left after them ([lines]), the average win and loss, the best and worst trade, and each coin (biggest result first).
 */
data class TradingTradesView(
    val head: String, val summary: String?, val lines: List<TradingPnlRow>, val averageLine: String?, val bestLine: String?,
    val coins: List<TradingCoinRow>, val moreLine: String?, val empty: String?, val foot: String, val spoken: String,
)

/** Money → TRADING · HYPERLIQUID. */
data class TradingView(
    val head: String, val total: String?, val totalLine: String?, val pnl: List<TradingPnlRow>, val unrealisedLine: String?,
    val positions: List<TradingPositionRow>, val wallets: List<TradingWalletRow>, val empty: String?, val hint: String, val foot: String,
    val spoken: String,
    /** Slice 2: the window's trades, once the server has read them for at least one address. */
    val trades: TradingTradesView? = null,
    /** Slice 3: ALERTS — when he's told (near liquidation, a drop from the month's high), with his choices; null with no address. */
    val alerts: TradingAlertsView? = null,
)

/** What an address typed into Money reads as. */
data class TradingAddressTyped(val address: String, val label: String?)

class TradingStore(private val replica: Replica) {
    fun wallets(): List<TradingWallet> = replica.entities(EntityTypes.TRADING_WALLET).mapNotNull { e ->
        val address = e[TradingWalletFields.ADDRESS].textOrNull?.trim()?.lowercase()?.takeIf { TradingTracker.isAddress(it) } ?: return@mapNotNull null
        TradingWallet(
            e.ref.entityId, address, e[TradingWalletFields.LABEL].textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: TradingTracker.DEFAULT_LABEL,
            e[TradingWalletFields.STATE].textOrNull, e[TradingWalletFields.READ_AT].longOrNull, e[TradingWalletFields.TRIED_AT].longOrNull,
            e[TradingWalletFields.EQUITY].longOrNull, e[TradingWalletFields.PNL_DAY].longOrNull, e[TradingWalletFields.PNL_WEEK].longOrNull,
            e[TradingWalletFields.PNL_MONTH].longOrNull, e[TradingWalletFields.PNL_ALL].longOrNull, e[TradingWalletFields.UNREALISED].longOrNull,
            TradingTracker.decode(e[TradingWalletFields.POSITIONS].textOrNull),
            TradingTracker.decodeTrades(e[TradingWalletFields.TRADES].textOrNull), e[TradingWalletFields.TRADES_AT].longOrNull,
            e[TradingWalletFields.TRADES_FROM].longOrNull,
            e[TradingWalletFields.DRAWDOWN].longOrNull, e[TradingWalletFields.DRAWDOWN_BASE].longOrNull,
        )
    }.sortedBy { it.label.lowercase() }

    /** Adds (or renames) the address typed in [text]; Undo by [undo] with the result's token. */
    fun add(text: String): MoneySaved {
        val t = TradingTracker.parse(text) ?: return MoneySaved(TradingTracker.BAD, refused = true)
        val id = TradingTracker.walletId(t.address)
        val have = wallets()
        val existing = have.firstOrNull { it.id == id }
        if (existing == null && have.size >= TradingTracker.MAX_WALLETS) return MoneySaved("Up to ${TradingTracker.MAX_WALLETS} addresses here.", refused = true)
        val label = t.label ?: existing?.label ?: if (have.isEmpty()) TradingTracker.DEFAULT_LABEL else "Account ${have.size + 1}"
        val token = TradingTracker.UNDO + id + "|" + (existing?.let { "1|" + it.label.replace("|", " ") } ?: "0|")
        replica.commitLocal(EntityTypes.TRADING_WALLET, id, mapOf(
            TradingWalletFields.ADDRESS to t.address.fv(), TradingWalletFields.LABEL to label.fv(), DELETED_FIELD to false.fv(),
        ))
        return MoneySaved(if (existing != null) "Renamed ${TradingTracker.short(t.address)} to $label" else TradingTracker.addedSaid(label, t.address), undo = token)
    }

    /** Takes [id] off every device, with what the server read; Undo by [undo] puts the address back (read again within minutes). */
    fun remove(id: String): MoneySaved {
        val w = wallets().firstOrNull { it.id == id } ?: return MoneySaved(MoneyRules.GONE, refused = true)
        clear(id)
        return MoneySaved("Removed ${w.label} · its figures are gone from Navilife", undo = TradingTracker.UNDO + id + "|2|" + w.address + "|" + w.label.replace("|", " "))
    }

    private fun clear(id: String) {
        replica.commitLocal(EntityTypes.TRADING_WALLET, id, mapOf(
            DELETED_FIELD to true.fv(), TradingWalletFields.ADDRESS to FieldValue.Null, TradingWalletFields.LABEL to FieldValue.Null,
        ) + TradingWalletFields.SERVER.associateWith { FieldValue.Null })
    }

    /** Undoes an [add] or a [remove]; false for a token that isn't one. */
    fun undo(token: String): Boolean {
        if (!token.startsWith(TradingTracker.UNDO)) return false
        val parts = token.removePrefix(TradingTracker.UNDO).split("|")
        val id = parts.getOrNull(0) ?: return false
        when (parts.getOrNull(1)) {
            "0" -> clear(id)
            "1" -> replica.commitLocal(EntityTypes.TRADING_WALLET, id, mapOf(TradingWalletFields.LABEL to (parts.getOrNull(2) ?: return false).fv()))
            "2" -> {
                val address = parts.getOrNull(2)?.takeIf { TradingTracker.isAddress(it) } ?: return false
                replica.commitLocal(EntityTypes.TRADING_WALLET, id, mapOf(
                    DELETED_FIELD to false.fv(), TradingWalletFields.ADDRESS to address.fv(), TradingWalletFields.LABEL to (parts.getOrNull(3) ?: TradingTracker.DEFAULT_LABEL).fv(),
                ))
            }
            else -> return false
        }
        return true
    }

    /** Delete all money data: every address and what was read for it. */
    fun deleteAll() {
        replica.batched { replica.entities(EntityTypes.TRADING_WALLET).forEach { clear(it.ref.entityId) } }
    }
}

object TradingTracker {
    const val HEAD = "TRADING · HYPERLIQUID"
    const val DEFAULT_LABEL = "Main account"
    const val UNDO = "trading|"
    const val OK = "ok"
    const val ERROR = "error"
    const val MAX_WALLETS = 8
    const val MAX_POSITIONS = 40
    /** Read every 5 minutes while a position is open, hourly otherwise, 15 minutes after a failure. */
    const val OPEN_MS = 5 * 60_000L
    const val IDLE_MS = 60 * 60_000L
    const val RETRY_MS = 15 * 60_000L
    /** A position this close to liquidation (share of the price) is lit. */
    const val LIQ_NEAR = 0.10
    const val HINT = "Public address 0x… and a name, e.g. Main 0x…"
    const val BAD = "That isn't a Hyperliquid address · it's 0x and 40 letters and digits (your public address, never a key)"
    const val EMPTY = "Add your Hyperliquid public address to see your equity, profit and loss and open positions. Read-only: only the public address, never a key."
    const val FOOT = "Read from Hyperliquid's public data with your public address only · no keys, nothing here can trade · US dollars · deposits and withdrawals aren't counted as profit · not trading advice"
    const val WAITING = "Reading it from Hyperliquid · within a minute or two"

    /** Slice 2: trades are added up over the last [TRADES_DAYS] days, read again hourly. */
    const val TRADES_DAYS = 30
    const val TRADES_MS = 60 * 60_000L
    const val MAX_COINS = 40
    /** Coins shown under YOUR TRADES before "+n more". */
    const val SHOWN_COINS = 8
    const val TRADES_HEAD = "YOUR TRADES · LAST $TRADES_DAYS DAYS"
    const val NO_TRADES = "No trades in the last $TRADES_DAYS days."
    const val TRADES_FOOT = "Perpetuals only · each closing order is one trade · a trade's result is before fees · funding received is +, paid is −"

    private val ADDRESS = Regex("0x[0-9a-fA-F]{40}")

    fun isAddress(s: String): Boolean = ADDRESS.matches(s.trim())

    fun walletId(address: String): String = "w" + ActivityRules.fnv64("hyperliquid:" + address.trim().lowercase())

    /** "0x12ab…cdef". */
    fun short(address: String): String = address.trim().let { if (it.length > 12) it.take(6) + "…" + it.takeLast(4) else it }

    /** "Main 0xAbC…" → the address (lower case) and "Main"; a key-length hex (64) is refused as not an address. */
    fun parse(text: String): TradingAddressTyped? {
        val t = text.trim()
        if (Regex("(0x)?[0-9a-fA-F]{64}").containsMatchIn(t)) return null
        val m = Regex("(?<![0-9a-fA-Fx])0x[0-9a-fA-F]{40}(?![0-9a-fA-F])").find(t) ?: return null
        val label = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1))
            .replace(Regex("[|\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim(' ', ',', '·', '-', ':', ';').take(30).trim()
        return TradingAddressTyped(m.value.lowercase(), label.takeIf { it.isNotEmpty() })
    }

    fun addedSaid(label: String, address: String) = "Added $label · ${short(address)} · " + WAITING.replaceFirstChar { it.lowercase() }

    /** Positions as lines of `coin|size|entry|mark|liquidation|unrealisedCents|leverage` (at most [MAX_POSITIONS]). */
    fun encode(positions: List<TradingPosition>): String = positions.take(MAX_POSITIONS).joinToString("\n") { p ->
        listOf(p.coin, p.size, p.entry.orEmpty(), p.mark.orEmpty(), p.liquidation.orEmpty(), p.unrealisedCents.toString(), p.leverage?.toString().orEmpty())
            .joinToString("|") { it.replace(Regex("[|\\n\\r]"), " ").trim() }
    }

    fun decode(text: String?): List<TradingPosition> = text.orEmpty().lines().mapNotNull { line ->
        val f = line.split("|")
        if (f.size < 6) return@mapNotNull null
        val coin = f[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val size = f[1].trim().takeIf { it.toDoubleOrNull() != null } ?: return@mapNotNull null
        TradingPosition(coin, size, f[2].trim().ifEmpty { null }, f[3].trim().ifEmpty { null }, f[4].trim().ifEmpty { null },
            f[5].trim().toLongOrNull() ?: 0L, f.getOrNull(6)?.trim()?.toIntOrNull())
    }

    /** Coin stats as lines of `coin|trades|wins|losses|realised|fees|funding|winSum|lossSum|best|worst` (at most [MAX_COINS]). */
    fun encodeTrades(stats: List<TradingCoinStats>): String = stats.take(MAX_COINS).joinToString("\n") { c ->
        listOf(c.coin.replace(Regex("[|\\n\\r]"), " ").trim(), c.trades, c.wins, c.losses, c.realisedCents, c.feesCents, c.fundingCents,
            c.winSumCents, c.lossSumCents, c.bestCents, c.worstCents).joinToString("|")
    }

    fun decodeTrades(text: String?): List<TradingCoinStats> = text.orEmpty().lines().mapNotNull { line ->
        val f = line.split("|")
        if (f.size < 11) return@mapNotNull null
        val coin = f[0].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val n = f.drop(1).take(10).map { it.trim().toLongOrNull() ?: return@mapNotNull null }
        if (n[0] < 0 || n[1] < 0 || n[2] < 0) return@mapNotNull null
        TradingCoinStats(coin, n[0].toInt(), n[1].toInt(), n[2].toInt(), n[3], n[4], n[5], n[6], n[7], n[8], n[9])
    }

    /** Whether the server should add up [w]'s trades again: never, or [TRADES_MS] since. */
    fun tradesDue(w: TradingWallet, nowMs: Long): Boolean = w.tradesAtMs.let { it == null || nowMs - it >= TRADES_MS }

    /** YOUR TRADES across [wallets] (the same coin in two accounts counts together); null until any has been read. */
    fun tradesView(wallets: List<TradingWallet>, nowMs: Long, cal: LocalCalendar): TradingTradesView? {
        val read = wallets.filter { it.tradesAtMs != null }
        if (read.isEmpty()) return null
        val coins = read.flatMap { it.trades }.groupBy { it.coin }.map { (_, l) -> l.reduce { a, b -> a + b } }
            .filter { it.trades > 0 || it.feesCents != 0L || it.fundingCents != 0L }
            .sortedWith(compareByDescending<TradingCoinStats> { abs(it.netCents) }.thenBy { it.coin })
        val at = read.mapNotNull { it.tradesAtMs }.max()
        val foot = "$TRADES_FOOT · updated ${HealthRules.at(at, nowMs, cal)}"
        if (coins.isEmpty()) return TradingTradesView(TRADES_HEAD, null, emptyList(), null, null, emptyList(), null, NO_TRADES, foot, NO_TRADES)
        val all = coins.reduce { a, b -> a + b }
        fun up(c: Long): Boolean? = when { c > 0 -> true; c < 0 -> false; else -> null }
        val summary = if (all.trades == 0) "No trades closed" else
            "${all.trades} trade${if (all.trades == 1) "" else "s"} closed · ${all.wins} won (${(all.wins * 100.0 / all.trades).roundToLong()}%)"
        val lines = listOfNotNull(
            TradingPnlRow("Closed trades", signedUsd(all.realisedCents), up(all.realisedCents)),
            all.feesCents.takeIf { it != 0L }?.let { TradingPnlRow("Fees", signedUsd(-it), up(-it)) },
            all.fundingCents.takeIf { it != 0L }?.let { TradingPnlRow("Funding", signedUsd(it), up(it)) },
            TradingPnlRow("After fees and funding", signedUsd(all.netCents), up(all.netCents)),
        )
        val average = listOfNotNull(
            all.wins.takeIf { it > 0 }?.let { "Average win ${signedUsd(all.winSumCents / it)}" },
            all.losses.takeIf { it > 0 }?.let { "average loss ${signedUsd(all.lossSumCents / it)}" },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
        val traded = coins.filter { it.trades > 0 }
        val best = traded.maxByOrNull { it.bestCents }?.takeIf { it.bestCents > 0 }
        val worst = traded.minByOrNull { it.worstCents }?.takeIf { it.worstCents < 0 }
        val bestLine = listOfNotNull(
            best?.let { "Best ${signedUsd(it.bestCents)} on ${it.coin}" }, worst?.let { "worst ${signedUsd(it.worstCents)} on ${it.coin}" },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
        val rows = coins.take(SHOWN_COINS).map { c ->
            val line = listOfNotNull(
                if (c.trades == 0) "no trades closed" else "${c.trades} trade${if (c.trades == 1) "" else "s"} · ${c.wins} won",
                c.feesCents.takeIf { it != 0L }?.let { "fees ${signedUsd(-it)}" },
                c.fundingCents.takeIf { it != 0L }?.let { "funding ${signedUsd(it)}" },
            ).joinToString(" · ").replaceFirstChar { it.uppercase() }
            TradingCoinRow("coin:${c.coin}", c.coin, signedUsd(c.netCents), up(c.netCents), line, "${c.coin}, ${signedUsd(c.netCents)} after fees and funding, ${line.lowercase()}")
        }
        val more = (coins.size - rows.size).takeIf { it > 0 }?.let { "+$it more coin${if (it == 1) "" else "s"}" }
        val spoken = listOfNotNull(
            "Last $TRADES_DAYS days: $summary", "${signedUsd(all.netCents)} after fees and funding", average, bestLine,
        ).joinToString(". ")
        return TradingTradesView(TRADES_HEAD, summary, lines, average, bestLine, rows, more, null, foot, spoken)
    }

    /** Whether the server should read [w] now: never tried, or its pace since the last try. */
    fun due(w: TradingWallet, nowMs: Long): Boolean {
        val last = w.triedAtMs ?: return true
        val wait = when {
            w.state == ERROR -> RETRY_MS
            w.positions.isNotEmpty() -> OPEN_MS
            else -> IDLE_MS
        }
        return nowMs - last >= wait
    }

    /** "$12,345.67" (whole dollars from $100,000). */
    fun usd(cents: Long): String {
        val c = abs(cents)
        val dollars = (c / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val sign = if (cents < 0) "−" else ""
        return if (c >= 10_000_000) "$sign$" + ((c + 50) / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        else "$sign$$dollars.${(c % 100).toString().padStart(2, '0')}"
    }

    /** "+$120.50", "−$40.00", "$0.00". */
    fun signedUsd(cents: Long): String = when {
        cents > 0 -> "+" + usd(cents)
        else -> usd(cents)
    }

    /** A price for reading: "$61,200", "$3,012.5", "$1.234", "$0.000123". */
    fun price(text: String?): String? {
        val d = text?.trim()?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return "$" + when {
            d >= 10_000 -> d.roundToLong().toString().reversed().chunked(3).joinToString(",").reversed()
            d >= 1_000 -> trimZeros(fixed(d, 1)).let { s -> val (w, f) = s.split(".").let { it[0] to it.getOrNull(1) }; w.reversed().chunked(3).joinToString(",").reversed() + (f?.let { ".$it" } ?: "") }
            d >= 1 -> trimZeros(fixed(d, 3))
            else -> trimZeros(fixed(d, 6))
        }
    }

    private fun fixed(d: Double, places: Int): String {
        var m = 1L
        repeat(places) { m *= 10 }
        val n = (d * m).roundToLong()
        val whole = n / m
        val frac = (n % m).toString().padStart(places, '0')
        return "$whole.$frac"
    }

    private fun trimZeros(s: String): String = if ('.' in s) s.trimEnd('0').trimEnd('.') else s

    /** "0.25" from "-0.2500". */
    private fun size(text: String): String = trimZeros(text.trim().removePrefix("-").removePrefix("+"))

    internal fun pct(share: Double): String = "${(share * 100).roundToLong()}%"

    fun positionRow(walletId: String, p: TradingPosition, near: Double = LIQ_NEAR): TradingPositionRow {
        val side = if (p.long) "long" else "short"
        val title = "${p.coin} · $side ${size(p.size)}"
        val line = listOfNotNull(
            price(p.entry)?.let { "entry $it" }, price(p.mark)?.let { "now $it" }, p.leverage?.takeIf { it > 0 }?.let { "$it×" },
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
        val dist = p.liquidationDistance
        val lit = dist != null && dist < near
        val liq = price(p.liquidation)?.let { l -> "Liquidation at $l" + (dist?.let { " · ${pct(it)} away" } ?: "") }
        val up = when { p.unrealisedCents > 0 -> true; p.unrealisedCents < 0 -> false; else -> null }
        val amount = signedUsd(p.unrealisedCents)
        return TradingPositionRow(
            "$walletId:${p.coin}", title, line, amount, up, liq, lit,
            listOfNotNull("${p.coin} $side ${size(p.size)}", "$amount not yet taken", liq).joinToString(", "),
        )
    }

    /** TRADING on the Money screen; [wallets] as [TradingStore.wallets] gives them. */
    fun view(wallets: List<TradingWallet>, nowMs: Long, cal: LocalCalendar, alerts: TradingAlertSetting = TradingAlertSetting.DEFAULT): TradingView {
        if (wallets.isEmpty()) return TradingView(HEAD, null, null, emptyList(), null, emptyList(), emptyList(), EMPTY, HINT, FOOT, EMPTY)
        val read = wallets.filter { it.equityCents != null }
        fun sum(f: (TradingWallet) -> Long?): Long? = read.mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()
        val equity = sum { it.equityCents }
        val last = read.mapNotNull { it.readAtMs }.maxOrNull()
        val n = wallets.size
        val totalLine = if (equity == null) null else listOfNotNull(
            if (n == 1) "Equity" else "Equity across $n accounts",
            last?.let { "updated ${HealthRules.at(it, nowMs, cal)}" },
        ).joinToString(" · ")
        val pnl = listOf("Today" to sum { it.pnlDayCents }, "This week" to sum { it.pnlWeekCents },
            "This month" to sum { it.pnlMonthCents }, "All time" to sum { it.pnlAllCents })
            .mapNotNull { (label, c) -> c?.let { TradingPnlRow(label, signedUsd(it), when { it > 0 -> true; it < 0 -> false; else -> null }) } }
        val open = read.flatMap { w -> w.positions.map { w to it } }
            .sortedWith(compareBy<Pair<TradingWallet, TradingPosition>> { it.second.liquidationDistance ?: 9.0 }.thenByDescending { abs(it.second.unrealisedCents) })
        val near = alerts.liquidation.share.takeIf { it > 0 } ?: LIQ_NEAR
        val rows = open.map { (w, p) -> positionRow(w.id, p, near).let { r -> if (n > 1) r.copy(line = r.line + " · " + w.label) else r } }
        val unrealised = sum { it.unrealisedCents }
        val unrealisedLine = when {
            open.isEmpty() && equity != null -> "No open positions"
            open.isEmpty() -> null
            else -> "${open.size} open position${if (open.size == 1) "" else "s"} · ${signedUsd(unrealised ?: 0L)} not yet taken"
        }
        val walletRows = wallets.map { w ->
            // Slice 3: how far below this month's high, lit at his drop alert's level.
            val drop = w.drawdownShare?.takeIf { w.equityCents != null && it >= TradingAlerts.DROP_SHOWN }
            val dropLit = drop != null && alerts.drop != TradingDropAlert.OFF && drop >= alerts.drop.share
            val line = when {
                w.state == ERROR -> "${short(w.address)} · couldn't read it from Hyperliquid" +
                    (w.triedAtMs?.let { " at ${HealthRules.at(it, nowMs, cal)}" } ?: "") + " · trying again soon" +
                    (w.readAtMs?.let { " · last ${HealthRules.at(it, nowMs, cal)}" } ?: "")
                w.equityCents == null -> "${short(w.address)} · " + WAITING.replaceFirstChar { it.lowercase() }
                else -> "${short(w.address)} · ${usd(w.equityCents)}" + (drop?.let { " · ${pct(it)} below this month's high" } ?: "") +
                    (w.readAtMs?.let { " · updated ${HealthRules.at(it, nowMs, cal)}" } ?: "")
            }
            TradingWalletRow(w.id, w.label, line, w.state == ERROR || dropLit)
        }
        val total = equity?.let { usd(it) }
        val spoken = listOfNotNull(
            total?.let { "Trading equity $it" },
            pnl.joinToString(", ") { "${it.label.lowercase()} ${it.amount}" }.takeIf { it.isNotEmpty() },
            unrealisedLine,
            rows.filter { it.lit }.takeIf { it.isNotEmpty() }?.joinToString(", ") { "${it.title} near liquidation" },
        ).joinToString(". ").ifEmpty { WAITING }
        return TradingView(HEAD, total, totalLine, pnl, unrealisedLine, rows, walletRows, null, HINT, FOOT, spoken, tradesView(wallets, nowMs, cal),
            TradingAlerts.view(alerts))
    }
}
