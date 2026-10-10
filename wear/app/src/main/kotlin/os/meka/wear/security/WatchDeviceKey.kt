package os.meka.wear.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import os.meka.core.facade.DeviceKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The watch's request-signing key (ADR-005; the same scheme as the phone's AndroidDeviceKey): ECDSA P-256 generated
 * inside the watch's Android Keystore, in StrongBox when the watch has one. The private key never leaves secure
 * hardware; the server enrols the watch with this key when Meka types its code (ADR-005 amendment 2026-10-10).
 */
class WatchDeviceKey : DeviceKey {
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    init {
        if (!keyStore.containsAlias(ALIAS)) {
            try {
                generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                generate(strongBox = false) // still hardware-backed (TEE), just not the separate secure element
            }
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    override val publicKeyDerBase64: String =
        Base64.encodeToString(keyStore.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)

    override fun signBase64(message: String): String {
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message.toByteArray()); sign() }
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    companion object {
        /** Unlinked: the next link makes a fresh key, so nothing the server knew of this watch is used again. */
        fun delete() {
            runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) }
        }

        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "meka_device_signing_v1"
    }
}
