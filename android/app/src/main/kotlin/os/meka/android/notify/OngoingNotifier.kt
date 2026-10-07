package os.meka.android.notify

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.shell.ShellDestination
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.FastingRules
import os.meka.core.domain.OngoingItem
import os.meka.core.domain.OngoingKind
import os.meka.core.domain.OngoingView

/**
 * Ongoing ("live") notifications (Outside the app, slice 1): the next event's countdown from 30 minutes before it, and
 * a running fast's timer with its progress, from the core's `OngoingRules`. Silent, on their own low channel ("Now"),
 * updated in place; the system ticks the clock (a chronometer), so nothing re-posts every minute. Samsung shows them
 * on the lock screen; the lock screen gets "Next event · 12:04" / "Fasting · 4:12:30", never the title.
 *
 * Runs whenever Today or the fast changes, and on its own windowed alarm (ADR-007: a soft change, `setWindow`) for the
 * next time the list changes by itself. Nothing is counted as an interruption: they never make a sound.
 */
class OngoingNotifier(private val context: Context, private val app: MekaApplication) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        val v = app.core.ongoing()
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty()
        // Swiped away: stays away for the life of that event or fast (Android 14+ lets ongoing ones be dismissed).
        val dismissed = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty() intersect v.items.map { it.key }.toSet()
        val wanted = v.items.filter { it.key !in dismissed }
        val now = if (WorkAlerts.canPost(context)) wanted.filter { post(it) }.map { it.key }.toSet() else emptySet()
        val nm = NotificationManagerCompat.from(context)
        (shown - now).forEach { nm.cancel(OngoingRouting.notificationId(it)) }
        prefs.edit().putStringSet(KEY_SHOWN, now).putStringSet(KEY_DISMISSED, dismissed).commit()
        schedule(v.nextChangeMs.takeIf { it != Long.MAX_VALUE })
    }

    private fun schedule(atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (atMs == null) { am.cancel(alarmIntent()); return }
        am.setWindow(AlarmManager.RTC_WAKEUP, atMs, OngoingRouting.WINDOW_MS, alarmIntent())
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, OngoingAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(item: OngoingItem): Boolean {
        val channel = ensureChannel()
        val id = OngoingRouting.notificationId(item.key)
        fun base() = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(item.clockBaseMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(item.countDown)
            .setCategory(if (item.kind == OngoingKind.MEETING) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_PROGRESS)
            .apply { item.progressPercent?.let { setProgress(100, it, false) } }
        val public = base().setContentTitle(item.publicTitle).build()
        val b = base()
            .setContentTitle(item.title)
            .setContentText(item.text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(openIntent(OngoingRouting.destination(item.kind), id))
            .setDeleteIntent(dismissIntent(item.key, id))
        // Fasting v2: End fast from the shade. A mistaken tap can be undone from the "Fast ended" note that follows.
        if (item.kind == OngoingKind.FAST) {
            val end = Intent(context, OngoingFastReceiver::class.java)
                .setAction(OngoingFastReceiver.ACTION_END).putExtra(EXTRA_FAST_ID, OngoingRouting.fastId(item.key))
            b.addAction(0, "End fast", PendingIntent.getBroadcast(context, id + 2, end, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        item.join?.let { j ->
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(j.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.addAction(0, j.label, PendingIntent.getActivity(context, id + 1, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        val n = b.build()
        // Android 16 "live updates": ask for the promoted (status-bar chip / lock-screen) treatment. Ignored before
        // API 36 and where the system declines it; the notification is the same either way.
        if (Build.VERSION.SDK_INT >= 36) n.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        val live = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
        return notify(id, n) && live
    }

    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost() in run(); a late revoke is caught here
    private fun notify(id: Int, n: android.app.Notification): Boolean = try {
        NotificationManagerCompat.from(context).notify(id, n)
        true
    } catch (e: SecurityException) {
        false
    }

    private fun openIntent(dest: ShellDestination, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DESTINATION_PREFIX + dest.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dismissIntent(key: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, Intent(context, OngoingDismissReceiver::class.java).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * After End fast from the shade: a silent "Fast ended · 62 h 10 m" on the Now channel with Undo, for as long as the
     * core allows a resume (10 minutes); it times out on its own.
     */
    fun postEnded(fastId: String) {
        val last = app.core.fastingView.value.last?.takeIf { it.id == fastId && it.canResume } ?: return
        if (!WorkAlerts.canPost(context)) return
        val id = OngoingRouting.endedId
        val undo = Intent(context, OngoingFastReceiver::class.java)
            .setAction(OngoingFastReceiver.ACTION_UNDO).putExtra(EXTRA_FAST_ID, fastId)
        val n = NotificationCompat.Builder(context, ensureChannel())
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentTitle("Fast ended")
            .setContentText(last.line)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, OngoingRouting.CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle("Fast ended").build())
            .setTimeoutAfter(FastingRules.RESUME_WINDOW_MS)
            .setContentIntent(openIntent(ShellDestination.GOALS, id))
            .addAction(0, "Undo", PendingIntent.getBroadcast(context, id + 1, undo, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        notify(id, n)
    }

    fun clearEnded() = NotificationManagerCompat.from(context).cancel(OngoingRouting.endedId)

    /** Meka swiped [key] away. */
    fun dismissed(key: String) {
        val set = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty() + key
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty() - key
        prefs.edit().putStringSet(KEY_DISMISSED, set).putStringSet(KEY_SHOWN, shown).apply()
    }

    private fun ensureChannel(): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(OngoingRouting.CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(OngoingRouting.CHANNEL, "Now", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Silent and ongoing: the next event's countdown from half an hour before, and a running fast."
                    setShowBadge(false)
                },
            )
        }
        return OngoingRouting.CHANNEL
    }

    companion object {
        private const val PREFS = "ongoing"
        private const val KEY_SHOWN = "shown"
        private const val KEY_DISMISSED = "dismissed"
        const val EXTRA_KEY = "os.meka.ongoing.key"
        const val EXTRA_FAST_ID = "os.meka.ongoing.fastId"
        /** `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` (API 36), spelled out so older SDK stubs don't matter. */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}

/** Pure mapping for the ongoing notifications, unit-tested. */
object OngoingRouting {
    const val CHANNEL = "meka_now"
    /** A soft change (ADR-007): within five minutes is fine, the chronometer itself is exact. */
    const val WINDOW_MS = 5 * 60_000L

    /** Stable per key, apart from the governor's ids (which come from notice keys) by a prefix. */
    fun notificationId(key: String): Int = ("ongoing:$key".hashCode() and 0x3FFFFFFF) or 0x40000000

    /** What a post shows; the app re-posts only when this changes (Today itself moves every minute). */
    fun signature(v: OngoingView): List<Any?> =
        v.items.map { listOf(it.key, it.title, it.text, it.publicTitle, it.clockBaseMs, it.countDown, it.progressPercent, it.lit, it.join?.url) } +
            listOf(v.nextChangeMs)

    /** The fast's id from its ongoing key ("fast-<id>"); null for anything else. */
    fun fastId(key: String): String? = key.takeIf { it.startsWith("fast-") }?.removePrefix("fast-")?.takeIf { it.isNotEmpty() }

    /** The "Fast ended · Undo" note. */
    val endedId: Int get() = notificationId("fast-ended")

    fun destination(kind: OngoingKind): ShellDestination = when (kind) {
        OngoingKind.MEETING -> ShellDestination.TODAY
        OngoingKind.FAST -> ShellDestination.GOALS
    }
}

/** The ongoing notifications' own windowed alarm: the next time the list changes by itself. */
class OngoingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { app.core.tick(); app.ongoing.run() } finally { pending.finish() }
        }
    }
}

/** Meka swiped an ongoing notification away: it stays away until that event or fast is over. */
class OngoingDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(OngoingNotifier.EXTRA_KEY) ?: return
        (context.applicationContext as MekaApplication).ongoing.dismissed(key)
    }
}

/**
 * End fast / Undo from the notification shade (Fasting v2). Ends only the fast the notification was about (a fast
 * started since on the Mac is left alone); Undo resumes it while the core still allows (10 minutes).
 */
class OngoingFastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(OngoingNotifier.EXTRA_FAST_ID) ?: return
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_END -> if (app.core.fastingView.value.current?.id == id) {
                        runCatching { app.core.endFast() }
                        app.core.tick()
                        app.ongoing.postEnded(id)
                    }
                    ACTION_UNDO -> {
                        runCatching { app.core.resumeFast(id) }
                        app.core.tick()
                        app.ongoing.clearEnded()
                    }
                }
                app.ongoing.run()
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_END = "os.meka.ongoing.END_FAST"
        const val ACTION_UNDO = "os.meka.ongoing.UNDO_END_FAST"
    }
}
