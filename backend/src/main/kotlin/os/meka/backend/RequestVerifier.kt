package os.meka.backend

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies device request signatures (ADR-005): ECDSA P-256 / SHA-256 over
 * "MEKA1\n<METHOD>\n<path>\n<timeMs>\n<nonce>\n<sha256 hex of body>", within ±5 minutes, each nonce once.
 * The nonce cache is per process: correct for the single sync task M0/M1 runs; move it to Postgres before scaling out.
 */
class RequestVerifier(private val now: () -> Long = System::currentTimeMillis) {
    private val seen = ConcurrentHashMap<String, Long>()

    /**
     * [p1363]: the signature is the raw r‖s pair WebCrypto makes (a browser on the family page), not the DER the apps'
     * hardware keys make; the signed message is the same.
     */
    fun verify(
        publicKeyB64: String, method: String, path: String, body: String, time: String?, nonce: String?, signatureB64: String?,
        p1363: Boolean = false,
    ): Boolean {
        val t = time?.toLongOrNull() ?: return false
        if (nonce == null || nonce.length !in 16..64 || signatureB64 == null) return false
        if (kotlin.math.abs(now() - t) > WINDOW_MS) return false
        val canonical = listOf("MEKA1", method.uppercase(), path, t.toString(), nonce, Secrets.sha256Hex(body)).joinToString("\n")
        val ok = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
            Signature.getInstance(if (p1363) "SHA256withECDSAinP1363Format" else "SHA256withECDSA").run {
                initVerify(key); update(canonical.toByteArray()); verify(Base64.getDecoder().decode(signatureB64))
            }
        }.getOrDefault(false)
        if (!ok) return false
        // Only a valid signature can burn a nonce, so garbage requests cannot fill the cache.
        if (seen.putIfAbsent(nonce, t) != null) return false
        if (seen.size > 50_000) seen.entries.removeIf { now() - it.value > WINDOW_MS }
        return true
    }

    /** True if [publicKeyB64] is a P-256 public key (what devices must register). */
    fun isP256(publicKeyB64: String): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        (key as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == 256
    }.getOrDefault(false)

    companion object { const val WINDOW_MS = 5 * 60_000L }
}
