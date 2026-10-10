package os.meka.android.security

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * The fingerprint for approving (approvals inbox, slice 1; ADR-006: financial and trading approvals always need
 * biometric confirmation). The platform's BiometricPrompt with **strong biometrics only** (no PIN or pattern fallback),
 * so a yes is always Meka's finger. No new library: android.hardware.biometrics is on every phone MEKA runs on (min SDK 31).
 */
object FoldBiometric {
    private const val STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    /** Whether this phone can take a strong fingerprint now (one is enrolled and the sensor works). */
    fun available(context: Context): Boolean =
        context.getSystemService(BiometricManager::class.java)?.canAuthenticate(STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system's fingerprint prompt ([title], [subtitle]); [done] runs once on the main thread with true only
     * when the finger was accepted. Cancel, too many tries or an error give false. A wrong finger lets Meka try again
     * in the same prompt.
     */
    fun confirm(context: Context, title: String, subtitle: String, done: (Boolean) -> Unit) {
        var answered = false
        val once: (Boolean) -> Unit = { ok -> if (!answered) { answered = true; done(ok) } }
        val executor = context.mainExecutor
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(STRONG)
            .setConfirmationRequired(true)
            .setNegativeButton("Cancel", executor) { _, _ -> once(false) }
            .build()
        prompt.authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = once(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = once(false)
        })
    }
}
