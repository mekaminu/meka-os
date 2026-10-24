package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Encrypts integration refresh tokens before they are stored (ADR-004: MEKA-specific KMS key). */
interface TokenCipher {
    fun encrypt(plain: ByteArray, context: Map<String, String>): ByteArray
    fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray
}

/**
 * KMS direct encryption (tokens are far below KMS's 4 KB limit). The encryption context binds each ciphertext to its
 * household and provider, so a ciphertext copied to another row will not decrypt.
 */
class KmsTokenCipher(private val keyId: String) : TokenCipher {
    private val kms = KmsClient.builder().httpClient(UrlConnectionHttpClient.create()).build()

    override fun encrypt(plain: ByteArray, context: Map<String, String>): ByteArray {
        KmsMeter.encrypts.incrementAndGet()
        return kms.encrypt { it.keyId(keyId).plaintext(SdkBytes.fromByteArray(plain)).encryptionContext(context) }.ciphertextBlob().asByteArray()
    }

    override fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray {
        KmsMeter.decrypts.incrementAndGet()
        return kms.decrypt { it.keyId(keyId).ciphertextBlob(SdkBytes.fromByteArray(cipher)).encryptionContext(context) }.plaintext().asByteArray()
    }
}

/**
 * KMS free tier (Meka, 2026-10-18: 18,309 of the 20,000 free requests used by the 12th). Every check of every signed-in
 * account (each 5 minutes) decrypted its refresh token through KMS, and Microsoft's rotating tokens were encrypted again
 * each time. This keeps what one decrypt or encrypt gave in the task's memory (keyed by a hash of the ciphertext and
 * its context, so a changed or copied ciphertext still goes to KMS), so a token costs one KMS call per task start.
 * The database still only ever holds ciphertext (ADR-004 addendum 2026-10-18). At most [max] tokens are kept.
 */
