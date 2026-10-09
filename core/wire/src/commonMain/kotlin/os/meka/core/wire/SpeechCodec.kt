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

    private val VOICE = Regex("[A-Za-z]{2,20}")
    private val json = Json { ignoreUnknownKeys = true }

    /** [voice] is a Polly voice name ("Amy"); null or one the server doesn't offer means the default voice. */
    data class Request(val text: String, val voice: String? = null)

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
    ) {
        companion object {
            const val SPOKEN = "spoken"
            const val OFF = "off"
            const val OVER = "over"
            const val FAILED = "failed"
        }
    }

    /** One voice MEKA can speak with: Polly's [id] ("Amy"), [gender] ("Female"), and the best [engine] it has here. */
    data class Voice(val id: String, val gender: String, val engine: String)

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
    }.toString()

    fun decodeRequest(body: String): Request = wrap("speech request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val text = o.str("text").trim()
        require(text.isNotEmpty() && text.length <= MAX_TEXT) { "text" }
        require(text.none { it.isISOControl() && it != '\n' && it != '\t' }) { "text" }
        val voice = (o["voice"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(voice == null || VOICE.matches(voice)) { "voice" }
        Request(text, voice)
    }

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
        )
    }

    fun encodeVoices(v: Voices): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", v.state)
        putJsonArray("voices") { v.voices.forEach { x -> addJsonObject { put("id", x.id); put("gender", x.gender); put("engine", x.engine) } } }
        v.defaultVoice?.let { put("default", it) }
        v.month?.let { put("month", it) }
        put("usedChars", v.usedChars)
        put("capChars", v.capChars)
        v.reason?.let { put("reason", it) }
    }.toString()

    fun decodeVoices(body: String): Voices = wrap("voices") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val voices = (o["voices"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val id = x.optStr("id")?.takeIf { VOICE.matches(it) } ?: return@mapNotNull null
            Voice(id, x.optStr("gender") ?: "", x.optStr("engine") ?: "")
        }
        Voices(
            state = o.str("state"),
            voices = voices,
            defaultVoice = o.optStr("default"),
            month = o.optStr("month"),
            usedChars = o.optLong("usedChars") ?: 0,
            capChars = o.optLong("capChars") ?: 0,
            reason = o.optStr("reason", MAX_REASON),
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
