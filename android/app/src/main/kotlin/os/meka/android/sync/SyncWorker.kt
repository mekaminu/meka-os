package os.meka.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import os.meka.android.MekaApplication
import java.util.concurrent.TimeUnit

/**
 * Background sync (ADR-007): periodic WorkManager job with a network constraint. While the app is in the
 * foreground, MekaCore.startSync() handles prompt pushes; this keeps the op queue draining when it isn't.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MekaApplication
        // Battery care: a periodic run is the heartbeat that shows MEKA wasn't stopped.
        os.meka.android.today.BatteryCare.beat(app)
        val ok = app.core.syncNow()
        // A Work switch flipped on the Mac arrives here: let the after-work nudge see it before the process sleeps.
        runCatching { app.nudger.evaluate(app.core.currentWorkMode()) }
        // An urgent voice message the call assistant just took (the server woke us at high priority): ring through.
        runCatching { app.nudger.alertVoiceMessages() }
        // A build published from the Mac is ready on the card the next time MEKA opens.
        runCatching { app.updater.check() }
        // Push: if the server doesn't have this phone's address yet (offline before, or push was just set up), send it.
        app.ensurePush()
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "meka-sync"
        private const val NOW = "meka-sync-now"

        /**
         * Push said another device changed something: one sync now (expedited; if the quota is spent it runs as
         * ordinary work as soon as it can). A wake arriving while one runs queues one more after it, so nothing
         * stored meanwhile is missed; the server sends at most one wake every 20 s.
         */
        fun syncSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
