package os.meka.android

import android.content.Context
import java.security.SecureRandom

/**
 * Device id and enrolment secret (ADR-005, M0 scheme). The secret is entered once from the enrolment output and
 * stored in app-private storage; M1 moves it into an encrypted Keystore-wrapped blob alongside the DB key.
 */
class DeviceIdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("meka.identity", Context.MODE_PRIVATE)

    fun deviceId(): String = prefs.getString("device_id", null) ?: ("android" + randomHex(6)).also {
        prefs.edit().putString("device_id", it).apply()
    }

    fun householdId(): String = prefs.getString("household_id", null) ?: "local".also {
        prefs.edit().putString("household_id", it).apply()
    }

    fun deviceSecret(): String? = prefs.getString("device_secret", null)

    fun enrol(householdId: String, deviceId: String, secret: String) {
        require(secret.length == 64 && secret.all { it in "0123456789abcdef" }) { "invalid device secret" }
        prefs.edit().putString("household_id", householdId).putString("device_id", deviceId).putString("device_secret", secret).apply()
    }

    private fun randomHex(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
