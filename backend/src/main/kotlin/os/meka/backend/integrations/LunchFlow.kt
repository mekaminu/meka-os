package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.CivilDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One account as the bank feed has it. [active] false when its bank's consent ran out (reconnect it in Lunch Flow). */
data class BankAccountRead(val id: String, val name: String, val institution: String, val currency: String?, val active: Boolean) {
    override fun toString() = "BankAccountRead(active=$active)"
}

/** One posted transaction: Lunch Flow's [id], its epoch [day], signed [pence] (money out negative) and the words. */
data class BankTxnRead(val id: String, val day: Long, val pence: Long, val merchant: String?, val description: String?, val currency: String?) {
    override fun toString() = "BankTxnRead(…)"
}

/** The key was refused (401/403): a new key is needed. */
class BankKeyRefused : RuntimeException("bank key refused")

/** The feed asked us to slow down (429): try again later, nothing is wrong. */
class BankRateLimited : RuntimeException("bank feed rate limited")

/** Where Money's bank lines come from (Lunch Flow); read-only. */
interface BankFeed {
    /** Whether the server holds a key; without it nothing is read and nothing is written. */
    fun configured(): Boolean
    fun accounts(): List<BankAccountRead>
    /** Posted transactions from [fromDay] to [toDay] (epoch days, inclusive). */
    fun transactions(accountId: String, fromDay: Long, toDay: Long): List<BankTxnRead>
    /** The balance in signed pence and its currency, or null when the feed gives none. */
    fun balance(accountId: String): Pair<Long, String?>?
}

/**
 * Lunch Flow's Personal API (Money manager, bank sync; Meka chose Lunch Flow 2026-10-17, Needs Meka #38): `GET
 * /accounts`, `GET /accounts/{id}/transactions?from&to` (posted only) and `GET /accounts/{id}/balance`, with the key in
 * the `x-api-key` header — never in a URL or a log. Read-only: there is nothing in the API that moves money, and
 * nothing here writes anything back. 401/403 mean the key was refused ([BankKeyRefused]); 429 means slow down
 * ([BankRateLimited]). Amounts are read from the JSON's own decimal text, not a float.
 */
class LunchFlowFeed internal constructor(private val key: () -> String?, private val http: Http) : BankFeed {
    /** (status, body) for a GET; injected so tests never touch the network. */
    fun interface Http { fun get(url: String, headers: Map<String, String>): Pair<Int, String> }

    override fun configured(): Boolean = key() != null

    private fun call(path: String): JsonObject {
        val k = key() ?: error("no key")
        val (status, body) = http.get(BASE + path, mapOf("x-api-key" to k))
        when {
            status == 401 || status == 403 -> throw BankKeyRefused()
            status == 429 -> throw BankRateLimited()
            status != 200 -> error("lunch flow $status")
        }
        if (body.length > MAX_BYTES) error("lunch flow answer too big")
        return runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: error("lunch flow unreadable")
    }

    override fun accounts(): List<BankAccountRead> {
        val list = call("/accounts")["accounts"] as? JsonArray ?: error("no accounts list")
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.text("id") ?: return@mapNotNull null
            BankAccountRead(id, o.text("name").orEmpty(), o.text("institution_name").orEmpty(), o.text("currency"),
                o.text("status")?.equals("ACTIVE", ignoreCase = true) ?: true)
        }
    }

    override fun transactions(accountId: String, fromDay: Long, toDay: Long): List<BankTxnRead> {
        val list = call("/accounts/${enc(accountId)}/transactions?from=${iso(fromDay)}&to=${iso(toDay)}&include_pending=false")["transactions"] as? JsonArray
            ?: error("no transactions list")
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            if ((o["isPending"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
            val day = day(o.text("date")) ?: return@mapNotNull null
            val pence = pence(o["amount"]) ?: return@mapNotNull null
            if (pence == 0L) return@mapNotNull null
            val id = o.text("id") ?: "${day}:${pence}:${o.text("merchant")}:${o.text("description")}"
            BankTxnRead(id, day, pence, o.text("merchant"), o.text("description"), o.text("currency"))
        }
    }

    override fun balance(accountId: String): Pair<Long, String?>? {
        val b = call("/accounts/${enc(accountId)}/balance")["balance"] as? JsonObject ?: return null
        val p = pence(b["amount"]) ?: return null
        return p to b.text("currency")
    }

    companion object {
        /** Lunch Flow answers its API on www (the bare host redirects). */
        const val BASE = "https://www.lunchflow.app/api/v1"
        private const val MAX_BYTES = 20_000_000

        private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString || k == "id" }
            ?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8)

        fun iso(day: Long): String = CivilDate.fromEpochDay(day).let { "%04d-%02d-%02d".format(it.year, it.month, it.day) }

        /** "2026-10-15" (a time after it is fine) → epoch day; null otherwise. */
        fun day(s: String?): Long? = DvlaCarLookup.day(s)

        /** -12.34 → -1234, "5" → 500, 12.335 → 1234 (half up); null for anything that isn't a number. */
        fun pence(e: kotlinx.serialization.json.JsonElement?): Long? {
            val raw = (e as? JsonPrimitive)?.content?.trim() ?: return null
            val bd = raw.toBigDecimalOrNull() ?: return null
            if (bd.abs() > BigDecimal("100000000")) return null
            return bd.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        }

        /** The secret's `{"api_key": "…"}` (or a bare key); null for `{}` or junk. */
        fun parseKey(secret: String?): String? {
            val s = secret?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val k = if (s.startsWith("{")) runCatching {
                val o = Json.parseToJsonElement(s).jsonObject
                listOf("api_key", "apiKey", "key", "API_KEY").firstNotNullOfOrNull { o[it]?.jsonPrimitive?.content }
            }.getOrNull() else s
            return k?.trim()?.takeIf { it.length in 8..500 && it.none(Char::isWhitespace) }
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

        val NETWORK = Http { url, headers ->
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }.GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            res.statusCode() to res.body()
        }

        /** From the deployment's secret (its name or ARN), read at use time and cached for a minute: pasting a key needs no restart. */
        fun fromSecret(secretId: String): LunchFlowFeed {
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
            return LunchFlowFeed(key, NETWORK)
        }
    }
}
