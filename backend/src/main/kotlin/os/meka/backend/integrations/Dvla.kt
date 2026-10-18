package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import os.meka.core.domain.CarCheck
import os.meka.core.domain.CarCheckRules
import os.meka.core.domain.CivilDate
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Car on autopilot, slice 2: what the DVLA says about Meka's car. Only the registration is sent (Needs Meka #26: two
 * free government keys); nothing else of Meka's leaves MEKA.
 */
interface CarLookupProvider {
    val id: String
    /** Shown on the Calendars screen with the other feeds. */
    val label: String
    /** Whether the server has at least one of the keys; without them the feed is off and nothing is sent anywhere. */
    fun configured(): Boolean
    /** What the DVLA has for [registration] ("AB12CDE"), checked at [nowMs]. Throws when a configured lookup fails. */
    fun lookup(registration: String, nowMs: Long): CarCheck
}

/** The keys from `meka-os-dev/car/dvla`; each half is null until its keys are all there. */
data class DvlaKeys(val vesApiKey: String?, val mot: MotKeys?) {
    data class MotKeys(val clientId: String, val clientSecret: String, val apiKey: String, val tokenUrl: String) {
        /** Never the keys themselves, should one ever reach a log. */
        override fun toString() = "MotKeys(…)"
    }

    override fun toString() = "DvlaKeys(ves=${vesApiKey != null}, mot=${mot != null})"

    val any: Boolean get() = vesApiKey != null || mot != null

    companion object {
        /** `{"ves_api_key","mot_client_id","mot_client_secret","mot_api_key","mot_token_url"}`; null for `{}` or junk. */
        fun parse(json: String?): DvlaKeys? = runCatching {
            val o = Json.parseToJsonElement(json ?: return null).jsonObject
            fun v(k: String) = o[k]?.jsonPrimitive?.content?.trim()?.takeIf { it.length in 8..2_000 && it.none(Char::isWhitespace) }
            val ves = v("ves_api_key")
            val tokenUrl = v("mot_token_url")?.takeIf { it.startsWith("https://") }
            val mot = listOf(v("mot_client_id"), v("mot_client_secret"), v("mot_api_key"), tokenUrl)
                .takeIf { l -> l.all { it != null } }?.let { MotKeys(it[0]!!, it[1]!!, it[2]!!, it[3]!!) }
            DvlaKeys(ves, mot).takeIf { it.any }
        }.getOrNull()
    }
}

/**
 * The DVLA's Vehicle Enquiry Service (tax status and due date, the MOT expiry, the make) and DVSA's MOT History API
 * (the tests: the MOT due date, the last result and its advisories). Either half works alone. Keys go in headers,
 * never in a URL or a log; a registration the DVLA doesn't know is an answer ([CarCheck.found] false), not a failure.
 */
