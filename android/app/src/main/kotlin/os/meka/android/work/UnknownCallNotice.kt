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
import android.provider.CallLog
import androidx.core.app.NotificationCompat
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
import os.meka.core.domain.CallNoticeRules
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
    private const val IN_NAME = "networkName"

    /** From the screening service, once it has answered Android: look at this call in a minute. */
    fun schedule(context: Context, number: String, atMs: Long, toAssistant: Boolean, networkName: String? = null) {
        val key = BlockedCallerRules.keyOf(number) ?: return
        val request = OneTimeWorkRequestBuilder<UnknownCallWorker>()
            .setInitialDelay(UnknownCallRules.CHECK_AFTER_MS, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, UnknownCallRules.RECHECK_MS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(IN_NUMBER to number, IN_AT to atMs, IN_ASSISTANT to toAssistant, IN_NAME to networkName))
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

    /**
     * Spam calls still ring, slice 1: how many incoming calls (any outcome) [number] made since [sinceMs], from the
     * phone's own call log; 0 without the permission. Read here only, never sent.
     */
    internal fun incomingCallsSince(context: Context, number: String, sinceMs: Long): Int {
        if (!permissionToRead(context)) return 0
        val key = People.key(number)
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} >= ?", arrayOf(sinceMs.toString()), null,
            )?.use { c ->
                var n = 0
                while (c.moveToNext()) {
                    if (c.getInt(1) != CallLog.Calls.OUTGOING_TYPE && c.getString(0)?.let { People.key(it) } == key) n++
                }
                n
            } ?: 0
        }.getOrDefault(0)
    }

    fun permissionToRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, UnknownCallRules.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = UnknownCallRules.CHANNEL_ABOUT
            },
        )
    }

    private fun base(context: Context, title: String, line: String, big: String = line): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
    }

    private fun send(context: Context, tag: String, b: NotificationCompat.Builder): Boolean = try {
        os.meka.android.notify.MekaPost.notify(context, CallNotification.id(tag), b.build()); true
    } catch (e: SecurityException) { false }

    /**
     * The after-call notice, in the caller's own notification (call notifications, slice 3). Returns true when it was
     * posted, or held back because the caller's notification already says more (the call assistant's message alert or
     * an automatic block from the last hour): either way the call has been told.
     */
    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost(), and a late revoke is caught below
    fun post(context: Context, offer: UnknownCallOffer): Boolean {
        if (!WorkAlerts.canPost(context)) return false
        val tag = CallNoticeRules.tag(offer.number) ?: return false
        if (!CallNotification.mayPost(context, tag, CallNoticeRules.Kind.AFTER_CALL)) return true
        ensureChannel(context)
        val notice = CallNoticeRules.Notice(
            title = offer.title, text = offer.line, big = offer.line, sub = null, listen = null, callBack = null,
            block = UnknownCallRules.BLOCK_ACTION, notSpam = if (offer.notSpam) CallNoticeRules.NOT_SPAM else null,
        )
        val b = CallNotification.mark(context, base(context, offer.title, offer.line), tag, CallNoticeRules.Kind.AFTER_CALL)
        CallNotification.addActions(context, b, tag, offer.number, notice, blockWhy = offer.why, reportText = offer.reportText)
        return send(context, tag, b)
    }

    /**
     * After Block, Unblock or Not spam on a notice that isn't a message alert: the same notification says what it did
     * and keeps the way back (Unblock after Block, Block after Unblock or Not spam).
     */
    @SuppressLint("MissingPermission")
    fun postConfirm(context: Context, number: String, blocked: Boolean? = null, notSpam: Boolean = false) {
        if (!WorkAlerts.canPost(context)) return
        val tag = CallNoticeRules.tag(number) ?: return
        ensureChannel(context)
        val notice = CallNoticeRules.confirmNotice(number, blocked, notSpam)
        val b = CallNotification.mark(context, base(context, notice.title, notice.text), tag, CallNoticeRules.Kind.CONFIRM)
        CallNotification.addActions(context, b, tag, number, notice, blockWhy = WorkAlerts.BLOCK_WHY,
            reportText = BlockedCallerRules.reportText(number))
        send(context, tag, b)
    }

    /**
     * An automatic block (spam calls still ring, slices 1 and 3), in the caller's own notification: who, "Blocked · and
     * blocked from now on" and what happened, with Undo (takes it off the list; Activity has the same Undo) and Report
     * to 7726. Stays until swiped.
     */
    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost(), and a late revoke is caught below
    fun postAutoBlocked(
        context: Context, number: String, activityId: String, trigger: os.meka.core.domain.AutoBlockTrigger,
        atMs: Long, calls: Int = 0,
    ) {
        if (!WorkAlerts.canPost(context)) return
        val tag = CallNoticeRules.tag(number) ?: return
        if (!CallNotification.mayPost(context, tag, CallNoticeRules.Kind.AUTO_BLOCKED)) return
        ensureChannel(context)
        val notice = runCatching { (context.applicationContext as MekaApplication).core.autoBlockNotice(number, trigger, atMs, calls) }
            .getOrNull() ?: return
        val b = CallNotification.mark(context, base(context, notice.title, notice.text, notice.big), tag, CallNoticeRules.Kind.AUTO_BLOCKED)
        CallNotification.addActions(context, b, tag, number, notice, activityId = activityId,
            reportText = BlockedCallerRules.reportText(number))
        send(context, tag, b)
    }

    internal const val KEY_NUMBER = IN_NUMBER
    internal const val KEY_AT = IN_AT
    internal const val KEY_ASSISTANT = IN_ASSISTANT
    internal const val KEY_NAME = IN_NAME
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
        val networkName = inputData.getString(UnknownCallNotice.KEY_NAME)
        // Spam calls still ring, slice 1: an unknown number calling again within a week without leaving a message is
        // blocked now (never one he spoke to, never a claimed police/bank/NHS/school: those get the heads-up below).
        if (outcome != os.meka.core.domain.CallOutcome.ANSWERED && os.meka.core.domain.OfficialCallerRules.claim(networkName) == null) {
            val since = atMs - os.meka.core.domain.AutoBlockRules.REPEAT_DAYS * 86_400_000L
            val calls = UnknownCallNotice.incomingCallsSince(ctx, number, since)
            val id = runCatching { app.core.autoBlockRepeatCaller(number, calls, atMs) }.getOrNull()
            if (id != null) {
                UnknownCallNotice.postAutoBlocked(ctx, number, id, os.meka.core.domain.AutoBlockTrigger.REPEAT_CALLER, atMs, calls)
                return Result.success()
            }
        }
        val offer = runCatching {
            app.core.unknownCallOffer(number, outcome, entry?.second ?: 0L, atMs, UnknownCallNotice.offered(ctx), networkName)
        }.getOrNull() ?: return Result.success()
        if (UnknownCallNotice.post(ctx, offer)) UnknownCallNotice.markOffered(ctx, offer.key, System.currentTimeMillis())
        return Result.success()
    }
}

