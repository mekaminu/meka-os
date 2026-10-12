package os.meka.android.goals

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import os.meka.core.domain.Workout
import os.meka.core.domain.WorkoutKind
import os.meka.core.domain.WorkoutRules
import os.meka.core.domain.WorkoutTickDone
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Health Connect on the Fold (build plan V1, slice 1). Off by default; the switch is kept on this phone only. With it on
 * and "Exercise" allowed, today's exercise sessions are read while MEKA is in front (Today or Goals opening, at most
 * every [WorkoutRules.READ_EVERY_MS]) and handed to the core, which ticks the habits they match
 * ([MekaCore.tickFromWorkouts]). The sessions themselves never leave the phone: only the tick syncs.
 */
object HealthWorkouts {
    private const val PREFS = "meka.health"
    private const val KEY_ON = "on"
    private const val KEY_READ_AT = "readAt"
    private const val KEY_LAST = "last"

    /** The one permission MEKA asks for: reading exercise sessions. */
    val READ_EXERCISE: String by lazy { HealthPermission.getReadPermission(ExerciseSessionRecord::class) }

    fun available(context: Context): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).putLong(KEY_READ_AT, 0L).apply()
    }

    /** The last tick this phone made ("Ticked Gym · 52-min workout"), for the Goals line; null before the first. */
    fun lastLine(context: Context): String? = prefs(context).getString(KEY_LAST, null)

    suspend fun permitted(context: Context): Boolean = try {
        if (!available(context)) false
        else READ_EXERCISE in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun access(context: Context): WorkoutRules.Access = when {
        !available(context) -> WorkoutRules.Access.UNAVAILABLE
        !isOn(context) -> WorkoutRules.Access.OFF
        !permitted(context) -> WorkoutRules.Access.NEEDS_PERMISSION
        else -> WorkoutRules.Access.ON
    }

    /**
     * Reads today's workouts and lets the core tick what they match. [force] skips the five-minute wait (the switch
     * just turned on). Never throws; the ticks made, empty when off, not allowed or nothing matched.
     */
    suspend fun refresh(context: Context, core: MekaCore, force: Boolean = false): List<WorkoutTickDone> = try {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (!isOn(context) || (!force && now - p.getLong(KEY_READ_AT, 0L) in 0 until WorkoutRules.READ_EVERY_MS)) emptyList()
        else if (!permitted(context)) emptyList()
        else {
            p.edit().putLong(KEY_READ_AT, now).apply()
            val zone = ZoneId.systemDefault()
            val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val response = HealthConnectClient.getOrCreate(context).readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(midnight, Instant.ofEpochMilli(now)),
                ),
            )
            val workouts = response.records.map { r ->
                Workout(r.metadata.id, kindOf(r.exerciseType), r.startTime.toEpochMilli(), r.endTime.toEpochMilli())
            }
            core.tickFromWorkouts(workouts).also { done ->
                done.lastOrNull()?.let { p.edit().putString(KEY_LAST, it.line).apply() }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Health Connect's exercise type → the kind a habit is matched on. Pure. */
    fun kindOf(type: Int): WorkoutKind = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP,
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> WorkoutKind.GYM
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> WorkoutKind.RUN
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> WorkoutKind.WALK
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> WorkoutKind.CYCLE
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> WorkoutKind.SWIM
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> WorkoutKind.YOGA
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> WorkoutKind.FOOTBALL
        else -> WorkoutKind.OTHER
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
