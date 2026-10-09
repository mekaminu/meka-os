package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The messages assistant on the wire (build plan V1, "Messages assistant", slice 1): `POST /v1/ai/message-triage` carries
 * **one** message's text, the sender's label as the notification named them, the group's name when it came from a group
 * that mentioned Meka, the time it came, the date and Meka's work days; never a number, a contact or any other chat. The
 * answer is a lane (needs_reply · action · fyi), a short gist, a reply draft for a message that needs one and, for an
 * action, proposals of [MessageRequestCodec]'s kinds. The device checks it all again (`MessageTriageRules.check`).
 *
 * A request outside the limits is refused, not trimmed.
 */
object MessageTriageCodec {
    const val MAX_GROUP = 60
    const val NEEDS_REPLY = "needs_reply"
    const val ACTION = "action"
    const val FYI = "fyi"
    val LANES = setOf(NEEDS_REPLY, ACTION, FYI)
    /** Draft and gist are read up to this long; the device keeps less. */
    const val MAX_TEXT_FIELD = 600

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val TIME = Regex("""\d{2}:\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** As [MessageRequestCodec.Request], plus [group] (empty for a 1:1 chat). */
    data class Request(
        val sender: String,
        val group: String,
        val sentAt: String,
        val date: String,
        val now: String,
        val work: String,
        val text: String,
    )

    /** [state]: the same states as Ask's; [lane] null unless answered (an unknown lane reads as null). */
    data class Response(
        val state: String,
        val lane: String? = null,
        val summary: String? = null,
        val draft: String? = null,
        val proposals: List<MessageRequestCodec.Proposal> = emptyList(),
        val reason: String? = null,
    )

    /** What a model's answer said, before the server wraps it in a [Response]. */
    data class Answer(
        val lane: String?,
        val summary: String? = null,
        val draft: String? = null,
        val proposals: List<MessageRequestCodec.Proposal> = emptyList(),
    )

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("sender", r.sender)
        put("group", r.group)
        put("sentAt", r.sentAt)
        put("date", r.date)
        put("now", r.now)
        put("work", r.work)
        put("text", r.text)
    }.toString()

    fun decodeRequest(body: String): Request = wrap("message triage request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val sender = o.str("sender").trim()
        require(sender.isNotEmpty() && sender.length <= MessageRequestCodec.MAX_SENDER) { "sender" }
        val group = o.str("group").trim()
        require(group.length <= MAX_GROUP) { "group" }
        val sentAt = o.str("sentAt")
        require(TIME.matches(sentAt)) { "sentAt" }
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MessageRequestCodec.MAX_NOW) { "now" }
        val work = o.str("work")
        require(work.length <= MessageRequestCodec.MAX_WORK) { "work" }
        val text = o.str("text").trim()
        require(text.isNotEmpty() && text.length <= MessageRequestCodec.MAX_TEXT) { "text" }
        Request(sender, group, sentAt, date, now, work, text)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.lane?.let { put("lane", it) }
        r.summary?.let { put("summary", it) }
        r.draft?.let { put("draft", it) }
        r.reason?.let { put("reason", it) }
        putJsonArray("proposals") { r.proposals.forEach { add(MessageRequestCodec.encodeProposal(it)) } }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("message triage response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(
            state = o.str("state"),
            lane = o.optStr("lane")?.takeIf { it in LANES },
            summary = o.optStr("summary"),
            draft = o.optStr("draft"),
            proposals = MessageRequestCodec.proposalsOf(o),
            reason = o.optStr("reason"),
        )
    }

    /**
     * A model's answer, which should be one JSON object `{"lane": …, "summary": …, "draft": …, "proposals": [...]}`
     * (possibly in a code fence). Anything that isn't that object, or names a lane MEKA doesn't know, is null, so a
     * chatty answer is never a lane, a draft or a proposal.
     */
    fun parseModelAnswer(text: String): Answer? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val lane = o.optStr("lane")?.takeIf { it in LANES } ?: return null
        return Answer(
            lane = lane,
            summary = o.optStr("summary"),
            draft = if (lane == NEEDS_REPLY) o.optStr("draft") else null,
            proposals = if (lane == ACTION) MessageRequestCodec.proposalsOf(o) else emptyList(),
        )
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
