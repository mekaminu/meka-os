package os.meka.backend

import os.meka.core.wire.VaultFileCodec
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.util.concurrent.ConcurrentHashMap

/**
 * The Vault's encrypted copies (Vault slice 2b). A phone or Mac seals a document's photo or PDF with the household's
 * Vault key before it is sent; the server keeps only those sealed bytes under `vault/<household>/<copy id>.bin` in
 * MEKA's own private bucket (SSE-KMS with MEKA's key on top, TLS only, versioned) and hands them back to the same
 * household's phones and Macs. It never holds the key, the file, its name or which document it belongs to, and logs
 * nothing about a copy.
 */
interface VaultFileStore {
    fun put(key: String, bytes: ByteArray)
    fun get(key: String): ByteArray?
    /** Deletes every version of [key]; one that isn't there is fine. */
    fun delete(key: String)
}

class InMemoryVaultFileStore : VaultFileStore {
    val objects = ConcurrentHashMap<String, ByteArray>()
    override fun put(key: String, bytes: ByteArray) { objects[key] = bytes.copyOf() }
    override fun get(key: String): ByteArray? = objects[key]?.copyOf()
    override fun delete(key: String) { objects.remove(key) }
}

/** MEKA's blob bucket (`MEKA_BLOB_BUCKET`), only under [VaultFiles.PREFIX]. */
class S3VaultFileStore(private val bucket: String) : VaultFileStore {
    private val s3 = S3Client.builder().httpClient(UrlConnectionHttpClient.create()).build()

    override fun put(key: String, bytes: ByteArray) {
        require(key.startsWith(VaultFiles.PREFIX))
        s3.putObject({ it.bucket(bucket).key(key).contentType("application/octet-stream") }, RequestBody.fromBytes(bytes))
    }

    override fun get(key: String): ByteArray? {
        require(key.startsWith(VaultFiles.PREFIX))
        return try {
            s3.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override fun delete(key: String) {
        require(key.startsWith(VaultFiles.PREFIX))
        // The bucket is versioned: every version and delete marker goes, so a removed copy is really gone.
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

/** The routes' work: one household's copies by id. Watches never get or send one (they never hold the Vault key). */
class VaultFiles(private val store: VaultFileStore) {
    private fun allowed(who: DeviceIdentity): Boolean =
        who.deviceId != ReleasePublisher.ID && !isLinkedDeviceId(who.deviceId) && safe(who.householdId)

    fun put(who: DeviceIdentity, body: String): LinkReply {
        if (!allowed(who)) return LinkReply(403, "forbidden")
        val p = VaultFileCodec.decodePut(body)
        val bytes = runCatching { java.util.Base64.getDecoder().decode(p.sealed) }.getOrNull()
            ?: return LinkReply(400, "bad request")
        if (bytes.isEmpty() || bytes.size > VaultFileCodec.MAX_SEALED_BYTES) return LinkReply(400, "bad request")
        store.put(key(who.householdId, p.id), bytes)
        return LinkReply(200, VaultFileCodec.encodeOk())
    }

    fun get(who: DeviceIdentity, body: String): LinkReply {
        if (!allowed(who)) return LinkReply(403, "forbidden")
        val id = VaultFileCodec.decodeRef(body)
        val bytes = store.get(key(who.householdId, id)) ?: return LinkReply(404, "no such copy")
        return LinkReply(200, VaultFileCodec.encodeSealed(java.util.Base64.getEncoder().encodeToString(bytes)))
    }

    fun delete(who: DeviceIdentity, body: String): LinkReply {
        if (!allowed(who)) return LinkReply(403, "forbidden")
        store.delete(key(who.householdId, VaultFileCodec.decodeRef(body)))
        return LinkReply(200, VaultFileCodec.encodeOk())
    }

    companion object {
        const val PREFIX = "vault/"

        private fun safe(s: String): Boolean = s.isNotEmpty() && s.length <= 64 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** `vault/<household>/<id>.bin`; the id is already checked by the codec. */
        fun key(householdId: String, id: String): String {
            require(safe(householdId) && VaultFileCodec.validId(id))
            return "$PREFIX$householdId/$id.bin"
        }

        /** The deployment's store: MEKA's blob bucket, or none (the routes are left out). */
        fun fromEnv(): VaultFiles? =
            System.getenv("MEKA_BLOB_BUCKET")?.takeIf { it.isNotBlank() }?.let { VaultFiles(S3VaultFileStore(it)) }
    }
}
