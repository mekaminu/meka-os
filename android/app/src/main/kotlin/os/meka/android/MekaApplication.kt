package os.meka.android

import android.app.Application
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import os.meka.android.security.AndroidDeviceKey
import os.meka.android.security.DatabaseKeyStore
import os.meka.android.sync.SyncWorker
import os.meka.android.work.CaptureStore
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
    /** Hardware-held signing key; created on first use (ADR-005). */
    private val deviceKey by lazy { AndroidDeviceKey() }
    // Read timeout above the server's 20 s long-poll window (OkHttp's default is 10 s).
    private val http by lazy { HttpClient(OkHttp) { engine { config { readTimeout(45, TimeUnit.SECONDS) } } } }

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
