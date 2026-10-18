package os.meka.core.facade

/**
 * The platform crypto the Vault key needs (Vault slice 2a; ADR-005 addendum 2026-10-18), implemented by each app: the
 * Fold with a Keystore P-256 key-agreement key and javax AES-GCM, the Mac with a Secure Enclave key-agreement key and
 * CryptoKit. Only Strings cross (base64, standard alphabet), so Swift implements it without Kotlin byte arrays. The key
 * derivation (HKDF-SHA256) is the core's own ([os.meka.core.domain.Hkdf]), so both platforms derive identically.
 */
interface VaultCrypto {
    /** This device's P-256 key-agreement key's public half (X.509 DER), made in secure hardware on first ask; null when it can't hold one. */
    fun agreementPublicKey(): String?

    /** ECDH between this device's agreement key and [publicKey] (X.509 DER): the 32-byte shared secret; null on failure. */
    fun agree(publicKey: String): String?

    /** A fresh one-off P-256 key's ECDH with [publicKey]: the one-off key's public half and the shared secret; null on failure. */
    fun agreeOnce(publicKey: String): VaultAgreement?

    /** AES-256-GCM with [key] (32 bytes): a random 12-byte nonce, the ciphertext and the 16-byte tag; null on failure. */
    fun sealBox(key: String, plain: String): String?

    /** The opposite of [sealBox]; null when the key is wrong or the box was changed. */
    fun openBox(key: String, sealed: String): String?
}

/** A one-off key's public half (X.509 DER) and its ECDH shared secret, base64. */
class VaultAgreement(val publicKey: String, val shared: String)
