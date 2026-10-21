package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Ask MEKA on the wire (build plan V1, AI layer slice 3): a device's question with its picture of today
 * (`POST /v1/ai/ask`), the server's answer, and reading a model's answer into words and proposed actions.
 *
 * The limits mirror `AskRules` in core/domain (the wire module doesn't depend on it): a question of at most 500
 * characters, at most 60 lines of at most 160, handles "t1"…"t999" and "e1"…"e99". A request outside them is refused, not trimmed.
 */
object AskCodec {
    const val MAX_QUESTION = 500
    const val MAX_ITEMS = 60
    const val MAX_LINE = 160
    const val MAX_ANSWER = 1200
    /** At most this many proposals are read from a model's answer (the device shows at most three). */
    const val MAX_ACTIONS = 5
    const val MAX_FIELD = 200
    val KINDS = setOf("needs_you", "task", "done", "event", "weather", "shopping", "school", "meals", "date_night", "renewals", "wins", "queue", "leave", "facts", "protected", "errands", "appointments", "checklists", "goals", "bins", "car", "life_admin", "gifts", "people", "trips", "notifications", "commute", "mail", "help", "kids", "barca", "days")

    /** A task's handle ("t1"…"t999") or, since Edit your calendars completeness slice 4, an event's ("e1"…"e99"). */
    private val REF = Regex("t[1-9][0-9]{0,2}|e[1-9][0-9]?")
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val json = Json { ignoreUnknownKeys = true }

    /** Exchanges sent with a question in a conversation (Talk to MEKA); more are refused. */
    const val MAX_HISTORY = 6
    /** Lines of what Meka confirmed after one answer. */
    const val MAX_DONE = 3

    data class Item(val ref: String, val kind: String, val line: String)

    /**
     * An earlier exchange in a conversation: Meka's [question] (at most [MAX_QUESTION]), MEKA's [answer] (at most
     * [MAX_ANSWER]) and what Meka confirmed after it ([done]: at most [MAX_DONE] lines of at most [MAX_LINE]).
     */
    data class Turn(val question: String, val answer: String, val done: List<String> = emptyList())

    /**
     * [date] is the device's local ISO date, [now] its readable "Thursday 8 October 2026 · 17:05". [history] is the
     * conversation so far (oldest first, empty for a one-off question; left out of the JSON when empty, so an older
     * server reads it as before), [voice] says the answer will be spoken.
     */
    data class Request(
        val question: String,
        val date: String,
        val now: String,
        val items: List<Item>,
        val history: List<Turn> = emptyList(),
        val voice: Boolean = false,
        /**
         * Voice completeness, slice 3 (the first words sooner): a spoken question ([voice]) whose answer the device will
         * say in Navilife's voice asks the server to say the answer's first piece in [speakVoice] (null: the default)
         * with the answer, so the device needn't ask for it after. Left out of the JSON when false.
         */
        val speak: Boolean = false,
        val speakVoice: String? = null,
        /**
         * The companion, slice 3: how Navilife addresses him, [CALL_SIR] when he chose "sir" in Settings → Voice, null for
         * by name. Left out of the JSON when null; an older server simply ignores it, and an unknown value reads as null.
         */
        val callMe: String? = null,
        /**
         * The companion, slice 5: short notes of earlier chats (oldest first, at most [MAX_RECALL] of at most
         * [MAX_LINE]), so an open turn can pick up where they left off. Left out of the JSON when empty; an older
         * server ignores it.
         */
        val recall: List<String> = emptyList(),
        /**
         * One name everywhere, slice 2: what he is called (About you → Your name's first word), so the companion uses
         * the same name as Today and the orb. Null when Your name is empty: the server then calls him by no name. Left
         * out of the JSON when null; an older server ignores it, and anything that isn't a plain name reads as null.
         */
        val name: String? = null,
    )

    /** A proposal as the model wrote it: every field optional, strings at most [MAX_FIELD] characters. */
    data class Action(
        val kind: String,
        val ref: String? = null,
        val title: String? = null,
        val date: String? = null,
        val time: String? = null,
        val hours: Int? = null,
        val minutes: Int? = null,
    )

    /**
     * [state]: answered · off (no key) · over (the month's budget is spent) · failed ([reason] says why). [firstText] and
     * [firstAudio] (base64 MP3, said by [firstVoice]): the answer's first spoken piece, when the request asked to
     * [Request.speak] and the voice answered in time; both or neither.
     */
    data class Response(
        val state: String, val answer: String? = null, val actions: List<Action> = emptyList(), val reason: String? = null,
        val firstText: String? = null, val firstAudio: String? = null, val firstVoice: String? = null,
        /**
         * The companion, slice 2 (live world facts): the web results the answer drew on (title and https URL), at most
         * [MAX_SOURCES]. Left out of the JSON when empty; an older device simply ignores it.
         */
        val sources: List<Source> = emptyList(),
        /**
         * The companion, slice 5: one short line of what this conversation was about, for the device to keep (at most
         * [MAX_NOTE]); null when there's nothing worth keeping. Left out of the JSON when null; an older device ignores it.
         */
        val note: String? = null,
    ) {
        companion object {
            const val ANSWERED = "answered"
            const val OFF = "off"
            const val OVER = "over"
            const val FAILED = "failed"
        }
    }

    /** One web result an answer drew on: its [title] and its https [url]. */
    data class Source(val title: String, val url: String)

    const val MAX_SOURCES = 3
    const val MAX_SOURCE_TITLE = 160
    const val MAX_SOURCE_URL = 500

    /** [Request.callMe]'s one value. */
    const val CALL_SIR = "sir"

    /** [Request.name]'s longest. */
    const val MAX_NAME = 40

    /**
     * [Request.name] as sent and read: trimmed, at most [MAX_NAME] characters of letters, spaces, hyphens, apostrophes
     * and full stops, starting with a letter; anything else is null (no name, never an error).
     */
    fun plainName(raw: String?): String? =
        raw?.split(' ', '\t', '\n', '\r')?.filter { it.isNotEmpty() }?.joinToString(" ")?.takeIf { n ->
            n.isNotEmpty() && n.length <= MAX_NAME && n.first().isLetter() && n.all { it.isLetter() || it in " -'’." }
        }

    /** The companion, slice 5: notes of earlier chats sent with a question, and the longest note an answer carries. */
    const val MAX_RECALL = 6
    const val MAX_NOTE = 200

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("question", r.question)
        put("date", r.date)
        put("now", r.now)
        putJsonArray("items") { r.items.forEach { i -> addJsonObject { put("ref", i.ref); put("kind", i.kind); put("line", i.line) } } }
        if (r.history.isNotEmpty()) {
            putJsonArray("history") {
                r.history.forEach { t ->
                    addJsonObject {
                        put("q", t.question)
                        put("a", t.answer)
                        if (t.done.isNotEmpty()) putJsonArray("done") { t.done.forEach { add(JsonPrimitive(it)) } }
                    }
                }
            }
        }
        if (r.voice) put("voice", true)
        if (r.speak) {
            put("speak", true)
            r.speakVoice?.let { put("speakVoice", it) }
        }
        if (r.callMe == CALL_SIR) put("callMe", CALL_SIR)
        if (r.recall.isNotEmpty()) putJsonArray("recall") { r.recall.forEach { add(JsonPrimitive(it)) } }
        plainName(r.name)?.let { put("name", it) }
    }.toString()

    fun decodeRequest(body: String): Request = wrap("ask request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val q = o.str("question").trim()
        require(q.isNotEmpty() && q.length <= MAX_QUESTION) { "question" }
        val date = o.str("date")
        require(DATE.matches(date)) { "date" }
        val now = o.str("now")
        require(now.length <= 80) { "now" }
        val items = (o["items"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
            val i = e.jsonObject
            Item(i.str("ref"), i.str("kind"), i.str("line")).also {
                require(it.ref.isEmpty() || REF.matches(it.ref)) { "ref" }
                require(it.kind in KINDS) { "kind" }
                require(it.line.length <= MAX_LINE) { "line" }
            }
        }
        require(items.size <= MAX_ITEMS) { "items" }
        val history = (o["history"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
            val t = e.jsonObject
            val done = (t["done"] as? JsonArray ?: JsonArray(emptyList())).map { d ->
                ((d as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("done")).also {
                    require(it.length <= MAX_LINE) { "done" }
                }
            }
            Turn(t.str("q"), t.str("a"), done).also {
                require(it.question.isNotBlank() && it.question.length <= MAX_QUESTION) { "history question" }
                require(it.answer.length <= MAX_ANSWER) { "history answer" }
                require(done.size <= MAX_DONE) { "done" }
            }
        }
        require(history.size <= MAX_HISTORY) { "history" }
        val voice = (o["voice"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        val speak = voice && (o["speak"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        val speakVoice = if (!speak) null else o.optStr("speakVoice")?.also { require(SPEAK_VOICE.matches(it)) { "speakVoice" } }
        val callMe = (o["callMe"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it == CALL_SIR }
        val recall = (o["recall"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
            ((e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("recall")).also {
                require(it.isNotBlank() && it.length <= MAX_LINE) { "recall" }
            }
        }
        require(recall.size <= MAX_RECALL) { "recall" }
        val name = plainName((o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        Request(q, date, now, items, history, voice, speak, speakVoice, callMe, recall, name)
    }

    /** The answer's sources: only whole ones with an https URL within the limits; anything else is simply not there. */
    private fun sourcesOf(o: JsonObject): List<Source> =
        (o["sources"] as? JsonArray).orEmpty().mapNotNull { e ->
            val so = e as? JsonObject ?: return@mapNotNull null
            val title = so.optStr("title")?.trim()?.takeIf { it.length <= MAX_SOURCE_TITLE } ?: return@mapNotNull null
            val url = so.optStr("url")?.trim()?.takeIf { it.startsWith("https://") && it.length <= MAX_SOURCE_URL && it.none(Char::isWhitespace) }
                ?: return@mapNotNull null
            Source(title, url)
        }.take(MAX_SOURCES)

    /** A voice id as [SpeechCodec] takes it ("Amy", "Aoede", "Aoede-US"). */
    private val SPEAK_VOICE = Regex("[A-Za-z]{2,20}(-US)?")

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.answer?.let { put("answer", it) }
        r.reason?.let { put("reason", it) }
        putJsonArray("actions") { r.actions.forEach { add(encodeAction(it)) } }
        if (r.firstText != null && r.firstAudio != null) {
            put("firstText", r.firstText)
            put("firstAudio", r.firstAudio)
            r.firstVoice?.let { put("firstVoice", it) }
        }
        if (r.sources.isNotEmpty()) {
            putJsonArray("sources") { r.sources.take(MAX_SOURCES).forEach { src -> addJsonObject { put("title", src.title); put("url", src.url) } } }
        }
        r.note?.takeIf { it.isNotBlank() && it.length <= MAX_NOTE }?.let { put("note", it) }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("ask response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(
            state = o.str("state"),
            answer = o.optStr("answer"),
            actions = actionsOf(o),
            reason = o.optStr("reason"),
            sources = sourcesOf(o),
            note = o.optStr("note")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NOTE },
        ).let { r ->
            // The first piece's clip: kept only whole and within SpeechCodec's limits, else simply not there.
            val text = o.optStr("firstText")?.takeIf { it.isNotBlank() && it.length <= SpeechCodec.MAX_TEXT }
            val audio = o.optStr("firstAudio")?.takeIf { it.isNotEmpty() && it.length <= SpeechCodec.MAX_AUDIO_B64 }
            if (text == null || audio == null) r else r.copy(firstText = text, firstAudio = audio, firstVoice = o.optStr("firstVoice")?.take(40))
        }
    }

    /**
     * The companion, slice 9 (speech that starts within about a second): `POST /v1/ai/ask-stream` takes the same request
     * as `/v1/ai/ask` and answers with lines of JSON as they are ready: at most one [StreamLine.First] (the answer's
     * first sentence, said by the server while the model was still writing the rest), then the [StreamLine.Final]
     * response exactly as `/v1/ai/ask` sends it. A server without the route answers 404 and the device asks as before.
     */
    const val STREAM_PATH = "/v1/ai/ask-stream"

    sealed interface StreamLine {
        /** The first piece of Navilife's own words, and its clip (base64 MP3) in [voice]. */
        data class First(val text: String, val audio: String, val voice: String?) : StreamLine
        data class Final(val response: Response) : StreamLine
    }

    fun encodeStreamFirst(f: StreamLine.First): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("first", buildJsonObject {
            put("text", f.text)
            put("audio", f.audio)
            f.voice?.let { put("voice", it) }
        })
    }.toString()

    /**
     * One line of the stream: a first piece (kept only whole and within [SpeechCodec]'s limits, else null and simply not
     * there), the final response ([decodeResponse], which throws on a bad one), or null for a blank line.
     */
    fun decodeStreamLine(line: String): StreamLine? {
        if (line.isBlank()) return null
        val o = wrap("ask stream line") { json.parseToJsonElement(line).jsonObject }
        val f = o["first"]
        if (f != null) {
            val fo = f as? JsonObject ?: return null
            if ((o["w"] as? JsonPrimitive)?.intOrNull != WireCodec.VERSION) return null
            val text = (fo["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= SpeechCodec.MAX_TEXT } ?: return null
            val audio = (fo["audio"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.length <= SpeechCodec.MAX_AUDIO_B64 } ?: return null
            val voice = (fo["voice"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(40)
            return StreamLine.First(text, audio, voice)
        }
        return StreamLine.Final(decodeResponse(line))
    }

    /**
     * A model's answer, which should be one JSON object `{"answer": "…", "actions": [...]}` (possibly in a code fence):
     * its words (at most [MAX_ANSWER] characters) and up to [MAX_ACTIONS] actions. Anything that isn't that JSON is
     * taken as plain words with no actions, so a chatty answer still reads and can never act.
     */
    fun parseModelAnswer(text: String): Pair<String, List<Action>> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val o = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() else null
        val words = o?.optStr("answer")
        if (o == null || words == null) return text.trim().take(MAX_ANSWER) to emptyList()
        return words.trim().take(MAX_ANSWER) to actionsOf(o)
    }

    /**
     * The companion, slice 5: the `"note"` in a model's answer (one short line of what the conversation was about), or
     * null when there is none or it isn't a plain string within [MAX_NOTE].
     */
    fun modelNote(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val o = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        return o.optStr("note")?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NOTE }
    }

    /** An earlier answer as the model wrote it, for the conversation's turns: `{"answer":"…","actions":[]}`. */
    fun encodeModelAnswer(answer: String): String = buildJsonObject {
        put("answer", answer)
        putJsonArray("actions") {}
    }.toString()

    /** One feature's calls on the household's day ("ask.talk" · 6 calls · 4,200 micro-dollars). */
    data class DayUse(val feature: String, val calls: Long, val microUsd: Long)

    /**
     * The server's AI status (`POST /v1/ai/status`): on/off/failing, why, and the month's spend when it meters;
     * [today] is the day's calls per feature (empty from an older server or when nothing was asked).
     */
    data class Status(
        val state: String, val reason: String?, val spentCents: Long?, val budgetCents: Long?, val level: String?,
        val today: List<DayUse> = emptyList(),
        /** The month's calls and cost per feature (`budget.features`); empty from an older server. */
        val month: List<MonthUse> = emptyList(),
        /** Anthropic refused the key itself (revoked, expired or not allowed), not just a failed check. */
        val keyRefused: Boolean = false,
    )

    /** One feature's calls and cost (whole cents) this month ("mail.sort" · 340 calls · 85¢). */
    data class MonthUse(val feature: String, val calls: Long, val cents: Long)

    /**
     * Reads `{"state", "reason"?, "budget": {"spentCents", "budgetCents", "level", "today": [{"feature", "calls",
     * "microUsd"}]?, "features": [{"feature", "calls", "cents"}]?}?, "keyRefused"?}` (the status carries no wire version: it is a small read-only answer, and unknown fields are
     * ignored; a malformed day entry is skipped, never the whole status).
     */
    fun decodeStatus(body: String): Status = wrap("ai status") {
        val o = json.parseToJsonElement(body).jsonObject
        val b = o["budget"] as? JsonObject
        fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val today = (b?.get("today") as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val feature = d.optStr("feature")?.takeIf { it.isNotBlank() && it.length <= 40 } ?: return@mapNotNull null
            val calls = d.long("calls")?.takeIf { it >= 0 } ?: return@mapNotNull null
            DayUse(feature, calls, d.long("microUsd")?.coerceAtLeast(0) ?: 0)
        }
        val month = (b?.get("features") as? JsonArray).orEmpty().mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val feature = d.optStr("feature")?.takeIf { it.isNotBlank() && it.length <= 40 } ?: return@mapNotNull null
            val calls = d.long("calls")?.takeIf { it >= 0 } ?: return@mapNotNull null
            MonthUse(feature, calls, d.long("cents")?.coerceAtLeast(0) ?: 0)
        }
        val refused = (o["keyRefused"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        Status(o.str("state"), o.optStr("reason"), b?.long("spentCents"), b?.long("budgetCents"), b?.optStr("level"), today, month, refused)
    }

    private fun actionsOf(o: JsonObject): List<Action> =
        (o["actions"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            val kind = a.optStr("kind") ?: return@mapNotNull null
            Action(kind, a.optStr("ref"), a.optStr("title"), a.optStr("date"), a.optStr("time"), a.optInt("hours"), a.optInt("minutes"))
        }.take(MAX_ACTIONS)

    private fun encodeAction(a: Action): JsonObject = buildJsonObject {
        put("kind", a.kind)
        a.ref?.let { put("ref", it) }
        a.title?.let { put("title", it) }
        a.date?.let { put("date", it) }
        a.time?.let { put("time", it) }
        a.hours?.let { put("hours", it) }
        a.minutes?.let { put("minutes", it) }
    }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    /** A string field, or null when absent, not a string, or longer than [MAX_FIELD] ([MAX_ANSWER] for the answer). */
    private fun JsonObject.optStr(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= (if (k == "answer") MAX_ANSWER * 4 else MAX_FIELD) }

    private fun JsonObject.optInt(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

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
