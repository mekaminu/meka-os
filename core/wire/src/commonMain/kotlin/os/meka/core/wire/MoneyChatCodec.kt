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
 * Ask Obi's money chat on the wire (Money redesign, slice 2; data sharing approved by Meka 2026-10-24 21:13):
 * `POST /v1/ai/money-chat` carries his question, the last few turns of the chat, and **only the facts the device made**
 * (`MoneyChatObi.facts` in core/domain): monthly totals in and out and by category, the biggest payees with amounts,
 * what's still to go out (payee, amount, day) and the payday's date. Never an account name or number, a sort code, an
 * IBAN or a balance. The answer is one short paragraph, which the device checks (`MoneyChatObi.check`: only numbers it
 * sent) before showing.
 *
 * A request outside the limits is refused, not trimmed (the device cuts before sending).
 */
object MoneyChatCodec {
    const val MAX_QUESTION = 300
    const val MAX_FACTS = 40
    const val MAX_FACT = 700
    const val MAX_HISTORY = 4
    const val MAX_TURN = 700
    /** What is read of a model's answer (the device keeps less). */
    const val MAX_ANSWER = 800

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** One earlier turn: his [question] and the [answer] he was shown. */
    data class Turn(val question: String, val answer: String)

    /** [date] the device's local ISO date. */
    data class Request(val date: String, val question: String, val facts: List<String>, val history: List<Turn> = emptyList())

    /** [state]: the same states as Ask's; [answer] only when answered. */
    data class Response(val state: String, val answer: String? = null, val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("question", r.question)
        putJsonArray("facts") { r.facts.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("history") { r.history.forEach { t -> add(buildJsonObject { put("q", t.question); put("a", t.answer) }) } }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("money chat request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val question = o.str("question", MAX_QUESTION).trim()
        require(question.isNotEmpty()) { "question" }
        val facts = (o["facts"] as? JsonArray ?: throw IllegalArgumentException("facts")).also { require(it.size in 1..MAX_FACTS) { "facts" } }
            .map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { require(it.length <= MAX_FACT) { "fact" } } ?: throw IllegalArgumentException("fact") }
        val history = ((o["history"] as? JsonArray) ?: JsonArray(emptyList())).also { require(it.size <= MAX_HISTORY) { "history" } }.map { e ->
            val t = e as? JsonObject ?: throw IllegalArgumentException("turn")
            Turn(t.str("q", MAX_QUESTION), t.str("a", MAX_TURN))
        }
        Request(date, question, facts, history)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.answer?.let { put("answer", it) }
        r.reason?.let { put("reason", it) }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("money chat response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        fun opt(k: String, max: Int) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(max)
        Response(o.str("state"), opt("answer", MAX_ANSWER), opt("reason", MAX_TURN))
    }

    /** A model's answer: its text trimmed, or null when empty or too long to be one short paragraph. */
    fun parseModelAnswer(text: String): Response {
        val t = text.trim().removeSurrounding("\"").trim()
        return Response(AskCodec.Response.ANSWERED, t.takeIf { it.isNotEmpty() && it.length <= MAX_ANSWER })
    }

    private fun JsonObject.str(k: String, max: Int = Int.MAX_VALUE): String {
        val s = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)
        require(s.length <= max) { k }
        return s
    }

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
