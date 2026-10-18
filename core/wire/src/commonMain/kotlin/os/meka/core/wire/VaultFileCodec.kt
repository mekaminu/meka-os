package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The Vault's encrypted copies (Vault slice 2b). A phone or Mac seals a document's photo or PDF with the household's
 * Vault key before it leaves; these routes only move the sealed bytes (base64 in JSON, so the request signature covers
 * them), keyed by the copy's random id within the household:
 * - `POST` [PUT_PATH] {id, sealed}: kept (a second put of the same id replaces it), answered {ok: true};
 * - `POST` [GET_PATH] {id}: {sealed}, or 404 when there is none;
 * - `POST` [DELETE_PATH] {id}: removed for good (none there is fine).
 * The server never sees the key, the file, its name or which document it belongs to.
 */
object VaultFileCodec {
    const val PUT_PATH = "/v1/vault/file/put"
    const val GET_PATH = "/v1/vault/file/get"
    const val DELETE_PATH = "/v1/vault/file/delete"
    /** Matches [os.meka.core.domain.VaultFileRules.MAX_SEALED_BYTES]; base64 of it stays under the server's 2 MB body. */
    const val MAX_SEALED_BYTES = 1_450_000

    private val json = Json { ignoreUnknownKeys = true }

    data class Put(val id: String, val sealed: String)

    fun validId(id: String): Boolean = id.length in 16..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun encodePut(p: Put): String = buildJsonObject { put("w", WireCodec.VERSION); put("id", p.id); put("sealed", p.sealed) }.toString()

    fun decodePut(body: String): Put = wrap("vault file") {
        val o = parse(body)
        val sealed = o.str("sealed") ?: throw IllegalArgumentException("sealed")
        // base64 length → bytes, without decoding twice.
        if (sealed.isEmpty() || sealed.length / 4 * 3 > MAX_SEALED_BYTES + 3) throw IllegalArgumentException("sealed size")
        Put(id(o), sealed)
    }

    fun encodeRef(id: String): String = buildJsonObject { put("w", WireCodec.VERSION); put("id", id) }.toString()

    fun decodeRef(body: String): String = wrap("vault file ref") { id(parse(body)) }

    fun encodeSealed(sealed: String): String = buildJsonObject { put("w", WireCodec.VERSION); put("sealed", sealed) }.toString()

    fun decodeSealed(body: String): String = wrap("vault file answer") {
        parse(body).str("sealed")?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("sealed")
    }

    fun encodeOk(): String = buildJsonObject { put("w", WireCodec.VERSION); put("ok", true) }.toString()

    private fun parse(body: String): JsonObject {
        val o = json.parseToJsonElement(body).jsonObject
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
        return o
    }

    private fun id(o: JsonObject): String = o.str("id")?.takeIf(::validId) ?: throw IllegalArgumentException("id")

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