/**
 * The buttons on a caller's notification (call notifications, slice 3): Block (onto the synced block list; Work →
 * Spam protection lists it), Unblock, Not spam (off Suspected spam, never flagged again) and Undo on an automatic
 * block. Afterwards the same notification shows the new state: a message alert is redrawn with its new block line,
 * anything else says what was done.
 */
class UnknownCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(ACTION_BLOCK, ACTION_UNDO, ACTION_UNBLOCK, ACTION_NOT_SPAM)) return
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        val why = intent.getStringExtra(EXTRA_WHY)
        val activityId = intent.getStringExtra(EXTRA_ACTIVITY)
        val itemId = intent.getStringExtra(EXTRA_ITEM)
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val key = BlockedCallerRules.keyOf(number)
                val done: Boolean? = when (action) {
                    // Undo on an auto-block: the same Undo as Activity's (it takes the number off the list).
                    ACTION_UNDO -> { if (activityId != null) runCatching { app.core.undoActivity(activityId) }; false }
                    ACTION_UNBLOCK -> if (key != null && runCatching { app.core.unblockCaller(key) }.getOrDefault(false)) false else null
                    ACTION_NOT_SPAM -> if (key != null && runCatching { app.core.dismissSuspectedCaller(key) }.getOrDefault(false)) false else null
                    else -> if (runCatching { app.core.blockCaller(number, why) }.getOrDefault(false)) true else null
                }
                if (done != null) show(app, number, itemId, blocked = done, notSpam = action == ACTION_NOT_SPAM)
            } finally { pending.finish() }
        }
    }

    private fun show(app: MekaApplication, number: String, itemId: String?, blocked: Boolean, notSpam: Boolean) {
        val tag = CallNoticeRules.tag(number)
        val showing = tag?.let { CallNotification.showing(app, it) }
        val item = if (itemId != null && showing?.kind == CallNoticeRules.Kind.MESSAGE && showing.itemId == itemId) {
            runCatching {
                app.core.afterWork.value.withLists(app.captures.lists.value, CallerLookup.names(app)).people
                    .flatMap { it.items }.firstOrNull { it.id == itemId }
            }.getOrNull()
        } else null
        if (item != null) WorkAlerts.post(app, item, os.meka.core.domain.BreakThrough.URGENT, play = item.hasAudio)
        else UnknownCallNotice.postConfirm(app, number, blocked = blocked, notSpam = notSpam)
    }

    companion object {
        const val ACTION_BLOCK = "os.meka.work.BLOCK_UNKNOWN_CALLER"
        const val ACTION_UNDO = "os.meka.work.UNDO_AUTO_BLOCK"
        const val ACTION_UNBLOCK = "os.meka.work.UNBLOCK_CALLER"
        const val ACTION_NOT_SPAM = "os.meka.work.NOT_SPAM_CALLER"
        const val EXTRA_ACTIVITY = "os.meka.work.activity"
        const val EXTRA_NUMBER = "os.meka.work.number"
        const val EXTRA_WHY = "os.meka.work.why"
        const val EXTRA_ITEM = "os.meka.work.item"
    }
}
