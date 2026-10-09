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
import os.meka.core.domain.NoticeAction
import os.meka.core.domain.NoticePrecision
import os.meka.core.domain.NoticeTarget
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.NotifyRules
import os.meka.core.domain.SessionCard
import os.meka.core.domain.SessionRules

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
            val posted = r.post.filter { post(it) }
            val digest = r.digest?.takeIf { postDigest(it) }
            // Counts only, for the weekly review's Interruptions (ADR-013): what actually reached a live channel.
            // The same notices (and the digest) go in the activity log, "What MEKA did and why".
            app.core.notificationsPosted(posted)
            digest?.let { app.core.digestPosted(it) }
        } // else: what would have posted still shows in the app; nothing piles up for later (and nothing counts)
        prefs.edit().putString(KEY_STATE, r.stateEncoded).commit()
        schedule(r.nextWakeMs, r.nextWakePrecision)
    }

    /**
     * ADR-007: a CLOCK reminder (Remind me / Leave by on an event) uses an exact alarm when Meka has allowed "Alarms &
     * reminders" for MEKA; otherwise, and for everything else, a windowed one. No exact alarm is ever asked for silently.
     */
    private fun schedule(atMs: Long?, precision: NoticePrecision) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (atMs == null) { am.cancel(alarmIntent()); return }
        if (precision == NoticePrecision.CLOCK && am.canScheduleExactAlarms()) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarmIntent())
                return
            } catch (e: SecurityException) {
                // Withdrawn between the check and the call: fall back to the window below.
            }
        }
        am.setWindow(AlarmManager.RTC_WAKEUP, atMs, NotifyRouting.windowMs(precision), alarmIntent())
    }

    /** Whether event reminders arrive on time ("Alarms & reminders" allowed) or within a few minutes. */
    fun exactAllowed(): Boolean = context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, GovernorAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Posts [n]; false when its channel is turned off in the phone's settings or the post was refused. */
    private fun post(n: Notice): Boolean {
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
        // Buttons answered from the shade ("Did you go?": Went · Didn't go); the core checks each still applies.
        n.actions.forEach { a ->
            val i = Intent(context, NoticeActionReceiver::class.java)
                .setAction(NoticeActionReceiver.ACTION_ANSWER)
                .putExtra(NoticeActionReceiver.EXTRA_KEY, n.key)
                .putExtra(NoticeActionReceiver.EXTRA_ACTION, NotifyRules.actionName(a))
            b.addAction(0, NotifyRules.actionLabel(a), PendingIntent.getBroadcast(
                context, NotifyRouting.actionRequestCode(n.key, a), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
        }
        val live = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
        return notify(NotifyRouting.notificationId(n.key), b.build()) && live
    }

    /**
     * After Went / Didn't go from the shade: the same notification turns into a silent note, "Gym · Push" ·
     * "Went · 2 of 3 this week · Next: Thu 17:45 · Pull", with Undo for ten minutes; it times out on its own.
     */
    fun postAnswered(key: String, card: SessionCard) {
        if (!WorkAlerts.canPost(context)) return
        val id = NotifyRouting.notificationId(key)
        val channel = ensureChannel(NoticeTier.HEADS_UP)
        val undo = Intent(context, NoticeActionReceiver::class.java)
            .setAction(NoticeActionReceiver.ACTION_UNDO)
            .putExtra(NoticeActionReceiver.EXTRA_KEY, key)
            .putExtra(NoticeActionReceiver.EXTRA_HABIT, card.habitId)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentTitle(card.heading)
            .setContentText(SessionRules.answeredLine(card))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(NotifyRouting.publicTitle(NoticeTier.HEADS_UP)).build())
            .setTimeoutAfter(NotifyRouting.ANSWERED_NOTE_MS)
            .setContentIntent(openIntent(NoticeTarget.TODAY, id))
            .addAction(0, "Undo", PendingIntent.getBroadcast(
                context, NotifyRouting.undoRequestCode(key), undo, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .build()
        notify(id, n)
    }

    fun cancel(key: String) = NotificationManagerCompat.from(context).cancel(NotifyRouting.notificationId(key))

    /** Posts the digest; false when its channel is turned off in the phone's settings or the post was refused. */
    private fun postDigest(d: Digest): Boolean {
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
        val live = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
        return notify(NotifyRouting.DIGEST_ID, n) && live // a new digest replaces the last one
    }

    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost() in run(); a late revoke is caught here
    private fun notify(id: Int, n: android.app.Notification): Boolean = try {
        NotificationManagerCompat.from(context).notify(id, n)
        true
    } catch (e: SecurityException) {
        // Permission withdrawn between the check and the post: the app still shows it.
        false
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
                NoticeTier.HEADS_UP -> Triple("Heads-ups", NotificationManager.IMPORTANCE_DEFAULT, "Event reminders, cancel-by dates, your fasting goal, the morning brief, the evening shutdown and requests from people you chose to hear from straight away.")
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
        NoticeTarget.REVIEW -> ShellDestination.REVIEW
    }

    /** Stable per key, never the digest's id. */
    fun notificationId(key: String): Int = (key.hashCode() and 0x3FFFFFFF).let { if (it == DIGEST_ID) it + 1 else it }

    /** ADR-007: soft milestones may be batched into the next ten minutes; clock-precision ones five. */
    fun windowMs(precision: NoticePrecision): Long = if (precision == NoticePrecision.CLOCK) 5 * 60_000L else 10 * 60_000L

    fun publicTitle(tier: NoticeTier): String = when (tier) {
        NoticeTier.CRITICAL, NoticeTier.ACTION -> "MEKA · needs you"
        else -> "MEKA · a heads-up"
    }

    /** How long the "Went · 2 of 3 this week · Undo" note stays in the shade. */
    const val ANSWERED_NOTE_MS = 10 * 60_000L

    /** One PendingIntent per notice and button (extras alone don't tell two apart). */
    fun actionRequestCode(key: String, action: NoticeAction): Int = "$key#${action.name}".hashCode()

    fun undoRequestCode(key: String): Int = "$key#undo".hashCode()
}

/**
 * Buttons on a governor notification (Gym slice 2b): Went / Didn't go on "Did you go?", then Undo on the note that
 * replaces it. The core answers only that day's session while it still asks (answered on the Mac already, or left
 * over from yesterday: the notification just goes).
 */
class NoticeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_ANSWER -> {
                        val action = NotifyRules.actionFromName(intent.getStringExtra(EXTRA_ACTION))
                        val card = action?.let { runCatching { app.core.answerSessionNotice(key, it) }.getOrNull() }
                        if (card != null) app.governor.postAnswered(key, card) else app.governor.cancel(key)
                    }
                    ACTION_UNDO -> {
                        intent.getStringExtra(EXTRA_HABIT)?.let { id -> runCatching { app.core.undoSession(id) } }
                        app.governor.cancel(key)
                    }
                }
                app.governor.run()
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_ANSWER = "os.meka.notify.ANSWER"
        const val ACTION_UNDO = "os.meka.notify.UNDO_ANSWER"
        const val EXTRA_KEY = "os.meka.notify.key"
        const val EXTRA_ACTION = "os.meka.notify.action"
        const val EXTRA_HABIT = "os.meka.notify.habit"
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

private val RESTART_ACTIONS = setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
)

/**
 * After a reboot, an app update or a change to "Alarms & reminders" the system has dropped (or may now upgrade) MEKA's alarms; re-register them from the local data, which
 * is the source of truth (ADR-007). Starting the app process also re-arms the end-of-work alarm.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTART_ACTIONS) return
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                app.governor.run()
                app.ongoing.run()
                app.widgets.run()
                app.nudger.evaluate(app.core.currentWorkMode())
            } finally {
                pending.finish()
            }
        }
    }
}
