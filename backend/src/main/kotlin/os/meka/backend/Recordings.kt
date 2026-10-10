package os.meka.backend

import os.meka.core.domain.VoiceRecordingRules
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.util.concurrent.ConcurrentHashMap

/**
 * Where MEKA's server keeps callers' recordings (call assistant polish 8c). Keys come only from
 * [VoiceRecordingRules.key] (`voice/<household>/<held id>.mp3`). The bucket is MEKA's own: private, SSE-KMS with
 * MEKA's key by default, TLS only; a lifecycle rule on `voice/` removes anything left after 30 days.
 */
interface RecordingStore {
    fun put(key: String, bytes: ByteArray)
    /** The recording's bytes, or null when there is none. */
    fun get(key: String): ByteArray?
    /** Deletes the recording for good (every version of it). Deleting one that isn't there is fine. */
    fun delete(key: String)
    /** What is stored under [prefix] (a household's `voice/<household>/`) and when each was stored, for the sweep. */
    fun list(prefix: String): List<StoredRecording>
}

/** One stored object: its key and when it was stored (epoch ms). */
data class StoredRecording(val key: String, val storedAtMs: Long)

/** Tests, and a server without a bucket configured. */
class InMemoryRecordingStore(private val now: () -> Long = System::currentTimeMillis) : RecordingStore {
    val objects = ConcurrentHashMap<String, ByteArray>()
    private val storedAt = ConcurrentHashMap<String, Long>()
    override fun put(key: String, bytes: ByteArray) { objects[key] = bytes.copyOf(); storedAt[key] = now() }
    override fun get(key: String): ByteArray? = objects[key]?.copyOf()
    override fun delete(key: String) { objects.remove(key); storedAt.remove(key) }
    override fun list(prefix: String): List<StoredRecording> =
        objects.keys.filter { it.startsWith(prefix) }.sorted().map { StoredRecording(it, storedAt[it] ?: 0L) }
}

/**
 * MEKA's blob bucket ([bucket], `MEKA_BLOB_BUCKET`). The bucket is versioned, so deleting removes every version of the
 * key (and any delete marker), not just the current one: a recording Meka has dismissed is really gone.
 */
class S3RecordingStore(private val bucket: String) : RecordingStore {
    private val s3 = S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build()

    override fun put(key: String, bytes: ByteArray) {
        require(key.startsWith(VoiceRecordingRules.PREFIX))
        // Encrypted with the bucket's default (SSE-KMS, MEKA's key); never public.
        s3.putObject({ it.bucket(bucket).key(key).contentType("audio/mpeg") }, RequestBody.fromBytes(bytes))
    }

    override fun get(key: String): ByteArray? {
        require(key.startsWith(VoiceRecordingRules.PREFIX))
        return try {
            s3.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override fun delete(key: String) {
        require(key.startsWith(VoiceRecordingRules.PREFIX))
        val versions = s3.listObjectVersions { it.bucket(bucket).prefix(key) }
        val ids = versions.versions().filter { it.key() == key }.map { it.versionId() } +
            versions.deleteMarkers().filter { it.key() == key }.map { it.versionId() }
        if (ids.isEmpty()) {
            s3.deleteObject { it.bucket(bucket).key(key) }
            return
        }
        for (v in ids) s3.deleteObject { it.bucket(bucket).key(key).versionId(v) }
    }

    override fun list(prefix: String): List<StoredRecording> {
        require(prefix.startsWith(VoiceRecordingRules.PREFIX))
        return s3.listObjectsV2Paginator { it.bucket(bucket).prefix(prefix) }.contents()
            .map { StoredRecording(it.key(), it.lastModified().toEpochMilli()) }
    }
}
