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
    /**
     * Something on the renewals radar ("Handle this", slice 2): only "Handle this" proposes it, so a watched person's
     * message or a group never does ([parseModelAnswer] and the triage codecs read [KINDS] only). A device answer may
     * carry it ([decodeResponse] reads [ALL_KINDS]); an older device's decoder drops it as an unknown kind.
     */
    const val RENEWAL = "renewal"
    val ALL_KINDS = KINDS + RENEWAL

    /** A renewal's repeat on the wire ([Proposal.repeat]). */
    val REPEATS = setOf("none", "month", "quarter", "half_year", "year")
    /** What a renewal is on the wire ([Proposal.type]); the domain maps each to its obligation kind. */
    val RENEWAL_TYPES = setOf("mot", "car_tax", "insurance", "boiler", "subscription", "bill", "licence", "warranty", "other")
    private val COST = Regex("""\d{1,7}(?:\.\d{1,2})?""")

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
     * A [RENEWAL] may also carry [type] (one of [RENEWAL_TYPES]), [repeat] (one of [REPEATS]) and [cost] in pounds
     * ("412" or "9.99"); anything else in them is dropped.
     */
    data class Proposal(
        val kind: String,
        val title: String? = null,
        val date: String? = null,
        val time: String? = null,
        val words: String? = null,
        val type: String? = null,
        val repeat: String? = null,
        val cost: String? = null,
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
        Response(o.str("state"), proposalsOf(o, ALL_KINDS), o.optStr("reason"))
    }

    /**
     * A model's answer, which should be one JSON object `{"proposals": [...]}` (possibly in a code fence): up to
     * [MAX_PROPOSALS] proposals of a known kind. Anything else is no proposals, so a chatty answer can never act.
     */
    fun parseModelAnswer(text: String, kinds: Set<String> = KINDS): List<Proposal> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return emptyList()
        return proposalsOf(o, kinds)
    }

    /** The `proposals` array of [o]: [kinds] only, at most [MAX_PROPOSALS] (shared with [MessageTriageCodec]). */
    internal fun proposalsOf(o: JsonObject, kinds: Set<String> = KINDS): List<Proposal> =
        (o["proposals"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val kind = p.optStr("kind")?.takeIf { it in kinds } ?: return@mapNotNull null
            val renewal = kind == RENEWAL
            Proposal(
                kind, p.optStr("title"), p.optStr("date")?.takeIf(DATE::matches), p.optStr("time")?.takeIf(TIME::matches), p.optStr("words"),
                type = p.optStr("type")?.takeIf { renewal && it in RENEWAL_TYPES },
                repeat = p.optStr("repeat")?.takeIf { renewal && it in REPEATS },
                cost = p.optStr("cost")?.removePrefix("£")?.replace(",", "")?.trim()?.takeIf { renewal && COST.matches(it) },
            )
        }.take(MAX_PROPOSALS)

    internal fun encodeProposal(p: Proposal): JsonObject = buildJsonObject {
        put("kind", p.kind)
        p.title?.let { put("title", it) }
        p.date?.let { put("date", it) }
        p.time?.let { put("time", it) }
        p.words?.let { put("words", it) }
        p.type?.let { put("type", it) }
        p.repeat?.let { put("repeat", it) }
        p.cost?.let { put("cost", it) }
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
