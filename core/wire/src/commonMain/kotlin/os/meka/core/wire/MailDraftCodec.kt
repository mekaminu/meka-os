package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A reply drafted by MEKA's AI on the wire (Inbox autopilot, slice 6b-2b): `POST /v1/ai/mail-draft` carries one email
 * as its card shows it — the account's label, whether it is the business account, the sender's name (never the
 * address), the subject and the provider's short preview — with the date and time, and up to [MAX_EXAMPLES] replies
 * Meka wrote and sent before (his own words, for his tone). Never a mailbox id, an address or anyone's whole email.
 * The answer is the draft, which the device checks (`MailDraftRules.check`) and only ever puts in his editor.
 *
 * A request outside the limits is refused, not trimmed (the device cuts before sending).
 */
object MailDraftCodec {
    const val MAX_ACCOUNT = 30
    const val MAX_FROM = 60
    const val MAX_SUBJECT = 200
    const val MAX_PREVIEW = 300
    const val MAX_EXAMPLES = 3
    const val MAX_EXAMPLE = 600
    /** What is read of a model's answer at most (the device keeps 1,200). */
    const val MAX_DRAFT = 2_000
    const val MAX_REASON = 300

    private val json = Json { ignoreUnknownKeys = true }

    /** [date] the device's local ISO date, [now] its readable "Tuesday 13 October 2026 · 17:05". */
    data class Request(
        val date: String,
        val now: String,
        val account: String,
        val business: Boolean,
        val from: String,
        val subject: String,
        val preview: String? = null,
        val examples: List<String> = emptyList(),
    )

    /** [state]: the same states as Ask's; [draft] only when answered. */
    data class Response(val state: String, val draft: String? = null, val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("now", r.now)
        put("account", r.account)
        put("business", r.business)
        put("from", r.from)
        put("subject", r.subject)
        r.preview?.let { put("preview", it) }
        putJsonArray("examples") { r.examples.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("mail draft request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(isDate(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MessageRequestCodec.MAX_NOW) { "now" }
        val account = o.str("account").trim()
        require(account.isNotEmpty() && account.length <= MAX_ACCOUNT) { "account" }
        val business = (o["business"] as? JsonPrimitive)?.booleanOrNull ?: false
        val from = o.str("from").trim()
        require(from.isNotEmpty() && from.length <= MAX_FROM) { "from" }
        val subject = o.str("subject").trim()
        require(subject.length <= MAX_SUBJECT) { "subject" }
        val preview = (o["preview"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        require(preview == null || preview.length <= MAX_PREVIEW) { "preview" }
        val examples = (o["examples"] as? JsonArray).orEmpty().map { e ->
            val t = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw IllegalArgumentException("example")
            require(t.isNotEmpty() && t.length <= MAX_EXAMPLE) { "example" }
            t
        }
        require(examples.size <= MAX_EXAMPLES) { "examples" }
        Request(date, now, account, business, from, subject, preview, examples)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.draft?.let { put("draft", it) }
        r.reason?.let { put("reason", it.take(MAX_REASON)) }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("mail draft response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(o.str("state"), o.optStr("draft", MAX_DRAFT), o.optStr("reason", MAX_REASON))
    }

    /**
     * A model's answer, which should be one JSON object `{"draft": "…"}` (possibly in a code fence). Anything that isn't
     * that object is answered with no draft.
     */
    fun parseModelAnswer(text: String): Response {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Response(AskCodec.Response.ANSWERED)
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return Response(AskCodec.Response.ANSWERED)
        val draft = (o["draft"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DRAFT)
        return Response(AskCodec.Response.ANSWERED, draft)
    }

    // By hand, not with regexes, so Kotlin/Native reads them exactly as the JVM does.
    private fun isDate(s: String) = s.length == 10 && s[4] == '-' && s[7] == '-' &&
        s.filterIndexed { i, _ -> i != 4 && i != 7 }.all { it in '0'..'9' }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    private fun JsonObject.optStr(k: String, max: Int): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(max)

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
