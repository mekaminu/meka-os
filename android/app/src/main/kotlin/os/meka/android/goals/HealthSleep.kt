package os.meka.android.goals

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import os.meka.core.domain.SleepSession
import os.meka.core.domain.WorkoutRules
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Health Connect, slice 3 (build plan V1): last night's sleep for a lighter plan after a short night. Off by default and
 * kept on this phone; with it on and "Sleep" allowed, the sleep sessions since yesterday 18:00 are read when Goals opens
 * and just before Plan my day plans, and handed to the core ([MekaCore.setSleep]), which keeps them in memory only.
 * Nothing read leaves the phone, and nothing about sleep is synced.
 */
object HealthSleep {
    private const val PREFS = "meka.health"
    private const val KEY_ON = "sleepOn"

    val READ_SLEEP: String by lazy { HealthPermission.getReadPermission(SleepSessionRecord::class) }

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    suspend fun permitted(context: Context): Boolean = try {
        if (!HealthWorkouts.available(context)) false
        else READ_SLEEP in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun access(context: Context): WorkoutRules.Access = when {
        !HealthWorkouts.available(context) -> WorkoutRules.Access.UNAVAILABLE
        !isOn(context) -> WorkoutRules.Access.OFF
        !permitted(context) -> WorkoutRules.Access.NEEDS_PERMISSION
        else -> WorkoutRules.Access.ON
    }

    /**
     * Reads the sleep sessions since yesterday 18:00 and tells the core (an empty list when off or not allowed, so a
     * switched-off Sleep plans as usual). Never throws; last night's minutes asleep, or null when none was logged.
     */
    suspend fun refresh(context: Context, core: MekaCore): Int? = try {
        val sessions = if (!isOn(context) || !permitted(context)) emptyList() else {
            val zone = ZoneId.systemDefault()
            val from = LocalDate.now(zone).minusDays(1).atTime(18, 0).atZone(zone).toInstant()
            HealthConnectClient.getOrCreate(context).readRecords(
                ReadRecordsRequest(recordType = SleepSessionRecord::class, timeRangeFilter = TimeRangeFilter.between(from, Instant.now())),
            ).records.map { SleepSession(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
        }
        core.setSleep(sessions)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { core.setSleep(emptyList()) }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
