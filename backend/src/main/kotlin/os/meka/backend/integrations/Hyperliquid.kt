package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import os.meka.core.domain.TradingCoinStats
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

/** Hyperliquid asked us to slow down (429). */
class TradingRateLimited : RuntimeException("hyperliquid rate limited")

/** Reads a public address's account; read-only. */
fun interface TradingFeed {
    fun read(address: String): TradingAccountRead

    /** Slice 2: the address's perp trades between [fromMs] and [toMs] added up per coin; null where not offered. */
    fun trades(address: String, fromMs: Long, toMs: Long): List<TradingCoinStats>? = null
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
