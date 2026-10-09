package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A caller's recording (call assistant polish 8c): `POST /v1/voice-message/audio`, signed by a keyed device, names one
 * held voice message by its id; the server answers with the recording as raw MP3 ([AUDIO_TYPE]), or 404 when it keeps
 * none (never kept, Done tapped, over 30 days old, or another household's). Nothing but the id is sent.
 */
object VoiceMessageCodec {
    const val PATH = "/v1/voice-message/audio"
    const val AUDIO_TYPE = "audio/mpeg"
    /** The largest recording a device accepts (matches the server's own limit). */
    const val MAX_AUDIO_BYTES = 5 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^h[0-9a-f]{16}$")

    fun encodeRequest(id: String): String {
        require(idPattern.matches(id)) { "not a held message id" }
        return buildJsonObject { put("w", WireCodec.VERSION); put("id", id) }.toString()
    }

    /** The held message's id; anything that couldn't be one is refused. */
    fun decodeRequest(body: String): String = try {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("id")
        if (!idPattern.matches(id)) throw IllegalArgumentException("id")
        id
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad voice message request: ${e.message}", e)
    }

    private fun checkVersion(o: JsonObject) {
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
    }
}
