package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Units of each currency to £1 × 1,000 ("NGN" → 2,050,000) and the (epoch) day the rates are for. */
data class PoundRatesRead(val milliPerPound: Map<String, Long>, val day: Long)

/** Today's rates against the pound, or null when they can't be read just now. */
fun interface PoundRates {
    fun read(): PoundRatesRead?
}

/**
 * Spending plans, slice 3 (Meka's yes 2026-11-11 22:15 to a free public rate service): ExchangeRate-API's open access
 * endpoint (`https://open.er-api.com/v6/latest/GBP`, updated once a day, no key, free, attribution shown in the rate
 * sheet). It covers the naira and the other currencies a plan can be in, which the ECB's file does not. A plain public
 * GET of the pound's rates: nothing about Meka is sent. Read at most every [EVERY_MS]; a failed read keeps the last good
 * rates and is tried again after [RETRY_MS].
 */
class OpenPoundRates internal constructor(
    private val http: Http,
    private val now: () -> Long = System::currentTimeMillis,
) : PoundRates {
    /** (status, body) for a GET of [url]; injected so tests never touch the network. */
    fun interface Http { fun get(url: String): Pair<Int, String> }

    @Volatile private var last: PoundRatesRead? = null
    @Volatile private var triedAt = Long.MIN_VALUE / 2
    @Volatile private var readAt = Long.MIN_VALUE / 2

    override fun read(): PoundRatesRead? {
        val t = now()
        if (last != null && t - readAt < EVERY_MS) return last
        if (t - triedAt < RETRY_MS) return last
        triedAt = t
        runCatching {
            val (status, body) = http.get(URL)
            if (status == 200 && body.length <= MAX_BYTES) parse(body) else null
        }.getOrNull()?.let { last = it; readAt = t }
        return last
    }

    companion object {
        const val URL = "https://open.er-api.com/v6/latest/GBP"
        const val EVERY_MS = 6 * 60 * 60_000L
        const val RETRY_MS = 30 * 60_000L
        private const val MAX_BYTES = 64_000
        /** The highest rate believed (a million to £1), as the devices cap it. */
        private const val MAX_MILLI = 1_000_000_000L

        /** The service's answer → each currency's units to £1 × 1,000; null unless it's a success against the pound. */
        fun parse(json: String): PoundRatesRead? {
            val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            if (o["result"]?.jsonPrimitive?.content != "success" || o["base_code"]?.jsonPrimitive?.content != "GBP") return null
            val at = o["time_last_update_unix"]?.jsonPrimitive?.longOrNull ?: return null
            val rates = runCatching { o["rates"]?.jsonObject }.getOrNull() ?: return null
            val milli = rates.mapNotNull { (code, v) ->
                val d = runCatching { v.jsonPrimitive.doubleOrNull }.getOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
                val m = BigDecimal(d).movePointRight(3).setScale(0, RoundingMode.HALF_UP).toLong()
                (code.uppercase() to m).takeIf { code.length == 3 && m in 1..MAX_MILLI }
            }.toMap()
            if (milli.isEmpty()) return null
            return PoundRatesRead(milli, Instant.ofEpochSecond(at).atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay())
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

        val NETWORK = Http { url ->
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            res.statusCode() to res.body()
        }

        fun network(): OpenPoundRates = OpenPoundRates(NETWORK)
    }
}
