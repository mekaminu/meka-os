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
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
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
import os.meka.core.domain.InstallSessionRules
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
    private val installing get() = INSTALLING
    @Volatile private var lastCheckMs = 0L
    /** Android's Install prompt, kept until MEKA is on screen and the phone unlocked (a receiver can't open it from behind). */
    @Volatile private var pendingPrompt: Intent? = null
    /** The build whose install session is with Android (survives the card's state changing under it). */
    @Volatile private var committed: AppRelease? = null
    /** Download attempts the committed build took (for a failure report). */
    @Volatile private var committedAttempts = 1
    /** The session handed to Android and when (one install at a time: a second tap shows its prompt instead). */
    @Volatile private var committedSession: Int? = null
    @Volatile private var committedAtMs = 0L
    /** Fresh sessions tried by themselves since the last tap ("Session files in use"). */
    @Volatile private var autoRetries = 0
    /** [pendingPrompt] is Android's own installer (the fallback), not a session's prompt. */
    @Volatile private var handedOver = false
    /** The last session read the kept file itself (Android 14+), for the report. */
    @Volatile private var wroteFromFile = false

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
        if (_state.value is UpdateState.Downloading) return
        // Confirming waits for Android, unless its session has gone quiet or vanished (then the card offers Install again).
        if (_state.value is UpdateState.Confirming && (pendingPrompt != null || InstallSessionRules.isLive(outstanding(), mySessions(), now))) return
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
        autoRetries = 0
        app.appScope.launch { install(release) }
    }

    suspend fun install(release: AppRelease) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        if (!installing.tryLock()) return
        try {
            // One install at a time: while Android has the last one (its prompt up or on its way), a tap brings the
            // prompt back instead of making a second session beside it.
            when (InstallSessionRules.onTap(outstanding(), mySessions(), System.currentTimeMillis())) {
                InstallSessionRules.OnTap.SHOW_PROMPT -> { _state.value = UpdateState.Confirming(release); showPrompt(null); return }
                InstallSessionRules.OnTap.WAIT -> { _state.value = UpdateState.Confirming(release); return }
                InstallSessionRules.OnTap.START -> Unit
            }
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
            // A build Android's own installer already took once goes straight back there: its sessions kept failing,
            // and a new round beside Android's install is what "Failed to override installation location" came from.
            if (InstallSessionRules.straightToAndroidsInstaller(handedOverCode(), release.versionCode, AppUpdateRules.isWhole(file.length(), release.sizeBytes)) &&
                handToAndroidsInstaller(release, null, null, report = false)
            ) return
            commit(release, file, attempts.coerceAtLeast(1))
        } catch (e: CancellationException) {
            _state.value = UpdateState.Ready(release)
            throw e
        } catch (e: Exception) {
            // Writing into Android's install session failed (space, a session Android closed): say so with its words.
            val words = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            if (InstallSessionRules.retriesItself(null, words, autoRetries)) {
                autoRetries++
                abandonOwnSessions()
                app.appScope.launch { delay(RETRY_PAUSE_MS); install(release) }
                return
            }
            if (InstallSessionRules.handsToAndroidsInstaller(null, words, autoRetries) && handToAndroidsInstaller(release, null, words)) return
            fail(release, UpdateFailureRules.STAGE_INSTALL, null, InstallSessionRules.reportMessage(words, autoRetries + 1, false, wroteFromFile), 1)
        } finally {
            installing.unlock()
        }
    }

    // ---- Android's install sessions ("Session files in use", 2026-10-13) ----

    private fun mySessions(): List<InstallSessionRules.Session> = runCatching {
        app.packageManager.packageInstaller.mySessions.map { InstallSessionRules.Session(it.sessionId, it.isCommitted) }
    }.getOrDefault(emptyList())

    private fun outstanding(): InstallSessionRules.Outstanding? {
        val id = committedSession ?: return null
        return InstallSessionRules.Outstanding(id, committedAtMs, hasPrompt = pendingPrompt != null)
    }

    /** Abandons MEKA's own sessions left from earlier attempts (all of them unless one still has Android's prompt). */
    private fun abandonOwnSessions(keepOutstanding: Boolean = false) {
        val installer = app.packageManager.packageInstaller
        val ids = InstallSessionRules.toAbandon(mySessions(), if (keepOutstanding) outstanding() else null, System.currentTimeMillis())
        ids.forEach { id -> runCatching { installer.abandonSession(id) } }
        if (committedSession?.let { it in ids } == true) { committedSession = null; pendingPrompt = null }
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
        // Nothing of an earlier attempt may still hold a file: every own session goes before a new one is made.
        abandonOwnSessions(keepOutstanding = false)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(release.sizeBytes)
        }
        val id = withContext(Dispatchers.IO) { installer.createSession(params) }
        val session = installer.openSession(id)
        try {
            withContext(Dispatchers.IO) {
                // Its own name per session, and a committed session is never reopened.
                val name = InstallSessionRules.apkName(release.versionCode, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+: the session reads the kept file itself, so no write stream of ours is open at commit
                    // ("Session files in use" on every fresh session, 17 and 19 Oct).
                    writeFromFile(session, name, file, release.sizeBytes)
                    wroteFromFile = true
                } else {
                    // The stream is closed (use) before commit.
                    session.openWrite(name, 0, release.sizeBytes).use { out ->
                        file.inputStream().use { it.copyTo(out, 256 * 1024) }
                        session.fsync(out)
                    }
                    wroteFromFile = false
                }
            }
            // Android checks the written file has been let go when the session is committed, and the close reaches it
            // asynchronously: committing straight away raced it ("Session files in use", build 942 at 11:12).
            delay(InstallSessionRules.settleMs(autoRetries))
            committed = release
            committedAttempts = attempts
            committedSession = id
            committedAtMs = System.currentTimeMillis()
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

    /** Android 14+: hands the session the kept file to read itself (nothing of ours stays open for it to find). */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun writeFromFile(session: PackageInstaller.Session, name: String, file: File, size: Long) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> session.write(name, 0, size, pfd) }
    }

    /**
     * From the install session: show Android's prompt, or say how it went (with Android's own words). Only the session
     * committed now counts ([sessionId] from Android's own extra): a late "Session destroyed" from one already abandoned,
     * or anything after the build went to Android's installer, is dropped instead of turning the card to a failure.
     */
    internal fun onStatus(status: Int, message: String?, prompt: Intent?, sessionId: Int? = null) {
        if (!InstallSessionRules.isCurrent(sessionId, committedSession)) return
        val release = committed ?: (_state.value as? UpdateState.Confirming)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingPrompt = prompt
                handedOver = false
                // (5) Locked or not on screen: kept, and shown when MEKA is next open and unlocked (MainActivity.onStart).
                if (app.isOnScreen && !locked()) showPrompt(null)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                pendingPrompt = null; committed = null; committedSession = null
                _state.value = UpdateState.None
            }
            else -> {
                pendingPrompt = null; committed = null; committedSession = null
                if (release == null) return
                // (4) Dismissed or closed prompt: back to Install, no error.
                if (!UpdateFailureRules.isFailure(status)) { _state.value = UpdateState.Ready(release); return }
                // "Session files in use": every own session goes and a fresh one is tried once, by itself (the kept
                // download is reused, so nothing is fetched again).
                if (InstallSessionRules.retriesItself(status, message, autoRetries)) {
                    autoRetries++
                    abandonOwnSessions()
                    _state.value = UpdateState.Downloading(release, 0)
                    app.appScope.launch { delay(RETRY_PAUSE_MS); install(release) }
                    return
                }
                // Still "in use" after every fresh session: Android's own installer takes the kept download instead.
                if (InstallSessionRules.handsToAndroidsInstaller(status, message, autoRetries) && handToAndroidsInstaller(release, status, message)) return
                fail(release, UpdateFailureRules.STAGE_INSTALL, status, InstallSessionRules.reportMessage(message, autoRetries + 1, false, wroteFromFile), committedAttempts)
            }
        }
    }

    /**
     * Hands the kept, checked download to Android's own installer (it makes its own session and shows the same Install
     * prompt), shown now if Navilife is on screen and unlocked, else when it next is. Reported to the server (not Activity
     * or Health: nothing failed for Meka) so a build run sees how often it was needed, unless [report] is false (a later
     * tap going straight there). False if there's no whole file. The build is remembered, so its next tap goes straight
     * back to Android's installer ([InstallSessionRules.straightToAndroidsInstaller]).
     */
    private fun handToAndroidsInstaller(release: AppRelease, status: Int?, message: String?, report: Boolean = true): Boolean {
        val file = downloadFile(release)
        if (!AppUpdateRules.isWhole(file.length(), release.sizeBytes)) return false
        val intent = runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }.getOrNull() ?: return false
        // committedSession goes first, so the abandoned sessions' late answers are dropped (onStatus).
        committed = null; committedSession = null
        abandonOwnSessions()
        prefs.edit().putLong(KEY_HANDED_OVER, release.versionCode).apply()
        pendingPrompt = intent
        handedOver = true
        _state.value = UpdateState.Confirming(release)
        if (app.isOnScreen && !locked()) showPrompt(null)
        if (!report) return true
        val failure = UpdateFailure(
            stage = UpdateFailureRules.STAGE_INSTALL, status = status,
            message = UpdateFailureRules.cleanMessage(InstallSessionRules.reportMessage(message, autoRetries + 1, handedOver = true, fromFile = wroteFromFile)),
            fromCode = installedCode, toCode = release.versionCode, atMs = System.currentTimeMillis(),
            storage = UpdateFailureRules.storageBand(freeBytes()), network = network(), attempts = committedAttempts,
        )
        app.appScope.launch { runCatching { app.core.reportUpdateFailure(failure) } }
        return true
    }

    private fun handedOverCode(): Long? = prefs.getLong(KEY_HANDED_OVER, -1).takeIf { it > 0 }

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
        val shown = runCatching {
            if (activity != null) activity.startActivity(prompt)
            else app.startActivity(Intent(prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        // Android's own installer reports nothing back: once it is up, the next check offers Install again if the
        // build still isn't in (dismissed), and the new build's first run clears the card.
        if (shown && handedOver) { pendingPrompt = null; handedOver = false }
        return shown
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
        noteIconOnce(hadEarlierBuild = recorded != null && recorded < installedCode)
        if (!AppUpdateRules.shouldRecordInstall(installedCode, recorded)) return
        val size = runCatching { File(app.applicationInfo.sourceDir).length() }.getOrDefault(0L).takeIf { it > 0 } ?: return
        val previousSize = prefs.getLong(KEY_SIZED_BYTES, -1).takeIf { it > 0 }
        runCatching { app.core.recordInstalledBuild(installedCode, size, recorded, previousSize) }
        prefs.edit().putLong(KEY_SIZED_CODE, installedCode).putLong(KEY_SIZED_BYTES, size)
            .putLong(KEY_PREVIOUS_BYTES, previousSize ?: -1).apply()
    }

    /** App icon → option B: the first run of an update that changed the icon says so once in Activity. */
    private suspend fun noteIconOnce(hadEarlierBuild: Boolean) {
        val noted = prefs.getString(KEY_ICON, null)
        if (noted == AppUpdateRules.ICON) return
        if (AppUpdateRules.shouldNoteIcon(noted, hadEarlierBuild)) {
            if (runCatching { app.core.recordIconChanged() }.isFailure) return
        }
        prefs.edit().putString(KEY_ICON, AppUpdateRules.ICON).apply()
    }

    /** Health's Updates row: this build's size on the phone ("10.3 MB"), null if Android won't say. */
    val installedSizeBytes: Long?
        get() = runCatching { File(app.applicationInfo.sourceDir).length() }.getOrNull()?.takeIf { it > 0 }

    /** The build before's size, as recorded when this build first ran ("· was 55.1 MB"). */
    val previousSizeBytes: Long?
        get() = prefs.getLong(KEY_PREVIOUS_BYTES, -1).takeIf { it > 0 }

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
        private const val KEY_ICON = "icon_noted"
        private const val KEY_SIZED_BYTES = "sized_bytes"
        private const val KEY_PREVIOUS_BYTES = "previous_bytes"
        /** The build last handed to Android's own installer (its next tap goes straight there). */
        private const val KEY_HANDED_OVER = "handed_over"
        const val CHECK_EVERY_MS = 10 * 60_000L
        /** A breath before a fresh session after "files in use", so Android finishes abandoning the old ones. */
        private const val RETRY_PAUSE_MS = 1_500L
        private const val APK_TYPE = "application/vnd.android.package-archive"
        /** One install at a time across the whole app (Today's Install, Health's Try again, a retry). */
        private val INSTALLING = Mutex()
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
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 },
        )
    }
}
