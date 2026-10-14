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
    /**
     * A drafted reply Meka copies and sends himself ("Handle this", slice 3): like [RENEWAL], only "Handle this" asks
     * for it; its words are [Proposal.body] (at most [MAX_BODY] characters, else the proposal is dropped).
     */
    const val REPLY = "reply"
    /**
     * A travel booking for Trips (Travel mode, slice 2b): like [RENEWAL], only "Handle this" asks for it. It may carry
     * [Proposal.type] (one of [BOOKING_TYPES]), [Proposal.ref] (the booking reference, [REF]) and [Proposal.until] (a
     * stay's check-out or a hire car's return day, YYYY-MM-DD); anything else in them is dropped.
     */
    const val BOOKING = "booking"
    /**
     * Something for Someday → Ideas (Voice notes into actions, slice 2): only a voice note's tidy-up proposes it
     * ([VoiceNoteCodec]); it carries a title and nothing else. An older device's decoder drops it as an unknown kind.
     */
    const val IDEA = "idea"
    val ALL_KINDS = KINDS + RENEWAL + REPLY + BOOKING + IDEA
    /** What a booking is on the wire ([Proposal.type]); the domain maps each to its booking kind. */
    val BOOKING_TYPES = setOf("flight", "train", "stay", "car", "other")
    /** A booking reference: 3–16 letters, digits or hyphens. */
    private val REF = Regex("""[A-Za-z0-9-]{3,16}""")
    const val MAX_BODY = 1_500

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
     * ("412" or "9.99"); anything else in them is dropped. A [REPLY] carries its drafted words in [body]. A [BOOKING]
     * may carry [type] (one of [BOOKING_TYPES]), [ref] and [until] (additive: an older reader ignores them).
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
        val body: String? = null,
        val ref: String? = null,
        val until: String? = null,
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

    /** The `proposals` array of [o]: [kinds] only, at most [max] (shared with [MessageTriageCodec], [VoiceNoteCodec]). */
    internal fun proposalsOf(o: JsonObject, kinds: Set<String> = KINDS, max: Int = MAX_PROPOSALS): List<Proposal> =
        (o["proposals"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val kind = p.optStr("kind")?.takeIf { it in kinds } ?: return@mapNotNull null
            val renewal = kind == RENEWAL
            val booking = kind == BOOKING
            // A reply without its words, or with more than MAX_BODY of them, is nothing to offer.
            val body = if (kind == REPLY) {
                (p["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_BODY } ?: return@mapNotNull null
            } else null
            Proposal(
                kind, p.optStr("title"), p.optStr("date")?.takeIf(DATE::matches), p.optStr("time")?.takeIf(TIME::matches), p.optStr("words"),
                type = p.optStr("type")?.takeIf { (renewal && it in RENEWAL_TYPES) || (booking && it in BOOKING_TYPES) },
                repeat = p.optStr("repeat")?.takeIf { renewal && it in REPEATS },
                cost = p.optStr("cost")?.removePrefix("£")?.replace(",", "")?.trim()?.takeIf { renewal && COST.matches(it) },
                body = body,
                ref = p.optStr("ref")?.trim()?.takeIf { booking && REF.matches(it) },
                until = p.optStr("until")?.takeIf { booking && DATE.matches(it) },
            )
        }.take(max)

    internal fun encodeProposal(p: Proposal): JsonObject = buildJsonObject {
        put("kind", p.kind)
        p.title?.let { put("title", it) }
        p.date?.let { put("date", it) }
        p.time?.let { put("time", it) }
        p.words?.let { put("words", it) }
        p.type?.let { put("type", it) }
        p.repeat?.let { put("repeat", it) }
        p.cost?.let { put("cost", it) }
        p.body?.let { put("body", it) }
        p.ref?.let { put("ref", it) }
        p.until?.let { put("until", it) }
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
