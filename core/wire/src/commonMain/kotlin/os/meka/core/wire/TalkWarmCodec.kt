package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The orb speed pass, slice 5: "get ready, he's about to talk" (`POST /v1/talk/warm`). Sent when the orb is tapped,
 * Talk opens or listening starts; the server opens its connections to the AI and to Navilife's voice ahead of the
 * answer. The request carries nothing but the version (it is signed like every device request): nothing he said,
 * nothing about him. The answer says whether a warm-up started ([Response.WARMING]), one ran a moment ago
 * ([Response.RECENT]) or the server has nothing to warm ([Response.OFF]).
 */
object TalkWarmCodec {
    data class Response(val state: String) {
        companion object {
            const val WARMING = "warming"
            const val RECENT = "recent"
            const val OFF = "off"
            val STATES = setOf(WARMING, RECENT, OFF)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeRequest(body: String) = wrap("talk warm request") { checkVersion(json.parseToJsonElement(body).jsonObject) }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
    }.toString()

    /** An unknown state reads as [Response.OFF]: nothing is waited on either way. */
    fun decodeResponse(body: String): Response = wrap("talk warm response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val s = (o["state"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        Response(s?.takeIf { it in Response.STATES } ?: Response.OFF)
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
