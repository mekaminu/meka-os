package os.meka.android

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import os.meka.android.security.DatabaseKeyStore
import os.meka.android.sync.SyncWorker
import os.meka.core.data.AndroidDatabase
import os.meka.core.data.SqlReplicaStore
import os.meka.core.facade.HttpSyncTransport
import os.meka.core.facade.MekaCore
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

class MekaApplication : Application() {
    lateinit var core: MekaCore
        private set

    override fun onCreate() {
        super.onCreate()
        val identity = DeviceIdentityStore(this)
        val key = DatabaseKeyStore(this).getOrCreateKey()
        val driver = AndroidDatabase.open(this, key) // the driver keeps its own copy
        key.fill(0)

        val transport = BuildConfig.SYNC_URL.takeIf { it.isNotBlank() && identity.deviceSecret() != null }?.let { url ->
            HttpSyncTransport(HttpClient(OkHttp), url) { identity.deviceSecret()!! }
        }
        core = MekaCore(
            householdId = identity.householdId(),
            deviceId = identity.deviceId(),
            store = SqlReplicaStore(driver),
            transport = transport,
            secureRandom = SecureRandom().asKotlinRandom(),
        )
        if (transport != null) SyncWorker.schedulePeriodic(this)
    }
}
