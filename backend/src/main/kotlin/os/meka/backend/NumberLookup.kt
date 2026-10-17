package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.NumberLookupRules
import os.meka.core.domain.NumberReputation
import os.meka.core.domain.NumberVerdict
import os.meka.core.domain.WebHit
import os.meka.core.wire.NumberLookupCodec
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

/** A web search failed; [reason] is a few plain words for the deploy report (never the query). */
class SearchFailed(val reason: String) : RuntimeException(reason)

/** Searches the web for one query (a number in quotes). */
interface NumberSearch {
    fun configured(): Boolean
    /** The results, or [SearchFailed]. */
    fun search(query: String): List<WebHit>
}

/**
 * Brave Search API's web search (Meka stored the key 2026-10-17 in `meka-os-dev/search/brave`, Search plan with $5 of
 * free credit a month and a $0 spend cap, so a lookup simply fails once the month's credit is used). The key goes in
 * the `X-Subscription-Token` header only, never a URL or a log; the query is the number alone. Brave asks for
 * attribution, which the server's home page carries.
 */
class BraveSearch internal constructor(private val key: () -> String?, private val http: Http) : NumberSearch {
    fun interface Http { fun get(url: String, headers: Map<String, String>): Pair<Int, String> }

    override fun configured(): Boolean = key() != null

    override fun search(query: String): List<WebHit> {
        val k = key() ?: throw SearchFailed("no key")
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val headers = mapOf("X-Subscription-Token" to k, "Accept" to "application/json")
        var (status, body) = http.get("$BASE?q=$q&count=$COUNT&country=GB&search_lang=en&extra_snippets=true", headers)
        // A refused parameter (422): ask again with the query alone.
        if (status == 422) http.get("$BASE?q=$q&count=$COUNT", headers).let { status = it.first; body = it.second }
        when {
            status == 401 || status == 403 -> throw SearchFailed("key refused ($status)")
            status == 402 || status == 429 -> throw SearchFailed("out of credit or rate limited ($status)")
            status != 200 -> throw SearchFailed("HTTP $status")
        }
        if (body.length > MAX_BYTES) throw SearchFailed("answer too big")
        return parse(body) ?: throw SearchFailed("answer unreadable")
    }

