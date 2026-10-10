package os.meka.android.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.core.domain.BlockedCallerRules
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.People
import os.meka.core.domain.UnknownCallOffer
import os.meka.core.domain.UnknownCallRules
import java.util.concurrent.TimeUnit

/**
 * "Unknown caller · Block?" (call assistant polish 8b b): a minute after a call the screening service marked
 * ([UnknownCallRules.watch]), this reads how the call ended from the phone's own call log (only with the call-log
 * permission Meka granted in Work → Call assistant → Recognise callers; nothing is sent anywhere) and posts one quiet
 * notification with Block and Report to 7726. A call still going is looked at again a little later.
 */
object UnknownCallNotice {
    const val CHANNEL = "unknown_caller"
    private const val PREFS = "call_screening"
    private const val KEY_OFFERED = "unknownOffered"

    private const val IN_NUMBER = "number"
    private const val IN_AT = "atMs"
    private const val IN_ASSISTANT = "toAssistant"

    /** From the screening service, once it has answered Android: look at this call in a minute. */
    fun schedule(context: Context, number: String, atMs: Long, toAssistant: Boolean) {
        val key = BlockedCallerRules.keyOf(number) ?: return
        val request = OneTimeWorkRequestBuilder<UnknownCallWorker>()
            .setInitialDelay(UnknownCallRules.CHECK_AFTER_MS, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, UnknownCallRules.RECHECK_MS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(IN_NUMBER to number, IN_AT to atMs, IN_ASSISTANT to toAssistant))
            .build()
        // A second call from the same number replaces the first look: one notification for the latest call.
        WorkManager.getInstance(context).enqueueUniqueWork("unknown-call-$key", ExistingWorkPolicy.REPLACE, request)
    }

    internal fun offered(context: Context) =
        CallScreeningRules.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_OFFERED, null))

    internal fun markOffered(context: Context, key: String, nowMs: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = UnknownCallRules.remember(CallScreeningRules.decode(prefs.getString(KEY_OFFERED, null)), key, nowMs)
        prefs.edit().putString(KEY_OFFERED, CallScreeningRules.encode(next)).apply()
    }

    /** The call's log entry: (type, duration in seconds), or null while it isn't there yet or without the permission. */
    internal fun logEntry(context: Context, number: String, atMs: Long): Pair<Int, Long>? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val key = People.key(number)
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                "${CallLog.Calls.DATE} >= ?", arrayOf((atMs - UnknownCallRules.LOG_SLOP_MS).toString()),
                "${CallLog.Calls.DATE} ASC",
            )?.use { c ->
                var found: Pair<Int, Long>? = null
                while (found == null && c.moveToNext()) {
                    if (c.getString(0)?.let { People.key(it) } == key && c.getInt(1) != CallLog.Calls.OUTGOING_TYPE) {
                        found = c.getInt(1) to c.getLong(2)
                    }
                }
                found
            }
        }.getOrNull()
    }

    fun permissionToRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, UnknownCallRules.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = UnknownCallRules.CHANNEL_ABOUT
            },
        )
    }

    private fun id(key: String) = ("unknown-call:" + key).hashCode()

    /** Opens Messages with "Call 01904618691" to 7726 (Meka sends it); straight to the activity, no trampoline. */
    private fun reportIntent(context: Context, offer: UnknownCallOffer): PendingIntent = PendingIntent.getActivity(
        context, id(offer.key),
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + BlockedCallerRules.REPORT_TO))
            .putExtra("sms_body", offer.reportText).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun base(context: Context, offer: UnknownCallOffer, line: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(offer.title)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost(), and a late revoke is caught below
    fun post(context: Context, offer: UnknownCallOffer): Boolean {
        if (!WorkAlerts.canPost(context)) return false
        ensureChannel(context)
        val block = PendingIntent.getBroadcast(
            context, id(offer.key),
            Intent(context, UnknownCallReceiver::class.java).setAction(UnknownCallReceiver.ACTION_BLOCK)
                .putExtra(UnknownCallReceiver.EXTRA_NUMBER, offer.number)
                .putExtra(UnknownCallReceiver.EXTRA_WHY, offer.why),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = base(context, offer, offer.line)
            .addAction(0, UnknownCallRules.BLOCK_ACTION, block)
            .addAction(0, UnknownCallRules.REPORT_ACTION, reportIntent(context, offer))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id(offer.key), n); true
        } catch (e: SecurityException) { false }
    }

    /** After Block: the same notification says it's done, keeps Report to 7726, and goes by itself. */
    @SuppressLint("MissingPermission")
    fun postBlocked(context: Context, number: String, why: String) {
        if (!WorkAlerts.canPost(context)) return
        val key = BlockedCallerRules.keyOf(number) ?: return
        val shown = BlockedCallerRules.display(number)
        val offer = UnknownCallOffer(key, number, "Unknown caller · $shown", BlockedCallerRules.BLOCKED_LINE, why,
            BlockedCallerRules.reportText(number) ?: return)
        val n = base(context, offer, BlockedCallerRules.BLOCKED_LINE)
            .addAction(0, UnknownCallRules.REPORT_ACTION, reportIntent(context, offer))
            .setTimeoutAfter(BLOCKED_SHOWN_MS)
            .build()
        try { NotificationManagerCompat.from(context).notify(id(key), n) } catch (e: SecurityException) { }
    }

    private const val BLOCKED_SHOWN_MS = 60_000L

    internal const val KEY_NUMBER = IN_NUMBER
    internal const val KEY_AT = IN_AT
    internal const val KEY_ASSISTANT = IN_ASSISTANT
}

/** Looks at one watched call: how it ended, then the notification (or nothing). */
class UnknownCallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val number = inputData.getString(UnknownCallNotice.KEY_NUMBER) ?: return Result.success()
        val atMs = inputData.getLong(UnknownCallNotice.KEY_AT, 0L)
        val toAssistant = inputData.getBoolean(UnknownCallNotice.KEY_ASSISTANT, false)
        val ctx = applicationContext
        val entry = if (toAssistant) null else UnknownCallNotice.logEntry(ctx, number, atMs)
        // Still on the call (no log entry yet): look again later, a few times, then word it without how it ended.
        if (!toAssistant && entry == null && UnknownCallNotice.permissionToRead(ctx) && runAttemptCount + 1 < UnknownCallRules.MAX_CHECKS) {
            return Result.retry()
        }
        val app = ctx as MekaApplication
        val outcome = UnknownCallRules.outcomeOf(entry?.first, entry?.second ?: 0L, toAssistant)
        val offer = runCatching {
            app.core.unknownCallOffer(number, outcome, entry?.second ?: 0L, atMs, UnknownCallNotice.offered(ctx))
        }.getOrNull() ?: return Result.success()
        if (UnknownCallNotice.post(ctx, offer)) UnknownCallNotice.markOffered(ctx, offer.key, System.currentTimeMillis())
        return Result.success()
    }
}

/** Block on "Unknown caller · Block?": the number goes on the synced block list (Work → Spam protection lists it). */
class UnknownCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BLOCK) return
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        val why = intent.getStringExtra(EXTRA_WHY)
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val ok = runCatching { app.core.blockCaller(number, why) }.getOrDefault(false)
                if (ok) UnknownCallNotice.postBlocked(app, number, why.orEmpty())
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_BLOCK = "os.meka.work.BLOCK_UNKNOWN_CALLER"
        const val EXTRA_NUMBER = "os.meka.work.number"
        const val EXTRA_WHY = "os.meka.work.why"
    }
}
