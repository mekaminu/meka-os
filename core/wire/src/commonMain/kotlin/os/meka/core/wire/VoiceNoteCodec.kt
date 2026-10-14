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
 * A voice note's tidy-up on the wire (build plan V1, Voice notes into actions, slice 2): `POST /v1/ai/voice-note`
 * carries the **words** the phone heard in one voice note (never the recording), the note's subject ("Voice note ·
 * 14:02"), today's date and his work days. The answer is a tidy note in his own words, the first names of the people
 * it mentions, and up to [MAX_PROPOSALS] proposals (task · event · reminder · renewal · idea) the device checks again
 * and shows as cards that do nothing until Meka taps. Nothing is kept or logged on the server.
 *
 * A request outside the limits is refused, not trimmed (the device cuts before sending).
 */
object VoiceNoteCodec {
    const val MAX_TEXT = 6_000
    const val MAX_SUBJECT = 200
    const val MAX_NOW = 80
    const val MAX_WORK = 120
    /** A tidy note longer than this is dropped (the cards still come). */
    const val MAX_NOTE = 1_500
    const val MAX_PEOPLE = 6
    const val MAX_PERSON = 40
    /** A rambling note may hold more than a shared email: up to five proposals. */
    const val MAX_PROPOSALS = 5

    val KINDS = setOf(
        MessageRequestCodec.TASK, MessageRequestCodec.EVENT, MessageRequestCodec.REMINDER, MessageRequestCodec.RENEWAL,
        MessageRequestCodec.IDEA,
    )

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** As [HandleThisCodec.Request]: [text] is the note's words. */
    data class Request(val date: String, val now: String, val work: String, val subject: String, val text: String)

    /**
     * [state] as Ask's ([AskCodec.Response.ANSWERED] …); [note] the tidy note ("" when the model gave none),
     * [people] the names it mentions.
     */
    data class Response(
        val state: String,
        val note: String = "",
        val people: List<String> = emptyList(),
        val proposals: List<MessageRequestCodec.Proposal> = emptyList(),
        val reason: String? = null,
    )

    /** What a model's answer holds. */
    data class ModelAnswer(val note: String, val people: List<String>, val proposals: List<MessageRequestCodec.Proposal>)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("now", r.now)
        put("work", r.work)
        put("subject", r.subject)
        put("text", r.text)
    }.toString()

    fun decodeRequest(body: String): Request = wrap("voice-note request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MAX_NOW) { "now" }
        val work = o.str("work")
        require(work.length <= MAX_WORK) { "work" }
        val subject = o.str("subject").trim()
        require(subject.length <= MAX_SUBJECT) { "subject" }
        val text = o.str("text").trim()
        require(text.isNotEmpty() && text.length <= MAX_TEXT) { "text" }
        Request(date, now, work, subject, text)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        put("note", r.note)
        putJsonArray("people") { r.people.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("proposals") { r.proposals.forEach { add(MessageRequestCodec.encodeProposal(it)) } }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("voice-note response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val a = answerOf(o)
        Response(o.str("state"), a.note, a.people, a.proposals, (o["reason"] as? JsonPrimitive)?.contentOrNull?.take(MessageRequestCodec.MAX_FIELD))
    }

    /**
     * A model's answer, which should be one JSON object `{"note": "…", "people": [...], "proposals": [...]}` (possibly
     * in a code fence). Anything else is an empty answer, so a chatty reply can never act.
     */
    fun parseModelAnswer(text: String): ModelAnswer {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return ModelAnswer("", emptyList(), emptyList())
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return ModelAnswer("", emptyList(), emptyList())
        return answerOf(o)
    }

    private fun answerOf(o: JsonObject): ModelAnswer {
        val note = (o["note"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            ?.takeIf { it.length <= MAX_NOTE }.orEmpty()
        val people = (o["people"] as? JsonArray).orEmpty().mapNotNull { e ->
            (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_PERSON }
        }.distinctBy { it.lowercase() }.take(MAX_PEOPLE)
        return ModelAnswer(note, people, MessageRequestCodec.proposalsOf(o, KINDS, MAX_PROPOSALS))
    }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

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
