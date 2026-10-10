package os.meka.wear

import android.app.Application
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import os.meka.core.data.AndroidDatabase
import os.meka.core.data.SqlReplicaStore
import os.meka.core.domain.WatchLinkRules
import os.meka.core.facade.Enrolment
import os.meka.core.facade.MekaCore
import os.meka.core.facade.WatchLink
import os.meka.wear.security.DatabaseKeyStore
import os.meka.wear.security.WatchDeviceKey
import os.meka.wear.surfaces.WatchSurfaces
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.random.asKotlinRandom

/** Where linking stands while the watch has no household yet. */
sealed class LinkState {
    /** This build wasn't given MEKA's server address (tools/install-watch.sh passes it). */
    object NoServer : LinkState()
    object Asking : LinkState()
    /** The code on screen; [ranOut] once the server says it has gone (tap for a new one). */
    data class Showing(val pending: WatchIdentity.Pending, val ranOut: Boolean = false) : LinkState()
    data class Failed(val reason: String) : LinkState()
}

/**
 * MEKA on the Galaxy Watch (Galaxy Watch, slice 2). Unlinked, it makes its key, asks MEKA's server for a code and waits
 * while Meka types it on the Fold or the Mac; linked, it runs [MekaCore] over its own encrypted replica like any other
 * device. Nothing goes through Google's Wearable Data Layer or the phone's app (ADR-005 amendment 2026-10-10).
 */
class WatchApplication : Application() {
    lateinit var identity: WatchIdentity
        private set

    private val _core = MutableStateFlow<MekaCore?>(null)
    /** The day, once linked; null while linking. */
    val core: StateFlow<MekaCore?> = _core.asStateFlow()

    private val _link = MutableStateFlow<LinkState>(LinkState.Asking)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var deviceKey: WatchDeviceKey? = null
    private var driver: SqlDriver? = null
    private var linking: Job? = null
    private var surfaces: Job? = null
    // Read timeout above the server's 20 s long-poll window (OkHttp's default is 10 s).
    private val http by lazy { HttpClient(OkHttp) { engine { config { readTimeout(45, TimeUnit.SECONDS) } } } }

    val serverUrl: String get() = BuildConfig.SYNC_URL.trim().trimEnd('/')

    override fun onCreate() {
        super.onCreate()
        identity = WatchIdentity(this)
        identity.linked()?.let { open(it) }
    }

    private fun key(): WatchDeviceKey = deviceKey ?: WatchDeviceKey().also { deviceKey = it }

    private fun open(l: WatchIdentity.Linked) {
        val dbKey = DatabaseKeyStore(this).getOrCreateKey()
        val d = AndroidDatabase.open(this, dbKey) // the driver keeps its own copy
        dbKey.fill(0)
        driver = d
        _core.value = MekaCore(
            householdId = l.householdId,
            deviceId = l.deviceId,
            store = SqlReplicaStore(d),
            transport = Enrolment.transport(http, l.serverUrl, l.secret, key()),
            secureRandom = SecureRandom().asKotlinRandom(),
        )
        watchSurfaces(_core.value!!)
    }

    /**
     * Galaxy Watch, slice 3: the tile and complication follow the day. A quarter-hourly background sync keeps them
     * current while MEKA isn't on screen, and any change to Today or the fast (a sync, a tap on the watch) draws them
     * again a moment later.
     */
    private fun watchSurfaces(core: MekaCore) {
        WatchSurfaces.scheduleSync(this)
        surfaces?.cancel()
        surfaces = appScope.launch {
            combine(core.today, core.fastingView) { t, f -> t to f }.collectLatest {
                delay(SURFACE_SETTLE_MS)
                WatchSurfaces.refresh(this@WatchApplication)
            }
        }
    }

    /**
     * Shows a code and waits for Meka to type it (while the screen is open: [stopLinking] when it goes). Picks up the
     * code already on screen if it hasn't run out; [fresh] asks for a new one.
     */
    fun startLinking(fresh: Boolean = false) {
        if (_core.value != null || linking?.isActive == true) return
        if (serverUrl.isEmpty()) { _link.value = LinkState.NoServer; return }
        if (fresh) identity.clearPending()
        linking = appScope.launch {
            try {
                var pending = identity.pending()?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
                if (pending == null) {
                    _link.value = LinkState.Asking
                    pending = when (val s = WatchLink.start(http, serverUrl, key(), identity.deviceId(), deviceName(), System.currentTimeMillis())) {
                        is WatchLink.Started.Code -> WatchIdentity.Pending(s.linkId, s.code, s.expiresAtMs).also(identity::savePending)
                        WatchLink.Started.Busy -> { _link.value = LinkState.Failed(BUSY); return@launch }
                        is WatchLink.Started.Failed -> { _link.value = LinkState.Failed(s.reason); return@launch }
                    }
                }
                _link.value = LinkState.Showing(pending)
                while (System.currentTimeMillis() < pending.expiresAtMs) {
                    delay(POLL_MS)
                    when (val s = WatchLink.status(http, serverUrl, key(), pending.linkId, System.currentTimeMillis())) {
                        is WatchLink.Status.Linked -> {
                            identity.saveLinked(serverUrl, s.householdId, s.deviceId, s.secret)
                            identity.linked()?.let { open(it) }
                            return@launch
                        }
                        WatchLink.Status.Expired -> break
                        WatchLink.Status.Waiting, is WatchLink.Status.Failed -> Unit // a missed look; try again
                    }
                }
                identity.clearPending()
                _link.value = LinkState.Showing(pending, ranOut = true) // the screen says so: tap for a new code
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _link.value = LinkState.Failed(WatchLinkRules.OFFLINE)
            }
        }
    }

    fun stopLinking() { linking?.cancel(); linking = null }

    /**
     * Unlinked on the Fold or the Mac (the server signs the watch out): forget the household, the replica and the key,
     * so the watch can be linked again as a new one.
     */
    fun forgetAndRelink() {
        surfaces?.cancel(); surfaces = null
        WatchSurfaces.cancelSync(this)
        _core.value?.let { it.stopSync(); it.close() }
        _core.value = null
        WatchSurfaces.refresh(this) // the tile and complication say to link it again
        runCatching { driver?.close() }
        driver = null
        deleteDatabase(DB_NAME)
        identity.forget()
        WatchDeviceKey.delete()
        deviceKey = null
        startLinking(fresh = true)
    }

    private fun deviceName(): String =
        Build.MODEL?.takeIf { it.isNotBlank() && it.contains("watch", ignoreCase = true) } ?: WatchLinkRules.DEFAULT_NAME

    private companion object {
        const val POLL_MS = 3_000L
        const val SURFACE_SETTLE_MS = 1_000L
        const val DB_NAME = "meka.db"
        const val BUSY = "MEKA's server is busy linking · try again in a minute"
    }
}
