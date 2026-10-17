package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
) {
    override fun toString() = "TradingAccountRead(${positions.size} positions)"
}

/** Hyperliquid asked us to slow down (429). */
class TradingRateLimited : RuntimeException("hyperliquid rate limited")

/** Reads a public address's account; read-only. */
fun interface TradingFeed {
    fun read(address: String): TradingAccountRead
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
        val periods = runCatching { portfolio(call("portfolio", a)) }.getOrNull().orEmpty()
        fun pnl(period: String): Long? = periods[period]?.second?.let { h -> if (h.isEmpty()) null else h.last() - h.first() }
        val equity = periods["day"]?.first?.lastOrNull() ?: perpValue
        return TradingAccountRead(
            equity, pnl("day"), pnl("week"), pnl("month"),
            periods["allTime"]?.second?.lastOrNull(),
            positions.sumOf { it.unrealisedCents }, positions,
        )
    }

    companion object {
        const val URL = "https://api.hyperliquid.xyz/info"
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
