package os.meka.android.goals

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import os.meka.android.MekaApplication
import os.meka.core.domain.WorkoutRules
import java.util.concurrent.TimeUnit

/**
 * Health Connect, slice 2: today's workouts read every [WorkoutRules.BACKGROUND_EVERY_MIN] minutes while MEKA is closed
 * (only with the switch on and Health Connect's background permission), so a workout the watch logs ticks Gym before
 * "Did you go?" comes. No network needed: the reading stays on the phone, the tick syncs with the next sync.
 */
class HealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MekaApplication
        // Switched off or the permission taken away in Health Connect: stop the job and stop holding the question.
        if (!HealthWorkouts.sync(app, app.core)) return Result.success()
        HealthWorkouts.refresh(app, app.core)
        return Result.success()
    }

    companion object {
        private const val NAME = "meka-health"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthWorker>(WorkoutRules.BACKGROUND_EVERY_MIN.toLong(), TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
