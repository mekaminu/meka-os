package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import os.meka.core.domain.LiveModeRules
import os.meka.core.wire.LiveCodec
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * Ada's Live mode, slice 1 (Meka 2026-10-21 20:56): Navilife's server side of a Gemini Live session.
 *
 * The Gemini key (Secrets Manager `meka-os-dev/ai/gemini`, `{"api_key"}`) stays here: for each session the server
 * asks Google for an ephemeral token (`POST v1beta/auth_tokens`, one use, [LiveModeRules.START_MS] to open,
 * [LiveModeRules.TOKEN_MS] to talk, locked to the Live model and audio answers) and hands the device only that token.
 * Every minted session is counted at its whole window at once ([LiveModeRules.reserveMicroUsd]) and drops to what the
 * device reports it really heard and spoke; the month's total in pounds against Meka's cap decides whether a new
 * token is minted at all. Nothing of what is said passes through or is kept here.
 */
interface LiveTokens {
    /** Whether the key is in. */
    fun configured(): Boolean

    /** Google's token for one session, or null when Google refused or couldn't be reached. Never the key. */
    fun mint(model: String, voice: String, nowMs: Long): String?
}

/** One minted session as the meter keeps it: the reserve until [reported], then what it used. */
data class LiveSessionRow(val id: String, val householdId: String, val month: String, val mintedAtMs: Long, val microUsd: Long, val reported: Boolean)

interface LiveSessionStore {
    fun add(row: LiveSessionRow)
    fun get(id: String): LiveSessionRow?
    /** Sets a not yet reported session's count; false when it was unknown or already reported. */
    fun report(id: String, householdId: String, inSeconds: Long, outSeconds: Long, microUsd: Long): Boolean
    fun monthMicroUsd(month: String): Long
}

class LiveSessions(
    private val tokens: LiveTokens,
    private val store: LiveSessionStore,
    private val capPence: Long = LiveModeRules.capPence(System.getenv("MEKA_LIVE_BUDGET_GBP")),
    private val model: String = LiveModeRules.model(System.getenv("MEKA_LIVE_MODEL")),
    /** Pounds per dollar × 1,000,000 (the ECB's daily rate), or null when it can't be read. */
    private val gbpPerUsd: () -> Long? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val onLevel: (LiveModeRules.Level, String) -> Unit = { _, _ -> },
) {
    private val told = mutableSetOf<Pair<String, LiveModeRules.Level>>()

    fun month(): String = Instant.ofEpochMilli(nowMs()).atOffset(ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    fun meter(): LiveCodec.Meter {
        val configured = runCatching { tokens.configured() }.getOrDefault(false)
        val spent = LiveModeRules.pence(store.monthMicroUsd(month()), rate())
        return LiveCodec.Meter(configured, LiveModeRules.level(spent, capPence).wire, spent, capPence)
    }

    /** A token for one session in [voice] (his pick, else the default), or why not. */
    fun session(householdId: String, request: LiveCodec.SessionRequest): LiveCodec.Session {
        val before = meter()
        if (!before.configured) return LiveCodec.Session(LiveCodec.Session.OFF, before, reason = "Live needs the Gemini key on Navilife's server")
        if (before.level == LiveModeRules.Level.OVER.wire) {
            return LiveCodec.Session(LiveCodec.Session.OVER, before, reason = LiveModeRules.line(LiveModeRules.Level.OVER, before.spentPence, before.capPence))
        }
        val voice = LiveModeRules.voice(request.voice) ?: LiveModeRules.DEFAULT_VOICE
        val now = nowMs()
        val token = runCatching { tokens.mint(model, voice, now) }.getOrNull()
            ?: return LiveCodec.Session(LiveCodec.Session.FAILED, before, reason = "Google didn't open a Live session just now")
        val id = "ls_" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        store.add(LiveSessionRow(id, householdId, month(), now, LiveModeRules.reserveMicroUsd(), reported = false))
        val after = meter().also(::tell)
        return LiveCodec.Session(
            LiveCodec.Session.READY, after, sessionId = id, token = token, model = model, url = WS_URL, voice = voice,
            startByMs = now + LiveModeRules.START_MS, expiresAtMs = now + LiveModeRules.TOKEN_MS,
        )
    }

    /** What a session really used (seconds only); counted once, and only for the household that opened it. */
    fun usage(householdId: String, r: LiveCodec.UsageRequest): LiveCodec.Usage {
        val counted = store.report(r.sessionId, householdId, r.inSeconds, r.outSeconds, LiveModeRules.reportedMicroUsd(r.inSeconds, r.outSeconds))
        return LiveCodec.Usage(counted, meter().also(::tell))
    }

    private fun rate(): Long = runCatching { gbpPerUsd() }.getOrNull()?.takeIf { it > 0 } ?: LiveModeRules.FALLBACK_GBP_PER_USD_MICROS

    private fun tell(m: LiveCodec.Meter) {
        val level = LiveModeRules.Level.of(m.level)
        if (level == LiveModeRules.Level.OK) return
        val first = synchronized(told) { told.add(month() to level) }
        if (first) onLevel(level, month())
    }

    companion object {
        /** The Live API's WebSocket for ephemeral tokens (v1beta; the token goes in `access_token`). */
        const val WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
    }
}

/**
 * Google's ephemeral tokens for the Live API (`POST https://generativelanguage.googleapis.com/v1beta/auth_tokens`,
 * the key in `x-goog-api-key`): one use, locked to [model], audio answers in [voice] and session resumption, so a
 * token taken off a device can't be spent on anything else. The key is read at use time through [key] (Secrets
 * Manager via SecretCache) and is never logged or returned.
 */
class GeminiLiveTokens(private val key: () -> String?, private val http: HttpExchange = HttpExchange.jdk(java.time.Duration.ofSeconds(10))) : LiveTokens {
    override fun configured(): Boolean = key() != null

    override fun mint(model: String, voice: String, nowMs: Long): String? {
        val k = key() ?: return null
        val (code, body) = http.send("POST", TOKENS_URL, mapOf("x-goog-api-key" to k, "content-type" to "application/json"), request(model, voice, nowMs))
        if (code !in 200..299) {
            System.err.println("live: Google refused a token (HTTP $code)")
            return null
        }
        return parse(body)
    }

    companion object {
        const val TOKENS_URL = "https://generativelanguage.googleapis.com/v1beta/auth_tokens"

        fun request(model: String, voice: String, nowMs: Long): String = buildJsonObject {
            put("uses", 1)
            put("expireTime", Instant.ofEpochMilli(nowMs + LiveModeRules.TOKEN_MS).toString())
            put("newSessionExpireTime", Instant.ofEpochMilli(nowMs + LiveModeRules.START_MS).toString())
            putJsonObject("liveConnectConstraints") {
                put("model", "models/$model")
                putJsonObject("config") {
                    putJsonObject("sessionResumption") {}
                    putJsonArray("responseModalities") { add("AUDIO") }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } }
                    }
                }
            }
        }.toString()

        /** The token's name from Google's answer, or null. */
        fun parse(body: String): String? = runCatching {
            Json.parseToJsonElement(body).jsonObject["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 }
        }.getOrNull()

        /** The key from `{"api_key": "…"}`, read through SecretCache (unset or `{}`: Live stays off). */
        fun fromSecret(secretId: String): GeminiLiveTokens =
            GeminiLiveTokens(os.meka.backend.integrations.SecretCache.shared.reader(secretId) { os.meka.backend.integrations.GoogleMapsKey.parse(it) })
    }
}

