package os.meka.android

import android.content.Context
import java.security.SecureRandom

/**
 * Device identity and sync enrolment (ADR-005, M0 scheme). Everything here lives in app-private storage, which is
 * excluded from backups (data_extraction_rules.xml). M1 moves the device secret into a Keystore-wrapped blob.
 */
class DeviceIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("meka.identity", Context.MODE_PRIVATE)

    fun deviceId(): String = prefs.getString("device_id", null) ?: ("android" + randomHex(6)).also {
        check(prefs.edit().putString("device_id", it).commit())
    }

    /** Both of Meka's devices share one household; the id is part of every op, so it never changes once set. */
    fun householdId(): String = prefs.getString("household_id", null) ?: HOUSEHOLD.also {
        check(prefs.edit().putString("household_id", it).commit())
    }

    fun serverUrl(): String? = prefs.getString("server_url", null)
    fun deviceSecret(): String? = prefs.getString("device_secret", null)

    fun saveEnrolment(serverUrl: String, secret: String) {
        require(secret.length == 64 && secret.all { it in "0123456789abcdef" }) { "invalid device secret" }
        check(prefs.edit().putString("server_url", serverUrl).putString("device_secret", secret).commit())
    }

    private fun randomHex(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        const val HOUSEHOLD = "home"
    }
}
