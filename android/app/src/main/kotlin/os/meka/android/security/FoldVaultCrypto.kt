package os.meka.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import os.meka.core.facade.VaultAgreement
import os.meka.core.facade.VaultCrypto
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Fold's crypto for the Vault key (Vault slice 2a; ADR-005 addendum 2026-10-18): a P-256 key-agreement key made in
 * the Android Keystore (TEE; the private half never leaves it), ECDH with it or with a one-off software key, and
 * AES-256-GCM (12-byte nonce, then ciphertext and 16-byte tag). The HKDF in between is the core's, so the Mac's
 * CryptoKit derives the same keys. Called on the core's thread.
 */
class FoldVaultCrypto(private val own: AgreementKey = KeystoreAgreementKey) : VaultCrypto {
    /** This device's agreement key; the Keystore one in the app, a software one in tests. */
    interface AgreementKey {
        fun publicKey(): PublicKey?
        fun agree(other: PublicKey): ByteArray?
    }

    private val random = SecureRandom()
    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
    private fun parse(publicKey: String): PublicKey? =
        unb64(publicKey)?.let { runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(it)) }.getOrNull() }

    override fun agreementPublicKey(): String? = own.publicKey()?.encoded?.let(::b64)

    override fun agree(publicKey: String): String? = parse(publicKey)?.let(own::agree)?.let(::b64)

    override fun agreeOnce(publicKey: String): VaultAgreement? {
        val other = parse(publicKey) ?: return null
        return runCatching {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
            val shared = KeyAgreement.getInstance("ECDH").run { init(pair.private); doPhase(other, true); generateSecret() }
            VaultAgreement(b64(pair.public.encoded), b64(shared))
        }.getOrNull()
    }

    override fun sealBox(key: String, plain: String): String? {
        val k = unb64(key)?.takeIf { it.size == 32 } ?: return null
        val p = unb64(plain) ?: return null
        return runCatching {
            val nonce = ByteArray(NONCE).also(random::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, nonce)) }
            b64(nonce + c.doFinal(p))
        }.getOrNull()
    }

    override fun openBox(key: String, sealed: String): String? {
        val k = unb64(key)?.takeIf { it.size == 32 } ?: return null
        val box = unb64(sealed)?.takeIf { it.size >= NONCE + TAG_BITS / 8 } ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, box.copyOfRange(0, NONCE)))
            }
            b64(c.doFinal(box.copyOfRange(NONCE, box.size)))
        }.getOrNull()
    }

    /** The Keystore's agreement key, made on first ask (not StrongBox: its ECDH support varies; the TEE holds it). */
    object KeystoreAgreementKey : AgreementKey {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "meka_vault_agree_v1"

        private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

        private fun ensure() {
            if (keyStore().containsAlias(ALIAS)) return
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        }

        override fun publicKey(): PublicKey? = runCatching { ensure(); keyStore().getCertificate(ALIAS).publicKey }.getOrNull()

        override fun agree(other: PublicKey): ByteArray? = runCatching {
            ensure()
            val mine = keyStore().getKey(ALIAS, null) as PrivateKey
            KeyAgreement.getInstance("ECDH", PROVIDER).run { init(mine); doPhase(other, true); generateSecret() }
        }.getOrNull()
    }

    private companion object {
        const val NONCE = 12
        const val TAG_BITS = 128
    }
}
