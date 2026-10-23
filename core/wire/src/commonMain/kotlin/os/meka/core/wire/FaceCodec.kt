package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Obi's face (slice 1): the seven files of Meka's talking face — the film loop and six mouth shapes — live only in
 * MEKA's private bucket (`face/`), never in this public repository. A keyed phone or Mac asks:
 * - `POST` [MANIFEST_PATH] {}: {files: [{name, tag, size}]} for the allowed files that are actually there (none yet:
 *   an empty list, and the apps keep the orb with no error shown);
 * - `POST` [FILE_PATH] {file}: the file's raw bytes ([contentType]), or 404 when it isn't there. Only the seven names in
 *   [FILES] are ever read; anything else is refused before the bucket is touched.
 * The device keeps them in its own storage and asks for the manifest again about weekly; a changed tag is fetched again.
 */
object FaceCodec {
    const val MANIFEST_PATH = "/v1/face/manifest"
    const val FILE_PATH = "/v1/face/file"

    const val LOOP = "face-loop.mp4"
    /** The six mouth shapes, in [os.meka.core.domain.MouthShape] order. */
    val MOUTHS = listOf("mouth-rest.png", "mouth-m.png", "mouth-f.png", "mouth-ee.png", "mouth-oh.png", "mouth-ah.png")
    /** The only names the server will read. */
    val FILES: Set<String> = (listOf(LOOP) + MOUTHS).toSet()

    /** The largest file a device will accept (the loop is a few MB; the patches are small). */
    const val MAX_BYTES = 40L * 1024 * 1024

    fun allowed(name: String): Boolean = name in FILES

    fun contentType(name: String): String = if (name.endsWith(".mp4")) "video/mp4" else "image/png"

    data class Entry(val name: String, val tag: String, val size: Long)

    /** True when every one of the seven files is there: the face shows only then. */
    fun complete(entries: List<Entry>): Boolean = entries.map { it.name }.toSet() == FILES

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeManifestRequest(): String = buildJsonObject { put("w", WireCodec.VERSION) }.toString()

    fun decodeManifestRequest(body: String) = wrap("face manifest request") { parse(body); Unit }

    fun encodeManifest(entries: List<Entry>): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("files", buildJsonArray {
            entries.forEach { e -> add(buildJsonObject { put("name", e.name); put("tag", e.tag); put("size", e.size) }) }
        })
    }.toString()

    /** Unknown names, empty tags and impossible sizes are dropped, so a device only ever asks for an allowed file. */
    fun decodeManifest(body: String): List<Entry> = wrap("face manifest") {
        val files = parse(body)["files"] as? JsonArray ?: throw IllegalArgumentException("files")
        files.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val tag = (o["tag"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return@mapNotNull null
            val size = (o["size"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            if (!allowed(name) || size <= 0 || size > MAX_BYTES) null else Entry(name, tag, size)
        }.distinctBy { it.name }
    }

    fun encodeFileRequest(name: String): String {
        require(allowed(name))
        return buildJsonObject { put("w", WireCodec.VERSION); put("file", name) }.toString()
    }

    fun decodeFileRequest(body: String): String = wrap("face file request") {
        val name = (parse(body)["file"] as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("file")
        if (!allowed(name)) throw IllegalArgumentException("file not allowed")
        name
    }

    private fun parse(body: String): JsonObject {
        val o = json.parseToJsonElement(body).jsonObject
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
        return o
    }

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
