package os.meka.android.update

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import os.meka.android.MekaApplication
import os.meka.core.domain.AppUpdateRules
import os.meka.core.domain.UpdateFailure
import os.meka.core.domain.UpdateFailureRules
import os.meka.core.facade.AppRelease
import os.meka.core.facade.ReleaseTransfer
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** What Today's update card shows. */
sealed interface UpdateState {
    data object None : UpdateState
    data class Ready(val release: AppRelease) : UpdateState
    /** Android hasn't let MEKA install apps yet ("Install unknown apps" for MEKA, asked once). */
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val chunksDone: Int) : UpdateState
    /** Downloaded and checked; Android's own Install prompt is up (or waiting for MEKA to be on screen). */
    data class Confirming(val release: AppRelease) : UpdateState
    /** [reason] in Meka's words; [detail] is Android's own (status and message), shown smaller under it. */
    data class Failed(val release: AppRelease, val reason: String, val detail: String? = null) : UpdateState
}

/**
 * Self-updating phone app (build plan M1). Asks the server for the newest published build (GitHub publishes one after
 * each green CI run that changed the phone app; the Mac can still publish by hand); when it is newer
 * than this one, Today offers it. Install downloads it chunk by chunk into a kept file (a broken download resumes and is
 * tried [UpdateFailureRules.DOWNLOAD_ATTEMPTS] times; Try again reuses a whole file whose hash matches), checks the
 * whole file's SHA-256 against what was published, and hands it to Android, which shows its own Install prompt
 * (Meka's one tap) and refuses a build not signed with this app's key. Nothing installs by itself.
 *
 * When it doesn't go in (build plan: phone updates sometimes fail to install), Android's own status and message are
 * kept and shown, written to Activity, kept for Health, and reported to MEKA's server so a build run can read why.
 */
