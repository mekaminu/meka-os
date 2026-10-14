package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import os.meka.core.domain.Coord
import os.meka.core.domain.FuelKind
import os.meka.core.domain.FuelRules
import os.meka.core.domain.FuelSite
import os.meka.core.wire.FuelCodec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The keys from `meka-os-dev/car/fuelfinder` (Needs Meka #34: a free Fuel Finder developer account). */
data class FuelFinderKeys(val clientId: String, val clientSecret: String) {
    /** Never the keys themselves, should one ever reach a log. */
    override fun toString() = "FuelFinderKeys(…)"

    companion object {
        /** `{"client_id","client_secret"}`; null for `{}` or junk. */
        fun parse(json: String?): FuelFinderKeys? = runCatching {
            val o = Json.parseToJsonElement(json ?: return null).jsonObject
            fun v(k: String) = o[k]?.jsonPrimitive?.content?.trim()?.takeIf { it.length in 4..2_000 && it.none(Char::isWhitespace) }
            FuelFinderKeys(v("client_id") ?: return null, v("client_secret") ?: return null)
        }.getOrNull()
    }
}

/**
 * Cheapest fuel nearby (Car and parking, slice 6) from the government's **Fuel Finder** open data: every forecourt in
 * the UK and its latest prices, kept in memory here (no table, no migration) and refreshed only when a phone asks and the
 * copy is over [PRICES_MS] old (the forecourts themselves once a day). The phone sends a point rounded to about 1 km and
 * a fuel; [near] answers from the copy at once. The very first ask after a start fetches the copy in the background and
 * says "loading". The point is neither stored nor logged; only the keys go to Fuel Finder, nothing of Meka's.
 */
class FuelFinder internal constructor(
    private val keys: () -> FuelFinderKeys?,
    private val http: Http,
    private val now: () -> Long = System::currentTimeMillis,
    /** Runs the refresh off the request. */
    private val background: (() -> Unit) -> Unit = { Thread.ofVirtual().start(it) },
    private val baseUrl: String = BASE_URL,
) {
    /** (status, body); injected so tests never touch the network. */
    interface Http {
        fun postJson(url: String, body: String): Pair<Int, String>
        fun get(url: String, headers: Map<String, String>): Pair<Int, String>
    }

    private data class Copy(val sites: List<FuelSite>, val pricesAtMs: Long, val sitesAtMs: Long)

    @Volatile private var copy: Copy? = null
    @Volatile private var refreshing = false
    @Volatile private var lastTryMs = 0L
    @Volatile private var lastFailed = false
    private var token: Pair<String, Long>? = null
    private val lock = Any()

    fun configured(): Boolean = keys() != null

    fun near(request: FuelCodec.Request): FuelCodec.Response {
        val fuel = FuelKind.of(request.fuel) ?: return FuelCodec.Response(FuelCodec.Response.FAILED, reason = "Unknown fuel")
        if (!configured()) return FuelCodec.Response(FuelCodec.Response.OFF)
        val t = now()
        val c = copy
        if (c == null || t - c.pricesAtMs > PRICES_MS) kick(t)
        if (c == null) {
            return if (lastFailed && !refreshing) FuelCodec.Response(FuelCodec.Response.FAILED, reason = "Fuel Finder isn't answering right now")
            else FuelCodec.Response(FuelCodec.Response.LOADING)
        }
        val point = Coord(request.lat, request.lon)
        val stations = FuelRules.nearest(c.sites, point, fuel).map {
            FuelCodec.Station(it.name, it.brand, it.postcode, it.lat, it.lon, it.pence, it.updatedMs, it.distanceM)
        }
        return FuelCodec.Response(FuelCodec.Response.OK, stations)
    }

    /** Starts one refresh unless one is running or the last try was under [RETRY_MS] ago. */
    private fun kick(t: Long) {
        synchronized(lock) {
            if (refreshing || t - lastTryMs < RETRY_MS) return
            refreshing = true
            lastTryMs = t
        }
        background {
            try {
                refresh()
                lastFailed = false
            } catch (e: Exception) {
                lastFailed = true // the old copy (if any) keeps answering
            } finally {
                refreshing = false
            }
        }
    }

    /** Fetches the prices (and the forecourts when a day old) and swaps the copy in. Throws when Fuel Finder fails. */
    internal fun refresh() {
        val k = keys() ?: error("no keys")
        val t = now()
        val old = copy
        val sites = if (old == null || t - old.sitesAtMs > SITES_MS) {
            batches(k, "/pfs").mapNotNull(::site).associateBy { it.id }
        } else old.sites.associate { it.id to it.copy(prices = emptyMap()) }
        val prices = HashMap<String, Map<FuelKind, Pair<Double, Long?>>>()
        for (o in batches(k, "/pfs/fuel-prices")) {
            val id = o.str("node_id") ?: continue
            prices[id] = pricesOf(o)
        }
        check(sites.isNotEmpty() && prices.isNotEmpty()) { "empty" }
        val merged = sites.values.mapNotNull { s -> prices[s.id]?.takeIf { it.isNotEmpty() }?.let { s.copy(prices = it) } }
        copy = Copy(merged, t, if (old == null || t - old.sitesAtMs > SITES_MS) t else old.sitesAtMs)
    }

    /** Every batch of [path] (500 a batch) until an empty one or a 404, at most [MAX_BATCHES]. */
    private fun batches(k: FuelFinderKeys, path: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        for (n in 1..MAX_BATCHES) {
            val (status, body) = authorisedGet(k, "$baseUrl$path?batch-number=$n")
            if (status == 404) break
            check(status == 200) { "fuel finder $status" }
            val items = list(body) ?: error("unreadable")
            if (items.isEmpty()) break
            out += items
            if (items.size < BATCH) break
        }
        return out
    }

    private fun authorisedGet(k: FuelFinderKeys, url: String): Pair<Int, String> {
        val first = http.get(url, mapOf("Authorization" to "Bearer ${bearer(k)}"))
        if (first.first != 401) return first
        synchronized(lock) { token = null }
        return http.get(url, mapOf("Authorization" to "Bearer ${bearer(k)}"))
    }

    private fun bearer(k: FuelFinderKeys): String = synchronized(lock) {
        val t = now()
        token?.let { (v, until) -> if (t < until) return v }
        val body = buildJsonObject { put("client_id", k.clientId); put("client_secret", k.clientSecret) }.toString()
        val (status, text) = http.postJson("$baseUrl/oauth/generate_access_token", body)
        check(status == 200) { "fuel finder token $status" }
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: error("token unreadable")
        val data = (o["data"] as? JsonObject) ?: o
        val v = data.str("access_token") ?: error("no token")
        val secs = (data["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3_600L
        token = v to t + (secs - 60).coerceAtLeast(60) * 1_000
        v
    }

    companion object {
        const val BASE_URL = "https://www.fuel-finder.service.gov.uk/api/v1"
        /** Prices are refreshed when a phone asks and the copy is older than this. */
        const val PRICES_MS = 60 * 60_000L
        /** The forecourts (names, places) once a day. */
        const val SITES_MS = 24 * 60 * 60_000L
        /** After a try (good or bad), the next at the earliest this much later. */
        const val RETRY_MS = 10 * 60_000L
        const val BATCH = 500
        const val MAX_BATCHES = 60
        private const val MAX_BYTES = 20_000_000
        private val LONDON: ZoneId = ZoneId.of("Europe/London")

        private fun JsonObject.str(k: String): String? =
            (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true

        private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.trim().toDoubleOrNull() }

        /** A batch's records: a bare array, or one wrapped in `data`. Null when it doesn't read. */
        internal fun list(body: String): List<JsonObject>? {
            if (body.length > MAX_BYTES) return null
            val e = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
            val arr = when (e) {
                is JsonArray -> e
                is JsonObject -> (e["data"] as? JsonArray) ?: return if (e["data"] == null) emptyList() else null
                else -> return null
            }
            return arr.mapNotNull { it as? JsonObject }
        }

        /** A forecourt: its id, name, brand, postcode and place; closed ones and ones with no place are left out. */
        internal fun site(o: JsonObject): FuelSite? {
            val id = o.str("node_id") ?: return null
            if (o.bool("temporary_closure") || o.bool("permanent_closure")) return null
            val loc = o["location"] as? JsonObject ?: return null
            val lat = loc["latitude"].num() ?: return null
            val lon = loc["longitude"].num() ?: return null
            if (lat !in 49.0..61.5 || lon !in -9.0..2.5) return null
            val name = o.str("trading_name") ?: o.str("brand_name") ?: return null
            return FuelSite(id, name.take(80), o.str("brand_name")?.take(40), loc.str("postcode")?.take(10), lat, lon, emptyMap())
        }

        /** A forecourt's prices: each fuel Navilife knows, in pence, with when it last changed. */
        internal fun pricesOf(o: JsonObject): Map<FuelKind, Pair<Double, Long?>> {
            val out = HashMap<FuelKind, Pair<Double, Long?>>()
            for (e in (o["fuel_prices"] as? JsonArray).orEmpty()) {
                val p = e as? JsonObject ?: continue
                val kind = FuelRules.kindOf(p.str("fuel_type")) ?: continue
                val pence = FuelRules.pence(p["price"].num()) ?: continue
                val at = time(p.str("price_change_effective_timestamp")) ?: time(p.str("price_last_updated"))
                val prev = out[kind]
                if (prev == null || (at ?: 0L) > (prev.second ?: 0L)) out[kind] = pence to at
            }
            return out
        }

        /** "2026-10-14T09:10:00Z", "2026-10-14T09:10:00.000+01:00" or "2026-10-14 09:10:00" (UK time) → ms; else null. */
        internal fun time(text: String?): Long? {
            val t = text?.trim()?.takeIf { it.length >= 16 } ?: return null
            runCatching { return Instant.parse(t).toEpochMilli() }
            runCatching { return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }
            runCatching { return LocalDateTime.parse(t.replace(' ', 'T').take(19)).atZone(LONDON).toInstant().toEpochMilli() }
            return null
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

        private fun send(req: HttpRequest.Builder): Pair<Int, String> {
            val res = client.send(req.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())
            return res.statusCode() to res.body()
        }

        val NETWORK = object : Http {
            override fun postJson(url: String, body: String) = send(
                HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)),
            )

            override fun get(url: String, headers: Map<String, String>) = send(
                HttpRequest.newBuilder(URI(url)).header("Accept", "application/json").apply { headers.forEach { (k, v) -> header(k, v) } }.GET(),
            )
        }

        /** From the deployment's secret, read at use time and cached for a minute (pasting the keys needs no restart). */
        fun fromSecret(secretId: String): FuelFinder {
            val sm = software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.builder()
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, FuelFinderKeys?>? = null
            val lock = Any()
            val keys = {
                synchronized(lock) {
                    val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 60_000 }
                    if (hit != null) hit.second else {
                        val v = runCatching { FuelFinderKeys.parse(sm.getSecretValue { it.secretId(secretId) }.secretString()) }.getOrNull()
                        cached = System.currentTimeMillis() to v
                        v
                    }
                }
            }
            return FuelFinder(keys, NETWORK)
        }
    }
}
