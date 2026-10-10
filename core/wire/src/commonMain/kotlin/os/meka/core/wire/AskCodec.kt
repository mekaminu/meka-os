package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Ask MEKA on the wire (build plan V1, AI layer slice 3): a device's question with its picture of today
 * (`POST /v1/ai/ask`), the server's answer, and reading a model's answer into words and proposed actions.
 *
 * The limits mirror `AskRules` in core/domain (the wire module doesn't depend on it): a question of at most 500
 * characters, at most 60 lines of at most 160, handles "t1"…"t999". A request outside them is refused, not trimmed.
 */
object AskCodec {
    const val MAX_QUESTION = 500
    const val MAX_ITEMS = 60
    const val MAX_LINE = 160
    const val MAX_ANSWER = 1200
    /** At most this many proposals are read from a model's answer (the device shows at most three). */
    const val MAX_ACTIONS = 5
    const val MAX_FIELD = 200
    val KINDS = setOf("needs_you", "task", "done", "event", "weather", "shopping")

    private val REF = Regex("t[1-9][0-9]{0,2}")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** Exchanges sent with a question in a conversation (Talk to MEKA); more are refused. */
    const val MAX_HISTORY = 6
    /** Lines of what Meka confirmed after one answer. */
    const val MAX_DONE = 3

    data class Item(val ref: String, val kind: String, val line: String)

    /**
     * An earlier exchange in a conversation: Meka's [question] (at most [MAX_QUESTION]), MEKA's [answer] (at most
     * [MAX_ANSWER]) and what Meka confirmed after it ([done]: at most [MAX_DONE] lines of at most [MAX_LINE]).
     */
    data class Turn(val question: String, val answer: String, val done: List<String> = emptyList())

    /**
     * [date] is the device's local ISO date, [now] its readable "Thursday 8 October 2026 · 17:05". [history] is the
     * conversation so far (oldest first, empty for a one-off question; left out of the JSON when empty, so an older
     * server reads it as before), [voice] says the answer will be spoken.
     */
    data class Request(
        val question: String,
        val date: String,
        val now: String,
        val items: List<Item>,
        val history: List<Turn> = emptyList(),
        val voice: Boolean = false,
    )

    /** A proposal as the model wrote it: every field optional, strings at most [MAX_FIELD] characters. */
    data class Action(
        val kind: String,
        val ref: String? = null,
        val title: String? = null,
        val date: String? = null,
        val time: String? = null,
        val hours: Int? = null,
        val minutes: Int? = null,
    )

    /** [state]: answered · off (no key) · over (the month's budget is spent) · failed ([reason] says why). */
    data class Response(val state: String, val answer: String? = null, val actions: List<Action> = emptyList(), val reason: String? = null) {
        companion object {
            const val ANSWERED = "answered"
            const val OFF = "off"
            const val OVER = "over"
            const val FAILED = "failed"
        }
    }

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("question", r.question)
        put("date", r.date)
        put("now", r.now)
        putJsonArray("items") { r.items.forEach { i -> addJsonObject { put("ref", i.ref); put("kind", i.kind); put("line", i.line) } } }
        if (r.history.isNotEmpty()) {
            putJsonArray("history") {
                r.history.forEach { t ->
                    addJsonObject {
                        put("q", t.question)
                        put("a", t.answer)
                        if (t.done.isNotEmpty()) putJsonArray("done") { t.done.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
        }
        if (r.voice) put("voice", true)
    }.toString()

    fun decodeRequest(body: String): Request = wrap("ask request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val q = o.str("question").trim()
        require(q.isNotEmpty() && q.length <= MAX_QUESTION) { "question" }
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= 80) { "now" }
        val items = (o["items"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
            val i = e.jsonObject
            Item(i.str("ref"), i.str("kind"), i.str("line")).also {
                require(it.ref.isEmpty() || REF.matches(it.ref)) { "ref" }
                require(it.kind in KINDS) { "kind" }
                require(it.line.length <= MAX_LINE) { "line" }
            }
        }
        require(items.size <= MAX_ITEMS) { "items" }
        val history = (o["history"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
            val t = e.jsonObject
            val done = (t["done"] as? JsonArray ?: JsonArray(emptyList())).map { d ->
                ((d as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("done")).also {
                    require(it.length <= MAX_LINE) { "done" }
                }
            }
            Turn(t.str("q"), t.str("a"), done).also {
                require(it.question.isNotBlank() && it.question.length <= MAX_QUESTION) { "history question" }
                require(it.answer.length <= MAX_ANSWER) { "history answer" }
                require(done.size <= MAX_DONE) { "done" }
            }
        }
        require(history.size <= MAX_HISTORY) { "history" }
        val voice = (o["voice"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        Request(q, date, now, items, history, voice)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.answer?.let { put("answer", it) }
        r.reason?.let { put("reason", it) }
        putJsonArray("actions") { r.actions.forEach { add(encodeAction(it)) } }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("ask response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(
            state = o.str("state"),
            answer = o.optStr("answer"),
            actions = actionsOf(o),
            reason = o.optStr("reason"),
        )
    }

    /**
     * A model's answer, which should be one JSON object `{"answer": "…", "actions": [...]}` (possibly in a code fence):
     * its words (at most [MAX_ANSWER] characters) and up to [MAX_ACTIONS] actions. Anything that isn't that JSON is
     * taken as plain words with no actions, so a chatty answer still reads and can never act.
     */
    fun parseModelAnswer(text: String): Pair<String, List<Action>> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val o = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() else null
        val words = o?.optStr("answer")
        if (o == null || words == null) return text.trim().take(MAX_ANSWER) to emptyList()
        return words.trim().take(MAX_ANSWER) to actionsOf(o)
    }

    /** An earlier answer as the model wrote it, for the conversation's turns: `{"answer":"…","actions":[]}`. */
    fun encodeModelAnswer(answer: String): String = buildJsonObject {
        put("answer", answer)
        putJsonArray("actions") {}
    }.toString()

    /** One feature's calls on the household's day ("ask.talk" · 6 calls · 4,200 micro-dollars). */
    data class DayUse(val feature: String, val calls: Long, val microUsd: Long)

    /**
     * The server's AI status (`POST /v1/ai/status`): on/off/failing, why, and the month's spend when it meters;
     * [today] is the day's calls per feature (empty from an older server or when nothing was asked).
     */
    data class Status(
        val state: String, val reason: String?, val spentCents: Long?, val budgetCents: Long?, val level: String?,
        val today: List<DayUse> = emptyList(),
    )

    /**
     * Reads `{"state", "reason"?, "budget": {"spentCents", "budgetCents", "level", "today": [{"feature", "calls",
     * "microUsd"}]?}?}` (the status carries no wire version: it is a small read-only answer, and unknown fields are
     * ignored; a malformed day entry is skipped, never the whole status).
     */
    fun decodeStatus(body: String): Status = wrap("ai status") {
        val o = json.parseToJsonElement(body).jsonObject
        val b = o["budget"] as? JsonObject
        fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val today = (b?.get("today") as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val feature = d.optStr("feature")?.takeIf { it.isNotBlank() && it.length <= 40 } ?: return@mapNotNull null
            val calls = d.long("calls")?.takeIf { it >= 0 } ?: return@mapNotNull null
            DayUse(feature, calls, d.long("microUsd")?.coerceAtLeast(0) ?: 0)
        }
        Status(o.str("state"), o.optStr("reason"), b?.long("spentCents"), b?.long("budgetCents"), b?.optStr("level"), today)
    }

    private fun actionsOf(o: JsonObject): List<Action> =
        (o["actions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val kind = a.optStr("kind") ?: return@mapNotNull null
            Action(kind, a.optStr("ref"), a.optStr("title"), a.optStr("date"), a.optStr("time"), a.optInt("hours"), a.optInt("minutes"))
        }.take(MAX_ACTIONS)

    private fun encodeAction(a: Action): JsonObject = buildJsonObject {
        put("kind", a.kind)
        a.ref?.let { put("ref", it) }
        a.title?.let { put("title", it) }
        a.date?.let { put("date", it) }
        a.time?.let { put("time", it) }
        a.hours?.let { put("hours", it) }
        a.minutes?.let { put("minutes", it) }
    }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    /** A string field, or null when absent, not a string, or longer than [MAX_FIELD] ([MAX_ANSWER] for the answer). */
    private fun JsonObject.optStr(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= (if (k == "answer") MAX_ANSWER * 4 else MAX_FIELD) }

    private fun JsonObject.optInt(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

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
