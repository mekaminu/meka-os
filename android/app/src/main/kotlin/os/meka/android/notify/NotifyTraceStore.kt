package os.meka.android.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import os.meka.android.MekaApplication
import os.meka.android.alarm.AlarmRingService
import os.meka.android.ask.ErrandProximity
import os.meka.android.update.UpdateNotice
import os.meka.android.work.UnknownCallNotice
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.NotificationHealth
import os.meka.core.domain.NotifyChannelPosts
import os.meka.core.domain.NotifyChannelState
import os.meka.core.domain.NotifyTrace
import os.meka.core.domain.NotifyTraceEntry
import os.meka.core.domain.NotifyTraceKind

/**
 * Notifications never seen (slice 1): every MEKA channel is made when the app starts, so each exists in Android's
 * settings (and can be tuned) before anything posts, and their ids never change, so per-channel settings survive
 * updates. Before this, channels were made just before a first post, and Meka's Fold listed none at all.
 */
object MekaChannels {
    fun ensureAll(app: MekaApplication) {
        listOf(NoticeTier.CRITICAL, NoticeTier.ACTION, NoticeTier.HEADS_UP, NoticeTier.DIGEST).forEach { tier ->
            runCatching { app.governor.ensureChannel(tier) }
        }
        runCatching { WorkAlerts.ensureChannel(app) }
        runCatching { app.nudger.ensureChannel() }
        runCatching { app.ongoing.ensureChannel() }
        runCatching { UpdateNotice.ensureChannel(app) }
        runCatching { AlarmRingService.ensureChannel(app) }
        runCatching { UnknownCallNotice.ensureChannel(app) }
        runCatching { ErrandProximity.ensureChannel(app) }
        runCatching { os.meka.android.ask.CarBluetooth.ensureChannel(app) }
    }

    /** Each MEKA channel as Android has it now (on unless its importance is None). */
    fun states(context: Context): List<NotifyChannelState> = runCatching {
        context.getSystemService(NotificationManager::class.java).notificationChannels.map {
            NotifyChannelState(it.id, it.name.toString(), it.importance != NotificationManager.IMPORTANCE_NONE)
        }
    }.getOrDefault(emptyList())
}

/**
 * Notifications never seen (slice 3): every MEKA post goes through here, so each channel's last post is noted for
 * Health (the governor's and the paths outside it alike). Throws what Android throws (a SecurityException when the
 * permission was withdrawn), as the call sites already expect.
 */
object MekaPost {
    @SuppressLint("MissingPermission") // each caller checks WorkAlerts.canPost() or catches the SecurityException
    fun notify(context: Context, id: Int, n: Notification) {
        NotificationManagerCompat.from(context).notify(id, n)
        NotifyTraceStore.posted(context, n.channelId)
    }
}

/**
 * The phone's notification trace ([NotifyTrace]): what each notification path did, newest first, kept on this phone
 * only. Holds counts, channel names, reasons and error class names; never a notice's words.
 */
object NotifyTraceStore {
    private const val PREFS = "meka_notify_trace"
    private const val KEY_LOG = "log"
    private const val KEY_POSTS = "channel_posts"

    /** MEKA posted on [channelId] just now (slice 3): kept per channel for Health's list and the trace report. */
    @Synchronized
    fun posted(context: Context, channelId: String?) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val posts = NotifyChannelPosts.record(NotifyChannelPosts.decode(prefs.getString(KEY_POSTS, null)), channelId, System.currentTimeMillis())
            prefs.edit().putString(KEY_POSTS, NotifyChannelPosts.encode(posts)).apply()
        }
    }

    private fun posts(context: Context): Map<String, Long> =
        NotifyChannelPosts.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_POSTS, null))

    @Synchronized
    fun record(context: Context, entry: NotifyTraceEntry) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val log = NotifyTrace.add(NotifyTrace.decode(prefs.getString(KEY_LOG, null)), entry)
            prefs.edit().putString(KEY_LOG, NotifyTrace.encode(log)).apply()
        }
    }

    fun failed(context: Context, where: String, e: Throwable) =
        record(context, NotifyTrace.failure(System.currentTimeMillis(), where, e::class.qualifiedName, e.message))

    fun log(context: Context): List<NotifyTraceEntry> =
        NotifyTrace.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, null))

    fun started(context: Context, channels: Int) =
        record(context, NotifyTraceEntry(System.currentTimeMillis(), NotifyTraceKind.STARTED, "$channels channels"))

    /** What Health shows about notifications on this phone. */
    fun health(context: Context): NotificationHealth =
        NotificationHealth(
            allowed = WorkAlerts.canPost(context), channels = NotifyChannelPosts.withPosts(MekaChannels.states(context), posts(context)), trace = log(context),
            phoneOff = (context.applicationContext as? MekaApplication)?.governor?.device?.value == os.meka.core.domain.DeviceAlerts.OFF,
        )

    /** The phone's own calendar, for the trace's times. */
    fun calendar(): LocalCalendar =
        LocalCalendar.fixedOffset(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong())
}