class InMemoryLiveSessionStore : LiveSessionStore {
    private val rows = LinkedHashMap<String, LiveSessionRow>()
    @Synchronized override fun add(row: LiveSessionRow) { rows[row.id] = row }
    @Synchronized override fun get(id: String) = rows[id]
    @Synchronized override fun report(id: String, householdId: String, inSeconds: Long, outSeconds: Long, microUsd: Long): Boolean {
        val r = rows[id]?.takeIf { it.householdId == householdId && !it.reported } ?: return false
        rows[id] = r.copy(microUsd = microUsd, reported = true)
        return true
    }
    @Synchronized override fun monthMicroUsd(month: String) = rows.values.filter { it.month == month }.sumOf { it.microUsd }
}

/** The meter in `live_session` (V29): ids, months, seconds and cost only. */
class PostgresLiveSessionStore(private val ds: DataSource) : LiveSessionStore {
    override fun add(row: LiveSessionRow) = ds.connection.use { c ->
        c.prepareStatement("INSERT INTO live_session(id, household_id, month, minted_at_ms, micro_usd, reported) VALUES (?,?,?,?,?,?)").use {
            it.setString(1, row.id); it.setString(2, row.householdId); it.setString(3, row.month); it.setLong(4, row.mintedAtMs)
            it.setLong(5, row.microUsd); it.setBoolean(6, row.reported)
            it.executeUpdate()
        }
        Unit
    }

    override fun get(id: String): LiveSessionRow? = ds.connection.use { c ->
        c.prepareStatement("SELECT id, household_id, month, minted_at_ms, micro_usd, reported FROM live_session WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) LiveSessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getBoolean(6)) else null }
        }
    }

    override fun report(id: String, householdId: String, inSeconds: Long, outSeconds: Long, microUsd: Long): Boolean = ds.connection.use { c ->
        c.prepareStatement(
            "UPDATE live_session SET in_seconds = ?, out_seconds = ?, micro_usd = ?, reported = TRUE WHERE id = ? AND household_id = ? AND NOT reported",
        ).use {
            it.setLong(1, inSeconds); it.setLong(2, outSeconds); it.setLong(3, microUsd); it.setString(4, id); it.setString(5, householdId)
            it.executeUpdate() == 1
        }
    }

    override fun monthMicroUsd(month: String): Long = ds.connection.use { c ->
        c.prepareStatement("SELECT COALESCE(SUM(micro_usd), 0) FROM live_session WHERE month = ?").use { st ->
            st.setString(1, month)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}

/** Null unless the deployment names the Gemini secret; the key itself may still be unset (Live then says "off"). */
fun liveFromEnv(ds: DataSource): LiveSessions? {
    val secret = System.getenv("MEKA_GEMINI_SECRET")?.takeIf { it.isNotBlank() } ?: return null
    val rate = os.meka.backend.integrations.EcbDollarRate.network()
    return LiveSessions(
        GeminiLiveTokens.fromSecret(secret), PostgresLiveSessionStore(ds), gbpPerUsd = { rate.read()?.gbpPerUsdMicros },
        onLevel = { level, month ->
            System.err.println("live: ${if (level == LiveModeRules.Level.OVER) "this month's cap is used up, Live off until the 1st" else "80 % of this month's cap used"} ($month)")
        },
    )
}
