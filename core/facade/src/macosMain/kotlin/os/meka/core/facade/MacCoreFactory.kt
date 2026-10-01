package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import os.meka.core.data.MacDatabase
import os.meka.core.data.SqlReplicaStore
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.random.Random

/** CSPRNG backed by SecRandomCopyBytes, for ids (ADR-003). */
@OptIn(ExperimentalForeignApi::class)
internal object AppleSecureRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        val bytes = ByteArray(4)
        bytes.usePinned { pinned ->
            check(SecRandomCopyBytes(kSecRandomDefault, 4u, pinned.addressOf(0)) == errSecSuccess) { "SecRandomCopyBytes failed" }
        }
        val v = (bytes[0].toInt() and 0xff shl 24) or (bytes[1].toInt() and 0xff shl 16) or
            (bytes[2].toInt() and 0xff shl 8) or (bytes[3].toInt() and 0xff)
        return v ushr (32 - bitCount) and (-bitCount shr 31)
    }
}

/**
 * Swift entry point. Swift supplies identity and secrets from the Keychain; Kotlin owns everything else.
 * [databaseKeyHex] is only applied when [encrypted] is true, i.e. once spike S6 links SQLCipher (ADR-002).
 */
object MacCoreFactory {
    fun create(
        householdId: String,
        deviceId: String,
        syncUrl: String?,
        deviceSecret: String?,
        databaseKeyHex: String?,
        encrypted: Boolean,
        databaseDirectory: String,
        databaseName: String = "meka.db",
    ): MekaCore {
        val store = SqlReplicaStore(MacDatabase.open(databaseKeyHex, encrypted, databaseDirectory, databaseName))
        val transport = if (!syncUrl.isNullOrBlank() && deviceSecret != null) {
            HttpSyncTransport(HttpClient(Darwin), syncUrl) { deviceSecret }
        } else {
            null
        }
        return MekaCore(householdId, deviceId, store, transport, AppleSecureRandom)
    }

    private val http by lazy { HttpClient(Darwin) }

    /** One-time enrolment from the Mac's Connect sheet. */
    suspend fun enrol(serverUrl: String, enrolCode: String, householdId: String, deviceId: String, deviceName: String): EnrolmentResult =
        Enrolment.enrol(http, serverUrl.trim().trimEnd('/'), enrolCode, householdId, deviceId, deviceName)

    suspend fun connect(core: MekaCore, serverUrl: String, deviceSecret: String) =
        core.connect(Enrolment.transport(http, serverUrl.trim().trimEnd('/'), deviceSecret))
}
