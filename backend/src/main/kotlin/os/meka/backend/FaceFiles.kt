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
    /** The allowed files that are there, with a tag that changes when a file is replaced. May throw (storage refused). */
    fun list(): List<FaceCodec.Entry>
    fun get(name: String): ByteArray?
    /**
     * Meka 2026-10-24 19:26: the store's word on its last [list] when it didn't find all seven ("nothing under face/ ·
     * top-level folders: voice/, vault/"), or where it found them when not under `face/`; null when there's nothing to say.
     */
    fun note(): String? = null
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
 * MEKA's blob bucket (`MEKA_BLOB_BUCKET`), only the seven names. Meka 2026-10-24 13:12: the face didn't show after his
 * upload; a zip opened into a folder puts the files one level down (`face/obi-face/mouth-m.png`), so [FaceLayout.resolve]
 * finds each name anywhere under `face/` (the top level first) and serves it from where it is. Meka 2026-10-24 19:26:
 * still "0 of 7" with the files uploaded: when `face/` doesn't hold all seven, the bucket's top level is looked at too
 * (folder names only) and any folder named like the face ("Face/", "obi-face/", "face /") is listed as well, so a
 * folder named a little differently in the console still works; [note] says what was seen.
 */
class S3FaceFileStore(private val bucket: String) : FaceFileStore {
    private val s3 = S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build()
    @Volatile private var keys: Map<String, String> = emptyMap()
    @Volatile private var lastNote: String? = null

    private fun listUnder(prefix: String, limit: Int): List<FaceLayout.Object> =
        s3.listObjectsV2Paginator { it.bucket(bucket).prefix(prefix).maxKeys(200) }.contents()
            .take(limit)
            .map { o -> FaceLayout.Object(o.key(), o.size(), o.eTag().trim('"').ifEmpty { o.lastModified().toString() }) }

    override fun list(): List<FaceCodec.Entry> {
        val objects = try {
            val under = listUnder(FaceFiles.PREFIX, FaceLayout.MAX_OBJECTS)
            if (FaceLayout.resolve(under).size >= FaceCodec.FILES.size) {
                under to emptyList<String>()
            } else {
                // The top level: folder names and loose files only (one page), then any face-like folder's contents.
                val top = s3.listObjectsV2 { it.bucket(bucket).delimiter("/").maxKeys(200) }
                val folders = top.commonPrefixes().map { it.prefix() }
                val loose = top.contents().map { o -> FaceLayout.Object(o.key(), o.size(), o.eTag().trim('"').ifEmpty { o.lastModified().toString() }) }
                val others = folders.filter { it != FaceFiles.PREFIX && FaceLayout.faceFolder(it) }.take(FaceLayout.MAX_FOLDERS)
                    .flatMap { listUnder(it, FaceLayout.MAX_OBJECTS) }
                (under + others + loose) to folders
            }
        } catch (e: Exception) {
            lastNote = FaceLayout.errorNote(e)
            FaceLog.once("face listing failed: ${lastNote}")
            throw e
        }
        val found = FaceLayout.resolve(objects.first)
        keys = found.mapValues { it.value.key }
        lastNote = FaceLayout.note(found, objects.second)
        FaceLog.once("face listing: ${found.size} of ${FaceCodec.FILES.size}" + (lastNote?.let { " · $it" } ?: ""))
        return found.map { (name, o) -> FaceCodec.Entry(name, o.tag, o.size) }.sortedBy { it.name }
    }

