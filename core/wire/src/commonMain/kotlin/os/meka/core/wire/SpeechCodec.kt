package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * MEKA's voice on the wire (build plan V1, "Weather and a voice Meka likes", item 3): a keyed device asks the server to
 * say a piece of MEKA's own reply (`POST /v1/speech/speak`) and to list the voices it can use (`POST /v1/speech/voices`).
 * The server turns the text into speech with Amazon Polly in MEKA's own AWS account; nothing is stored or logged.
 *
 * Only MEKA's words are ever sent (an answer, a brief line, a sample), never what Meka said. A request outside the
 * limits is refused, not trimmed: at most [MAX_TEXT] characters (a device sends a reply sentence by sentence), plain
 * text with no control characters, a voice name of letters only.
 */
object SpeechCodec {
    const val MAX_TEXT = 600
    const val MAX_REASON = 200
    /** The base64 of the longest clip the server returns (600 characters ≈ 45 s of 48 kbit/s MP3, with room). */
    const val MAX_AUDIO_B64 = 1_500_000
    /** The longest clip as raw MP3 (what [MAX_AUDIO_B64] decodes to, rounded down). */
    const val MAX_AUDIO_BYTES = 1_100_000

    /**
     * Binary audio (voice slice 2, cutting latency): a request with [Request.binary] gets a spoken clip back as the raw
     * MP3 body ([AUDIO_TYPE]) with the voice and engine in [HEADER_VOICE] / [HEADER_ENGINE], a quarter smaller than
     * base64 in JSON and nothing to parse. Every other answer (off · over · failed) stays JSON, and an older server that
     * ignores the flag answers JSON too, so the device reads whichever comes back.
     */
    const val AUDIO_TYPE = "audio/mpeg"
    const val HEADER_VOICE = "X-Meka-Voice"
    const val HEADER_ENGINE = "X-Meka-Engine"
    private const val BINARY = "mp3"

    /**
     * Obi's face, slice 2 (lips that move with the words): a request with [Request.visemes] also gets the clip's mouth
     * sounds, Polly's viseme speech marks, beside the audio: in [HEADER_VISEMES] with raw MP3, in `"visemes"` in JSON.
     * Compact text: `time:viseme` pairs (milliseconds from the clip's start, Polly's own viseme name) joined by spaces,
     * e.g. `"0:sil 62:p 125:a"`. A voice that gives no marks (Google's) sends none and the face follows the loudness.
     * Only ever sent when asked, so a device without the face never pays for them.
     */
    const val HEADER_VISEMES = "X-Meka-Visemes"
    /** At most this many marks (a 600-character piece is well under it); more is cut at the limit. */
    const val MAX_MARKS = 900
    /** The marks' text at most this long (fits a response header comfortably). */
    const val MAX_MARKS_CHARS = 7_200
    /** Polly's English viseme names; anything else is not a mark. */
    val POLLY_VISEMES = setOf("p", "t", "S", "T", "f", "k", "i", "r", "s", "u", "@", "a", "e", "E", "o", "O", "sil")

    /** One mouth sound in a clip: from [atMs] after its start, Polly's viseme [viseme]. */
    data class Mark(val atMs: Long, val viseme: String)

