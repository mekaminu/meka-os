package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import os.meka.core.data.MacDatabase
import os.meka.core.data.SqlReplicaStore
import os.meka.core.domain.AppUpdateRules
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.memcpy
import platform.posix.unlink
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
        deviceKey: DeviceKey? = null,
    ): MekaCore {
        val store = SqlReplicaStore(MacDatabase.open(databaseKeyHex, encrypted, databaseDirectory, databaseName))
        val transport = if (!syncUrl.isNullOrBlank() && deviceSecret != null) {
            HttpSyncTransport(http, syncUrl, deviceKey) { deviceSecret }
        } else {
            null
        }
        return MekaCore(householdId, deviceId, store, transport, AppleSecureRandom)
    }

    private val http by lazy { HttpClient(Darwin) }

    /** One-time enrolment from the Mac's Connect sheet. */
    suspend fun enrol(serverUrl: String, enrolCode: String, householdId: String, deviceId: String, deviceName: String): EnrolmentResult =
        Enrolment.enrol(http, serverUrl.trim().trimEnd('/'), enrolCode, householdId, deviceId, deviceName)

    suspend fun connect(core: MekaCore, serverUrl: String, deviceSecret: String, deviceKey: DeviceKey? = null) =
        core.connect(Enrolment.transport(http, serverUrl.trim().trimEnd('/'), deviceSecret, deviceKey))

    /**
     * Self-updating phone app: what publishing the APK at [apkPath] would send ("MEKA 0.1.412 · build 412 · 24.3 MB"),
     * or why it can't be published. Reads the Gradle `output-metadata.json` beside the APK for the build number.
     */
    fun checkFoldUpdate(apkPath: String): FoldUpdateCheck {
        val (meta, problem) = readFoldUpdate(apkPath)
        if (meta == null) return FoldUpdateCheck(null, problem)
        return FoldUpdateCheck(AppUpdateRules.summary(AppUpdateRules.Build(meta.first.versionCode, meta.first.versionName, meta.second.size.toLong())), null)
    }

    /** Publishes the APK at [apkPath] to the server for the Fold to offer. Returns one line for the Mac to show. */
    suspend fun publishFoldUpdate(core: MekaCore, apkPath: String): String {
        val (meta, problem) = readFoldUpdate(apkPath)
        if (meta == null) return problem ?: "That file can't be published."
        return core.publishRelease(meta.second, meta.first.versionCode, meta.first.versionName).message
    }

    /**
     * Spike S6 (ADR-002): proves this build's SQLite is SQLCipher and that a keyed database is really encrypted.
     * Works on throwaway databases in [directory] (deleted afterwards); never touches the replica.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun databaseEncryptionProbe(directory: String): DatabaseEncryptionProbe {
        val dir = directory.trimEnd('/')
        val tag = randomHex(8)
        val plainName = "s6-version-$tag.db"
        val keyedName = "s6-keyed-$tag.db"
        try {
            val version = MacDatabase.cipherVersion(dir, plainName)
                ?: return DatabaseEncryptionProbe(null, opensWithKey = false, fileUnreadable = false, refusedWithoutKey = false)
            val key = randomHex(32)
            val created = MacDatabase.opens(key, dir, keyedName)
            val reopens = created && MacDatabase.opens(key, dir, keyedName)
            // Closing the last connection checkpoints the WAL, so page 1 is in the main file by now.
            val bytes = readMacFile("$dir/$keyedName")
            val plainHeader = "SQLite format 3".encodeToByteArray()
            val unreadable = bytes != null && bytes.size >= 1024 &&
                !bytes.copyOfRange(0, plainHeader.size).contentEquals(plainHeader)
            val refused = !MacDatabase.opens(randomHex(32), dir, keyedName) && !MacDatabase.opens(null, dir, keyedName)
            return DatabaseEncryptionProbe(version, opensWithKey = reopens, fileUnreadable = unreadable, refusedWithoutKey = refused)
        } finally {
            for (name in listOf(plainName, keyedName)) {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) unlink("$dir/$name$suffix")
            }
        }
    }

    /**
     * Mac database encryption (slice 2b, ADR-002): whether the existing database [name] in [directory] opens and reads
     * with [keyHex] exactly as [create] will open it (the same driver and keying). The app calls it on an encrypted copy
     * before swapping it in, and on a sealed database before opening it. Only call it for a file that exists (a
     * missing one would be created) and once [databaseEncryptionProbe] found SQLCipher.
     */
    fun databaseOpensWithKey(directory: String, name: String, keyHex: String): Boolean =
        MacDatabase.opens(keyHex, directory.trimEnd('/'), name)

    private fun randomHex(bytes: Int): String =
        AppleSecureRandom.nextBytes(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun readFoldUpdate(apkPath: String): Pair<Pair<os.meka.core.wire.WireCodec.ApkMetadata, ByteArray>?, String?> {
        val apk = readMacFile(apkPath)
        val dir = apkPath.substringBeforeLast('/', "")
        val metaJson = readMacFile("$dir/output-metadata.json")?.decodeToString()
        val (meta, problem) = ReleaseTransfer.checkApk(apk, metaJson)
        if (meta == null || apk == null) return null to problem
        val name = apkPath.substringAfterLast('/')
        if (meta.outputFile != name) return null to "output-metadata.json beside it describes ${meta.outputFile.take(60)}, not $name."
        return (meta to apk) to null
    }
}

/**
 * Spike S6: what [MacCoreFactory.databaseEncryptionProbe] found. [cipherVersion] is null when the app is linked
 * against the system SQLite (then nothing else was tried).
 */
data class DatabaseEncryptionProbe(
    val cipherVersion: String?,
    val opensWithKey: Boolean,
    val fileUnreadable: Boolean,
    val refusedWithoutKey: Boolean,
)

/** What the Mac shows before publishing a phone build: a summary, or the reason it can't be published. */
data class FoldUpdateCheck(val summary: String?, val problem: String?)

/** Reads a whole file, or null if it can't be read. */
@OptIn(ExperimentalForeignApi::class)
internal fun readMacFile(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val n = data.length.toInt()
    val out = ByteArray(n)
    if (n > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
    return out
}
