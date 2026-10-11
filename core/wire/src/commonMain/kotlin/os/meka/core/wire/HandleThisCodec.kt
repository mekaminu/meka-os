package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * "Handle this" on the wire (build plan V1, "Handle this", slice 1): `POST /v1/ai/handle-this` carries **one** piece
 * of text Meka chose to share with MEKA (an email he shared or forwarded from the mail app, a message, a web page's
 * words, selected text), its subject when the share had one, today's date and his work days. Never who sent it, an
 * address, a number or anything else of the device's. The answer is the same shape as a watched message's
 * ([MessageRequestCodec.Response]): zero or more proposals the device checks again
 * (`HandleThisRules` / `MessageRequestRules.check` in core/domain) and shows as cards that do nothing until Meka taps.
 *
 * A request outside the limits is refused, not trimmed (the device cuts before sending).
 */
object HandleThisCodec {
    const val MAX_TEXT = 6_000
    const val MAX_SUBJECT = 200
    const val MAX_NOW = 80
    const val MAX_WORK = 120

    /**
     * The kinds "Handle this" may propose (no work-from-home day: that is only ever asked by someone close; a renewal
     * since slice 2).
     */
    val KINDS = setOf(MessageRequestCodec.TASK, MessageRequestCodec.EVENT, MessageRequestCodec.REMINDER, MessageRequestCodec.RENEWAL)

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [date] the device's local ISO date, [now] its readable "Saturday 10 October 2026 · 21:40", [work] Meka's work
     * days in a line (empty when none are set), [subject] the share's subject ("" when it had none), [text] what was
     * shared.
     */
    data class Request(val date: String, val now: String, val work: String, val subject: String, val text: String)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("now", r.now)
        put("work", r.work)
        put("subject", r.subject)
        put("text", r.text)
    }.toString()

    fun decodeRequest(body: String): Request = try {
        val o = json.parseToJsonElement(body).jsonObject
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MAX_NOW) { "now" }
        val work = o.str("work")
        require(work.length <= MAX_WORK) { "work" }
        val subject = o.str("subject").trim()
        require(subject.length <= MAX_SUBJECT) { "subject" }
        val text = o.str("text").trim()
        require(text.length <= MAX_TEXT) { "text" }
        require(text.isNotEmpty() || subject.isNotEmpty()) { "text" }
        Request(date, now, work, subject, text)
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad handle-this request: ${e.message}", e)
    }

    /** A model's answer: [MessageRequestCodec.parseModelAnswer], then only [KINDS]. */
    fun parseModelAnswer(text: String): List<MessageRequestCodec.Proposal> =
        MessageRequestCodec.parseModelAnswer(text, KINDS)

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)
}
