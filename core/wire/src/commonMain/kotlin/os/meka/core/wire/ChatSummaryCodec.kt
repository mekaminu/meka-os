package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A busy chat's summary on the wire (build plan V1, "Messages grouped per chat", slice 3c): `POST /v1/ai/chat-summary`
 * carries the busy chats in Needs you — each chat's name, whether it is a group, and its lines (the sender's label as the
 * notification named them, the time and MEKA's gist of the message) — with the date and the time; never a number, a
 * contact or a message's own text. The answer is one line per chat. The device checks it again (`ChatSummaryRules.check`).
 *
 * A request outside the limits is refused, not trimmed.
 */
object ChatSummaryCodec {
    const val MAX_CHATS = 6
    const val MAX_LINES = 40
    const val MAX_LINE = 200
    const val MAX_NAME = 60
    /** Summaries are read up to this long; the device keeps less. */
    const val MAX_TEXT_FIELD = 400

    private val json = Json { ignoreUnknownKeys = true }

    data class Line(val from: String, val at: String, val text: String)
    data class Chat(val name: String, val group: Boolean, val lines: List<Line>)

    /** [date] the device's local ISO date, [now] its readable "Monday 12 October 2026 · 11:20". */
    data class Request(val date: String, val now: String, val chats: List<Chat>)

    data class ChatAnswer(val name: String, val summary: String? = null)

    /** [state]: the same states as Ask's; [chats] empty unless answered. */
    data class Response(val state: String, val chats: List<ChatAnswer> = emptyList(), val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("now", r.now)
        putJsonArray("chats") {
            r.chats.forEach { c ->
                addJsonObject {
                    put("name", c.name)
                    put("group", c.group)
                    putJsonArray("lines") {
                        c.lines.forEach { l -> addJsonObject { put("from", l.from); put("at", l.at); put("text", l.text) } }
                    }
                }
            }
        }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("chat summary request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(isDate(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MessageRequestCodec.MAX_NOW) { "now" }
        val chats = (o["chats"] as? JsonArray ?: throw IllegalArgumentException("chats")).map { ce ->
            val c = ce as? JsonObject ?: throw IllegalArgumentException("chat")
            val name = c.str("name").trim()
            require(name.isNotEmpty() && name.length <= MAX_NAME) { "name" }
            val group = (c["group"] as? JsonPrimitive)?.booleanOrNull ?: false
            val lines = (c["lines"] as? JsonArray ?: throw IllegalArgumentException("lines")).map { le ->
                val l = le as? JsonObject ?: throw IllegalArgumentException("line")
                val from = l.str("from").trim()
                require(from.isNotEmpty() && from.length <= MessageRequestCodec.MAX_SENDER) { "from" }
                val at = l.str("at")
                require(isTime(at)) { "at" }
                val text = l.str("text").trim()
                require(text.isNotEmpty() && text.length <= MAX_LINE) { "text" }
                Line(from, at, text)
            }
            require(lines.isNotEmpty() && lines.size <= MAX_LINES) { "lines" }
            Chat(name, group, lines)
        }
        require(chats.isNotEmpty() && chats.size <= MAX_CHATS) { "chats" }
        Request(date, now, chats)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        putJsonArray("chats") {
            r.chats.forEach { c -> addJsonObject { put("name", c.name); c.summary?.let { put("summary", it) } } }
        }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("chat summary response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(o.str("state"), chatsOf(o), o.optStr("reason"))
    }

    /**
     * A model's answer, which should be one JSON object `{"chats": [{"name": …, "summary": …}]}` (possibly in a code
     * fence). Anything that isn't that object is no chats.
     */
    fun parseModelAnswer(text: String): List<ChatAnswer> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        return chatsOf(o)
    }

    private fun chatsOf(o: JsonObject): List<ChatAnswer> =
        (o["chats"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val name = c.optStr("name")?.takeIf { it.isNotBlank() && it.length <= MAX_NAME } ?: return@mapNotNull null
            ChatAnswer(name, c.optStr("summary"))
        }.take(MAX_CHATS)

    // By hand, not with regexes, so Kotlin/Native reads them exactly as the JVM does.
    private fun isDate(s: String) = s.length == 10 && s[4] == '-' && s[7] == '-' &&
        s.filterIndexed { i, _ -> i != 4 && i != 7 }.all { it in '0'..'9' }

    private fun isTime(s: String) = s.length == 5 && s[2] == ':' && s.removeRange(2, 3).all { it in '0'..'9' }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    private fun JsonObject.optStr(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= MAX_TEXT_FIELD }

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
