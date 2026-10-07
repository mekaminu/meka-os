package os.meka.android

import android.app.Application
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import os.meka.android.security.AndroidDeviceKey
import os.meka.android.security.DatabaseKeyStore
import os.meka.android.sync.SyncWorker
import os.meka.android.notify.NotificationGovernor
import os.meka.android.notify.OngoingNotifier
import os.meka.android.notify.OngoingRouting
import os.meka.android.shell.ShellDestination
import os.meka.android.update.AppUpdater
import os.meka.android.widgets.HomeWidgetUpdater
import os.meka.android.widgets.WidgetRouting
import os.meka.android.work.AfterWorkNudger
import os.meka.android.work.CaptureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import os.meka.core.data.AndroidDatabase
import os.meka.core.data.SqlReplicaStore
import os.meka.core.facade.Enrolment
import os.meka.core.facade.EnrolmentResult
import os.meka.core.facade.MekaCore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.random.asKotlinRandom

class MekaApplication : Application() {
    lateinit var core: MekaCore
        private set
    lateinit var identity: DeviceIdentityStore
        private set
    /** Work mode's held messages and people lists; on this phone only, never synced. */
    val captures: CaptureStore by lazy { CaptureStore(this) }
    /** "Your after-work summary is ready" when work mode ends with something held. */
    val nudger: AfterWorkNudger by lazy { AfterWorkNudger(this, this) }
    /** Notification governor: tiers, quiet hours and the two digests, posted on this phone. */
    val governor: NotificationGovernor by lazy { NotificationGovernor(this, this) }
    /** Ongoing notifications: the next event's countdown and a running fast. */
    val ongoing: OngoingNotifier by lazy { OngoingNotifier(this, this) }
    /** Home-screen widgets: Next up, Needs you and Fast. */
    val widgets: HomeWidgetUpdater by lazy { HomeWidgetUpdater(this, this) }
    /** Self-updating phone app: newer builds the Mac published, offered in Today. */
    val updater: AppUpdater by lazy { AppUpdater(this) }
    /** Set by tapping a MEKA notification: the shell opens this destination. */
    val openDestination = MutableStateFlow<ShellDestination?>(null)
    /** Set by tapping a search result: Lists or Goals opens the right tab and unfolds the row, then clears it. */
    val openItem = MutableStateFlow<os.meka.android.shell.OpenItem?>(null)
    /** MainActivity is visible (set in onStart/onStop): Meka is looking, so no nudge. */
    @Volatile var isOnScreen: Boolean = false
    /** Set by the nudge's tap: the shell opens Needs you with the after-work summary. */
    val openAfterWork = MutableStateFlow(false)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Hardware-held signing key; created on first use (ADR-005). */
    private val deviceKey by lazy { AndroidDeviceKey() }
    // Read timeout above the server's 20 s long-poll window (OkHttp's default is 10 s).
    private val http by lazy { HttpClient(OkHttp) { engine { config { readTimeout(45, TimeUnit.SECONDS) } } } }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        identity = DeviceIdentityStore(this)
        val key = DatabaseKeyStore(this).getOrCreateKey()
        val driver = AndroidDatabase.open(this, key) // the driver keeps its own copy
        key.fill(0)

        val url = identity.serverUrl()
        val secret = identity.deviceSecret()
        val transport = if (url != null && secret != null) Enrolment.transport(http, url, secret, deviceKey) else null
        core = MekaCore(
            householdId = identity.householdId(),
            deviceId = identity.deviceId(),
            store = SqlReplicaStore(driver),
            transport = transport,
            secureRandom = SecureRandom().asKotlinRandom(),
        )
        if (transport != null) SyncWorker.schedulePeriodic(this)
        // Every work-mode change on this phone (clock tick, sync, listener) goes past the nudger. Also runs once at
        // process start (after a reboot the listener's rebind starts us), which re-registers the end-of-work alarm.
        appScope.launch { core.workMode.collect { nudger.evaluate(it) } }
        // The governor looks again whenever what it reads changes (settled for a moment, so a burst of edits or a
        // sync is one evaluation), and once at process start, which re-arms its alarm. Time is its own alarm.
        appScope.launch {
            merge(
                core.listsView.map { }, core.fastingView.map { }, core.shutdownView.map { }, core.briefView.map { }, core.today.map { },
                core.reviewView.map { it.card }.distinctUntilChanged().map { },
                core.notificationSettings.map { }, governor.device.map { },
                // Event reminders: a reminder set or changed, or an event moved on the server.
                core.eventMarks.map { }, core.calendarView.map { },
            ).debounce(GOVERNOR_SETTLE_MS).collect { runCatching { governor.run() } }
        }
        // Ongoing notifications follow Today and the fast, but only re-post when what they show changes (Today moves
        // every minute; the system ticks their clocks). Also once at process start, which re-arms their alarm.
        appScope.launch {
            merge(core.today.map { }, core.fastingView.map { })
                .map { OngoingRouting.signature(core.ongoing()) }
                .distinctUntilChanged()
                .collect { runCatching { ongoing.run() } }
        }
        // Home-screen widgets the same way: redrawn only when what they say changes (the launcher ticks their
        // clocks), and once at process start, which re-arms their alarm. Nothing runs without a MEKA widget placed.
        appScope.launch {
            merge(core.today.map { }, core.fastingView.map { }, core.listsView.map { }, core.needsYouStack.map { })
                .map { WidgetRouting.signature(core.homeWidgets()) }
                .distinctUntilChanged()
                .collect { runCatching { widgets.run() } }
        }
    }

    companion object {
        private const val GOVERNOR_SETTLE_MS = 2_000L
    }

    /** Looks for a newer published build (at most every 10 minutes unless [force]d); never installs anything. */
    fun lookForUpdate(force: Boolean = false) {
        if (!core.isConnected) return
        appScope.launch { runCatching { updater.check(force) } }
    }

    val defaultServerUrl: String get() = identity.serverUrl() ?: BuildConfig.SYNC_URL

    /** One-time enrolment from the Connect card. Returns a user-facing error, or null on success. */
    suspend fun connect(serverUrl: String, enrolCode: String): String? {
        val url = serverUrl.trim().trimEnd('/')
        return when (val r = Enrolment.enrol(http, url, enrolCode, identity.householdId(), identity.deviceId(), Build.MODEL ?: "Android")) {
            is EnrolmentResult.Enrolled -> {
                identity.saveEnrolment(url, r.deviceSecret)
                core.connect(Enrolment.transport(http, url, r.deviceSecret, deviceKey))
                SyncWorker.schedulePeriodic(this)
                null
            }
            EnrolmentResult.Rejected -> "That enrolment code wasn't accepted."
            is EnrolmentResult.Failed -> r.reason
        }
    }
}
