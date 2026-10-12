package os.meka.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The Fold's approval key (Trading approvals, slice 2; ADR-006 addendum 2026-10-12): ECDSA P-256 made in the Android
 * Keystore (StrongBox when there is one) that **only a strong fingerprint unlocks, for each signature** (auth-bound,
 * no time window, no PIN fallback) and that Android destroys when a new fingerprint is enrolled. A yes in Needs you is
 * signed with it inside BiometricPrompt, so the server and Kestrel can check the yes came from Meka's finger on this
 * phone. Separate from the request-signing key ([AndroidDeviceKey]), which signs without a finger.
 */
object FoldApprovalKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "meka_approval_v1"
    const val LABEL = "Fold"

    sealed interface Ready {
        /** Sign with [signature] inside BiometricPrompt (its CryptoObject); [publicKey] is the key's public half. */
        class Sign(val signature: Signature, val publicKey: String) : Ready
        /** Fingerprints changed since the key was made: a new key was made (register it; approve again). */
        data object Renewed : Ready
        /** No strong fingerprint enrolled, or the Keystore refused: this phone can't approve. */
        data object Unavailable : Ready
    }

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** The key's public half (made now if there is none), or null when this phone can't hold one. */
    fun publicKey(): String? = runCatching { ensure(); publicHalf() }.getOrNull()

    /** A signature ready for the fingerprint prompt; renews the key when a fingerprint change killed it. */
    fun ready(): Ready = try {
        ensure()
        val key = keyStore().getKey(ALIAS, null) as PrivateKey
        Ready.Sign(Signature.getInstance("SHA256withECDSA").apply { initSign(key) }, publicHalf())
    } catch (e: KeyPermanentlyInvalidatedException) {
        runCatching { keyStore().deleteEntry(ALIAS) }
        if (publicKey() != null) Ready.Renewed else Ready.Unavailable
    } catch (e: Exception) {
        Ready.Unavailable
    }

    private fun publicHalf(): String = Base64.encodeToString(keyStore().getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP)

    private fun ensure() {
        if (keyStore().containsAlias(ALIAS)) return
        try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false) // still hardware-backed (TEE)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // every signature, finger only
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }
}
