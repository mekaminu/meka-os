package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Talking to Ada like a conversation, slice 2: how long the server took over its answers (`POST` [PATH], a keyed
 * device or GitHub's release publisher). The request carries only the version; the answer lists the household's
 * newest timings (at most [TURNS_MAX]), each the ms since the question reached the server for each step, whether it
 * was an open turn and spoken, and the model's name. Times only: nothing of what was asked or answered.
 */
object ServerSpeedCodec {
    const val PATH = "/v1/talk/speed"
    const val TURNS_MAX = 200
    const val MODEL_MAX = 40

    data class Turn(
        val atMs: Long,
        val open: Boolean,
        val spoken: Boolean,
        val model: String?,
        val webMs: Long? = null,
        val firstTokenMs: Long? = null,
        val firstSentenceMs: Long? = null,
        val clipMs: Long? = null,
        val doneMs: Long? = null,
        val answered: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeRequest(body: String) = wrap("talk speed request") {
        if (body.isBlank()) return@wrap
        checkVersion(json.parseToJsonElement(body).jsonObject)
    }

    fun encodeResponse(turns: List<Turn>): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("turns", buildJsonArray { turns.takeLast(TURNS_MAX).forEach { add(obj(it)) } })
    }.toString()

    /** The timings, oldest first; one that can't be read is skipped. */
    fun decodeResponse(body: String): List<Turn> = wrap("talk speed answer") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        (o["turns"] as? JsonArray ?: throw IllegalArgumentException("turns")).mapNotNull { e ->
            runCatching { turn(e as JsonObject) }.getOrNull()
        }.takeLast(TURNS_MAX)
    }

    /** One timing as the server stores it (also the database row's text). */
    fun encodeTurn(t: Turn): String = obj(t).toString()

    fun decodeTurn(text: String): Turn? = runCatching { turn(json.parseToJsonElement(text).jsonObject) }.getOrNull()

    private fun obj(t: Turn): JsonObject = buildJsonObject {
        put("at", t.atMs)
        put("o", t.open)
        put("s", t.spoken)
        t.model?.take(MODEL_MAX)?.let { put("m", it) }
        t.webMs?.let { put("web", it) }
        t.firstTokenMs?.let { put("tok", it) }
        t.firstSentenceMs?.let { put("sen", it) }
        t.clipMs?.let { put("clip", it) }
        t.doneMs?.let { put("done", it) }
        put("ok", t.answered)
    }

    private fun turn(o: JsonObject): Turn {
        val at = o.long("at")?.takeIf { it > 0 } ?: throw IllegalArgumentException("at")
        fun ms(k: String): Long? = o.long(k)?.takeIf { it >= 0 }
        return Turn(
            at, o.bool("o") ?: false, o.bool("s") ?: false, o.str("m")?.take(MODEL_MAX)?.takeIf { it.isNotBlank() },
            ms("web"), ms("tok"), ms("sen"), ms("clip"), ms("done"), o.bool("ok") ?: true,
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

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
