package os.meka.android.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * The fingerprint for approving (approvals inbox, slice 1; ADR-006: financial and trading approvals always need
 * biometric confirmation). The platform's BiometricPrompt with **strong biometrics only** (no PIN or pattern fallback),
 * so a yes is always Meka's finger. Since slice 2 of Trading approvals the prompt only ever unlocks the approval key
 * ([FoldApprovalKey]) for one signature ([sign]); there is no prompt that approves without it. No new library: android.hardware.biometrics is on every phone MEKA runs on (min SDK 31).
 */
object FoldBiometric {
    private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    /** Whether this phone can take a strong fingerprint now (one is enrolled and the sensor works). */
    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Trading approvals, slice 2: the same prompt with the approval key's [signature] as its CryptoObject, so the
     * fingerprint unlocks that one signature inside secure hardware. [done] gets the base64 DER signature over
     * [message] only when the finger was accepted and the key signed; null on cancel, an error or a failed signature.
     */
    fun sign(context: Context, title: String, subtitle: String, signature: java.security.Signature, message: String, done: (String?) -> Unit) {
        var answered = false
        val once: (String?) -> Unit = { sig -> if (!answered) { answered = true; done(sig) } }
        val executor = context.mainExecutor
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(STRONG)
            .setConfirmationRequired(true)
            .setNegativeButton("Cancel", executor) { _, _ -> once(null) }
            .build()
        prompt.authenticate(BiometricPrompt.CryptoObject(signature), CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature ?: return once(null)
                once(
                    runCatching {
                        s.update(message.toByteArray())
                        android.util.Base64.encodeToString(s.sign(), android.util.Base64.NO_WRAP)
                    }.getOrNull(),
                )
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = once(null)
        })
    }
}