    override fun note(): String? = lastNote

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

/** The server's log about the face's storage: names and counts only, at most once an hour for the same line. */
internal object FaceLog {
    private const val EVERY_MS = 60 * 60_000L
    @Volatile private var last: Pair<String, Long>? = null
    fun once(line: String, now: Long = System.currentTimeMillis()) {
        val l = last
        if (l != null && l.first == line && now - l.second < EVERY_MS) return
        last = line to now
        System.err.println("[face] $line")
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

    /** The most other face-like top-level folders listed besides `face/`. */
    const val MAX_FOLDERS = 5

    /**
     * A top-level folder that stands for the face (Meka 2026-10-24 19:26): `face/` itself, or one named like it in the
     * console: "Face/", "face /", "obi-face/", "Obi_Face/".
     */
    fun faceFolder(prefix: String): Boolean {
        val n = prefix.trimEnd('/').trim().lowercase()
        return n == "face" || (n.contains("face") && n.length <= 40 && !n.startsWith("__macosx"))
    }

    /**
     * The allowed name an object stands for, matched without case ("Mouth-M.PNG" is mouth-m.png), or null: under `face/`
     * or another face-like top-level folder ([faceFolder]), or loose at the bucket's top level.
     */
    fun nameOf(key: String): String? {
        if (junk(key)) return null
        val top = key.substringBefore('/', missingDelimiterValue = "")
        val placed = key.startsWith(FaceFiles.PREFIX) || !key.contains('/') || faceFolder(top + "/")
        if (!placed) return null
        val base = key.substringAfterLast('/').lowercase()
        return base.takeIf { FaceCodec.allowed(it) }
    }

    /** How far from `face/<name>` an object is: 0 there, then deeper under face/, then other folders, then loose. */
    private fun rank(key: String): Int = when {
        key.startsWith(FaceFiles.PREFIX) -> 0
        key.contains('/') -> 1
        else -> 2
    }

    /** The store's note: null when all seven are under `face/`; else where they were found, or what the top level holds. */
    fun note(found: Map<String, Object>, folders: List<String>): String? {
        val total = FaceCodec.FILES.size
        val elsewhere = found.values.map { it.key }.filter { !it.startsWith(FaceFiles.PREFIX) }
            .map { if (it.contains('/')) it.substringBefore('/') + "/" else "the top level" }.distinct()
        if (found.size >= total && elsewhere.isEmpty()) return null
        if (found.isNotEmpty() && elsewhere.isNotEmpty()) {
            return FaceCodec.cleanNote("${found.size} of $total found, under ${elsewhere.joinToString(", ") { it.take(30) }} (face/ is where they belong)")
        }
        if (found.isNotEmpty()) return null
        val shown = folders.take(8).joinToString(", ") { it.take(30) }
        return FaceCodec.cleanNote("nothing under face/ in storage" + if (shown.isEmpty()) " · the bucket's top level is empty" else " · top-level folders: $shown")
    }

    /** "couldn't list its storage: AccessDenied" (the AWS error code, or the exception's class). */
    fun errorNote(e: Throwable): String {
        val code = (e as? software.amazon.awssdk.awscore.exception.AwsServiceException)?.awsErrorDetails()?.errorCode()
        return "couldn't list its storage: " + (code ?: e.javaClass.simpleName).take(60)
    }

    private fun usable(o: Object) = o.size > 0 && o.size <= FaceCodec.MAX_BYTES

    /** Each allowed name to the object served for it: the top level `face/<name>` first, else the shallowest. */
    fun resolve(objects: List<Object>): Map<String, Object> =
        objects.filter { usable(it) }.mapNotNull { o -> nameOf(o.key)?.let { it to o } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (name, list) ->
                list.firstOrNull { it.key == FaceFiles.PREFIX + name }
                    ?: list.minWith(compareBy<Object>({ rank(it.key) }, { it.key.count { c -> c == '/' } }, { it.key }))
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
        if (found.isEmpty()) {
            // Meka 2026-10-24 19:26: say what the bucket does hold at its top level (folder names only).
            val folders = objects.mapNotNull { o -> o.key.substringBefore('/', "").takeIf { it.isNotEmpty() }?.let { "$it/" } }.distinct()
            note(found, folders)?.let { lines += "::warning title=Obi's face::$it" }
        }
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

    /** The last listing and its note; a failed listing isn't kept (the next ask tries again) but its note is. */
    private fun entries(): Pair<List<FaceCodec.Entry>, String?> {
        val c = cached
        if (c != null && now() - c.first < LIST_CACHE_MS) return c.second to store.note()
        return try {
            store.list().also { cached = now() to it } to store.note()
        } catch (e: Exception) {
            emptyList<FaceCodec.Entry>() to (store.note() ?: FaceLayout.errorNote(e))
        }
    }

    /**
     * The list, with the server's note when it isn't all seven (Meka 2026-10-24 19:26). A storage failure is a 200 with
     * no files and the note, so the device can say "couldn't list its storage: AccessDenied" instead of "waiting".
     */
    fun manifest(who: DeviceIdentity): LinkReply {
        if (!allowed(who)) return LinkReply(403, "forbidden")
        val (list, note) = entries()
        return LinkReply(200, FaceCodec.encodeManifest(list, note))
    }

    /**
     * The release publisher's line about the face (the deploy's probe, 2026-10-24): how many of the seven the deployed
     * server itself lists and its note. Names and counts only.
     */
    fun report(): String {
        val (list, note) = entries()
        val total = FaceCodec.FILES.size
        val missing = FaceCodec.FILES.filter { n -> list.none { it.name == n } }.sorted()
        return "the server lists ${list.size} of $total" +
            (if (missing.isNotEmpty() && missing.size < total) " · missing ${missing.joinToString(", ")}" else "") +
            (note?.let { " · $it" } ?: "")
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
