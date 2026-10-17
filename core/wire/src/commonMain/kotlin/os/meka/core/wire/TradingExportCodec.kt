package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Trading tracker, slice 8 on the wire: a device asks `POST /v1/trading/export` for the perpetuals orders and daily
 * funding of every Hyperliquid account the household follows between [Request.fromMs] and [Request.toMs] (a UK tax
 * year), saying how long it can wait. The server reads them from Hyperliquid's public data (public address only) and
 * keeps the answer in memory for a few minutes; "pending" means it is still reading (ask again), "off" that the server
 * has no trading tracker, "failed" that Hyperliquid couldn't be read. Accounts are named by their entity id only.
 * Slice 10: an "ok" answer also carries HMRC's monthly US dollars to £1 for each month of the window it could read
 * (`rates`, [Rate]), so the file can give pounds as well; an older server simply sends none.
 */
object TradingExportCodec {
    const val PATH = "/v1/trading/export"
    const val MAX_WAIT_MS = 20_000L
    const val MAX_SPAN_MS = 367L * 86_400_000L
    private const val MAX_ACCOUNTS = 8
    private const val MAX_ORDERS = 20_000
    private const val MAX_FUNDING = 5_000
    private const val MAX_TEXT = 30
    private const val MAX_RATES = 14

    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val fromMs: Long, val toMs: Long, val waitMs: Long)

    data class Order(
        val atMs: Long, val coin: String, val direction: String, val size: String, val price: String,
        val valueCents: Long, val closedCents: Long, val feeCents: Long,
    )

    data class Funding(val day: Long, val coin: String, val cents: Long)

    data class Account(val id: String, val orders: List<Order>, val funding: List<Funding>, val partial: Boolean, val failed: Boolean) {
        override fun toString() = "TradingExportCodec.Account(${orders.size} orders)"
    }

    /** HMRC's US dollars per £1 × 1,000,000 for [month] (yyyymm). */
    data class Rate(val month: Int, val usdPerGbpMicros: Long)

    data class Response(val state: String, val accounts: List<Account> = emptyList(), val rates: List<Rate> = emptyList()) {
        companion object {
            const val OK = "ok"
            const val PENDING = "pending"
            const val OFF = "off"
            const val FAILED = "failed"
            val STATES = setOf(OK, PENDING, OFF, FAILED)
        }
    }

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("from", r.fromMs)
        put("to", r.toMs)
        put("wait", r.waitMs.coerceIn(0, MAX_WAIT_MS))
    }.toString()

    fun decodeRequest(body: String): Request = wrap("export request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val from = num(o["from"]) ?: throw IllegalArgumentException("from")
        val to = num(o["to"]) ?: throw IllegalArgumentException("to")
        require(from >= EARLIEST_MS && to > from && to - from <= MAX_SPAN_MS) { "window" }
        Request(from, to, (num(o["wait"]) ?: 0L).coerceIn(0, MAX_WAIT_MS))
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        if (r.state == Response.OK) put("accounts", buildJsonArray {
            r.accounts.take(MAX_ACCOUNTS).forEach { a ->
                add(buildJsonObject {
                    put("id", a.id.take(64))
                    put("partial", a.partial)
                    put("failed", a.failed)
                    put("orders", buildJsonArray {
                        a.orders.take(MAX_ORDERS).forEach { o ->
                            add(buildJsonArray {
                                add(JsonPrimitive(o.atMs)); add(JsonPrimitive(o.coin.take(MAX_TEXT))); add(JsonPrimitive(o.direction.take(MAX_TEXT)))
                                add(JsonPrimitive(o.size.take(MAX_TEXT))); add(JsonPrimitive(o.price.take(MAX_TEXT)))
                                add(JsonPrimitive(o.valueCents)); add(JsonPrimitive(o.closedCents)); add(JsonPrimitive(o.feeCents))
                            })
                        }
                    })
                    put("funding", buildJsonArray {
                        a.funding.take(MAX_FUNDING).forEach { f ->
                            add(buildJsonArray { add(JsonPrimitive(f.day)); add(JsonPrimitive(f.coin.take(MAX_TEXT))); add(JsonPrimitive(f.cents)) })
                        }
                    })
                })
            }
        })
        if (r.state == Response.OK && r.rates.isNotEmpty()) put("rates", buildJsonArray {
            r.rates.take(MAX_RATES).forEach { x -> add(buildJsonArray { add(JsonPrimitive(x.month)); add(JsonPrimitive(x.usdPerGbpMicros)) }) }
        })
    }.toString()

    fun decodeResponse(body: String): Response = wrap("export response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val state = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in Response.STATES }
            ?: throw IllegalArgumentException("state")
        if (state != Response.OK) return@wrap Response(state)
        val accounts = (o["accounts"] as? JsonArray).orEmpty().take(MAX_ACCOUNTS).mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val id = text(a["id"])?.take(64) ?: return@mapNotNull null
            val orders = (a["orders"] as? JsonArray).orEmpty().take(MAX_ORDERS).mapNotNull { r ->
                val l = r as? JsonArray ?: return@mapNotNull null
                if (l.size < 8) return@mapNotNull null
                Order(num(l[0]) ?: return@mapNotNull null, text(l[1])?.take(MAX_TEXT) ?: return@mapNotNull null, text(l[2])?.take(MAX_TEXT).orEmpty(),
                    text(l[3])?.take(MAX_TEXT).orEmpty(), text(l[4])?.take(MAX_TEXT).orEmpty(),
                    num(l[5]) ?: 0L, num(l[6]) ?: 0L, num(l[7]) ?: 0L)
            }
            val funding = (a["funding"] as? JsonArray).orEmpty().take(MAX_FUNDING).mapNotNull { r ->
                val l = r as? JsonArray ?: return@mapNotNull null
                if (l.size < 3) return@mapNotNull null
                Funding(num(l[0]) ?: return@mapNotNull null, text(l[1])?.take(MAX_TEXT) ?: return@mapNotNull null, num(l[2]) ?: 0L)
            }
            Account(id, orders, funding, (a["partial"] as? JsonPrimitive)?.booleanOrNull ?: false, (a["failed"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
        val rates = (o["rates"] as? JsonArray).orEmpty().take(MAX_RATES).mapNotNull { r ->
            val l = r as? JsonArray ?: return@mapNotNull null
            if (l.size < 2) return@mapNotNull null
            val month = num(l[0])?.toInt()?.takeIf { it % 100 in 1..12 && it / 100 in 2019..2100 } ?: return@mapNotNull null
            val micros = num(l[1])?.takeIf { it > 0 } ?: return@mapNotNull null
            Rate(month, micros)
        }.distinctBy { it.month }
        Response(state, accounts, rates)
    }

    /** 1 Jan 2019: nothing on Hyperliquid is older. */
    private const val EARLIEST_MS = 1_546_300_800_000L

    private fun num(e: kotlinx.serialization.json.JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun text(e: kotlinx.serialization.json.JsonElement?): String? =
        (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun checkVersion(o: JsonObject) {
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
    }

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
