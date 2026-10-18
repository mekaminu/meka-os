package os.meka.core.domain

import os.meka.core.sync.Sha256

/**
 * HMAC-SHA256 (RFC 2104) and HKDF-SHA256 (RFC 5869) over the kernel's own SHA-256, so the Fold and the Mac derive the
 * Vault's wrapping keys with exactly the same code (Vault slice 2a; ADR-005 addendum 2026-10-18). Pure; the AES-GCM and
 * the ECDH stay in each platform's crypto (the Keystore, CryptoKit).
 */
object HmacSha256 {
    private const val BLOCK = 64

    fun mac(key: ByteArray, message: ByteArray): ByteArray {
        val k = if (key.size > BLOCK) Sha256.digest(key) else key
        val padded = k.copyOf(BLOCK)
        val inner = ByteArray(BLOCK) { (padded[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK) { (padded[it].toInt() xor 0x5c).toByte() }
        return Sha256.digest(outer + Sha256.digest(inner + message))
    }
}

object Hkdf {
    private const val HASH = 32

    /** HKDF-Extract then Expand: [length] bytes (at most 255 × 32) from [ikm], [salt] (empty = 32 zero bytes) and [info]. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH)
        val prk = HmacSha256.mac(if (salt.isEmpty()) ByteArray(HASH) else salt, ikm)
        val out = ArrayList<Byte>(length)
        var t = ByteArray(0)
        var i = 1
        while (out.size < length) {
            t = HmacSha256.mac(prk, t + info + byteArrayOf(i.toByte()))
            t.forEach { if (out.size < length) out += it }
            i++
        }
        return out.toByteArray()
    }
}
