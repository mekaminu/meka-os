package os.meka.core.facade

/**
 * A device's request-signing key (ADR-005): ECDSA P-256, created and held by the platform's secure hardware
 * (Android Keystore / StrongBox, Mac Secure Enclave) so the private key can never be copied off the device.
 * Implemented by each app; Kotlin only ever sees the public key and signatures.
 */
interface DeviceKey {
    /** X.509 SubjectPublicKeyInfo, DER, base64 (standard alphabet). */
    val publicKeyDerBase64: String

    /** ECDSA-SHA256 over the UTF-8 bytes of [message]; DER signature, base64 (standard alphabet). */
    fun signBase64(message: String): String
}

/** What a device signs for each request. Must match the server's verifier exactly. */
object RequestSigning {
    const val VERSION = "MEKA1"
    const val HEADER_TIME = "X-Meka-Time"
    const val HEADER_NONCE = "X-Meka-Nonce"
    const val HEADER_SIGNATURE = "X-Meka-Signature"

    fun canonical(method: String, path: String, timeMs: Long, nonce: String, body: String): String =
        listOf(VERSION, method.uppercase(), path, timeMs.toString(), nonce, Sha256.hex(body.encodeToByteArray())).joinToString("\n")
}

/** Plain SHA-256 for request body hashes (the implementation lives in core/sync, so the domain rules can use it too). */
object Sha256 {
    fun digest(input: ByteArray): ByteArray = os.meka.core.sync.Sha256.digest(input)

    fun hex(input: ByteArray): String = os.meka.core.sync.Sha256.hex(input)
}
