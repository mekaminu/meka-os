package os.meka.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
        val ok = app.core.syncNow()
        // A Work switch flipped on the Mac arrives here: let the after-work nudge see it before the process sleeps.
        runCatching { app.nudger.evaluate(app.core.currentWorkMode()) }
        // A build published from the Mac is ready on the card the next time MEKA opens.
        runCatching { app.updater.check() }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "meka-sync"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
