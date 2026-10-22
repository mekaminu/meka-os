package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/**
 * Ada's Live mode, slice 1: the device ↔ Navilife server exchange for a Gemini Live session.
 *
 * - [STATUS_PATH]: the month's Live meter (Settings' line under the switch). Nothing in but the version.
 * - [SESSION_PATH]: a short-lived token for one session, with the voice he picked. Out: the token (Google's, never the
 *   key), the model, the WebSocket URL, until when it can open and talk, and the meter.
 * - [USAGE_PATH]: when a session ends, how many seconds it heard and spoke, so the month's count drops from the
 *   window it was reserved at to what it really used. Nothing of what was said.
 */
object LiveCodec {
    const val STATUS_PATH = "/v1/ai/live/status"
    const val SESSION_PATH = "/v1/ai/live/session"
    const val USAGE_PATH = "/v1/ai/live/usage"
    /** Slice 4: a web search Ada asked for in a Live session (its query only, checked on the device and here). */
    const val SEARCH_PATH = "/v1/ai/live/search"

    /**
     * The month's meter: [level] is ok · heads_up · over; [configured] false until the Gemini key is in. Slice 2b
     * (additive; an older server leaves them out): [month] "2026-10" (UTC), the [sessions] opened this month and the
     * [seconds] they ran (the longer of heard and spoken, reported sessions only), for Activity's Live line.
     */
    data class Meter(
        val configured: Boolean,
        val level: String,
        val spentPence: Long,
        val capPence: Long,
        val month: String? = null,
        val sessions: Long = 0,
        val seconds: Long = 0,
    )

    /**
     * Slice 4 (additive; an older device leaves it out and Ada starts with no picture of his day): the compact day a
     * Live session starts with, as Ask sends it — the [date] and [now] line, today's lines ([items]: Needs you, the
     * tasks, the calendar, the weather, the train, the commute, the shopping; Ask's handles only with [tools]), what he is called ([name], [callMe]), short [recall] notes of earlier chats and whether Ada may
     * [introduce] herself. The server puts it in the session's system instruction, locked into Google's token.
     */
    data class Day(
        val date: String,
        val now: String,
        val items: List<AskCodec.Item>,
        val name: String? = null,
        val callMe: String? = null,
        val recall: List<String> = emptyList(),
        val introduce: Boolean = false,
        /**
         * Slice 5 (additive): the device answers Navilife's own Live tools (Ada looks up a day and offers Ask's cards,
         * [os.meka.core.domain.LiveToolRules]), so the server adds them to the session; the [items] then carry Ask's
         * handles (t1, e1). An older device leaves it out and its session has the web search alone.
         */
        val tools: Boolean = false,
        /**
         * Slice 2c (additive): what Ada has just said out loud in Navilife's own voice (the hello) before the Live
         * session takes over, so she carries on from it rather than greeting him again. One line, at most [MAX_GREETED].
         */
        val greeted: String? = null,
        /**
         * Slice 5b (additive, only with [tools]): the device also answers the mail and money look-ups
         * ([os.meka.core.domain.LiveToolRules.MAIL_TOOL], [os.meka.core.domain.LiveToolRules.MONEY_TOOL]), so the server
         * adds them; a slice-5 device leaves it out and its session has the day look-up and the cards alone.
         */
        val mailMoney: Boolean = false,
    )

    /**
     * Slice 2c (additive): [resume] is Google's resumption handle for a conversation already running, so the fresh
     * token carries it on where it was (an older server ignores it and the device starts afresh).
     */
    data class SessionRequest(val voice: String? = null, val day: Day? = null, val resume: String? = null)

    /** The longest [Day.greeted] accepted. */
    const val MAX_GREETED = 400
    /** The longest [SessionRequest.resume] handle accepted. */
    const val MAX_RESUME = 2_000

    /** The most lines a Live day carries (fewer than Ask's: it is the compact day, said once at the start). */
    const val MAX_DAY_ITEMS = 40

    /**
     * [state]: [READY] (the rest is set) · [OFF] (no key on the server) · [OVER] (the month's cap) · [FAILED] (Google
     * refused the token; normal Ada answers).
     */
    data class Session(
        val state: String,
        val meter: Meter,
        val sessionId: String? = null,
        val token: String? = null,
        val model: String? = null,
        val url: String? = null,
        val voice: String? = null,
        val startByMs: Long? = null,
        val expiresAtMs: Long? = null,
        val reason: String? = null,
    ) {
        companion object {
            const val READY = "ready"
            const val OFF = "off"
            const val OVER = "over"
            const val FAILED = "failed"
            val STATES = setOf(READY, OFF, OVER, FAILED)
        }
    }

    /** Slice 4: a search for [sessionId]'s conversation; [query] at most [MAX_QUERY]. */
    data class SearchRequest(val sessionId: String, val query: String)