class CachingTokenCipher(private val inner: TokenCipher, private val max: Int = 64) : TokenCipher {
    private val kept = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > max
    }

    @Synchronized
    override fun encrypt(plain: ByteArray, context: Map<String, String>): ByteArray =
        inner.encrypt(plain, context).also { kept[key(it, context)] = plain.copyOf() }

    override fun decrypt(cipher: ByteArray, context: Map<String, String>): ByteArray {
        val k = key(cipher, context)
        synchronized(this) { kept[k]?.let { return it.copyOf() } }
        val v = inner.decrypt(cipher, context)
        synchronized(this) { kept[k] = v.copyOf() }
        return v
    }

    private fun key(cipher: ByteArray, context: Map<String, String>): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(cipher)
        context.toSortedMap().forEach { (k, v) -> d.update(0); d.update(k.toByteArray()); d.update(1); d.update(v.toByteArray()) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The task's KMS-backed calls since it started, for the deploy report ([reportLine]). */
object KmsMeter {
    val startedAtMs: Long = System.currentTimeMillis()
    val secretReads = AtomicLong()
    val decrypts = AtomicLong()
    val encrypts = AtomicLong()

    /** "KMS-backed reads since start: 9 (secrets 6 · token decrypts 3 · token encrypts 0) · up 3 h 10 min". */
    fun reportLine(secrets: Long, decrypts: Long, encrypts: Long, upMs: Long): String {
        val mins = (upMs / 60_000).coerceAtLeast(0)
        val up = if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
        return "KMS-backed reads since start: ${secrets + decrypts + encrypts} (secrets $secrets · token decrypts $decrypts · " +
            "token encrypts $encrypts) · up $up"
    }

    fun reportLine(nowMs: Long = System.currentTimeMillis()): String =
        reportLine(secretReads.get(), decrypts.get(), encrypts.get(), nowMs - startedAtMs)
}

/**
 * One cache for every Secrets Manager read in the server (KMS free tier, 2026-10-18): each read decrypts through
 * MEKA's KMS key, and ten readers each re-reading a minute apart used most of the month's free requests. A secret is
 * read once and then kept for [ttlMs] (six hours); an absent, empty or unusable one (`{}`, the CDK placeholder) is
 * re-read after [emptyTtlMs] (15 minutes), so pasting a key still needs no restart. When a provider refuses a key
 * ([SecretReader.rejected], a 401/403), the secret is read again at once, at most once per [rejectGapMs].
 */
class SecretCache(
    private val fetch: (secretId: String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    val ttlMs: Long = 6 * 3_600_000L,
    val emptyTtlMs: Long = 15 * 60_000L,
    val rejectGapMs: Long = 15 * 60_000L,
    private val onRead: () -> Unit = {},
) {
    private class Entry(val atMs: Long, val raw: String?, val usable: Boolean)
    private val entries = HashMap<String, Entry>()
    private val rejectedAt = HashMap<String, Long>()

    /** The secret's text, parsed by [parse]; null while absent or unusable. */
    @Synchronized
    fun <T> read(secretId: String, parse: (String?) -> T?): T? = read(secretId, parse) { true }

    /**
     * As [read], with [usable] deciding whether a parsed value counts as set (kept [ttlMs]) or not yet (re-read after
     * [emptyTtlMs]): for a parser that also describes an unusable secret (Live's key check, 2026-10-24).
     */
    @Synchronized
    fun <T> read(secretId: String, parse: (String?) -> T?, usable: (T) -> Boolean): T? {
        val t = now()
        entries[secretId]?.let { e ->
            if (t - e.atMs < (if (e.usable) ttlMs else emptyTtlMs)) return runCatching { parse(e.raw) }.getOrNull()
        }
        onRead()
        val raw = runCatching { fetch(secretId) }.getOrNull()
        val v = runCatching { parse(raw) }.getOrNull()
        entries[secretId] = Entry(t, raw, v != null && usable(v))
        return v
    }

    /** A provider refused the key: forget it so the next use reads it again; false when that happened too recently. */
    @Synchronized
    fun rejected(secretId: String): Boolean {
        val t = now()
        val last = rejectedAt[secretId]
        if (last != null && t - last < rejectGapMs) return false
        rejectedAt[secretId] = t
        entries.remove(secretId)
        return true
    }

    fun <T> reader(secretId: String, parse: (String?) -> T?): SecretReader<T> = SecretReader(this, secretId, parse)

    fun <T> reader(secretId: String, parse: (String?) -> T?, usable: (T) -> Boolean): SecretReader<T> = SecretReader(this, secretId, parse, usable)

    companion object {
        /** The server's one cache over Secrets Manager (one client for every reader). */
        val shared: SecretCache by lazy {
            val sm = SecretsManagerClient.builder().httpClient(UrlConnectionHttpClient.create()).build()
            SecretCache(fetch = { id -> sm.getSecretValue { it.secretId(id) }.secretString() }, onRead = { KmsMeter.secretReads.incrementAndGet() })
        }
    }
}

/** A secret as its reader sees it: call it for the value (cached); [rejected] when the provider refused it. */
class SecretReader<T>(
    private val cache: SecretCache,
    val secretId: String,
    private val parse: (String?) -> T?,
    private val usable: (T) -> Boolean = { true },
) : () -> T? {
    override fun invoke(): T? = cache.read(secretId, parse, usable)
    fun rejected(): Boolean = cache.rejected(secretId)
}

/** Where provider OAuth app credentials come from. Null when the owner has not set them up yet. */
fun interface OAuthClientSource {
    fun get(provider: String): OAuthClient?
}

/**
 * Reads `{"client_id","client_secret"}` from Secrets Manager at use time through [SecretCache] (six hours; 15 minutes
 * while unset), so pasting the credentials in the console takes effect without a redeploy. The CDK placeholder has an
 * empty client_id.
 */
class SecretsManagerOAuthClients(
    private val secretIds: Map<String, String>,
    private val cache: SecretCache = SecretCache.shared,
) : OAuthClientSource {
    override fun get(provider: String): OAuthClient? {
        val id = secretIds[provider] ?: return null
        return cache.read(id, ::parse)
    }

    companion object {
        fun parse(json: String?): OAuthClient? {
            val o = Json.parseToJsonElement(json ?: return null).jsonObject
            val cid = o["client_id"]?.jsonPrimitive?.content.orEmpty().trim()
            val sec = o["client_secret"]?.jsonPrimitive?.content.orEmpty().trim()
            return if (cid.isEmpty() || sec.isEmpty()) null else OAuthClient(cid, sec)
        }
    }
}