class DvlaCarLookup internal constructor(
    private val keys: () -> DvlaKeys?,
    private val http: Http,
) : CarLookupProvider {
    /** (status, body) for a request; injected so tests never touch the network. */
    interface Http {
        fun postJson(url: String, headers: Map<String, String>, body: String): Pair<Int, String>
        fun postForm(url: String, form: Map<String, String>): Pair<Int, String>
        fun get(url: String, headers: Map<String, String>): Pair<Int, String>
    }

    override val id = CarCheckRules.PROVIDER
    override val label = CarCheckRules.LABEL

    /** The MOT API's bearer token and when it runs out (ms). */
    private var token: Pair<String, Long>? = null

    override fun configured(): Boolean = keys()?.any == true

    override fun lookup(registration: String, nowMs: Long): CarCheck {
        require(registration.length in 2..8 && registration.all { it in 'A'..'Z' || it in '0'..'9' }) { "not a registration" }
        val k = keys() ?: error("no keys")
        val ves = k.vesApiKey?.let { ves(it, registration) }
        val mot = k.mot?.let { mot(it, registration, nowMs) }
        val found = (ves?.found ?: false) || (mot?.found ?: false)
        return CarCheck(
            registration = registration,
            found = found,
            make = mot?.make ?: ves?.make,
            motDueDay = mot?.motDueDay ?: ves?.motDueDay,
            taxStatus = ves?.taxStatus,
            taxDueDay = ves?.taxDueDay,
            lastTestDay = mot?.lastTestDay,
            lastPassed = mot?.lastPassed,
            advisories = mot?.advisories ?: 0,
            checkedMs = nowMs,
        )
    }

    private fun ves(apiKey: String, reg: String): CarCheck {
        val body = buildJsonObject { put("registrationNumber", reg) }.toString()
        val (status, text) = http.postJson(VES_URL, mapOf("x-api-key" to apiKey), body)
        if (status == 404) return CarCheck(reg, found = false)
        check(status == 200) { "ves $status" }
        return parseVes(reg, text) ?: error("ves unreadable")
    }

    private fun mot(keys: DvlaKeys.MotKeys, reg: String, nowMs: Long): CarCheck {
        val bearer = bearer(keys, nowMs)
        val (status, text) = http.get(MOT_URL + URLEncoder.encode(reg, Charsets.UTF_8), mapOf("Authorization" to "Bearer $bearer", "X-API-Key" to keys.apiKey))
        if (status == 401) token = null
        if (status == 404) return CarCheck(reg, found = false)
        check(status == 200) { "mot $status" }
        return parseMot(reg, text) ?: error("mot unreadable")
    }

    @Synchronized
    private fun bearer(keys: DvlaKeys.MotKeys, nowMs: Long): String {
        token?.let { (t, until) -> if (nowMs < until) return t }
        val (status, text) = http.postForm(
            keys.tokenUrl,
            mapOf("grant_type" to "client_credentials", "client_id" to keys.clientId, "client_secret" to keys.clientSecret, "scope" to MOT_SCOPE),
        )
        check(status == 200) { "mot token $status" }
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: error("mot token unreadable")
        val t = (o["access_token"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: error("no mot token")
        val secs = (o["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3_600L
        token = t to nowMs + (secs - 60).coerceAtLeast(60) * 1_000
        return t
    }

    companion object {
        const val VES_URL = "https://driver-vehicle-licensing.api.gov.uk/vehicle-enquiry/v1/vehicles"
        const val MOT_URL = "https://history.mot.api.gov.uk/v1/trade/vehicles/registration/"
        const val MOT_SCOPE = "https://tapi.dvsa.gov.uk/.default"
        private const val MAX_BYTES = 500_000

        /** "2026-11-02" (or "2025-11-03T10:15:00.000Z": the date part) → epoch day; null otherwise. Read by hand. */
        fun day(text: String?): Long? {
            val t = text?.trim()?.take(10) ?: return null
            if (t.length != 10 || t[4] != '-' || t[7] != '-') return null
            val y = t.substring(0, 4).toIntOrNull() ?: return null
            val m = t.substring(5, 7).toIntOrNull() ?: return null
            val d = t.substring(8, 10).toIntOrNull() ?: return null
            if (y !in 1950..2200 || m !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, m)) return null
            return CivilDate.toEpochDay(y, m, d)
        }

        private fun obj(body: String): JsonObject? =
            if (body.length > MAX_BYTES) null else runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

        /** The Vehicle Enquiry Service's answer: tax status and due date, MOT expiry, make. */
        fun parseVes(reg: String, body: String): CarCheck? {
            val o = obj(body) ?: return null
            return CarCheck(
                registration = reg, found = true,
                make = o.str("make"),
                motDueDay = day(o.str("motExpiryDate")),
                taxStatus = o.str("taxStatus"),
                taxDueDay = day(o.str("taxDueDate")),
            )
        }

        /**
         * The MOT History API's answer. The MOT is due when the newest passed test expires (a failed test since then
         * doesn't move it), or on a new car's `motTestDueDate`; the last test is the newest of all, with its advisories.
         */
        fun parseMot(reg: String, body: String): CarCheck? {
            val o = obj(body) ?: return null
            val tests = (o["motTests"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .mapNotNull { t -> day(t.str("completedDate"))?.let { it to t } }
                .sortedByDescending { it.first }
            val last = tests.firstOrNull()
            val passed = tests.firstOrNull { it.second.str("testResult").equals("PASSED", ignoreCase = true) }
            val due = passed?.second?.let { day(it.str("expiryDate")) } ?: day(o.str("motTestDueDate"))
            val advisories = last?.second?.let { t ->
                (t["defects"] as? JsonArray).orEmpty().count { d -> (d as? JsonObject)?.str("type").equals("ADVISORY", ignoreCase = true) }
            } ?: 0
            return CarCheck(
                registration = reg, found = true,
                make = o.str("make"),
                motDueDay = due,
                lastTestDay = last?.first,
                lastPassed = last?.second?.str("testResult")?.equals("PASSED", ignoreCase = true),
                advisories = advisories,
            )
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

        private fun send(req: HttpRequest.Builder): Pair<Int, String> {
            val res = client.send(req.timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString())
            return res.statusCode() to res.body()
        }

        val NETWORK = object : Http {
            override fun postJson(url: String, headers: Map<String, String>, body: String) = send(
                HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json").header("Accept", "application/json")
                    .apply { headers.forEach { (k, v) -> header(k, v) } }.POST(HttpRequest.BodyPublishers.ofString(body)),
            )

            override fun postForm(url: String, form: Map<String, String>) = send(
                HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" })),
            )

            override fun get(url: String, headers: Map<String, String>) = send(
                HttpRequest.newBuilder(URI(url)).header("Accept", "application/json").apply { headers.forEach { (k, v) -> header(k, v) } }.GET(),
            )

            private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
        }

        /** From the deployment's secret, read at use time through SecretCache (six hours; 15 minutes while unset, so pasting it needs no restart). */
        fun fromSecret(secretId: String): DvlaCarLookup {
            val keys = os.meka.backend.integrations.SecretCache.shared.reader(secretId) { DvlaKeys.parse(it) }
            return DvlaCarLookup(keys, NETWORK)
        }
    }
}
