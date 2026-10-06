package os.meka.android.notify

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.shell.ShellDestination
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.Digest
import os.meka.core.domain.Notice
import os.meka.core.domain.NoticePrecision
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier

/**
 * Posts what the shared notification governor (core `Governor`) says, on one channel per tier, and wakes itself with
 * an inexact alarm for the next thing (ADR-007: `setWindow`, no exact-alarm permission). Runs when what MEKA knows
 * changes (lists, fasting, the shutdown, the morning brief, Today, the settings), on that alarm, and after a reboot or an update.
 *
 * What has been posted is kept on this phone only, so each notice posts once here.
 */
class NotificationGovernor(private val context: Context, private val app: MekaApplication) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _device = MutableStateFlow(
        DeviceAlerts.entries.firstOrNull { it.name == prefs.getString(KEY_DEVICE, null) } ?: DeviceAlerts.ALL,
    )
    /** What this phone posts (everything, digests only, or nothing). Not synced: the Mac has its own. */
    val device: StateFlow<DeviceAlerts> = _device.asStateFlow()

    fun setDevice(d: DeviceAlerts) {
        prefs.edit().putString(KEY_DEVICE, d.name).apply()
        _device.value = d
    }

    suspend fun run() = mutex.withLock {
        val r = app.core.governNotifications(prefs.getString(KEY_STATE, null), _device.value)
        if (WorkAlerts.canPost(context)) {
            r.post.forEach { post(it) }
            r.digest?.let { postDigest(it) }
        } // else: what would have posted still shows in the app; nothing piles up for later
        prefs.edit().putString(KEY_STATE, r.stateEncoded).commit()
        schedule(r.nextWakeMs, r.nextWakePrecision)
    }

    private fun schedule(atMs: Long?, precision: NoticePrecision) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (atMs == null) { am.cancel(alarmIntent()); return }
        am.setWindow(AlarmManager.RTC_WAKEUP, atMs, NotifyRouting.windowMs(precision), alarmIntent())
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, GovernorAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(n: Notice) {
        val channel = ensureChannel(n.tier)
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(NotifyRouting.publicTitle(n.tier))
            .build()
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(n.title)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public) // titles stay off the lock screen
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (n.tier == NoticeTier.HEADS_UP) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(n.target, NotifyRouting.notificationId(n.key)))
            .setAutoCancel(true)
        if (n.text.isNotBlank()) b.setContentText(n.text)
        notify(NotifyRouting.notificationId(n.key), b.build())
    }

    private fun postDigest(d: Digest) {
        val channel = ensureChannel(NoticeTier.DIGEST)
        val style = NotificationCompat.InboxStyle().setSummaryText(d.summary)
        d.lines.forEach { style.addLine(it) }
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(d.publicTitle)
            .build()
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(d.title)
            .setContentText(d.summary)
            .setStyle(style)
            .setNumber(d.count)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(d.target, NotifyRouting.DIGEST_ID))
            .setAutoCancel(true)
            .build()
        notify(NotifyRouting.DIGEST_ID, n) // a new digest replaces the last one
    }

    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost() in run(); a late revoke is caught here
    private fun notify(id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post: the app still shows it.
        }
    }

    private fun openIntent(target: NoticeTarget, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DESTINATION_PREFIX + NotifyRouting.destination(target).name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** One channel per tier, so each can be tuned (or let through Do Not Disturb) in the phone's settings. */
    fun ensureChannel(tier: NoticeTier): String {
        val id = NotifyRouting.channelId(tier)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val (name, importance, about) = when (tier) {
                NoticeTier.CRITICAL -> Triple("Critical", NotificationManager.IMPORTANCE_HIGH, "Rare: things that can't wait, even in quiet hours.")
                NoticeTier.ACTION -> Triple("Needs a decision", NotificationManager.IMPORTANCE_HIGH, "Approvals and choices only you can make; held during quiet hours.")
                NoticeTier.HEADS_UP -> Triple("Heads-ups", NotificationManager.IMPORTANCE_DEFAULT, "Cancel-by dates, your fasting goal, the morning brief and the evening shutdown.")
                else -> Triple("Digests", NotificationManager.IMPORTANCE_LOW, "The midday and evening round-up of what's due.")
            }
            nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = about })
        }
        return id
    }

    companion object {
        private const val PREFS = "notify-governor"
        private const val KEY_STATE = "state"
        private const val KEY_DEVICE = "device"
    }
}

/** Pure mapping between the shared governor and Android, unit-tested. */
object NotifyRouting {
    /** One id for the digest, so a new one replaces the last. Individual notices use ids from their keys. */
    const val DIGEST_ID = 0x4D454B44 // "MEKD"

    fun channelId(tier: NoticeTier): String = when (tier) {
        NoticeTier.CRITICAL -> "meka_critical"
        NoticeTier.ACTION -> "meka_action"
        NoticeTier.HEADS_UP -> "meka_heads_up"
        NoticeTier.DIGEST, NoticeTier.SILENT -> "meka_digest"
    }

    fun destination(target: NoticeTarget): ShellDestination = when (target) {
        NoticeTarget.TODAY -> ShellDestination.TODAY
        NoticeTarget.NEEDS_YOU -> ShellDestination.NEEDS_YOU
        NoticeTarget.LISTS -> ShellDestination.LISTS
        NoticeTarget.GOALS -> ShellDestination.GOALS
    }

    /** Stable per key, never the digest's id. */
    fun notificationId(key: String): Int = (key.hashCode() and 0x3FFFFFFF).let { if (it == DIGEST_ID) it + 1 else it }

    /** ADR-007: soft milestones may be batched into the next ten minutes; clock-precision ones five. */
    fun windowMs(precision: NoticePrecision): Long = if (precision == NoticePrecision.CLOCK) 5 * 60_000L else 10 * 60_000L

    fun publicTitle(tier: NoticeTier): String = when (tier) {
        NoticeTier.CRITICAL, NoticeTier.ACTION -> "MEKA · needs you"
        else -> "MEKA · a heads-up"
    }
}

/** The governor's own inexact alarm. */
class GovernorAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { app.core.tick(); app.governor.run() } finally { pending.finish() }
        }
    }
}

/**
 * After a reboot or an app update the system has dropped MEKA's alarms; re-register them from the local data, which
 * is the source of truth (ADR-007). Starting the app process also re-arms the end-of-work alarm.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.governor.run()
                app.nudger.evaluate(app.core.currentWorkMode())
            } finally {
                pending.finish()
            }
        }
    }
}
