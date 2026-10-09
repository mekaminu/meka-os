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
}

/** Tests, and a server without a bucket configured. */
class InMemoryRecordingStore : RecordingStore {
    val objects = ConcurrentHashMap<String, ByteArray>()
    override fun put(key: String, bytes: ByteArray) { objects[key] = bytes.copyOf() }
    override fun get(key: String): ByteArray? = objects[key]?.copyOf()
    override fun delete(key: String) { objects.remove(key) }
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
}
