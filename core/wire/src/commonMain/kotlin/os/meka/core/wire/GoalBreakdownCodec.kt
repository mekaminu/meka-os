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
 * "Break it down" on the wire (build plan V1, Goals, properly, slice 4): `POST /v1/ai/goal-breakdown` carries **one**
 * goal in Meka's own words — its title, horizon, done-by, why it matters, what done looks like, and the milestones and
 * open steps it already has — with today's date. Never another goal, a task that isn't linked to it, or anything else of
 * the device's. The answer is proposed milestones and first steps (plain titles) the device checks again
 * (`GoalBreakdownRules.check` in core/domain) and shows for Meka to tick; nothing is added until he keeps them.
 *
 * A request outside the limits is refused, not trimmed (the device cuts before sending).
 */
object GoalBreakdownCodec {
    const val MAX_TITLE = 200
    const val MAX_LABEL = 60
    const val MAX_TEXT = 500
    const val MAX_KNOWN = 12
    const val MAX_ITEM = 120
    /** What is read of a model's answer at most (the device keeps fewer). */
    const val MAX_MILESTONES = 7
    const val MAX_STEPS = 3

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [date] the device's local ISO date, [now] its readable "Sunday 11 October 2026 · 21:40"; [horizon] "This month";
     * [due] "by Sat 31 Oct" or ""; [why] and [done] Meka's words or ""; [milestones] and [steps] what the goal already has.
     */
    data class Request(
        val date: String,
        val now: String,
        val title: String,
        val horizon: String,
        val due: String,
        val why: String,
        val done: String,
        val milestones: List<String>,
        val steps: List<String>,
    )

    /** [state]: the same states as Ask's; the lists are empty unless answered. */
    data class Response(
        val state: String,
        val milestones: List<String> = emptyList(),
        val steps: List<String> = emptyList(),
        val reason: String? = null,
    )

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("date", r.date)
        put("now", r.now)
        put("title", r.title)
        put("horizon", r.horizon)
        put("due", r.due)
        put("why", r.why)
        put("done", r.done)
        putJsonArray("milestones") { r.milestones.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("steps") { r.steps.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("goal breakdown request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= MessageRequestCodec.MAX_NOW) { "now" }
        val title = o.str("title").trim()
        require(title.isNotEmpty() && title.length <= MAX_TITLE) { "title" }
        val horizon = o.str("horizon").trim()
        require(horizon.length <= MAX_LABEL) { "horizon" }
        val due = o.str("due").trim()
        require(due.length <= MAX_LABEL) { "due" }
        val why = o.str("why").trim()
        require(why.length <= MAX_TEXT) { "why" }
        val done = o.str("done").trim()
        require(done.length <= MAX_TEXT) { "done" }
        Request(date, now, title, horizon, due, why, done, o.list("milestones"), o.list("steps"))
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.reason?.let { put("reason", it) }
        putJsonArray("milestones") { r.milestones.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("steps") { r.steps.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("goal breakdown response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(o.str("state"), o.items("milestones", MAX_MILESTONES), o.items("steps", MAX_STEPS), o.optStr("reason"))
    }

    /**
     * A model's answer, which should be one JSON object `{"milestones": [...], "steps": [...]}` of plain strings
     * (possibly in a code fence). Anything that isn't that object is nothing; a non-string or over-long item is dropped,
     * and at most [MAX_MILESTONES] milestones and [MAX_STEPS] steps are read.
     */
    fun parseModelAnswer(text: String): Response {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Response(AskCodec.Response.ANSWERED)
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return Response(AskCodec.Response.ANSWERED)
        return Response(AskCodec.Response.ANSWERED, o.items("milestones", MAX_MILESTONES), o.items("steps", MAX_STEPS))
    }

    /** A request's list: strings only, each non-blank and at most [MAX_ITEM], at most [MAX_KNOWN] of them. */
    private fun JsonObject.list(k: String): List<String> {
        val a = this[k] as? JsonArray ?: throw IllegalArgumentException(k)
        require(a.size <= MAX_KNOWN) { k }
        return a.map { e ->
            val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: throw IllegalArgumentException(k)
            require(s.isNotEmpty() && s.length <= MAX_ITEM) { k }
            s
        }
    }

    /** An answer's list: the strings that fit, the rest skipped, at most [max]. */
    private fun JsonObject.items(k: String, max: Int): List<String> =
        (this[k] as? JsonArray).orEmpty()
            .mapNotNull { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ITEM } }
            .take(max)

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    private fun JsonObject.optStr(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= MAX_TEXT }

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