    /** One result: the page's [title], its https [url] and a short [text] from it. */
    data class SearchHit(val title: String, val url: String, val text: String)

    /** [state]: [FOUND] (with [hits], maybe none) · [REFUSED] (about his life) · [FAILED] (no key, the day's cap, slow, an unknown session). */
    data class SearchResult(val state: String, val hits: List<SearchHit> = emptyList()) {
        companion object {
            const val FOUND = "found"
            const val REFUSED = "refused"
            const val FAILED = "failed"
        }
    }

    const val MAX_QUERY = 120
    const val MAX_HITS = 4

    fun encodeSearchRequest(r: SearchRequest): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("sessionId", r.sessionId)
        put("query", r.query)
    }.toString()

    fun decodeSearchRequest(body: String): SearchRequest = wrap("live search request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val id = str(o, "sessionId")?.takeIf { it.length in 1..MAX_ID } ?: throw IllegalArgumentException("sessionId")
        val q = str(o, "query")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_QUERY } ?: throw IllegalArgumentException("query")
        SearchRequest(id, q)
    }

    fun encodeSearchResult(r: SearchResult): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        putJsonArray("hits") { r.hits.forEach { h -> addJsonObject { put("title", h.title); put("url", h.url); put("text", h.text) } } }
    }.toString()

    /** Anything unreadable, an unknown state or a link that isn't https reads as less: never more than was sent. */
    fun decodeSearchResult(body: String): SearchResult = wrap("live search result") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val state = str(o, "state")?.takeIf { it in setOf(SearchResult.FOUND, SearchResult.REFUSED, SearchResult.FAILED) } ?: SearchResult.FAILED
        val hits = (o["hits"] as? JsonArray).orEmpty().mapNotNull { e ->
            val h = e as? JsonObject ?: return@mapNotNull null
            val url = str(h, "url")?.takeIf { it.startsWith("https://") && it.length <= 2_000 } ?: return@mapNotNull null
            SearchHit(str(h, "title").orEmpty().take(200), url, str(h, "text").orEmpty().take(400))
        }.take(MAX_HITS)
        SearchResult(state, if (state == SearchResult.FOUND) hits else emptyList())
    }

    data class UsageRequest(val sessionId: String, val inSeconds: Long, val outSeconds: Long)

    /** [counted]: false when the server didn't know the session or had already counted it. */
    data class Usage(val counted: Boolean, val meter: Meter)

    /** The longest session id accepted. */
    const val MAX_ID = 64

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeStatusRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()
    fun decodeStatusRequest(body: String) = wrap("live status request") { checkVersion(json.parseToJsonElement(body).jsonObject) }

    fun encodeMeter(m: Meter): String = buildJsonObject { put("w", WireCodec.VERSION); putMeter(m) }.toString()
    fun decodeMeter(body: String): Meter = wrap("live meter") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        meter(o)
    }

    fun encodeSessionRequest(r: SessionRequest): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        r.voice?.let { put("voice", it) }
        r.day?.let { d ->
            putJsonObject("day") {
                put("date", d.date)
                put("now", d.now)
                putJsonArray("items") { d.items.forEach { i -> addJsonObject { if (i.ref.isNotEmpty()) put("ref", i.ref); put("kind", i.kind); put("line", i.line) } } }
                AskCodec.plainName(d.name)?.let { put("name", it) }
                if (d.callMe == AskCodec.CALL_SIR) put("callMe", AskCodec.CALL_SIR)
                if (d.recall.isNotEmpty()) putJsonArray("recall") { d.recall.forEach { add(it) } }
                if (d.introduce) put("introduce", true)
                if (d.tools) put("tools", true)
                d.greeted?.takeIf { it.isNotBlank() }?.let { put("greeted", it) }
                if (d.tools && d.mailMoney) put("mailMoney", true)
            }
        }
        r.resume?.let { put("resume", it) }
    }.toString()

    fun decodeSessionRequest(body: String): SessionRequest = wrap("live session request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val resume = str(o, "resume")?.also { h ->
            require(h.length <= MAX_RESUME && h.isNotEmpty() && h.all { it.code in 0x21..0x7e }) { "resume" }
        }
        SessionRequest(str(o, "voice")?.take(40), (o["day"] as? JsonObject)?.let(::day), resume)
    }

    /** A day as sent, refused (not trimmed) when outside Ask's own limits. */
    private fun day(o: JsonObject): Day {
        val date = str(o, "date") ?: throw IllegalArgumentException("date")
        require(DATE.matches(date)) { "date" }
        val now = str(o, "now").orEmpty()
        require(now.length <= 80) { "now" }
        val items = (o["items"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
            val i = e as? JsonObject ?: throw IllegalArgumentException("item")
            AskCodec.Item(str(i, "ref").orEmpty(), str(i, "kind").orEmpty(), str(i, "line").orEmpty()).also {
                require(it.ref.isEmpty() || AskCodec.validRef(it.ref)) { "ref" }
                require(it.kind in AskCodec.KINDS) { "kind" }
                require(it.line.length <= AskCodec.MAX_LINE) { "line" }
            }
        }
        require(items.size <= MAX_DAY_ITEMS) { "items" }
        val recall = (o["recall"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
            ((e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("recall")).also {
                require(it.isNotBlank() && it.length <= AskCodec.MAX_LINE) { "recall" }
            }
        }
        require(recall.size <= AskCodec.MAX_RECALL) { "recall" }
        val callMe = str(o, "callMe")?.takeIf { it == AskCodec.CALL_SIR }
        val introduce = (o["introduce"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        val tools = (o["tools"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        val greeted = str(o, "greeted")?.takeIf { it.isNotBlank() }?.also { require(it.length <= MAX_GREETED) { "greeted" } }
        // Handles mean something only to a device that answers the tools.
        val mailMoney = tools && (o["mailMoney"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        return Day(date, now, if (tools) items else items.map { it.copy(ref = "") }, AskCodec.plainName(str(o, "name")), callMe, recall, introduce, tools, greeted, mailMoney)
    }

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun encodeSession(s: Session): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", s.state)
        putMeter(s.meter)
        s.sessionId?.let { put("sessionId", it) }
        s.token?.let { put("token", it) }
        s.model?.let { put("model", it) }
        s.url?.let { put("url", it) }
        s.voice?.let { put("voice", it) }
        s.startByMs?.let { put("startByMs", it) }
        s.expiresAtMs?.let { put("expiresAtMs", it) }
        s.reason?.let { put("reason", it) }
    }.toString()

    /** An unknown state, or a ready one missing its token, model or URL, reads as [Session.FAILED]: normal Ada answers. */
    fun decodeSession(body: String): Session = wrap("live session") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val s = Session(
            state = str(o, "state")?.takeIf { it in Session.STATES } ?: Session.FAILED,
            meter = meter(o),
            sessionId = str(o, "sessionId"),
            token = str(o, "token"),
            model = str(o, "model"),
            url = str(o, "url")?.takeIf { it.startsWith("wss://") },
            voice = str(o, "voice"),
            startByMs = long(o, "startByMs"),
            expiresAtMs = long(o, "expiresAtMs"),
            reason = str(o, "reason"),
        )
        if (s.state == Session.READY && (s.sessionId == null || s.token == null || s.model == null || s.url == null || s.expiresAtMs == null)) {
            s.copy(state = Session.FAILED, token = null, reason = s.reason ?: "Live sent an incomplete session")
        } else s
    }

    fun encodeUsageRequest(r: UsageRequest): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("sessionId", r.sessionId)
        put("in", r.inSeconds)
        put("out", r.outSeconds)
    }.toString()

    fun decodeUsageRequest(body: String): UsageRequest = wrap("live usage request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val id = str(o, "sessionId")?.takeIf { it.length in 1..MAX_ID } ?: throw IllegalArgumentException("sessionId")
        UsageRequest(id, (long(o, "in") ?: 0).coerceAtLeast(0), (long(o, "out") ?: 0).coerceAtLeast(0))
    }

    fun encodeUsage(u: Usage): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("counted", u.counted)
        putMeter(u.meter)
    }.toString()

    fun decodeUsage(body: String): Usage = wrap("live usage") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Usage((o["counted"] as? JsonPrimitive)?.contentOrNull == "true", meter(o))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putMeter(m: Meter) {
        put("configured", m.configured)
        put("level", m.level)
        put("spentPence", m.spentPence)
        put("capPence", m.capPence)
        m.month?.let { put("month", it) }
        put("sessions", m.sessions)
        put("seconds", m.seconds)
    }

    private fun meter(o: JsonObject) = Meter(
        configured = (o["configured"] as? JsonPrimitive)?.contentOrNull == "true",
        level = str(o, "level")?.takeIf { it in LEVELS } ?: "ok",
        spentPence = (long(o, "spentPence") ?: 0).coerceAtLeast(0),
        capPence = (long(o, "capPence") ?: 0).coerceAtLeast(0),
        month = str(o, "month")?.takeIf { MONTH.matches(it) },
        sessions = (long(o, "sessions") ?: 0).coerceAtLeast(0),
        seconds = (long(o, "seconds") ?: 0).coerceAtLeast(0),
    )

    private val MONTH = Regex("\\d{4}-\\d{2}")

    private val LEVELS = setOf("ok", "heads_up", "over")

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun long(o: JsonObject, k: String): Long? = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

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
