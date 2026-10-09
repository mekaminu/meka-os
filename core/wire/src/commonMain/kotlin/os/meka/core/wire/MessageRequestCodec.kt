package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Requests from people Meka watches, on the wire (build plan V1, "Requests from my wife become tasks", slice 1):
 * `POST /v1/ai/message-request` carries **one** message's text, the sender's label as the notification named them and
 * the time it came; never a number, a contact, or any other chat. The answer is zero or more proposals the device
 * checks again (`MessageRequestRules` in core/domain) and shows as a card that does nothing until Meka taps it.
 *
 * A request outside the limits is refused, not trimmed.
 */
object MessageRequestCodec {
    const val MAX_TEXT = 2_000
    const val MAX_SENDER = 60
    const val MAX_NOW = 80
    const val MAX_WORK = 120
    /** At most this many proposals are read from a model's answer. */
    const val MAX_PROPOSALS = 3
    const val MAX_FIELD = 200

    const val TASK = "task"
    const val WORK_FROM_HOME = "work_from_home"
    const val EVENT = "event"
    const val REMINDER = "reminder"
    val KINDS = setOf(TASK, WORK_FROM_HOME, EVENT, REMINDER)

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""\d{2}:\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [sender] is the label the notification gave ("Wife", "Ada"), [sentAt] "14:02", [date] the device's local ISO date,
     * [now] its readable "Friday 9 October 2026 · 14:03", [work] Meka's work days in a line ("Mon–Fri 09:00–17:30"; empty
     * when none are set), [text] the message as the notification showed it.
     */
    data class Request(val sender: String, val sentAt: String, val date: String, val now: String, val work: String, val text: String)

    /**
     * A proposal as the model wrote it, every field optional: [kind] (one of [KINDS]), [title], [date] (YYYY-MM-DD),
     * [time] (HH:MM), [words] the message's own when-words ("Thursday", "on the 15th") that the device resolves again.
     */
    data class Proposal(
        val kind: String,
        val title: String? = null,
        val date: String? = null,
        val time: String? = null,
        val words: String? = null,
    )

    /** [state]: the same states as Ask's ([AskCodec.Response.ANSWERED] …). */
    data class Response(val state: String, val proposals: List<Proposal> = emptyList(), val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("sender", r.sender)
        put("sentAt", r.sentAt)
        put("date", r.date)
        put("now", r.now)
        put("work", r.work)
        put("text", r.text)
    }.toString()

    fun decodeRequest(body: String): Request = wrap("message request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val sender = o.str("sender").trim()
        require(sender.isNotEmpty() && sender.length <= MAX_SENDER) { "sender" }
        val sentAt = o.str("sentAt")
        require(TIME.matches(sentAt)) { "sentAt" }
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MAX_NOW) { "now" }
        val work = o.str("work")
        require(work.length <= MAX_WORK) { "work" }
        val text = o.str("text").trim()
        require(text.isNotEmpty() && text.length <= MAX_TEXT) { "text" }
        Request(sender, sentAt, date, now, work, text)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        putJsonArray("proposals") { r.proposals.forEach { add(encodeProposal(it)) } }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("message response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(o.str("state"), proposalsOf(o), o.optStr("reason"))
    }

    /**
     * A model's answer, which should be one JSON object `{"proposals": [...]}` (possibly in a code fence): up to
     * [MAX_PROPOSALS] proposals of a known kind. Anything else is no proposals, so a chatty answer can never act.
     */
    fun parseModelAnswer(text: String): List<Proposal> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        return proposalsOf(o)
    }

    /** The `proposals` array of [o]: known kinds only, at most [MAX_PROPOSALS] (shared with [MessageTriageCodec]). */
    internal fun proposalsOf(o: JsonObject): List<Proposal> =
        (o["proposals"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val kind = p.optStr("kind")?.takeIf { it in KINDS } ?: return@mapNotNull null
            Proposal(kind, p.optStr("title"), p.optStr("date")?.takeIf(DATE::matches), p.optStr("time")?.takeIf(TIME::matches), p.optStr("words"))
        }.take(MAX_PROPOSALS)

    internal fun encodeProposal(p: Proposal): JsonObject = buildJsonObject {
        put("kind", p.kind)
        p.title?.let { put("title", it) }
        p.date?.let { put("date", it) }
        p.time?.let { put("time", it) }
        p.words?.let { put("words", it) }
    }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    private fun JsonObject.optStr(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= MAX_FIELD }

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