    /**
     * [marks] as the compact text, in time order, cut to [MAX_MARKS] and [MAX_MARKS_CHARS]; marks with a negative time
     * or a viseme Polly doesn't use are left out. Null when nothing is left.
     */
    fun encodeMarks(marks: List<Mark>): String? {
        val sb = StringBuilder()
        var n = 0
        for (m in marks.filter { it.atMs >= 0 && it.viseme in POLLY_VISEMES }.sortedBy { it.atMs }) {
            val part = "${m.atMs}:${m.viseme}"
            if (n >= MAX_MARKS || sb.length + part.length + 1 > MAX_MARKS_CHARS) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(part)
            n++
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /**
     * The compact text back to marks. Never trusted: anything malformed, too long, out of order or naming an unknown
     * viseme gives no marks at all (the face then follows the loudness), never half a list.
     */
    fun decodeMarks(text: String?): List<Mark> {
        if (text.isNullOrBlank() || text.length > MAX_MARKS_CHARS) return emptyList()
        val parts = text.trim().split(' ')
        if (parts.size > MAX_MARKS) return emptyList()
        val out = ArrayList<Mark>(parts.size)
        var last = -1L
        for (p in parts) {
            val colon = p.indexOf(':')
            if (colon <= 0 || colon > 7) return emptyList()
            val t = p.substring(0, colon).toLongOrNull() ?: return emptyList()
            val v = p.substring(colon + 1)
            if (t < 0 || t < last || v !in POLLY_VISEMES) return emptyList()
            last = t
            out += Mark(t, v)
        }
        return out
    }

    /**
     * A voice's id: a Polly name ("Amy"), or a Google Chirp 3 HD voice's short name ("Aoede" for the British one,
     * "Aoede-US" for the American; natural voice, slice 1). The server keeps the ids unique across providers.
     */
    private val VOICE = Regex("[A-Za-z]{2,20}(-US)?")
    private val ENGINE = Regex("[a-z0-9]{1,20}")

    /** Who makes a voice's speech ([Voice.provider]). */
    const val POLLY = "polly"
    const val GOOGLE = "google"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [voice] is a Polly voice name ("Amy"); null or one the server doesn't offer means the default voice. [binary]: send
     * a spoken clip back as raw MP3 rather than base64 JSON (`"as": "mp3"` on the wire). [visemes]: send the clip's mouth
     * sounds too ([HEADER_VISEMES]; `"visemes": true` on the wire, left out when false).
     */
    data class Request(val text: String, val voice: String? = null, val binary: Boolean = false, val visemes: Boolean = false)

    /**
     * [state]: spoken (with [audio], base64 [format] "mp3", and the [voice] and [engine] that said it) · off (no voice on
     * this server) · over (the month's characters are used up; the device's own voice speaks instead) · failed
     * ([reason] says why).
     */
    data class Response(
        val state: String,
        val audio: String? = null,
        val format: String? = null,
        val voice: String? = null,
        val engine: String? = null,
        val reason: String? = null,
        /** Obi's face, slice 2: the clip's mouth sounds as [encodeMarks] text, when asked for and the voice gave them. */
        val visemes: String? = null,
    ) {
        companion object {
            const val SPOKEN = "spoken"
            const val OFF = "off"
            const val OVER = "over"
            const val FAILED = "failed"
        }
    }

    /**
     * One voice MEKA can speak with: its [id] ("Amy", "Aoede", "Aoede-US"), [gender] ("Female"), the best [engine] it
     * has here ("generative", "neural", "chirp3hd") and its [provider] ([POLLY] or [GOOGLE]; absent on the wire = Polly).
     */
    data class Voice(val id: String, val gender: String, val engine: String, val provider: String = POLLY)

    /**
     * Google's voices on this server (natural voice, slice 1): [state] on · nokey (no key with Text-to-Speech allowed
     * yet) · failed (Google didn't answer); this month's characters said by Google ([usedChars]) against the free
     * [freeChars] (1M), past [fallbackAtChars] Polly speaks instead; [failedAtMs] the last time a Google clip failed and
     * Polly said it instead (null: not since the server started).
     */
    data class Google(
        val state: String,
        val usedChars: Long = 0,
        val freeChars: Long = 0,
        val fallbackAtChars: Long = 0,
        val failedAtMs: Long? = null,
    ) {
        companion object {
            const val ON = "on"
            const val NO_KEY = "nokey"
            const val FAILED = "failed"
        }
    }

    /**
     * The voices list: [state] on/off/failed, the voices (best first), the server's default, and this month's metered
     * characters against the cap ([month] "2026-10", UTC).
     */
    data class Voices(
        val state: String,
        val voices: List<Voice> = emptyList(),
        val defaultVoice: String? = null,
        val month: String? = null,
        val usedChars: Long = 0,
        val capChars: Long = 0,
        val reason: String? = null,
        /** Google's voices (natural voice, slice 1); null from an older server. [capChars] is Polly's cap. */
        val google: Google? = null,
    ) {
        companion object {
            const val ON = "on"
            const val OFF = "off"
            const val FAILED = "failed"
        }
    }

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("text", r.text)
        r.voice?.let { put("voice", it) }
        if (r.binary) put("as", BINARY)
        if (r.visemes) put("visemes", true)
    }.toString()

    fun decodeRequest(body: String): Request = wrap("speech request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val text = o.str("text").trim()
        require(text.isNotEmpty() && text.length <= MAX_TEXT) { "text" }
        require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) { "text" }
        val voice = (o["voice"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(voice == null || VOICE.matches(voice)) { "voice" }
        val binary = (o["as"] as? JsonPrimitive)?.takeIf { it.isString }?.content == BINARY
        val visemes = (o["visemes"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
        Request(text, voice, binary, visemes)
    }

    /**
     * A spoken clip that came back as raw MP3: [audioB64] is its base64 (as the JSON answer carries it, so the apps see
     * no difference), [voice] and [engine] from the headers. Empty or oversized audio, or a voice header that isn't a
     * plain name, is a failed answer, never trusted.
     */
    fun binaryResponse(audioB64: String, voice: String?, engine: String?, visemes: String? = null): Response {
        if (audioB64.isEmpty() || audioB64.length > MAX_AUDIO_B64) return Response(Response.FAILED, reason = "Navilife's voice sent an unusable clip")
        val v = voice?.takeIf { VOICE.matches(it) }
        val e = engine?.takeIf { ENGINE.matches(it) }
        return Response(Response.SPOKEN, audioB64, BINARY, v, e, visemes = checkedMarks(visemes))
    }

    /** [text] when it reads as marks ([decodeMarks]), else null: a bad header or field is simply not there. */
    fun checkedMarks(text: String?): String? = text?.takeIf { decodeMarks(it).isNotEmpty() }

    /** The voices request carries nothing but the version (it is signed like every device request). */
    fun encodeVoicesRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeVoicesRequest(body: String) = wrap("voices request") { checkVersion(json.parseToJsonElement(body).jsonObject) }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        r.audio?.let { put("audio", it) }
        r.format?.let { put("format", it) }
        r.voice?.let { put("voice", it) }
        r.engine?.let { put("engine", it) }
        r.reason?.let { put("reason", it) }
        r.visemes?.let { put("visemes", it) }
    }.toString()

    fun decodeResponse(body: String): Response = wrap("speech response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        Response(
            state = o.str("state"),
            audio = o.optStr("audio", MAX_AUDIO_B64),
            format = o.optStr("format"),
            voice = o.optStr("voice"),
            engine = o.optStr("engine"),
            reason = o.optStr("reason", MAX_REASON),
            visemes = checkedMarks(o.optStr("visemes", MAX_MARKS_CHARS)),
        )
    }

    fun encodeVoices(v: Voices): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", v.state)
        putJsonArray("voices") { v.voices.forEach { x -> addJsonObject { put("id", x.id); put("gender", x.gender); put("engine", x.engine); put("provider", x.provider) } } }
        v.defaultVoice?.let { put("default", it) }
        v.month?.let { put("month", it) }
        put("usedChars", v.usedChars)
        put("capChars", v.capChars)
        v.reason?.let { put("reason", it) }
        v.google?.let { g ->
            put(
                "google",
                buildJsonObject {
                    put("state", g.state)
                    put("usedChars", g.usedChars)
                    put("freeChars", g.freeChars)
                    put("fallbackAtChars", g.fallbackAtChars)
                    g.failedAtMs?.let { put("failedAtMs", it) }
                },
            )
        }
    }.toString()

    fun decodeVoices(body: String): Voices = wrap("voices") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val voices = (o["voices"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val id = x.optStr("id")?.takeIf { VOICE.matches(it) } ?: return@mapNotNull null
            val provider = when (x.optStr("provider")) {
                GOOGLE -> GOOGLE
                null, POLLY -> POLLY
                else -> return@mapNotNull null // a provider this app doesn't know: not offered here
            }
            Voice(id, x.optStr("gender") ?: "", x.optStr("engine") ?: "", provider)
        }
        val google = (o["google"] as? JsonObject)?.let { g ->
            Google(
                state = g.optStr("state") ?: return@let null,
                usedChars = (g.optLong("usedChars") ?: 0).coerceAtLeast(0),
                freeChars = (g.optLong("freeChars") ?: 0).coerceAtLeast(0),
                fallbackAtChars = (g.optLong("fallbackAtChars") ?: 0).coerceAtLeast(0),
                failedAtMs = g.optLong("failedAtMs")?.takeIf { it > 0 },
            )
        }
        Voices(
            state = o.str("state"),
            voices = voices,
            defaultVoice = o.optStr("default"),
            month = o.optStr("month"),
            usedChars = o.optLong("usedChars") ?: 0,
            capChars = o.optLong("capChars") ?: 0,
            reason = o.optStr("reason", MAX_REASON),
            google = google,
        )
    }

    private fun JsonObject.str(k: String): String =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(k)

    private fun JsonObject.optStr(k: String, max: Int = 40): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= max }

    private fun JsonObject.optLong(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

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