class AppUpdater(private val app: MekaApplication) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val prefs = app.getSharedPreferences("meka.update", Context.MODE_PRIVATE)
    private val installing = Mutex()
    @Volatile private var lastCheckMs = 0L
    /** Android's Install prompt, kept until MEKA is on screen and the phone unlocked (a receiver can't open it from behind). */
    @Volatile private var pendingPrompt: Intent? = null
    /** The build whose install session is with Android (survives the card's state changing under it). */
    @Volatile private var committed: AppRelease? = null
    /** Download attempts the committed build took (for a failure report). */
    @Volatile private var committedAttempts = 1

    val installedCode: Long by lazy { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }
    val installedName: String by lazy { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.1.$installedCode" }

    /** The newest published build's number once asked (null until then, or offline): Appearance's build line. */
    private val _latestCode = MutableStateFlow<Long?>(null)
    val latestCode: StateFlow<Long?> = _latestCode.asStateFlow()

    private val _lastFailure = MutableStateFlow(readFailure())
    /** The last update that didn't go in (kept across restarts) for Health; cleared once that build is installed. */
    val lastFailure: StateFlow<UpdateFailure?> = _lastFailure.asStateFlow()

    /** Looks for a newer build at most every [CHECK_EVERY_MS] unless [force]d. Offline: leaves the card as it is. */
    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMs < CHECK_EVERY_MS) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Confirming) return
        lastCheckMs = now
        recordInstalledOnce()
        forgetInstalledFailure()
        sendUnreportedFailure()
        val latest = app.core.latestRelease() ?: return
        _latestCode.value = latest.versionCode
        clearOldDownloads(keep = latest.versionCode)
        val offer = AppUpdateRules.offer(latest.build, installedCode, prefs.getLong(KEY_LATER, -1).takeIf { it > 0 })
        val current = _state.value
        _state.value = when {
            offer == null -> UpdateState.None
            current is UpdateState.Failed && current.release == latest -> current
            current is UpdateState.NeedsPermission && current.release == latest -> current
            else -> UpdateState.Ready(latest)
        }
        // A quiet note once per build while MEKA isn't on screen (GitHub publishes builds by itself now).
        UpdateNotice.onOffer(app, offer, app.isOnScreen)
    }

    /** "Later": hides this build; a newer one is offered again. */
    fun later(release: AppRelease) {
        prefs.edit().putLong(KEY_LATER, release.versionCode).apply()
        UpdateNotice.cancel(app)
        _state.value = UpdateState.None
    }

    /** Opens Android's "Install unknown apps" switch for MEKA. */
    fun openPermission(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
    }

    /**
     * Install, from a tap. Runs in the app's own scope, not the screen's: leaving Today (or the fold changing) mid-download
     * used to cancel it and put the card back to Install.
     */
    fun start(release: AppRelease) {
        app.appScope.launch { install(release) }
    }

    suspend fun install(release: AppRelease) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        if (!installing.tryLock()) return
        try {
            _state.value = UpdateState.Downloading(release, 0)
            val file = downloadFile(release)
            var attempts = 0
            var hex: String? = null
            // (6) A whole file kept from an earlier try is reused when its hash matches.
            if (AppUpdateRules.isWhole(file.length(), release.sizeBytes)) {
                hex = withContext(Dispatchers.IO) { sha256(file) }
                if (hex != release.sha256) { file.delete(); hex = null }
            } else if (file.length() > release.sizeBytes) {
                file.delete()
            }
            if (hex == null) {
                // (3) A broken download resumes from the whole chunks it has and is tried again after a pause.
                while (true) {
                    attempts++
                    val error = try {
                        download(release, file)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e
                    }
                    if (error == null) break
                    if (!UpdateFailureRules.retries(attempts)) {
                        fail(release, UpdateFailureRules.STAGE_DOWNLOAD, null, error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""), attempts)
                        return
                    }
                    delay(UpdateFailureRules.retryDelayMs(attempts + 1))
                }
                hex = withContext(Dispatchers.IO) { sha256(file) }
                if (hex != release.sha256) {
                    file.delete()
                    fail(release, UpdateFailureRules.STAGE_CHECK, null, "SHA-256 ${hex.take(12)}… ≠ ${release.sha256.take(12)}…", attempts)
                    return
                }
            }
            commit(release, file, attempts.coerceAtLeast(1))
        } catch (e: CancellationException) {
            _state.value = UpdateState.Ready(release)
            throw e
        } catch (e: Exception) {
            // Writing into Android's install session failed (space, a session Android closed): say so with its words.
            fail(release, UpdateFailureRules.STAGE_INSTALL, null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), 1)
        } finally {
            installing.unlock()
        }
    }

    private suspend fun download(release: AppRelease, file: File) {
        val from = AppUpdateRules.resumeFrom(file.length(), release.sizeBytes, ReleaseTransfer.CHUNK_BYTES)
        withContext(Dispatchers.IO) {
            // Keep only whole chunks; append the rest.
            java.io.RandomAccessFile(file, "rw").use { it.setLength(from.toLong() * ReleaseTransfer.CHUNK_BYTES) }
            FileOutputStream(file, true).use { out ->
                _state.value = UpdateState.Downloading(release, from)
                app.core.downloadRelease(
                    release,
                    sink = { bytes -> out.write(bytes) },
                    progress = { n -> _state.value = UpdateState.Downloading(release, n) },
                    fromChunk = from,
                )
                out.fd.sync()
            }
        }
    }

    private suspend fun commit(release: AppRelease, file: File, attempts: Int) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(release.sizeBytes)
        }
        val id = withContext(Dispatchers.IO) { installer.createSession(params) }
        val session = installer.openSession(id)
        try {
            withContext(Dispatchers.IO) {
                session.openWrite("meka.apk", 0, release.sizeBytes).use { out ->
                    file.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
            }
            committed = release
            committedAttempts = attempts
            _state.value = UpdateState.Confirming(release)
            val callback = PendingIntent.getBroadcast(
                app, id, Intent(app, UpdateStatusReceiver::class.java).setPackage(app.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer fills in the status
            )
            session.commit(callback.intentSender)
            session.close()
        } catch (e: Exception) {
            runCatching { session.abandon() }
            throw e
        }
    }

    /** From the install session: show Android's prompt, or say how it went (with Android's own words). */
    internal fun onStatus(status: Int, message: String?, prompt: Intent?) {
        val release = committed ?: (_state.value as? UpdateState.Confirming)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingPrompt = prompt
                // (5) Locked or not on screen: kept, and shown when MEKA is next open and unlocked (MainActivity.onStart).
                if (app.isOnScreen && !locked()) showPrompt(null)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                pendingPrompt = null; committed = null
                _state.value = UpdateState.None
            }
            else -> {
                pendingPrompt = null; committed = null
                if (release == null) return
                // (4) Dismissed or closed prompt: back to Install, no error.
                if (!UpdateFailureRules.isFailure(status)) { _state.value = UpdateState.Ready(release); return }
                fail(release, UpdateFailureRules.STAGE_INSTALL, status, message, committedAttempts)
            }
        }
    }

    private fun fail(release: AppRelease, stage: String, status: Int?, message: String?, attempts: Int) {
        val f = UpdateFailure(
            stage = stage, status = status, message = UpdateFailureRules.cleanMessage(message),
            fromCode = installedCode, toCode = release.versionCode, atMs = System.currentTimeMillis(),
            storage = UpdateFailureRules.storageBand(freeBytes()), network = network(), attempts = attempts,
        )
        _state.value = UpdateState.Failed(release, UpdateFailureRules.line(f), UpdateFailureRules.detail(f))
        keepFailure(f, reported = false)
        app.appScope.launch {
            val sent = runCatching { app.core.recordUpdateFailure(f) }.getOrDefault(false)
            if (sent && _lastFailure.value == f) keepFailure(f, reported = true)
        }
    }

    /** Shows Android's Install prompt if one is waiting (from MEKA's screen when [activity] is given). */
    fun showPrompt(activity: Activity?): Boolean {
        val prompt = pendingPrompt ?: return false
        if (locked()) return false
        return runCatching {
            if (activity != null) activity.startActivity(prompt)
            else app.startActivity(Intent(prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    val hasPrompt: Boolean get() = pendingPrompt != null

    private fun locked(): Boolean = runCatching { app.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)

    private fun freeBytes(): Long? = runCatching { StatFs(app.filesDir.path).availableBytes }.getOrNull()

    private fun network(): String = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    }.getOrDefault("other")

    // ---- The kept download ----

    private fun downloadDir(): File = File(app.cacheDir, "update").apply { mkdirs() }

    private fun downloadFile(release: AppRelease): File = File(downloadDir(), "meka-${release.versionCode}.apk")

    /** Drops kept downloads of any build but [keep]. */
    private fun clearOldDownloads(keep: Long) {
        runCatching { downloadDir().listFiles()?.filter { it.name != "meka-$keep.apk" }?.forEach { it.delete() } }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- The kept failure (Health, and a report that couldn't go yet) ----

    private fun keepFailure(f: UpdateFailure?, reported: Boolean) {
        _lastFailure.value = f
        val e = prefs.edit()
        if (f == null) {
            FAILURE_KEYS.forEach { e.remove(it) }
        } else {
            e.putString(F_STAGE, f.stage).putInt(F_STATUS, f.status ?: NO_STATUS).putString(F_MESSAGE, f.message)
                .putLong(F_FROM, f.fromCode).putLong(F_TO, f.toCode).putLong(F_AT, f.atMs).putString(F_STORAGE, f.storage)
                .putString(F_NETWORK, f.network).putInt(F_ATTEMPTS, f.attempts).putBoolean(F_REPORTED, reported)
        }
        e.apply()
    }

    private fun readFailure(): UpdateFailure? {
        val stage = prefs.getString(F_STAGE, null) ?: return null
        return UpdateFailure(
            stage = stage, status = prefs.getInt(F_STATUS, NO_STATUS).takeIf { it != NO_STATUS }, message = prefs.getString(F_MESSAGE, null),
            fromCode = prefs.getLong(F_FROM, 0), toCode = prefs.getLong(F_TO, 0), atMs = prefs.getLong(F_AT, 0),
            storage = prefs.getString(F_STORAGE, null), network = prefs.getString(F_NETWORK, null), attempts = prefs.getInt(F_ATTEMPTS, 1),
        )
    }

    /**
     * Smaller phone updates: the first check on a new build writes "Updated to build 471 · 18.2 MB" (and the change
     * from the build before) to Activity, once per build.
     */
    private suspend fun recordInstalledOnce() {
        val recorded = prefs.getLong(KEY_SIZED_CODE, -1).takeIf { it > 0 }
        if (!AppUpdateRules.shouldRecordInstall(installedCode, recorded)) return
        val size = runCatching { File(app.applicationInfo.sourceDir).length() }.getOrDefault(0L).takeIf { it > 0 } ?: return
        val previousSize = prefs.getLong(KEY_SIZED_BYTES, -1).takeIf { it > 0 }
        runCatching { app.core.recordInstalledBuild(installedCode, size, recorded, previousSize) }
        prefs.edit().putLong(KEY_SIZED_CODE, installedCode).putLong(KEY_SIZED_BYTES, size).apply()
    }

    /** Once the failed build (or a newer one) is installed, Health no longer shows it. */
    private fun forgetInstalledFailure() {
        val f = _lastFailure.value ?: return
        if (f.toCode <= installedCode && prefs.getBoolean(F_REPORTED, false)) keepFailure(null, reported = true)
    }

    /** A failure that couldn't be reported (offline) goes on the next check. */
    private suspend fun sendUnreportedFailure() {
        val f = _lastFailure.value ?: return
        if (prefs.getBoolean(F_REPORTED, false)) return
        if (runCatching { app.core.reportUpdateFailure(f) }.getOrDefault(false) && _lastFailure.value == f) keepFailure(f, reported = true)
    }

    companion object {
        private const val KEY_LATER = "later"
        private const val KEY_SIZED_CODE = "sized_code"
        private const val KEY_SIZED_BYTES = "sized_bytes"
        const val CHECK_EVERY_MS = 10 * 60_000L
        private const val NO_STATUS = Int.MIN_VALUE
        private const val F_STAGE = "failure.stage"
        private const val F_STATUS = "failure.status"
        private const val F_MESSAGE = "failure.message"
        private const val F_FROM = "failure.from"
        private const val F_TO = "failure.to"
        private const val F_AT = "failure.at"
        private const val F_STORAGE = "failure.storage"
        private const val F_NETWORK = "failure.network"
        private const val F_ATTEMPTS = "failure.attempts"
        private const val F_REPORTED = "failure.reported"
        private val FAILURE_KEYS = listOf(F_STAGE, F_STATUS, F_MESSAGE, F_FROM, F_TO, F_AT, F_STORAGE, F_NETWORK, F_ATTEMPTS, F_REPORTED)
    }
}

/** Receives the install session's status (exported = false: only the system's installer, through our PendingIntent). */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MekaApplication
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        app.updater.onStatus(
            status,
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
        )
    }
}
