package os.meka.android.notify

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.NotifyTestRules
import os.meka.core.domain.NotifyTestRun
import os.meka.core.domain.NotifyTraceEntry
import os.meka.core.domain.NotifyTraceKind
import os.meka.core.domain.NotifyTraceReportRules

/**
 * Send a test (notifications never seen, slice 2): a local notification posted by this phone on the Heads-ups channel,
 * and a push from MEKA's server (`{"t":"test"}`) which posts its own when it arrives ([MekaMessagingService]). Each step
 * goes in the trace, and the trace is sent to the server straight after, so a build run can read the result.
 */
object NotifyTester {
    private const val LOCAL_ID = 0x4D454B54 // "MEKT"
    private const val PUSH_ID = 0x4D454B50 // "MEKP"

    private val _run = MutableStateFlow<NotifyTestRun?>(null)
    /** The current (or last) test; null until one has run since MEKA started. */
    val run: StateFlow<NotifyTestRun?> = _run.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(app: MekaApplication) {
        job?.cancel()
        val started = System.currentTimeMillis()
        _run.value = NotifyTestRun(started)
        job = app.appScope.launch {
            val shown = postLocal(app)
            _run.value = _run.value?.copy(localShown = shown)
            NotifyTraceStore.record(app, NotifyTraceEntry(System.currentTimeMillis(), NotifyTraceKind.TEST, NotifyTestRules.localTrace(shown)))
            val result = app.core.testPush()
            _run.value = _run.value?.copy(push = result)
            NotifyTraceStore.record(app, NotifyTraceEntry(System.currentTimeMillis(), NotifyTraceKind.TEST, NotifyTestRules.serverTrace(result)))
            // Give the push its minute, then send the trace either way, so a build run sees how it went.
            delay(NotifyTestRules.PUSH_WAIT_MS)
            if (_run.value?.pushArrivedAtMs == null && result == os.meka.core.domain.PushTestResult.SENT) {
                NotifyTraceStore.record(app, NotifyTraceEntry(System.currentTimeMillis(), NotifyTraceKind.TEST, "push didn't arrive within a minute"))
            }
            NotifyTraceReporter.poke(app, force = true)
        }
    }

    /** The test push arrived (from [MekaMessagingService]): show it and note it. */
    fun pushArrived(context: Context) {
        val now = System.currentTimeMillis()
        val started = _run.value?.startedAtMs
        if (started != null) _run.value = _run.value?.copy(pushArrivedAtMs = now)
        NotifyTraceStore.record(context, NotifyTraceEntry(now, NotifyTraceKind.TEST, NotifyTestRules.arrivedTrace(started, now)))
        post(context, PUSH_ID, NotifyTestRules.PUSH_TITLE, NotifyTestRules.PUSH_TEXT)
    }

    private fun postLocal(app: MekaApplication): Boolean {
        if (!WorkAlerts.canPost(app)) return false
        val channel = app.governor.ensureChannel(NoticeTier.HEADS_UP)
        val live = app.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
        return post(app, LOCAL_ID, NotifyTestRules.LOCAL_TITLE, NotifyTestRules.LOCAL_TEXT) && live
    }

    @SuppressLint("MissingPermission") // a missing permission is caught below and reported as "didn't show"
    private fun post(context: Context, id: Int, title: String, text: String): Boolean = try {
        val channel = os.meka.android.notify.NotifyRouting.channelId(NoticeTier.HEADS_UP)
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(os.meka.android.R.drawable.ic_stat_meka)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        os.meka.android.notify.MekaPost.notify(context, id, n)
        true
    } catch (e: SecurityException) {
        false
    } catch (e: RuntimeException) {
        NotifyTraceStore.failed(context, "test", e)
        false
    }
}

/**
 * Sends the phone's notification trace to MEKA's server ([NotifyTraceReportRules]): when there's something new, at
 * most every half hour, or straight after a test. What goes: allowed or not, MEKA's "This phone" setting, each
 * channel on/off and the trace (no notification's words) and the phone's model name.
 */
object NotifyTraceReporter {
    private const val PREFS = "meka_notify_report"
    private const val KEY_THROUGH = "through"
    private const val KEY_SENT_AT = "sent_at"
    @Volatile private var failedAtMs: Long? = null
    @Volatile private var sending = false

    fun poke(app: MekaApplication, force: Boolean = false) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val log = NotifyTraceStore.log(app)
        val now = System.currentTimeMillis()
        val through = prefs.getLong(KEY_THROUGH, 0L)
        val sentAt = prefs.getLong(KEY_SENT_AT, 0L).takeIf { it > 0 }
        // Spam calls still ring, slice 2: a newly screened call is news too.
        val newest = listOfNotNull(log.firstOrNull()?.atMs, os.meka.android.work.CallScreenLog.log(app).firstOrNull()?.atMs).maxOrNull()
        if (sending || !NotifyTraceReportRules.due(newest, through, sentAt, failedAtMs, now, force)) return
        sending = true
        app.appScope.launch {
            try {
                val health = NotifyTraceStore.health(app)
                val calls = runCatching { os.meka.android.work.CallScreenLog.report(app) }.getOrNull()
                val ok = runCatching { app.core.reportNotifyTrace(Build.MODEL, health, app.governor.device.value.name, calls) }.getOrDefault(false)
                if (ok) {
                    failedAtMs = null
                    val sentThrough = listOfNotNull(health.trace.firstOrNull()?.atMs, calls?.calls?.firstOrNull()?.atMs, through).max()
                    prefs.edit().putLong(KEY_THROUGH, sentThrough).putLong(KEY_SENT_AT, System.currentTimeMillis()).apply()
                } else {
                    failedAtMs = System.currentTimeMillis()
                }
            } finally {
                sending = false
            }
        }
    }
}
