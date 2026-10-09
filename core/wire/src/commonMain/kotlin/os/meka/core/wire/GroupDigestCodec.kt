package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The group digest's gist on the wire (build plan V1, "Messages assistant", slice 4b): `POST /v1/ai/group-digest` carries
 * the busy groups in one digest — each group's name and its latest lines (the sender's label as the notification named
 * them, the time and the text) — with the date and the time; never a number, a contact or a 1:1 chat. The answer is a
 * one-line gist per group and, for anything that asks Meka for something, an ask (needs_reply · action with
 * [MessageRequestCodec]'s proposals). The device checks it all again (`GroupGistRules.check`).
 *
 * A request outside the limits is refused, not trimmed.
 */
object GroupDigestCodec {
    const val MAX_GROUPS = 6
    const val MAX_LINES = 40
    const val MAX_LINE = 300
    const val MAX_NAME = 60
    const val MAX_ASKS = 3
    /** Gists and summaries are read up to this long; the device keeps less. */
    const val MAX_TEXT_FIELD = 400

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""\d{2}:\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    data class Line(val from: String, val at: String, val text: String)
    data class Group(val name: String, val lines: List<Line>)

    /** [date] the device's local ISO date, [now] its readable "Friday 9 October 2026 · 12:30". */
    data class Request(val date: String, val now: String, val groups: List<Group>)

    data class Ask(
        val lane: String,
        val from: String? = null,
        val summary: String? = null,
        val proposals: List<MessageRequestCodec.Proposal> = emptyList(),
    )

    data class GroupAnswer(val name: String, val gist: String? = null, val asks: List<Ask> = emptyList())

    /** [state]: the same states as Ask's; [groups] empty unless answered. */
    data class Response(val state: String, val groups: List<GroupAnswer> = emptyList(), val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("now", r.now)
        putJsonArray("groups") {
            r.groups.forEach { g ->
                addJsonObject {
                    put("name", g.name)
                    putJsonArray("lines") {
                        g.lines.forEach { l -> addJsonObject { put("from", l.from); put("at", l.at); put("text", l.text) } }
                    }
                }
            }
        }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("group digest request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MessageRequestCodec.MAX_NOW) { "now" }
        val groups = (o["groups"] as? JsonArray ?: throw IllegalArgumentException("groups")).map { ge ->
            val g = ge as? JsonObject ?: throw IllegalArgumentException("group")
            val name = g.str("name").trim()
            require(name.isNotEmpty() && name.length <= MAX_NAME) { "name" }
            val lines = (g["lines"] as? JsonArray ?: throw IllegalArgumentException("lines")).map { le ->
                val l = le as? JsonObject ?: throw IllegalArgumentException("line")
                val from = l.str("from").trim()
                require(from.isNotEmpty() && from.length <= MessageRequestCodec.MAX_SENDER) { "from" }
                val at = l.str("at")
                require(TIME.matches(at)) { "at" }
                val text = l.str("text").trim()
                require(text.isNotEmpty() && text.length <= MAX_LINE) { "text" }
                Line(from, at, text)
            }
            require(lines.isNotEmpty() && lines.size <= MAX_LINES) { "lines" }
            Group(name, lines)
        }
        require(groups.isNotEmpty() && groups.size <= MAX_GROUPS) { "groups" }
        Request(date, now, groups)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        putJsonArray("groups") { r.groups.forEach { add(encodeGroup(it)) } }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("group digest response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(o.str("state"), groupsOf(o), o.optStr("reason"))
    }

    /**
     * A model's answer, which should be one JSON object `{"groups": [{"name": …, "gist": …, "asks": [...]}]}` (possibly
     * in a code fence). Anything that isn't that object is no groups; an ask in a lane MEKA doesn't know is dropped, and
     * at most [MAX_ASKS] asks are read in all, so a chatty answer is never a card.
     */
    fun parseModelAnswer(text: String): List<GroupAnswer> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        return groupsOf(o)
    }

    private fun groupsOf(o: JsonObject): List<GroupAnswer> {
        var asksLeft = MAX_ASKS
        return (o["groups"] as? JsonArray).orEmpty().mapNotNull { e ->
            val g = e as? JsonObject ?: return@mapNotNull null
            val name = g.optStr("name")?.takeIf { it.isNotBlank() && it.length <= MAX_NAME } ?: return@mapNotNull null
            val asks = (g["asks"] as? JsonArray).orEmpty().mapNotNull ask@{ ae ->
                if (asksLeft <= 0) return@ask null
                val a = ae as? JsonObject ?: return@ask null
                val lane = a.optStr("lane")?.takeIf { it == MessageTriageCodec.NEEDS_REPLY || it == MessageTriageCodec.ACTION } ?: return@ask null
                asksLeft--
                Ask(
                    lane, a.optStr("from"), a.optStr("summary"),
                    if (lane == MessageTriageCodec.ACTION) MessageRequestCodec.proposalsOf(a) else emptyList(),
                )
            }
            GroupAnswer(name, g.optStr("gist"), asks)
        }.take(MAX_GROUPS)
    }

    private fun encodeGroup(g: GroupAnswer): JsonObject = buildJsonObject {
        put("name", g.name)
        g.gist?.let { put("gist", it) }
        putJsonArray("asks") {
            g.asks.forEach { a ->
                add(buildJsonObject {
                    put("lane", a.lane)
                    a.from?.let { put("from", it) }
                    a.summary?.let { put("summary", it) }
                    putJsonArray("proposals") { a.proposals.forEach { add(MessageRequestCodec.encodeProposal(it)) } }
                })
            }
        }
    }

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
