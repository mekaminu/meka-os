package os.meka.android.update

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import os.meka.android.MekaApplication
import os.meka.core.domain.AppUpdateRules
import os.meka.core.facade.AppRelease
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
    data class Failed(val release: AppRelease, val reason: String) : UpdateState
}

/**
 * Self-updating phone app (build plan M1). Asks the server for the newest published build (GitHub publishes one after
 * each green CI run that changed the phone app; the Mac can still publish by hand); when it is newer
 * than this one, Today offers it. Install downloads it chunk by chunk straight into an Android install session,
 * checks the whole file's SHA-256 against what was published, and hands it to Android, which shows its own
 * Install prompt (Meka's one tap) and refuses a build not signed with this app's key. Nothing installs by itself.
 */
class AppUpdater(private val app: MekaApplication) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val prefs = app.getSharedPreferences("meka.update", Context.MODE_PRIVATE)
    private val installing = Mutex()
    @Volatile private var lastCheckMs = 0L
    /** Android's Install prompt, kept until MEKA is on screen to show it (a receiver can't open it from behind). */
    @Volatile private var pendingPrompt: Intent? = null

    val installedCode: Long by lazy { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }
    val installedName: String by lazy { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.1.$installedCode" }

    /** The newest published build's number once asked (null until then, or offline): Appearance's build line. */
    private val _latestCode = MutableStateFlow<Long?>(null)
    val latestCode: StateFlow<Long?> = _latestCode.asStateFlow()

    /** Looks for a newer build at most every [CHECK_EVERY_MS] unless [force]d. Offline: leaves the card as it is. */
    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckMs < CHECK_EVERY_MS) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Confirming) return
        lastCheckMs = now
        val latest = app.core.latestRelease() ?: return
        _latestCode.value = latest.versionCode
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

    suspend fun install(release: AppRelease) {
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return
        }
        if (!installing.tryLock()) return
        try {
            _state.value = UpdateState.Downloading(release, 0)
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(release.sizeBytes)
            }
            val id = withContext(Dispatchers.IO) { installer.createSession(params) }
            val session = installer.openSession(id)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                withContext(Dispatchers.IO) {
                    session.openWrite("meka.apk", 0, release.sizeBytes).use { out ->
                        app.core.downloadRelease(
                            release,
                            sink = { bytes -> digest.update(bytes); out.write(bytes) },
                            progress = { n -> _state.value = UpdateState.Downloading(release, n) },
                        )
                        session.fsync(out)
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != release.sha256) {
                    session.abandon()
                    _state.value = UpdateState.Failed(release, "The download didn't match the published build. Try again.")
                    return
                }
                _state.value = UpdateState.Confirming(release)
                val callback = PendingIntent.getBroadcast(
                    app, id, Intent(app, UpdateStatusReceiver::class.java).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer fills in the status
                )
                session.commit(callback.intentSender)
                session.close()
            } catch (e: CancellationException) {
                runCatching { session.abandon() }
                _state.value = UpdateState.Ready(release)
                throw e
            } catch (e: Exception) {
                runCatching { session.abandon() }
                _state.value = UpdateState.Failed(release, "Couldn't download the update. Check the connection and try again.")
            }
        } finally {
            installing.unlock()
        }
    }

    /** From the install session: show Android's prompt, or say how it went. */
    internal fun onStatus(status: Int, prompt: Intent?) {
        val release = (_state.value as? UpdateState.Confirming)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingPrompt = prompt
                if (app.isOnScreen) showPrompt(null)
            }
            PackageInstaller.STATUS_SUCCESS -> { pendingPrompt = null; _state.value = UpdateState.None }
            else -> {
                pendingPrompt = null
                if (release == null) return
                _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) UpdateState.Ready(release)
                else UpdateState.Failed(release, failureLine(status))
            }
        }
    }

    /** Shows Android's Install prompt if one is waiting (from MEKA's screen when [activity] is given). */
    fun showPrompt(activity: Activity?): Boolean {
        val prompt = pendingPrompt ?: return false
        return runCatching {
            if (activity != null) activity.startActivity(prompt)
            else app.startActivity(Intent(prompt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    val hasPrompt: Boolean get() = pendingPrompt != null

    companion object {
        private const val KEY_LATER = "later"
        const val CHECK_EVERY_MS = 10 * 60_000L

        fun failureLine(status: Int): String = when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "Android refused it: it isn't signed like the MEKA you have (it must carry the debug key of the Mac that installed MEKA)."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space on the phone for the update."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install."
            else -> "Android couldn't install it. Try again."
        }
    }
}

/** Receives the install session's status (exported = false: only the system's installer, through our PendingIntent). */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MekaApplication
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        app.updater.onStatus(status, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
    }
}
