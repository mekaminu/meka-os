package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.TrainDeparture

/** One read of a departure board: the departures kept and how many services the board listed. */
data class RailBoardRead(val departures: List<TrainDeparture>, val services: Int)

/** The rail data key was refused (401/403 on every endpoint tried). */
class RailKeyRefused : RuntimeException("rail key refused")

/**
 * Live trains (Needs Meka #16, 2026-10-17): the next departures from one station, optionally only trains calling at
 * another. Read-only; the only things sent are the two stations' public National Rail codes.
 */
interface RailBoardProvider {
    /** Whether the server holds the rail key; without it nothing is read. */
    fun configured(): Boolean
    /** The next departures from [fromCrs] (calling at [toCrs] when given). Throws when they can't be read. */
    fun departures(fromCrs: String, toCrs: String?): RailBoardRead
    /** Which endpoint last answered ("1010-live-departure-board-dep1_2 · GetDepBoardWithDetails"), for the deploy report. */
    val endpoint: String?
}

/**
 * National Rail's Live Departure Board on the Rail Data Marketplace (raildata.org.uk; Rail Delivery Group, open
 * licence): `GET {base}/GetDepBoardWithDetails/{crs}?numRows&filterCrs&filterType=to&timeOffset&timeWindow` with the
 * product's consumer key in the `x-apikey` header — never in a URL or a log. The product's exact base path differs by
 * version, so the secret may name it (`base_url`); otherwise the known paths are tried in order and the one that
 * answers is kept ([endpoint] says which). "With details" gives each train's calling points, so the arrival where he
 * gets off is known; a product without it falls back to `GetDepartureBoard`.
 */