    companion object {
        const val BASE = "https://api.search.brave.com/res/v1/web/search"
        const val COUNT = 20
        private const val MAX_BYTES = 2_000_000

        /** Brave's `web.results` as hits (tags stripped); an answer with no web block is no results; junk is null. */
        fun parse(body: String): List<WebHit>? = runCatching {
            val o = Json.parseToJsonElement(body).jsonObject
            val results = ((o["web"] as? JsonObject)?.get("results") as? JsonArray) ?: return@runCatching emptyList()
            results.mapNotNull { it as? JsonObject }.mapNotNull { r ->
                val url = r.text("url") ?: return@mapNotNull null
                val extra = (r["extra_snippets"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                WebHit(url, strip(r.text("title").orEmpty()), strip((listOfNotNull(r.text("description")) + extra).joinToString(" ")))
            }
        }.getOrNull()

        private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun strip(s: String) = s.replace(Regex("<[^>]{0,40}>"), "").replace("&amp;", "&").replace("&#x27;", "'")
            .replace("&quot;", "\"").replace("&#39;", "'").trim()

        /** The secret's `{"api_key": "…"}` (or a bare key); null for `{}` or junk. */
        fun parseKey(secret: String?): String? {
            val s = secret?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val k = if (s.startsWith("{")) runCatching {
                val o = Json.parseToJsonElement(s).jsonObject
                listOf("api_key", "apiKey", "key", "API_KEY").firstNotNullOfOrNull { o[it]?.jsonPrimitive?.content }
            }.getOrNull() else s
            return k?.trim()?.takeIf { it.length in 8..500 && it.none(Char::isWhitespace) }
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

        val NETWORK = Http { url, headers ->
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(6))
                .apply { headers.forEach { (k, v) -> header(k, v) } }.GET().build()
            try {
                val res = client.send(req, HttpResponse.BodyHandlers.ofString())
                res.statusCode() to res.body()
            } catch (e: java.net.http.HttpTimeoutException) {
                throw SearchFailed("timed out")
            } catch (e: java.io.IOException) {
                throw SearchFailed("network error")
            }
        }

        /** From the deployment's secret (its name), read at use time and cached for a minute: pasting a key needs no restart. */
        fun fromSecret(secretId: String): BraveSearch {
            val sm = software.amazon.awssdk.services.secretsmanager.SecretsManagerClient.builder()
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create()).build()
            var cached: Pair<Long, String?>? = null
            val lock = Any()
            val key = {
                synchronized(lock) {
                    val hit = cached?.takeIf { System.currentTimeMillis() - it.first < 60_000 }
                    if (hit != null) hit.second else {
                        val v = runCatching { parseKey(sm.getSecretValue { it.secretId(secretId) }.secretString()) }.getOrNull()
                        cached = System.currentTimeMillis() to v
                        v
                    }
                }
            }
            return BraveSearch(key, NETWORK)
        }
    }
}

/**
 * One number's looked-up answer, kept per household under a hash of the number (never the number): [verdict] scam ·
 * nuisance · legit · unknown, or failed (with [reason]); [results] and [reportHits] are counts for the deploy report.
 */
data class LookupRow(
    val verdict: String,
    val reports: Int = 0,
    val sure: Boolean = false,
    val why: String? = null,
    val results: Int = 0,
    val reportHits: Int = 0,
    val reason: String? = null,
    val checkedAtMs: Long,
) {
    val failed: Boolean get() = verdict == FAILED

    fun reputation(): NumberReputation? = when (verdict) {
        "scam" -> NumberReputation(NumberVerdict.SCAM, reports, sure, why)
        "nuisance" -> NumberReputation(NumberVerdict.NUISANCE, reports, sure, why)
        "legit" -> NumberReputation(NumberVerdict.LEGIT, reports, sure, why)
        "unknown" -> NumberReputation(NumberVerdict.UNKNOWN, reports, sure, why)
        else -> null
    }

    companion object {
        const val FAILED = "failed"
        fun word(v: NumberVerdict) = v.name.lowercase()
    }
}

interface NumberLookupStore {
    fun get(householdId: String, hash: String): LookupRow?
    fun put(householdId: String, hash: String, row: LookupRow)
    /** How many rows were checked since [sinceMs], by verdict. */
    fun counts(householdId: String, sinceMs: Long): Map<String, Int>
}

class InMemoryNumberLookupStore : NumberLookupStore {
    private val rows = HashMap<Pair<String, String>, LookupRow>()
    @Synchronized override fun get(householdId: String, hash: String) = rows[householdId to hash]
    @Synchronized override fun put(householdId: String, hash: String, row: LookupRow) { rows[householdId to hash] = row }
    @Synchronized override fun counts(householdId: String, sinceMs: Long) =
        rows.filter { it.key.first == householdId && it.value.checkedAtMs >= sinceMs }.values.groupingBy { it.verdict }.eachCount()
}

/** Migration V27's `number_lookup`: one row per household and number hash, replaced on each lookup. */
class PostgresNumberLookupStore(private val ds: DataSource) : NumberLookupStore {
    override fun get(householdId: String, hash: String): LookupRow? = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT verdict, reports, sure, why, results, report_hits, reason, checked_at_ms FROM number_lookup WHERE household_id = ? AND number_hash = ?",
        ).use { st ->
            st.setString(1, householdId); st.setString(2, hash)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else LookupRow(
                    rs.getString(1), rs.getInt(2), rs.getBoolean(3), rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getLong(8),
                )
            }
        }
    }

    override fun put(householdId: String, hash: String, row: LookupRow) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO number_lookup(household_id, number_hash, verdict, reports, sure, why, results, report_hits, reason, checked_at_ms)
               VALUES (?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT (household_id, number_hash) DO UPDATE SET verdict = EXCLUDED.verdict, reports = EXCLUDED.reports,
                 sure = EXCLUDED.sure, why = EXCLUDED.why, results = EXCLUDED.results, report_hits = EXCLUDED.report_hits,
                 reason = EXCLUDED.reason, checked_at_ms = EXCLUDED.checked_at_ms""",
        ).use {
            it.setString(1, householdId); it.setString(2, hash); it.setString(3, row.verdict); it.setInt(4, row.reports)
            it.setBoolean(5, row.sure); it.setString(6, row.why); it.setInt(7, row.results); it.setInt(8, row.reportHits)
            it.setString(9, row.reason); it.setLong(10, row.checkedAtMs)
            it.executeUpdate()
        }
        Unit
    }

    override fun counts(householdId: String, sinceMs: Long): Map<String, Int> = ds.connection.use { c ->
        c.prepareStatement("SELECT verdict, count(*) FROM number_lookup WHERE household_id = ? AND checked_at_ms >= ? GROUP BY verdict").use { st ->
            st.setString(1, householdId); st.setLong(2, sinceMs)
            st.executeQuery().use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getInt(2)) } }
        }
    }
}

/**
 * Spam-call lookup on the server (build plan "Take first: spam-call lookup is on", 2026-10-17). The Fold asks about a
 * caller it doesn't know; the answer comes from the household's cache when fresh ([NumberLookupRules.keepDays]: 30
 * days for a verdict, a week for "nothing known", a day after a failed search, so a number is searched at most once a
 * day), else from one web search read by [NumberLookupRules.classify]. The search runs on its own (two at a time):
 * when it takes longer than the Fold can wait, the answer is "pending" and the result is kept for the next ask (the
 * after-call look). The same number asked twice at once is searched once. Numbers are kept only as a hash and never
 * logged; a failed search is never spam.
 */
class NumberLookup(
    private val search: NumberSearch,
    private val store: NumberLookupStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val pool: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "number-lookup").apply { isDaemon = true } },
) {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<LookupRow>>()

    fun lookUp(householdId: String, number: String, waitMs: Long, maxAgeMs: Long? = null): NumberLookupCodec.Response {
        if (!search.configured()) return NumberLookupCodec.Response(NumberLookupCodec.Response.OFF)
        val national = NumberLookupRules.national(number)
            ?: return NumberLookupCodec.Response(NumberLookupCodec.Response.OK, "unknown")
        val hash = hash(householdId, national)
        runCatching { store.get(householdId, hash) }.getOrNull()?.takeIf { fresh(it, maxAgeMs) }?.let { return respond(it) }
        val mine = CompletableFuture<LookupRow>()
        val running = inFlight.putIfAbsent(hash, mine)
        if (running == null) {
            pool.execute {
                // Out of inFlight before anyone can see the answer, so an ask that comes after it (a day later, say)
                // reads the store instead of being handed this finished search (CI run 778's race).
                val result = runCatching { run(householdId, hash, number) }
                inFlight.remove(hash, mine)
                result.fold({ mine.complete(it) }, { mine.completeExceptionally(it) })
            }
        }
        val f = running ?: mine
        return try {
            respond(f.get(waitMs.coerceIn(0, NumberLookupCodec.MAX_WAIT_MS), TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            NumberLookupCodec.Response(NumberLookupCodec.Response.PENDING)
        } catch (e: Exception) {
            NumberLookupCodec.Response(NumberLookupCodec.Response.FAILED)
        }
    }

    /**
     * The call assistant's view (it talks to them, 2026-10-17): [lookUp]'s answer as a [NumberReputation], or null for
     * no lookup (off, pending, failed, not a number). Starting a lookup with [waitMs] 0 just sets the search going.
     */
    fun reputation(householdId: String, number: String, waitMs: Long): NumberReputation? {
        val r = lookUp(householdId, number, waitMs)
        if (r.state != NumberLookupCodec.Response.OK) return null
        val verdict = NumberVerdict.entries.firstOrNull { it.name.equals(r.verdict, ignoreCase = true) } ?: return null
        return NumberReputation(verdict, r.reports, r.sure, r.why)
    }

    private fun run(householdId: String, hash: String, number: String): LookupRow {
        val at = now()
        val row = try {
            val query = NumberLookupRules.query(number) ?: throw SearchFailed("not a number")
            val hits = search.search(query)
            val national = NumberLookupRules.national(number)!!
            val about = hits.filter { NumberLookupRules.mentions(it, national) }
            val rep = NumberLookupRules.classify(number, hits)
            LookupRow(
                LookupRow.word(rep.verdict), rep.reports, rep.sure, rep.why,
                results = hits.size, reportHits = about.count { NumberLookupRules.isReportSite(it.url) }, checkedAtMs = at,
            )
        } catch (e: SearchFailed) {
            LookupRow(LookupRow.FAILED, reason = e.reason, checkedAtMs = at)
        }
        runCatching { store.put(householdId, hash, row) }
        return row
    }

    private fun fresh(row: LookupRow, maxAgeMs: Long?): Boolean {
        val keep = NumberLookupRules.keepDays(row.reputation()) * DAY_MS
        val age = now() - row.checkedAtMs
        return age in 0 until (maxAgeMs?.let { minOf(it, keep) } ?: keep)
    }

    private fun respond(row: LookupRow): NumberLookupCodec.Response =
        if (row.failed) NumberLookupCodec.Response(NumberLookupCodec.Response.FAILED)
        else NumberLookupCodec.Response(NumberLookupCodec.Response.OK, row.verdict, row.reports, row.sure, row.why)

    /**
     * The deploy's "Spam-call lookup" notice: a probe of a public body's published number (searched at most once a
     * day; its row lives under its own household), then the household's lookups in the last 30 days, counts only.
     */
    fun report(householdId: String): String {
        if (!search.configured()) return "off · the server's Brave secret holds no key"
        val started = now()
        val answer = lookUp(PROBE_HOUSEHOLD, PROBE_NUMBER, NumberLookupCodec.MAX_WAIT_MS, maxAgeMs = DAY_MS)
        val probe = NumberLookupRules.national(PROBE_NUMBER)?.let { runCatching { store.get(PROBE_HOUSEHOLD, hash(PROBE_HOUSEHOLD, it)) }.getOrNull() }
        val head = when {
            answer.state == NumberLookupCodec.Response.PENDING -> "probe still searching after ${NumberLookupCodec.MAX_WAIT_MS / 1000} s"
            probe == null -> "probe failed · no answer kept"
            probe.failed -> "probe failed · ${probe.reason ?: "no reason"}"
            else -> "probe OK · ${probe.results} results · ${probe.reportHits} from report sites · verdict ${probe.verdict}" +
                (if (probe.checkedAtMs < started) " · checked ${NotifyReports.utc(probe.checkedAtMs)}" else "")
        }
        val c = runCatching { store.counts(householdId, now() - 30 * DAY_MS) }.getOrNull().orEmpty()
        val total = c.values.sum()
        val tail = if (total == 0) "no numbers looked up in 30 days" else
            "$total numbers looked up in 30 days (${c["scam"] ?: 0} scam, ${c["nuisance"] ?: 0} nuisance, ${c["legit"] ?: 0} business, " +
                "${c["unknown"] ?: 0} nothing known, ${c[LookupRow.FAILED] ?: 0} failed)"
        return "$head · $tail"
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        const val PROBE_HOUSEHOLD = "probe"
        /** Action Fraud's published number: a public body's line, so the probe never searches anything of Meka's. */
        const val PROBE_NUMBER = "0300 123 2040"

        fun hash(householdId: String, national: String): String =
            MessageDigest.getInstance("SHA-256").digest("$householdId|$national".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
