package os.meka.wear.surfaces

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import os.meka.wear.WatchApplication
import java.util.concurrent.TimeUnit

/**
 * What MEKA shows outside its own screen on the watch (Galaxy Watch, slice 3): the tile and the complication. Both
 * read the watch's own replica; this asks Wear OS to draw them again after anything that may have changed them.
 */
object WatchSurfaces {
    fun refresh(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(WatchTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, WatchComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    private const val SYNC = "meka-watch-sync"

    /**
     * A quarter-hourly sync while MEKA isn't on screen, so the tile and complication follow what the Fold and the Mac
     * did (ADR-007's periodic job, like the phone's). Only once linked; [cancelSync] when the watch is unlinked. In
     * quiet hours each run reaches the server at most hourly ([MekaCore.watchSyncDue]).
     */
    fun scheduleSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC)
    }
}

/** The watch's background sync: one round with MEKA's server, then the tile and complication drawn again. */
class WatchSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as WatchApplication
        val core = app.core.value ?: return Result.success() // unlinked: nothing to sync
        // Galaxy Watch completeness slice 2: in quiet hours the sync rests to hourly unless something waits or counts.
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(LAST_SYNC, 0L).takeIf { it > 0L }
        if (!runCatching { core.watchSyncDue(last) }.getOrDefault(true)) {
            WatchSurfaces.refresh(app)
            return Result.success()
        }
        val ok = runCatching { core.syncNow() }.getOrDefault(false)
        if (ok) prefs.edit().putLong(LAST_SYNC, System.currentTimeMillis()).apply()
        runCatching { core.tick() }
        WatchSurfaces.refresh(app)
        return if (ok) Result.success() else Result.retry()
    }

    private companion object {
        const val PREFS = "meka-watch-sync"
        const val LAST_SYNC = "lastBackgroundSyncMs"
    }
}
