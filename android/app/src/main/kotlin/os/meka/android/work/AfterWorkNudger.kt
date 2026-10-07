package os.meka.android.work

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.core.domain.AfterWorkNudge
import os.meka.core.domain.BreakThrough
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.LocalClock
import os.meka.core.domain.WorkModeState
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * "Your after-work summary is ready" (build plan M1): one quiet notification when work mode ends with something held.
 *
 * Every work-mode change on this phone is checked here (the app's clock tick, background sync, the listener, and an
 * inexact alarm at the end of work, ADR-007 "soft milestone": `setWindow`, no exact-alarm permission). The last state
 * seen is recorded, so the change from at work to off work nudges once, even if the phone was off when work ended.
 */
class AfterWorkNudger(private val context: Context, private val app: MekaApplication) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun evaluate(state: WorkModeState): Unit = synchronized(lock) {
        val was = if (prefs.contains(KEY_AT_WORK)) prefs.getBoolean(KEY_AT_WORK, false) else null
        val summary = app.core.afterWork.value.withLists(app.captures.lists.value)
        if (AfterWorkNudge.shouldNudge(was, state.atWork, summary, app.isOnScreen)) {
            AfterWorkNudge.text(summary)?.let { post(it.title, it.text, it.publicText) }
        }
        if (was != state.atWork) prefs.edit().putBoolean(KEY_AT_WORK, state.atWork).commit()
        val end = state.until
        if (state.atWork && end != null) scheduleEnd(end) else cancelEnd()
    }

    /**
     * Urgent voice messages the call assistant took (written by the server, which woke this phone at high priority):
     * each rings through once on the "Urgent while at work" channel, within an hour of the call. Called after every
     * sync and whenever the synced summary changes.
     */
    fun alertVoiceMessages(): Unit = synchronized(lock) {
        val lists = app.captures.lists.value
        val items = app.core.afterWork.value.withLists(lists).people.flatMap { it.items }
        val alerted = prefs.getStringSet(KEY_VOICE_ALERTED, emptySet()).orEmpty()
        val due = CallAssistantRules.toAlert(items, alerted, System.currentTimeMillis())
        if (due.isEmpty()) return
        due.forEach { WorkAlerts.post(context, it, BreakThrough.URGENT) }
        // Remember the latest few only: the rule's one-hour window keeps older ones from ringing again anyway.
        val keep = (alerted + due.map { it.id }).toList().takeLast(MAX_ALERTED).toSet()
        prefs.edit().putStringSet(KEY_VOICE_ALERTED, keep).commit()
    }

    /** The summary was read: the nudge has done its job. */
    fun dismiss() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun scheduleEnd(at: LocalClock) {
        val atMs = WorkEndTime.next(ZonedDateTime.now(), at).toInstant().toEpochMilli()
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setWindow(AlarmManager.RTC_WAKEUP, atMs, WINDOW_MS, endIntent())
    }

    private fun cancelEnd() {
        context.getSystemService(AlarmManager::class.java)?.cancel(endIntent())
    }

    private fun endIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, WorkEndReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost(), and a late revoke is caught below
    private fun post(title: String, text: String, publicText: String) {
        if (!WorkAlerts.canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "After-work summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "One note when work mode ends and messages or missed calls are waiting."
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_AFTER_WORK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(publicText)
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public) // who messaged stays off the lock screen
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post: the card in Needs you still shows it.
        }
    }

    companion object {
        const val CHANNEL = "after_work_summary"
        private const val NOTIFICATION_ID = 0x4D454B41 // "MEKA"
        private const val PREFS = "work-nudge"
        private const val KEY_AT_WORK = "atWork"
        private const val KEY_VOICE_ALERTED = "voiceAlerted"
        private const val MAX_ALERTED = 50
        /** Soft milestone: the system may batch it into the next ten minutes. */
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** Where a schedule's "next change" (a weekday and a minute) falls in real time, in the phone's zone. */
object WorkEndTime {
    fun next(now: ZonedDateTime, at: LocalClock): ZonedDateTime {
        val day = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(at.isoDayOfWeek)))
        val candidate = day.atTime(at.minuteOfDay / 60, at.minuteOfDay % 60).atZone(now.zone)
        return if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
    }
}

/** Fires near the end of work (inexact window); re-reads work mode, which records the change and nudges. */
class WorkEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.nudger.evaluate(app.core.currentWorkMode())
            } finally {
                pending.finish()
            }
        }
    }
}
