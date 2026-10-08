package os.meka.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import os.meka.android.MainActivity
import os.meka.core.domain.AlarmRing

/**
 * Registers the core's next alarm with Android (Alarms, slice 1). The wake alarm is ADR-007's "wake-up style" row,
 * which Meka opted into by asking for alarms: `setAlarmClock`, which shows the alarm icon and is what the phone's
 * "next alarm" (and MEKA's bedside clock) reads. That needs "Alarms & reminders" allowed for MEKA; until it is, a
 * 5-minute window is used and the shutdown pane says so with a one-tap grant. One alarm at a time (request code 0);
 * re-registered from the synced data at process start, so a reboot or an update re-arms it.
 */
class AlarmScheduler(private val context: Context) {
    fun register(ring: AlarmRing?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val fire = fireIntent(ring?.id)
        if (ring == null) { am.cancel(fire); return }
        if (am.canScheduleExactAlarms()) {
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(ring.ringAtMs, showIntent()), fire)
                return
            } catch (e: SecurityException) {
                // Withdrawn between the check and the call: the window below.
            }
        }
        am.setWindow(AlarmManager.RTC_WAKEUP, ring.ringAtMs, AlarmRouting.FALLBACK_WINDOW_MS, fire)
    }

    /** Whether the wake alarm rings on the minute ("Alarms & reminders" allowed) or within a few minutes. */
    fun onTime(): Boolean = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun fireIntent(id: String?): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).putExtra(AlarmRingService.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What the system's alarm icon opens: MEKA. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The alarm went off: start the ringing service (a foreground service may start from an exact alarm). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmRingService.ring(context, intent.getStringExtra(AlarmRingService.EXTRA_ID))
    }
}
