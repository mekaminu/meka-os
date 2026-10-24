package os.meka.backend

import os.meka.core.wire.FaceCodec
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.util.concurrent.ConcurrentHashMap

/**
 * Obi's face (slice 1): the seven files of Meka's talking face live only in MEKA's private blob bucket under `face/`
 * (he uploads them himself; they are never in the public repository). Keyed phones and Macs read the list and the
 * files; nothing else in the bucket can be reached through these routes, because only [FaceCodec.FILES] are ever read.
 */
interface FaceFileStore {
    /** The allowed files that are there, with a tag that changes when a file is replaced. */
    fun list(): List<FaceCodec.Entry>
    fun get(name: String): ByteArray?
}

class InMemoryFaceFileStore : FaceFileStore {
    val objects = ConcurrentHashMap<String, ByteArray>()
    var reads = 0
    override fun list(): List<FaceCodec.Entry> =
        objects.entries.filter { FaceCodec.allowed(it.key) }.map { FaceCodec.Entry(it.key, "t" + it.value.contentHashCode().toUInt().toString(16), it.value.size.toLong()) }
            .sortedBy { it.name }
    override fun get(name: String): ByteArray? { reads++; return objects[name]?.copyOf() }
}

/**
 * MEKA's blob bucket (`MEKA_BLOB_BUCKET`), only the seven names under `face/`. Meka 2026-10-24 13:12: the face didn't
 * show after his upload; a zip opened into a folder puts the files one level down (`face/obi-face/mouth-m.png`), so
 * [FaceLayout.resolve] finds each name anywhere under `face/` (the top level first) and serves it from where it is.
 */
class S3FaceFileStore(private val bucket: String) : FaceFileStore {
    private val s3 = S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build()
    @Volatile private var keys: Map<String, String> = emptyMap()

    override fun list(): List<FaceCodec.Entry> {
        val objects = s3.listObjectsV2Paginator { it.bucket(bucket).prefix(FaceFiles.PREFIX).maxKeys(200) }.contents()
            .take(FaceLayout.MAX_OBJECTS)
            .map { o -> FaceLayout.Object(o.key(), o.size(), o.eTag().trim('"').ifEmpty { o.lastModified().toString() }) }
        val found = FaceLayout.resolve(objects)
        keys = found.mapValues { it.value.key }
        return found.map { (name, o) -> FaceCodec.Entry(name, o.tag, o.size) }.sortedBy { it.name }
    }

    override fun get(name: String): ByteArray? {
        require(FaceCodec.allowed(name))
        val key = keys[name] ?: (FaceFiles.PREFIX + name)
        return try {
            s3.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }
    }
}

/**
 * Where the face's files are in the bucket, and the deploy's line about them (Meka 2026-10-24 13:12: "the deploy proves
 * the face is there"). Names and sizes only; nothing else in the bucket is read.
 */
object FaceLayout {
    data class Object(val key: String, val size: Long, val tag: String)

    const val MAX_OBJECTS = 1000

    /** Finder's and zip's leftovers, never the face. */
    private fun junk(key: String): Boolean =
        key.split('/').any { it.startsWith("__MACOSX") } || key.substringAfterLast('/').startsWith("._") || key.endsWith("/")

    /** The allowed name an object stands for, matched without case ("Mouth-M.PNG" is mouth-m.png), or null. */
    fun nameOf(key: String): String? {
        if (!key.startsWith(FaceFiles.PREFIX) || junk(key)) return null
        val base = key.substringAfterLast('/').lowercase()
        return base.takeIf { FaceCodec.allowed(it) }
    }

    private fun usable(o: Object) = o.size > 0 && o.size <= FaceCodec.MAX_BYTES

    /** Each allowed name to the object served for it: the top level `face/<name>` first, else the shallowest. */
    fun resolve(objects: List<Object>): Map<String, Object> =
        objects.filter { usable(it) }.mapNotNull { o -> nameOf(o.key)?.let { it to o } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (name, list) ->
                list.firstOrNull { it.key == FaceFiles.PREFIX + name }
                    ?: list.minWith(compareBy<Object>({ it.key.count { c -> c == '/' } }, { it.key }))
            }

    private fun mb(bytes: Long): String = ((bytes + 512 * 1024) / (1024 * 1024)).toString() + " MB"

