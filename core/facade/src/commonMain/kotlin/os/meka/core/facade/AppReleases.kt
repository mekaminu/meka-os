package os.meka.core.facade

import os.meka.core.domain.AppUpdateRules
import os.meka.core.sync.AuthRejectedException
import os.meka.core.sync.TransportException
import os.meka.core.wire.WireCodec
import kotlin.coroutines.cancellation.CancellationException

/** A published app build (self-updating phone app, build plan M1). */
data class AppRelease(
    val platform: String,
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val sizeBytes: Long,
    val chunkCount: Int,
) {
    val build: AppUpdateRules.Build get() = AppUpdateRules.Build(versionCode, versionName, sizeBytes)
}

/** The server refused a publish (not newer, the same build number with other contents, or no signing key). */
class PublishRefusedException(val reason: String) : Exception(reason)

/** Release calls, available once the device is connected and its signing key is registered. */
interface ReleasesApi {
    suspend fun latestRelease(platform: String): AppRelease?
    suspend fun releaseChunk(platform: String, versionCode: Long, index: Int): ByteArray
    /** Returns true once the server has every chunk and the whole file matched its hash. */
    suspend fun uploadReleaseChunk(release: AppRelease, index: Int, bytes: ByteArray): Boolean
}

sealed class PublishOutcome {
    data class Published(val release: AppRelease) : PublishOutcome()
    data class Refused(val reason: String) : PublishOutcome()
    data class Failed(val reason: String) : PublishOutcome()

    /** One line for the publisher's screen. */
    val message: String get() = when (this) {
        is Published -> "Published build ${release.versionCode} to the Fold. MEKA there offers it the next time it opens, or within 15 minutes."
        is Refused -> "Not published: $reason."
        is Failed -> reason
    }
}

/** Splitting a build into chunks, publishing it and fetching it back. */
object ReleaseTransfer {
    const val ANDROID = "android"
    const val ANDROID_APP_ID = "os.meka.android"
    private const val ATTEMPTS = 3

    fun describe(platform: String, bytes: ByteArray, versionCode: Long, versionName: String): AppRelease {
        val size = bytes.size.toLong()
        return AppRelease(platform, versionCode, versionName, Sha256.hex(bytes), size, WireCodec.releaseChunkCount(size))
    }

    /** Bytes of chunk [index] of [bytes]. */
    fun chunkOf(bytes: ByteArray, index: Int): ByteArray {
        val from = index * WireCodec.RELEASE_CHUNK_BYTES
        return bytes.copyOfRange(from, from + WireCodec.releaseChunkSize(bytes.size.toLong(), index))
    }

    /**
     * Publishes [bytes] chunk by chunk, retrying each a few times on network errors (the server keeps what arrived,
     * so a retry never starts over). The build is offered only once the server has checked the whole file's hash.
     */
    suspend fun publish(api: ReleasesApi, platform: String, bytes: ByteArray, versionCode: Long, versionName: String): PublishOutcome {
        if (bytes.isEmpty() || bytes.size > WireCodec.RELEASE_MAX_BYTES) return PublishOutcome.Refused("the file is empty or too large")
        val release = describe(platform, bytes, versionCode, versionName)
        var complete = false
        for (i in 0 until release.chunkCount) {
            val chunk = chunkOf(bytes, i)
            var attempt = 0
            while (true) {
                try {
                    complete = api.uploadReleaseChunk(release, i, chunk)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PublishRefusedException) {
                    return PublishOutcome.Refused(e.reason)
                } catch (e: AuthRejectedException) {
                    return PublishOutcome.Failed("The server didn't accept this device. Reconnect the Mac and try again.")
                } catch (e: TransportException) {
                    if (++attempt >= ATTEMPTS) return PublishOutcome.Failed("Couldn't reach the server. Try again; what arrived is kept.")
                } catch (e: Exception) {
                    return PublishOutcome.Failed("The server didn't accept this device. Reconnect the Mac and try again.")
                }
            }
        }
        return if (complete) PublishOutcome.Published(release) else PublishOutcome.Failed("The server didn't confirm the build. Try again.")
    }

    /**
     * Fetches [release] chunk by chunk into [sink], with [progress] after each one (chunks done). Each chunk is retried
     * a few times on network errors; a chunk of the wrong size stops the download. The caller checks the whole
     * file's hash before installing.
     */
    suspend fun download(api: ReleasesApi, release: AppRelease, sink: suspend (ByteArray) -> Unit, progress: (Int) -> Unit) {
        for (i in 0 until release.chunkCount) {
            val bytes = retrying { api.releaseChunk(release.platform, release.versionCode, i) }
            if (bytes.size != WireCodec.releaseChunkSize(release.sizeBytes, i)) throw TransportException("chunk $i has the wrong size")
            sink(bytes)
            progress(i + 1)
        }
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: AuthRejectedException) {
                throw e
            } catch (e: TransportException) {
                if (++attempt >= ATTEMPTS) throw e
            }
        }
    }

    /**
     * Checks that [apk] with the Gradle metadata beside it ([metadataJson]) is a MEKA phone build. Returns the
     * metadata, or a reason it can't be published.
     */
    internal fun checkApk(apk: ByteArray?, metadataJson: String?): Pair<WireCodec.ApkMetadata?, String?> {
        if (apk == null) return null to "That file couldn't be read."
        if (apk.size < 4 || apk[0] != 'P'.code.toByte() || apk[1] != 'K'.code.toByte()) return null to "That file isn't an APK."
        val meta = metadataJson?.let { WireCodec.decodeApkMetadata(it) }
            ?: return null to "There's no Gradle output-metadata.json beside the APK. Build it with tools/publish-fold.sh."
        if (meta.applicationId != ANDROID_APP_ID) return null to "That APK isn't MEKA (${meta.applicationId.take(60)})."
        return meta to null
    }
}
