package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Obi's wording of the money check on the wire (Money redesign, Plan; data sharing approved by Meka 2026-10-24 21:13):
 * `POST /v1/ai/money-check` carries **only the ideas the device's rules found**: each idea's id, kind, the rules' title,
 * figure and why, and the payee names and amounts behind it. Never an account name or number, a sort code, a balance or
 * a date of birth; nothing else of Money. The answer is the same ids, ranked, each with Obi's title and why, which the
 * device checks again (`MoneyCheckWords.check` in core/domain: only ids it sent, only numbers it sent) before showing.
 *
 * A request outside the limits is refused, not trimmed (the device cuts before sending).
 */
object MoneyCheckCodec {
    const val MAX_IDEAS = 12
    const val MAX_BEHIND = 6
    const val MAX_ID = 60
    const val MAX_SHORT = 80
    const val MAX_TEXT = 400
    /** What is read of a model's answer per idea (the device keeps less). */
    const val MAX_TITLE = 80
    const val MAX_WHY = 300

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** One payment behind an idea: who and how much ("Starbucks", "£4.20"); a regular payment's "monthly" amount the same. */
    data class Pay(val label: String, val amount: String)

    /** One idea as the rules found it. */
    data class Idea(val id: String, val kind: String, val title: String, val figure: String, val why: String, val behind: List<Pay>)

    /** [date] the device's local ISO date. */
    data class Request(val date: String, val ideas: List<Idea>)

    /** One idea in Obi's words. */
    data class Worded(val id: String, val title: String, val why: String)

    /** [state]: the same states as Ask's; [ideas] in Obi's order, empty unless answered. */
    data class Response(val state: String, val ideas: List<Worded> = emptyList(), val reason: String? = null)

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        putJsonArray("ideas") {
            r.ideas.forEach { i ->
                add(buildJsonObject {
                    put("id", i.id); put("kind", i.kind); put("title", i.title); put("figure", i.figure); put("why", i.why)
                    putJsonArray("behind") { i.behind.forEach { p -> add(buildJsonObject { put("label", p.label); put("amount", p.amount) }) } }
                })
            }
        }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("money check request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val a = o["ideas"] as? JsonArray ?: throw IllegalArgumentException("ideas")
        require(a.size in 1..MAX_IDEAS) { "ideas" }
        val ideas = a.map { e ->
            val i = e as? JsonObject ?: throw IllegalArgumentException("idea")
            val behind = (i["behind"] as? JsonArray ?: throw IllegalArgumentException("behind")).also { require(it.size <= MAX_BEHIND) { "behind" } }.map { p ->
                val po = p as? JsonObject ?: throw IllegalArgumentException("pay")
                Pay(po.str("label", MAX_SHORT), po.str("amount", MAX_SHORT))
            }
            Idea(i.str("id", MAX_ID), i.str("kind", MAX_SHORT), i.str("title", MAX_SHORT), i.str("figure", MAX_SHORT), i.str("why", MAX_TEXT), behind)
        }
        require(ideas.map { it.id }.toSet().size == ideas.size) { "ids" }
        Request(date, ideas)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        putJsonArray("ideas") { r.ideas.forEach { i -> add(buildJsonObject { put("id", i.id); put("title", i.title); put("why", i.why) }) } }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("money check response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(o.str("state"), worded(o["ideas"]), (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(MAX_TEXT))
    }

    /**
     * A model's answer, which should be one JSON object `{"ideas":[{"id":…,"title":…,"why":…}, …]}` (possibly in a code
     * fence), in Obi's order. Anything that isn't that is nothing; an entry missing a field or over-long is dropped, and
     * only ids in [known] are read, each once.
     */
    fun parseModelAnswer(text: String, known: Set<String>): Response {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Response(AskCodec.Response.ANSWERED)
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return Response(AskCodec.Response.ANSWERED)
        return Response(AskCodec.Response.ANSWERED, worded(o["ideas"]).filter { it.id in known })
    }

    private fun worded(e: JsonElement?): List<Worded> {
        val seen = HashSet<String>()
        return (e as? JsonArray).orEmpty().mapNotNull { x ->
            val i = x as? JsonObject ?: return@mapNotNull null
            fun s(k: String, max: Int) = (i[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }
            val id = s("id", MAX_ID) ?: return@mapNotNull null
            val title = s("title", MAX_TITLE) ?: return@mapNotNull null
            val why = s("why", MAX_WHY) ?: return@mapNotNull null
            if (!seen.add(id)) null else Worded(id, title, why)
        }.take(MAX_IDEAS)
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
