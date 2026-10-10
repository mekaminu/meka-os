package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import os.meka.core.domain.TravelRules
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Drive times to the kids' football (weekend football, slice 2b; Meka chose Google 2026-10-10, Needs Meka #19).
 * Only home's coordinates (the town the weather uses, never an address) and the ground's place leave MEKA.
 */
interface TravelProvider {
    val id: String
    /** Shown on the Calendars screen with the other feeds. */
    val label: String
    /** Whether the server has a key; without one the feed is off and nothing is sent anywhere. */
    fun configured(): Boolean
    /** Minutes of driving with traffic from [fromLat]/[fromLon] to [to], leaving at [departAtMs]. Throws on failure. */
    fun driveMinutes(fromLat: Double, fromLon: Double, to: String, departAtMs: Long): Int
}

/** The Google key from its secret (`{"api_key": "AIza…"}`); null for the placeholder `{}` or anything unlike a key. */
object GoogleMapsKey {
    fun parse(json: String?): String? = runCatching {
        val o = Json.parseToJsonElement(json ?: return null).jsonObject
        o["api_key"]?.jsonPrimitive?.content?.trim()?.takeIf { it.length in 20..200 && it.none(Char::isWhitespace) }
    }.getOrNull()
}

/**
 * Google's Routes API (`directions/v2:computeRoutes`), driving, traffic-aware (the Pro SKU: 5,000 free a month; MEKA
 * asks a few times per fixture and at most [TravelRules.MAX_PER_DAY] times a day). The field mask asks for the
 * duration only. The key goes in a header, never in a URL or a log.
 */
class GoogleRoutes internal constructor(
    private val key: () -> String?,
    /** POSTs JSON with headers; returns (status, body). */
    private val post: (url: String, headers: Map<String, String>, body: String) -> Pair<Int, String>,
) : TravelProvider {
    override val id = TravelRules.PROVIDER
    override val label = TravelRules.LABEL

    override fun configured(): Boolean = key() != null

    override fun driveMinutes(fromLat: Double, fromLon: Double, to: String, departAtMs: Long): Int {
        val k = key() ?: error("no key")
        val (status, body) = post(URL, mapOf("X-Goog-Api-Key" to k, "X-Goog-FieldMask" to "routes.duration"), request(fromLat, fromLon, to, departAtMs))
        check(status == 200) { "routes $status" }
        return parse(body) ?: error("no route")
    }

    companion object {
        const val URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
        private const val MAX_BYTES = 100_000

        fun request(fromLat: Double, fromLon: Double, to: String, departAtMs: Long): String = buildJsonObject {
            putJsonObject("origin") { putJsonObject("location") { putJsonObject("latLng") { put("latitude", fromLat); put("longitude", fromLon) } } }
            putJsonObject("destination") { put("address", to.take(TravelRules.MAX_PLACE)) }
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE")
            put("departureTime", Instant.ofEpochMilli(departAtMs).toString())
            put("regionCode", "GB")
        }.toString()

        /** The first route's minutes ("1534s" → 26), or null without a usable route. */
        fun parse(body: String): Int? {
            if (body.length > MAX_BYTES) return null
            val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val route = (o["routes"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val d = (route["duration"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val seconds = d.removeSuffix("s").toDoubleOrNull()?.takeIf { d.endsWith("s") && it.isFinite() } ?: return null
            return TravelRules.driveMin(kotlin.math.ceil(seconds).toLong())
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

        fun httpPost(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            return res.statusCode() to res.body()
        }

        /** From the deployment's secret, read at use time and cached for a minute (pasting the key needs no restart). */
        fun fromSecret(secretId: String): GoogleRoutes {
            val sm = software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.builder()
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, String?>? = null
            val lock = Any()
            val key = {
                synchronized(lock) {
                    val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 60_000 }
                    if (hit != null) hit.second else {
                        val v = runCatching { GoogleMapsKey.parse(sm.getSecretValue { it.secretId(secretId) }.secretString()) }.getOrNull()
                        cached = System.currentTimeMillis() to v
                        v
                    }
                }
            }
            return GoogleRoutes(key, ::httpPost)
        }
    }
}
