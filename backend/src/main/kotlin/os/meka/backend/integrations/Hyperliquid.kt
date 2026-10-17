package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import os.meka.core.domain.TradingCoinStats
import os.meka.core.domain.TradingExportFunding
import os.meka.core.domain.TradingExportOrder
import os.meka.core.domain.TradingFill
import os.meka.core.domain.TradingPosition
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What one public address's account looks like: all money in US cents; P&L null when Hyperliquid gave no history. */
data class TradingAccountRead(
    val equityCents: Long, val pnlDayCents: Long?, val pnlWeekCents: Long?, val pnlMonthCents: Long?, val pnlAllCents: Long?,
    val unrealisedCents: Long, val positions: List<TradingPosition>,
    /** Slice 3: how far the P&L is below this month's high, and the account value at that high (null without a month's history). */
    val drawdownCents: Long? = null, val drawdownBaseCents: Long? = null,
    /** Slice 4: the month's points as (time ms, account value, P&L) in cents, for the curve; empty without them. */
    val month: List<Triple<Long, Long, Long>> = emptyList(),
) {
    override fun toString() = "TradingAccountRead(${positions.size} positions)"
}

/** Slice 7: the last day's perp orders (their fills together) and the funding over it, received + / paid −, US cents. */
data class TradingRecentRead(val fills: List<TradingFill>, val fundingCents: Long) {
    override fun toString() = "TradingRecentRead(${fills.size} orders)"
}

/** Slice 8: a window's perp orders and daily funding for the tax export; [partial] when Hyperliquid had more than it gave. */
data class TradingExportRead(val orders: List<TradingExportOrder>, val funding: List<TradingExportFunding>, val partial: Boolean) {
    override fun toString() = "TradingExportRead(${orders.size} orders)"
}

/** Hyperliquid asked us to slow down (429). */
class TradingRateLimited : RuntimeException("hyperliquid rate limited")

/** Reads a public address's account; read-only. */
fun interface TradingFeed {
    fun read(address: String): TradingAccountRead

    /** Slice 2: the address's perp trades between [fromMs] and [toMs] added up per coin; null where not offered. */
    fun trades(address: String, fromMs: Long, toMs: Long): List<TradingCoinStats>? = null

    /** Slice 7: the orders and funding between [fromMs] and [toMs] (the last day), for the big-order and funding alerts; null where not offered. */
    fun recent(address: String, fromMs: Long, toMs: Long): TradingRecentRead? = null

    /** Slice 8: every perp order and each day's funding between [fromMs] and [toMs] (a tax year), for the export; null where not offered. */
    fun export(address: String, fromMs: Long, toMs: Long): TradingExportRead? = null
}

/**
 * Hyperliquid's public Info API (Trading tracker, slice 1): `POST https://api.hyperliquid.xyz/info` with
 * `{"type":"clearinghouseState","user":…}` (account value, open positions) and `{"type":"portfolio","user":…}` (the
 * account value and P&L histories per period). Only the **public address** is sent; there is no key, no signing and
 * nothing in this API that can place, change or cancel an order. Amounts are read from Hyperliquid's decimal text with
 * BigDecimal. The address never goes in a log.
 */
class HyperliquidInfo internal constructor(private val http: Http) : TradingFeed {
    /** (status, body) for a POST of [body]; injected so tests never touch the network. */
    fun interface Http { fun post(url: String, body: String): Pair<Int, String> }

    private fun call(type: String, address: String): JsonElement {
        val (status, body) = http.post(URL, """{"type":"$type","user":"$address"}""")
        when {
            status == 429 -> throw TradingRateLimited()
            status != 200 -> error("hyperliquid $status")
        }
        if (body.length > MAX_BYTES) error("hyperliquid answer too big")
        return runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: error("hyperliquid unreadable")
    }

