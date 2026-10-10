package os.meka.wear

import android.content.Context
import os.meka.core.domain.WatchLinkRules
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/**
 * The watch's identity (Galaxy Watch, slice 2; ADR-005 amendment 2026-10-10), in app-private storage that is never
 * backed up (data_extraction_rules.xml). Before linking it holds only its device id ("watch-" and 16 hex digits) and
 * the code it is showing; once Meka types the code on the Fold or the Mac, the server's answer (household, device id
 * and device secret, given once) makes it a device like the others.
 */
class WatchIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("meka.identity", Context.MODE_PRIVATE)

    /** Made once on first open and sent with the link request; the server refuses an id it unlinked before. */
    fun deviceId(): String = prefs.getString(DEVICE_ID, null) ?: WatchLinkRules.newDeviceId(SecureRandom().asKotlinRandom()).also {
        check(prefs.edit().putString(DEVICE_ID, it).commit())
    }

    val isLinked: Boolean get() = linked() != null

    /** The server's address, household, device id and secret once linked; null before. */
    fun linked(): Linked? {
        val url = prefs.getString(SERVER_URL, null) ?: return null
        val household = prefs.getString(HOUSEHOLD_ID, null) ?: return null
        val secret = prefs.getString(DEVICE_SECRET, null) ?: return null
        return Linked(url, household, deviceId(), secret)
    }

    fun saveLinked(serverUrl: String, householdId: String, deviceId: String, secret: String) {
        require(secret.length == 64 && secret.all { it in "0123456789abcdef" }) { "invalid device secret" }
        require(WatchLinkRules.isWatchId(deviceId)) { "not a watch id" }
        check(
            prefs.edit()
                .putString(SERVER_URL, serverUrl).putString(HOUSEHOLD_ID, householdId)
                .putString(DEVICE_ID, deviceId).putString(DEVICE_SECRET, secret)
                .remove(PENDING_ID).remove(PENDING_CODE).remove(PENDING_EXPIRES)
                .commit(),
        )
    }

    /** The code on screen, kept so closing MEKA and opening it again within the 10 minutes shows the same one. */
    fun pending(): Pending? {
        val id = prefs.getString(PENDING_ID, null) ?: return null
        val code = prefs.getString(PENDING_CODE, null) ?: return null
        return Pending(id, code, prefs.getLong(PENDING_EXPIRES, 0L))
    }

    fun savePending(p: Pending) {
        prefs.edit().putString(PENDING_ID, p.linkId).putString(PENDING_CODE, p.code).putLong(PENDING_EXPIRES, p.expiresAtMs).apply()
    }

    fun clearPending() {
        prefs.edit().remove(PENDING_ID).remove(PENDING_CODE).remove(PENDING_EXPIRES).apply()
    }

    /**
     * Unlinked on the Fold or the Mac: forget everything, including the device id (the server refuses an unlinked id
     * for good), so the next link starts as a new watch.
     */
    fun forget() {
        check(prefs.edit().clear().commit())
    }

    data class Linked(val serverUrl: String, val householdId: String, val deviceId: String, val secret: String)

    data class Pending(val linkId: String, val code: String, val expiresAtMs: Long)

    private companion object {
        const val DEVICE_ID = "device_id"
        const val HOUSEHOLD_ID = "household_id"
        const val SERVER_URL = "server_url"
        const val DEVICE_SECRET = "device_secret"
        const val PENDING_ID = "pending_link_id"
        const val PENDING_CODE = "pending_code"
        const val PENDING_EXPIRES = "pending_expires_ms"
    }
}