    /** The deploy's annotation lines: one summary, then one per problem (misplaced, too big, empty). */
    fun report(objects: List<Object>): List<String> {
        val total = FaceCodec.FILES.size
        val found = resolve(objects)
        val missing = (listOf(FaceCodec.LOOP) + FaceCodec.MOUTHS).filter { it !in found }
        val lines = mutableListOf<String>()
        lines += if (missing.isEmpty()) "::notice title=Obi's face::$total of $total files"
        else "::warning title=Obi's face::${found.size} of $total files in face/ · missing ${missing.joinToString(", ")}"
        found.forEach { (name, o) ->
            if (o.key != FaceFiles.PREFIX + name) {
                val folder = o.key.substringBeforeLast('/').take(80)
                lines += "::notice title=Obi's face::$name is under $folder/ (served from there; face/$name is where it belongs)"
            }
        }
        objects.forEach { o ->
            val name = nameOf(o.key) ?: return@forEach
            when {
                o.size <= 0 -> lines += "::warning title=Obi's face::$name is empty (0 bytes) · upload it again"
                o.size > FaceCodec.MAX_BYTES -> lines += "::warning title=Obi's face::$name is ${mb(o.size)}, over the ${mb(FaceCodec.MAX_BYTES)} limit"
            }
        }
        return lines
    }

    /** `aws s3api list-objects-v2 … --query "Contents[].{Key:Key,Size:Size}" --output json` (or "null" when empty). */
    fun parseListing(text: String): List<Object> {
        val el = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text.ifBlank { "null" }) }.getOrNull() ?: return emptyList()
        val arr = el as? kotlinx.serialization.json.JsonArray
            ?: ((el as? kotlinx.serialization.json.JsonObject)?.get("Contents") as? kotlinx.serialization.json.JsonArray)
            ?: return emptyList()
        return arr.mapNotNull { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val key = (o["Key"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val size = (o["Size"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            Object(key, size, "")
        }
    }

    /** `backend face-report`: reads the listing on stdin and prints the lines. Always exits 0. */
    fun cli(): Int {
        val text = runCatching { generateSequence(::readLine).joinToString("\n") }.getOrDefault("")
        report(parseListing(text)).forEach(::println)
        return 0
    }
}

/** The routes' work. Watches and the release publisher are refused (a watch never draws the face). */
class FaceFiles(private val store: FaceFileStore, private val now: () -> Long = System::currentTimeMillis) {
    private fun allowed(who: DeviceIdentity): Boolean = who.deviceId != ReleasePublisher.ID && !isLinkedDeviceId(who.deviceId)

    /** The listing is asked for at each app open; kept for a few minutes so a burst of opens lists the bucket once. */
    @Volatile private var cached: Pair<Long, List<FaceCodec.Entry>>? = null

    private fun entries(): List<FaceCodec.Entry> {
        val c = cached
        if (c != null && now() - c.first < LIST_CACHE_MS) return c.second
        return store.list().also { cached = now() to it }
    }

    fun manifest(who: DeviceIdentity): LinkReply {
        if (!allowed(who)) return LinkReply(403, "forbidden")
        return LinkReply(200, FaceCodec.encodeManifest(entries()))
    }

    /** The file's bytes, or null with the refusal. */
    fun file(who: DeviceIdentity, name: String): Pair<ByteArray?, LinkReply?> {
        if (!allowed(who)) return null to LinkReply(403, "forbidden")
        if (!FaceCodec.allowed(name)) return null to LinkReply(400, "bad request")
        val bytes = store.get(name)?.takeIf { it.isNotEmpty() && it.size <= FaceCodec.MAX_BYTES }
            ?: return null to LinkReply(404, "no such file")
        return bytes to null
    }

    companion object {
        const val PREFIX = "face/"
        const val LIST_CACHE_MS = 5 * 60_000L
        /** A device may keep a file a day before it asks again (it re-checks the manifest weekly anyway). */
        const val CACHE_CONTROL = "private, max-age=86400"

        fun fromEnv(): FaceFiles? =
            System.getenv("MEKA_BLOB_BUCKET")?.takeIf { it.isNotBlank() }?.let { FaceFiles(S3FaceFileStore(it)) }
    }
}
