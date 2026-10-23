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

/** MEKA's blob bucket (`MEKA_BLOB_BUCKET`), only `face/<one of the seven names>`. */
class S3FaceFileStore(private val bucket: String) : FaceFileStore {
    private val s3 = S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build()

    override fun list(): List<FaceCodec.Entry> =
        s3.listObjectsV2 { it.bucket(bucket).prefix(FaceFiles.PREFIX).maxKeys(50) }.contents().mapNotNull { o ->
            val name = o.key().removePrefix(FaceFiles.PREFIX)
            if (!FaceCodec.allowed(name) || o.size() <= 0 || o.size() > FaceCodec.MAX_BYTES) null
            else FaceCodec.Entry(name, o.eTag().trim('"').ifEmpty { o.lastModified().toString() }, o.size())
        }.sortedBy { it.name }

    override fun get(name: String): ByteArray? {
        require(FaceCodec.allowed(name))
        return try {
            s3.getObjectAsBytes { it.bucket(bucket).key(FaceFiles.PREFIX + name) }.asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }
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