class DarwinBoard internal constructor(
    private val key: () -> Pair<String, String?>?,
    private val http: Http,
) : RailBoardProvider {
    /** (status, body) for a GET; injected so tests never touch the network. */
    fun interface Http { fun get(url: String, headers: Map<String, String>): Pair<Int, String> }

    @Volatile private var working: Pair<String, String>? = null

    override val endpoint: String? get() = working?.let { (base, op) -> base.substringAfter("raildata.org.uk/").substringBefore('/') + " · " + op }

    override fun configured(): Boolean = key() != null

    private fun candidates(own: String?): List<Pair<String, String>> {
        val bases = (listOfNotNull(own) + BASES).distinct()
        return bases.flatMap { b -> OPS.map { b to it } }
    }

    override fun departures(fromCrs: String, toCrs: String?): RailBoardRead {
        require(CRS.matches(fromCrs) && (toCrs == null || CRS.matches(toCrs))) { "not a station code" }
        val (k, own) = key() ?: error("no key")
        val tries = working?.let { listOf(it) + candidates(own).filter { c -> c != it } } ?: candidates(own)
        var refused = false
        for ((base, op) in tries) {
            val url = "$base/$op/$fromCrs?numRows=$ROWS" + (toCrs?.let { "&filterCrs=$it&filterType=to" } ?: "") + "&timeOffset=0&timeWindow=$WINDOW_MIN"
            val (status, body) = http.get(url, mapOf("x-apikey" to k))
            when {
                status == 401 || status == 403 -> { refused = true; continue }
                status == 404 || status == 400 || status == 405 -> continue
                status == 429 -> error("rail rate limited")
                status != 200 -> error("rail $status")
            }
            val read = parse(body, toCrs) ?: continue // a 200 that isn't a board (a gateway page): try the next path
            working = base to op
            return read
        }
        if (refused) throw RailKeyRefused()
        error("no rail endpoint answered")
    }

    companion object {
        /** The Live Departure Board product's paths on the marketplace, newest first. */
        val BASES = listOf(
            "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120",
            "https://api1.raildata.org.uk/1010-live-departure-board-dep1_1/LDBWS/api/20220120",
            "https://api1.raildata.org.uk/1010-live-departure-board-dep/LDBWS/api/20220120",
        )
        val OPS = listOf("GetDepBoardWithDetails", "GetDepartureBoard")
        const val ROWS = 10
        const val WINDOW_MIN = 120
        private const val MAX_BYTES = 2_000_000
        private val CRS = Regex("[A-Z]{3}")

        /**
         * The board's departures (booked time, expected, platform, arrival at [toCrs] from the calling points), in
         * booked order; null when [body] isn't a departure board at all. A board with no trains is an empty list.
         */
        fun parse(body: String, toCrs: String?): RailBoardRead? {
            if (body.length > MAX_BYTES) error("rail answer too big")
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            if (listOf("trainServices", "generatedAt", "locationName", "crs").none { it in root }) return null
            val services = (root["trainServices"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val out = services.mapNotNull { s ->
                val dep = minute(s.text("std")) ?: return@mapNotNull null
                val etd = s.text("etd")?.trim().orEmpty()
                val cancelled = s.bool("isCancelled") == true || s.bool("filterLocationCancelled") == true || etd.equals("Cancelled", ignoreCase = true)
                val exp = when {
                    cancelled -> null
                    etd.equals("On time", ignoreCase = true) || etd.isEmpty() -> dep
                    else -> minute(etd) // "Delayed" (no estimate) reads as null
                }
                TrainDeparture(dep, exp, cancelled = cancelled, delayed = !cancelled && exp == null,
                    platform = os.meka.core.domain.TrainBoardCodec.platform(s.text("platform")), arrMin = toCrs?.let { arrival(s, it) })
            }
            return RailBoardRead(out, services.size)
        }

        /** The booked time at [crs] among the service's later calling points. */
        private fun arrival(s: JsonObject, crs: String): Int? {
            val lists = (s["subsequentCallingPoints"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            for (l in lists) {
                val points = (l["callingPoint"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                points.firstOrNull { it.text("crs") == crs }?.let { return minute(it.text("st")) }
            }
            return null
        }

        /** "07:12" → 432; null for anything else ("Delayed", "On time"). */
        fun minute(t: String?): Int? {
            val s = t?.trim() ?: return null
            if (s.length != 5 || s[2] != ':') return null
            val h = s.substring(0, 2).toIntOrNull() ?: return null
            val m = s.substring(3).toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }

        private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

        /** The secret's `{"api_key": "…", "base_url": "https://…"}` (base optional), or a bare key; null for `{}` or junk. */
        fun parseSecret(secret: String?): Pair<String, String?>? {
            val key = LunchFlowFeed.parseKey(secret) ?: return null
            val base = runCatching {
                Json.parseToJsonElement(secret!!.trim()).jsonObject["base_url"]?.jsonPrimitive?.content
            }.getOrNull()?.trim()?.trimEnd('/')?.takeIf { it.startsWith("https://") && it.length < 300 && it.none(Char::isWhitespace) }
            return key to base
        }

        /** From the deployment's secret (its name or ARN), read at use time and cached for a minute: pasting a key needs no restart. */
        fun fromSecret(secretId: String): DarwinBoard {
            val sm = software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.builder()
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, Pair<String, String?>?>? = null
            val lock = Any()
            val key = {
                synchronized(lock) {
                    val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 60_000 }
                    if (hit != null) hit.second else {
                        val v = runCatching { parseSecret(sm.getSecretValue { it.secretId(secretId) }.secretString()) }.getOrNull()
                        cached = System.currentTimeMillis() to v
                        v
                    }
                }
            }
            return DarwinBoard(key) { url, headers -> LunchFlowFeed.NETWORK.get(url, headers) }
        }
    }
}

/**
 * National Rail's station codes by name (backend/src/main/resources/rail/stations.csv, ODbL; see its README), so the
 * household types "Oakfield" or "King's Cross" in About you and the server finds the code. Matching ignores case,
 * apostrophes and full stops, "&"/"and", "St"/"Saint", a leading "London" and a trailing "International"/"(…)" ;
 * a typed three-letter code that is a station's code is taken as it is. A name two stations share finds neither.
 */
object RailStations {
    private val byKey: Map<String, String> by lazy { index(load()) }
    private val codes: Set<String> by lazy { load().map { it.second }.toSet() }

    private fun load(): List<Pair<String, String>> =
        (RailStations::class.java.getResourceAsStream("/rail/stations.csv") ?: error("no station list")).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
                val i = l.lastIndexOf(',')
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().uppercase()
            }.toList()
        }

    internal fun index(rows: List<Pair<String, String>>): Map<String, String> {
        val all = HashMap<String, MutableSet<String>>()
        for ((name, crs) in rows) for (k in keys(name)) all.getOrPut(k) { HashSet() }.add(crs)
        // The full name always wins over a shortened one ("London Bridge" vs a "Bridge" elsewhere).
        val exact = rows.groupBy { norm(it.first) }.filterValues { v -> v.map { it.second }.toSet().size == 1 }.mapValues { it.value.first().second }
        return all.filterValues { it.size == 1 }.mapValues { it.value.first() } + exact
    }

    /** The code for [typed]; null when no station (or more than one) has that name. */
    fun crs(typed: String?): String? {
        val t = typed?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.length == 3 && t.all { it.isLetter() } && t.uppercase() in codes) return t.uppercase()
        return keys(t).firstNotNullOfOrNull { byKey[it] }
    }

    /** The full normalised name, then shorter forms: without "London ", without a trailing "International" or "(…)". */
    internal fun keys(name: String): List<String> {
        val n = norm(name)
        val out = linkedSetOf(n)
        var short = n
        val paren = short.indexOf(" (")
        if (paren > 0) short = short.substring(0, paren).trim()
        short = short.removeSuffix(" international").removeSuffix(" intl").trim()
        out += short
        if (short.startsWith("london ")) out += short.removePrefix("london ").trim()
        return out.filter { it.isNotEmpty() }
    }

    internal fun norm(s: String): String {
        val t = s.lowercase().replace("’", "").replace("'", "").replace(".", "").replace("&", " and ")
        val words = t.split(' ', '-', ',', '/').filter { it.isNotEmpty() }.map { if (it == "saint") "st" else it }
        return words.joinToString(" ")
    }
}