    override fun read(address: String): TradingAccountRead {
        require(os.meka.core.domain.TradingTracker.isAddress(address)) { "not an address" }
        val a = address.lowercase()
        val state = call("clearinghouseState", a) as? JsonObject ?: error("no clearinghouse state")
        val perpValue = cents((state["marginSummary"] as? JsonObject)?.get("accountValue")) ?: error("no account value")
        val positions = (state["assetPositions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("position") as? JsonObject }
            .mapNotNull { p -> position(p) }
        // The portfolio adds spot and the P&L histories; without it the perps' figures still stand.
        val folio = runCatching { call("portfolio", a) }.getOrNull()
        val periods = folio?.let { f -> runCatching { portfolio(f) }.getOrNull() }.orEmpty()
        val month = folio?.let { f -> runCatching { timed(f, "month") }.getOrNull() }.orEmpty()
        fun pnl(period: String): Long? = periods[period]?.second?.let { h -> if (h.isEmpty()) null else h.last() - h.first() }
        val equity = periods["day"]?.first?.lastOrNull() ?: perpValue
        return TradingAccountRead(
            equity, pnl("day"), pnl("week"), pnl("month"),
            periods["allTime"]?.second?.lastOrNull(),
            positions.sumOf { it.unrealisedCents }, positions, month = month,
        ).let { r -> periods["month"]?.let { (values, pnl) -> drawdown(values, pnl) }?.let { (d, b) -> r.copy(drawdownCents = d, drawdownBaseCents = b) } ?: r }
    }

    /**
     * Slice 2: `userFillsByTime` (aggregated by time, 2,000 a page, up to [FILL_PAGES] pages) and `userFunding` (500 a
     * page, up to [FUNDING_PAGES]) for the window, added up per coin by [stats]. Still only the public address is sent.
     */
    override fun trades(address: String, fromMs: Long, toMs: Long): List<TradingCoinStats> {
        require(os.meka.core.domain.TradingTracker.isAddress(address)) { "not an address" }
        val a = address.lowercase()
        val fills = paged(FILL_PAGES, fromMs, toMs) { start ->
            """{"type":"userFillsByTime","user":"$a","startTime":$start,"endTime":$toMs,"aggregateByTime":true}"""
        }.distinctBy { f -> text(f["tid"]) ?: (text(f["hash"]) + text(f["time"]) + text(f["coin"])) }
        val funding = paged(FUNDING_PAGES, fromMs, toMs) { start ->
            """{"type":"userFunding","user":"$a","startTime":$start,"endTime":$toMs}"""
        }.distinctBy { f -> text(f["hash"]) + "|" + text(f["time"]) + "|" + text((f["delta"] as? JsonObject)?.get("coin")) }
        return stats(fills, funding)
    }

    /**
     * Slice 7: one page each of `userFillsByTime` (aggregated by time) and `userFunding` over the last day, made into
     * orders and the day's funding by [recentFrom]. Still only the public address is sent.
     */
    override fun recent(address: String, fromMs: Long, toMs: Long): TradingRecentRead {
        require(os.meka.core.domain.TradingTracker.isAddress(address)) { "not an address" }
        val a = address.lowercase()
        val fills = paged(1, fromMs, toMs) { start ->
            """{"type":"userFillsByTime","user":"$a","startTime":$start,"endTime":$toMs,"aggregateByTime":true}"""
        }.distinctBy { f -> text(f["tid"]) ?: (text(f["hash"]) + text(f["time"]) + text(f["coin"])) }
        val funding = paged(1, fromMs, toMs) { start -> """{"type":"userFunding","user":"$a","startTime":$start,"endTime":$toMs}""" }
            .distinctBy { f -> text(f["hash"]) + "|" + text(f["time"]) + "|" + text((f["delta"] as? JsonObject)?.get("coin")) }
        return recentFrom(fills, funding)
    }

    /**
     * Slice 8: `userFillsByTime` (aggregated by time, up to [EXPORT_FILL_PAGES] pages) and `userFunding` (up to
     * [EXPORT_FUNDING_PAGES]) over the window, made into orders and daily funding by [exportFrom]. Partial when either
     * ran out of pages or Hyperliquid's fill history (its most recent [FILL_HISTORY] fills) may not reach the start.
     * Still only the public address is sent.
     */
    override fun export(address: String, fromMs: Long, toMs: Long): TradingExportRead {
        require(os.meka.core.domain.TradingTracker.isAddress(address)) { "not an address" }
        val a = address.lowercase()
        val (fills, fillsAll) = pagedAll(EXPORT_FILL_PAGES, fromMs, toMs) { start ->
            """{"type":"userFillsByTime","user":"$a","startTime":$start,"endTime":$toMs,"aggregateByTime":true}"""
        }
        val (funding, fundingAll) = pagedAll(EXPORT_FUNDING_PAGES, fromMs, toMs) { start ->
            """{"type":"userFunding","user":"$a","startTime":$start,"endTime":$toMs}"""
        }
        val f = fills.distinctBy { x -> text(x["tid"]) ?: (text(x["hash"]) + text(x["time"]) + text(x["coin"])) }
        val g = funding.distinctBy { x -> text(x["hash"]) + "|" + text(x["time"]) + "|" + text((x["delta"] as? JsonObject)?.get("coin")) }
        return exportFrom(f, g, partial = !fillsAll || !fundingAll || f.size >= FILL_HISTORY)
    }

    private fun pagedAll(pages: Int, fromMs: Long, toMs: Long, body: (Long) -> String): Pair<List<JsonObject>, Boolean> {
        val out = mutableListOf<JsonObject>()
        var start = fromMs
        repeat(pages) {
            val (status, answer) = http.post(URL, body(start))
            when {
                status == 429 -> throw TradingRateLimited()
                status != 200 -> error("hyperliquid $status")
            }
            if (answer.length > MAX_BYTES) error("hyperliquid answer too big")
            val list = (runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonArray ?: error("hyperliquid unreadable"))
                .mapNotNull { it as? JsonObject }
            out += list
            val latest = list.mapNotNull { text(it["time"])?.toLongOrNull() }.maxOrNull()
            if (list.size < PAGE_FULL_AT || latest == null || latest >= toMs) return out to true
            start = latest + 1
        }
        return out to false
    }

    /** Pages a time-ordered list: when a page comes back full, the next starts just after its latest item. */
    private fun paged(pages: Int, fromMs: Long, toMs: Long, body: (Long) -> String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var start = fromMs
        repeat(pages) {
            val (status, answer) = http.post(URL, body(start))
            when {
                status == 429 -> throw TradingRateLimited()
                status != 200 -> error("hyperliquid $status")
            }
            if (answer.length > MAX_BYTES) error("hyperliquid answer too big")
            val list = (runCatching { Json.parseToJsonElement(answer) }.getOrNull() as? JsonArray ?: error("hyperliquid unreadable"))
                .mapNotNull { it as? JsonObject }
            out += list
            val latest = list.mapNotNull { text(it["time"])?.toLongOrNull() }.maxOrNull()
            if (list.size < PAGE_FULL_AT || latest == null || latest >= toMs) return out
            start = latest + 1
        }
        return out
    }

    companion object {
        const val URL = "https://api.hyperliquid.xyz/info"
        private const val FILL_PAGES = 5
        private const val FUNDING_PAGES = 10
        private const val EXPORT_FILL_PAGES = 10
        private const val EXPORT_FUNDING_PAGES = 40
        /** Hyperliquid keeps only an account's most recent 10,000 fills readable by time. */
        private const val FILL_HISTORY = 10_000
        /** A page this long or longer may have more after it (fills come 2,000 a page, funding 500). */
        private const val PAGE_FULL_AT = 500

        /**
         * Fills and funding added up per coin. Spot fills (`dir` Buy/Sell, coins like `@107` or `PURR/USDC`) are left
         * out. A **trade** is one closing order: the fills with a closed P&L that share an order id, together. Fees
         * count only in USDC (perp fees); funding is `delta.usdc` (received +, paid −). BigDecimal throughout, cents
         * at the end (half up).
         */
        fun stats(fills: List<JsonObject>, funding: List<JsonObject>): List<TradingCoinStats> {
            class Acc { var realised = BigDecimal.ZERO; var fees = BigDecimal.ZERO; var funding = BigDecimal.ZERO; val trades = linkedMapOf<String, BigDecimal>() }
            val by = linkedMapOf<String, Acc>()
            fun perp(coin: String) = !coin.startsWith("@") && '/' !in coin
            for (f in fills) {
                val coin = text(f["coin"])?.take(20) ?: continue
                val dir = text(f["dir"]).orEmpty()
                if (!perp(coin) || dir == "Buy" || dir == "Sell") continue
                val acc = by.getOrPut(coin) { Acc() }
                val token = text(f["feeToken"])
                if (token == null || token == "USDC") text(f["fee"])?.toBigDecimalOrNull()?.let { acc.fees += it }
                val closed = text(f["closedPnl"])?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                if (closed.signum() != 0) {
                    acc.realised += closed
                    val order = text(f["oid"]) ?: text(f["tid"]) ?: text(f["hash"]) ?: text(f["time"]).orEmpty()
                    acc.trades[order] = (acc.trades[order] ?: BigDecimal.ZERO) + closed
                }
            }
            for (f in funding) {
                val d = f["delta"] as? JsonObject ?: continue
                if (text(d["type"]) != "funding") continue
                val coin = text(d["coin"])?.take(20)?.takeIf { perp(it) } ?: continue
                text(d["usdc"])?.toBigDecimalOrNull()?.let { by.getOrPut(coin) { Acc() }.funding += it }
            }
            fun c(b: BigDecimal): Long = b.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            return by.map { (coin, a) ->
                val results = a.trades.values.map { c(it) }
                val wins = results.filter { it > 0 }
                val losses = results.filter { it < 0 }
                TradingCoinStats(coin, results.size, wins.size, losses.size, c(a.realised), c(a.fees), c(a.funding),
                    wins.sum(), losses.sum(), results.maxOrNull() ?: 0L, results.minOrNull() ?: 0L)
            }.sortedBy { it.coin }
        }
        private const val MAX_BYTES = 5_000_000

        /**
         * Slice 7: perp fills grouped by order (`oid`; a fill without one stands alone): when its last fill came, the coin,
         * the first fill's `dir`, what it was worth (Σ size × price) and its closed P&L; the [TradingTracker.MAX_FILLS]
         * biggest kept. Funding is Σ `delta.usdc` of the perp funding entries. BigDecimal, cents at the end.
         */
        fun recentFrom(fills: List<JsonObject>, funding: List<JsonObject>): TradingRecentRead {
            class Order(val coin: String, val dir: String) { var at = 0L; var value = BigDecimal.ZERO; var closed = BigDecimal.ZERO }
            fun perp(coin: String) = !coin.startsWith("@") && '/' !in coin
            val orders = linkedMapOf<String, Order>()
            for (f in fills) {
                val coin = text(f["coin"])?.take(20) ?: continue
                val dir = text(f["dir"]).orEmpty()
                if (!perp(coin) || dir == "Buy" || dir == "Sell") continue
                val sz = text(f["sz"])?.toBigDecimalOrNull()?.abs() ?: continue
                val px = text(f["px"])?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
                val at = text(f["time"])?.toLongOrNull() ?: continue
                val key = text(f["oid"])?.let { "o$it" } ?: ("t" + (text(f["tid"]) ?: (text(f["hash"]) + at + coin)))
                val o = orders.getOrPut(key) { Order(coin, dir.take(30)) }
                o.at = maxOf(o.at, at)
                o.value += sz * px
                text(f["closedPnl"])?.toBigDecimalOrNull()?.let { o.closed += it }
            }
            var paid = BigDecimal.ZERO
            for (f in funding) {
                val d = f["delta"] as? JsonObject ?: continue
                if (text(d["type"]) != "funding" || text(d["coin"])?.let { perp(it) } != true) continue
                text(d["usdc"])?.toBigDecimalOrNull()?.let { paid += it }
            }
            fun c(b: BigDecimal): Long = b.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            val list = orders.values.map { TradingFill(it.at, it.coin, it.dir, c(it.value), c(it.closed)) }
                .sortedByDescending { it.valueCents }.take(os.meka.core.domain.TradingTracker.MAX_FILLS).sortedBy { it.atMs }
            return TradingRecentRead(list, c(paid))
        }

        /**
         * Slice 8: perp fills grouped by order (`oid`; a fill without one stands alone) into [TradingExportOrder]s — the
         * last fill's time, the coin, the first fill's `dir`, Σ size, the size-weighted average price, Σ size × price,
         * Σ closed P&L and Σ USDC fees — and perp funding added up per UTC day and coin. Spot left out. BigDecimal,
         * cents at the end (half up).
         */
        fun exportFrom(fills: List<JsonObject>, funding: List<JsonObject>, partial: Boolean = false): TradingExportRead {
            class Order(val coin: String, val dir: String) { var at = 0L; var size = BigDecimal.ZERO; var value = BigDecimal.ZERO; var closed = BigDecimal.ZERO; var fee = BigDecimal.ZERO }
            fun perp(coin: String) = !coin.startsWith("@") && '/' !in coin
            val orders = linkedMapOf<String, Order>()
            for (f in fills) {
                val coin = text(f["coin"])?.take(20) ?: continue
                val dir = text(f["dir"]).orEmpty()
                if (!perp(coin) || dir == "Buy" || dir == "Sell") continue
                val sz = text(f["sz"])?.toBigDecimalOrNull()?.abs() ?: continue
                val px = text(f["px"])?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
                val at = text(f["time"])?.toLongOrNull() ?: continue
                val key = text(f["oid"])?.let { "o$it" } ?: ("t" + (text(f["tid"]) ?: (text(f["hash"]) + at + coin)))
                val o = orders.getOrPut(key) { Order(coin, dir.take(30)) }
                o.at = maxOf(o.at, at)
                o.size += sz
                o.value += sz * px
                text(f["closedPnl"])?.toBigDecimalOrNull()?.let { o.closed += it }
                val token = text(f["feeToken"])
                if (token == null || token == "USDC") text(f["fee"])?.toBigDecimalOrNull()?.let { o.fee += it }
            }
            val days = linkedMapOf<Pair<Long, String>, BigDecimal>()
            for (f in funding) {
                val d = f["delta"] as? JsonObject ?: continue
                if (text(d["type"]) != "funding") continue
                val coin = text(d["coin"])?.take(20)?.takeIf { perp(it) } ?: continue
                val at = text(f["time"])?.toLongOrNull() ?: continue
                val usdc = text(d["usdc"])?.toBigDecimalOrNull() ?: continue
                val k = Math.floorDiv(at, 86_400_000L) to coin
                days[k] = (days[k] ?: BigDecimal.ZERO) + usdc
            }
            fun c(b: BigDecimal): Long = b.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            val list = orders.values.map { o ->
                val avg = if (o.size.signum() == 0) BigDecimal.ZERO else o.value.divide(o.size, 8, RoundingMode.HALF_UP)
                TradingExportOrder(o.at, o.coin, o.dir, o.size.stripTrailingZeros().toPlainString(), avg.stripTrailingZeros().toPlainString(),
                    c(o.value), c(o.closed), c(o.fee))
            }.sortedBy { it.atMs }
            val daily = days.map { (k, v) -> TradingExportFunding(k.first, k.second, c(v)) }.sortedWith(compareBy({ it.day }, { it.coin }))
            return TradingExportRead(list, daily, partial)
        }

        private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        /** "-12.345" → -1235 (half up); null for anything that isn't a number or is absurd. */
        fun cents(e: JsonElement?): Long? {
            val bd = text(e)?.toBigDecimalOrNull() ?: return null
            if (bd.abs() > BigDecimal("1000000000000")) return null
            return bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        }

        private fun decimal(e: JsonElement?): String? = text(e)?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()

        /** One `assetPositions[].position`; the mark price is its value over its size. A zero size is no position. */
        fun position(p: JsonObject): TradingPosition? {
            val coin = text(p["coin"])?.take(20) ?: return null
            val size = text(p["szi"])?.toBigDecimalOrNull()?.takeIf { it.signum() != 0 } ?: return null
            val value = text(p["positionValue"])?.toBigDecimalOrNull()
            val mark = value?.takeIf { it.signum() > 0 }?.divide(size.abs(), 8, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
            val lev = ((p["leverage"] as? JsonObject)?.get("value") as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.toInt()
            return TradingPosition(coin, size.stripTrailingZeros().toPlainString(), decimal(p["entryPx"]), mark, decimal(p["liquidationPx"]),
                cents(p["unrealizedPnl"]) ?: 0L, lev)
        }

        /**
         * Slice 3: from one period's account values and P&L (oldest first, the same points), how far the P&L now sits
         * below its high in the period and the account value at that high; null when the two don't line up or the
         * account was empty then. P&L, not the account value, so a withdrawal never reads as a drop.
         */
        fun drawdown(values: List<Long>, pnl: List<Long>): Pair<Long, Long>? {
            if (pnl.isEmpty() || values.size != pnl.size) return null
            val peak = pnl.indices.maxByOrNull { pnl[it] } ?: return null
            val base = values[peak].takeIf { it > 0 } ?: return null
            return (pnl[peak] - pnl.last()).coerceAtLeast(0L) to base
        }

        /**
         * Slice 4: one period's points as (time ms, account value, P&L) in cents, oldest first; only times that carry
         * both (Hyperliquid gives the two histories at the same times).
         */
        fun timed(e: JsonElement, period: String): List<Triple<Long, Long, Long>> {
            val o = (e as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
                .firstOrNull { text(it.getOrNull(0)) == period }?.getOrNull(1) as? JsonObject ?: return emptyList()
            fun series(k: String): Map<Long, Long> = (o[k] as? JsonArray).orEmpty().mapNotNull { pt ->
                val p = pt as? JsonArray ?: return@mapNotNull null
                val ms = text(p.getOrNull(0))?.toLongOrNull() ?: return@mapNotNull null
                cents(p.getOrNull(1))?.let { ms to it }
            }.toMap()
            val values = series("accountValueHistory")
            val pnl = series("pnlHistory")
            return values.keys.filter { it in pnl }.sorted().map { Triple(it, values.getValue(it), pnl.getValue(it)) }
        }

        /** The portfolio's periods: name → (account values, P&L values), each oldest first, in cents. */
        fun portfolio(e: JsonElement): Map<String, Pair<List<Long>, List<Long>>> {
            val list = e as? JsonArray ?: return emptyMap()
            return list.mapNotNull { item ->
                val pair = item as? JsonArray ?: return@mapNotNull null
                val name = text(pair.getOrNull(0)) ?: return@mapNotNull null
                val o = pair.getOrNull(1) as? JsonObject ?: return@mapNotNull null
                fun series(k: String): List<Long> = (o[k] as? JsonArray).orEmpty().mapNotNull { pt ->
                    val p = pt as? JsonArray ?: return@mapNotNull null
                    val ms = text(p.getOrNull(0))?.toLongOrNull() ?: return@mapNotNull null
                    cents(p.getOrNull(1))?.let { ms to it }
                }.sortedBy { it.first }.map { it.second }
                name to (series("accountValueHistory") to series("pnlHistory"))
            }.toMap()
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        val NETWORK = Http { url, body ->
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            res.statusCode() to res.body()
        }

        fun network(): HyperliquidInfo = HyperliquidInfo(NETWORK)
    }
}
