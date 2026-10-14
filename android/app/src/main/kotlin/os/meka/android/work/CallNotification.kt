package os.meka.android.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import os.meka.core.domain.CallNoticeRules

/**
 * Call notifications, slice 3: one live notification per caller. The after-call notice, the call assistant's message
 * alert, an automatic block and what Block / Unblock / Not spam did all post under the caller's [CallNoticeRules.tag]
 * (same id, same group), so a call updates its notification in place instead of stacking a second one; which kind is
 * showing travels in the notification's extras, and [CallNoticeRules.replaces] stops a less telling notice (the
 * after-call "Sent to your call assistant") wiping a more telling one (the message alert it led to).
 */
object CallNotification {
    private const val EXTRA_KIND = "os.meka.callKind"
    private const val EXTRA_ITEM = "os.meka.callItem"

    fun id(tag: String): Int = tag.hashCode()

    /** What the caller's notification shows now, or null when nothing is showing. */
    data class Showing(val kind: CallNoticeRules.Kind, val postedAtMs: Long, val itemId: String?)

    fun showing(context: Context, tag: String): Showing? = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id(tag) }?.let { sbn ->
            val extras = sbn.notification.extras
            val kind = extras.getString(EXTRA_KIND)?.let { k -> CallNoticeRules.Kind.entries.firstOrNull { it.name == k } }
                ?: CallNoticeRules.Kind.CONFIRM
            Showing(kind, sbn.postTime, extras.getString(EXTRA_ITEM))
        }
    }.getOrNull()

    /** Whether [incoming] may take over the caller's notification now. */
    fun mayPost(context: Context, tag: String, incoming: CallNoticeRules.Kind): Boolean {
        val now = showing(context, tag) ?: return true
        return CallNoticeRules.replaces(incoming, now.kind, now.postedAtMs, System.currentTimeMillis())
    }

    /** Marks a builder as the caller's notification of [kind] (and, for a message alert, which message); its tap opens the call. */
    fun mark(context: Context, b: NotificationCompat.Builder, tag: String, kind: CallNoticeRules.Kind, itemId: String? = null): NotificationCompat.Builder =
        b.setGroup(tag).setContentIntent(open(context, tag, itemId)).addExtras(Bundle().apply {
            putString(EXTRA_KIND, kind.name)
            if (itemId != null) putString(EXTRA_ITEM, itemId)
        })

    /**
     * Call notifications, slice 5: tapping the caller's notification opens Activity → Calls with the call unfolded
     * ([heldId] for a message alert, else the caller's newest call by [tag]'s number key). Its own request code per
     * caller, so one caller's tap never carries another's call.
     */
    fun open(context: Context, tag: String, heldId: String? = null): PendingIntent = PendingIntent.getActivity(
        context, id(tag) + 7,
        Intent(context, os.meka.android.MainActivity::class.java)
            .putExtra(os.meka.android.MainActivity.EXTRA_OPEN, os.meka.core.domain.CallLogRules.openCall(heldId))
            .putExtra(os.meka.android.MainActivity.EXTRA_CALL_KEY, tag.removePrefix("call:"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun broadcast(context: Context, code: Int, action: String, number: String, extra: (Intent) -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, UnknownCallReceiver::class.java).setAction(action)
                .putExtra(UnknownCallReceiver.EXTRA_NUMBER, number).also(extra),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The notice's buttons, at most three (Android shows no more): ▶ Listen when given, then Block or Unblock, Not spam,
     * Undo, Call back, Report to 7726, in that order of importance.
     */
    fun addActions(
        context: Context,
        b: NotificationCompat.Builder,
        tag: String,
        number: String,
        notice: CallNoticeRules.Notice,
        listen: PendingIntent? = null,
        blockWhy: String? = null,
        itemId: String? = null,
        activityId: String? = null,
        reportText: String? = null,
    ) {
        val base = id(tag)
        val actions = mutableListOf<Pair<String, PendingIntent>>()
        fun withItem(i: Intent) { if (itemId != null) i.putExtra(UnknownCallReceiver.EXTRA_ITEM, itemId) }
        val listenLabel = notice.listen
        if (listen != null && listenLabel != null) actions.add(listenLabel to listen)
        notice.block?.let { label ->
            actions.add(label to broadcast(context, base + 2, UnknownCallReceiver.ACTION_BLOCK, number) { i -> i.putExtra(UnknownCallReceiver.EXTRA_WHY, blockWhy); withItem(i) })
        }
        notice.unblock?.let { label -> actions.add(label to broadcast(context, base + 3, UnknownCallReceiver.ACTION_UNBLOCK, number, ::withItem)) }
        notice.notSpam?.let { label -> actions.add(label to broadcast(context, base + 4, UnknownCallReceiver.ACTION_NOT_SPAM, number, ::withItem)) }
        notice.undo?.let { label ->
            actions.add(label to broadcast(context, base + 5, UnknownCallReceiver.ACTION_UNDO, number) { i -> i.putExtra(UnknownCallReceiver.EXTRA_ACTIVITY, activityId) })
        }
        notice.callBack?.let {
            actions.add(it to PendingIntent.getActivity(
                context, base + 1,
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
        }
        if (reportText != null) {
            actions.add(os.meka.core.domain.UnknownCallRules.REPORT_ACTION to PendingIntent.getActivity(
                context, base + 6,
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + os.meka.core.domain.BlockedCallerRules.REPORT_TO))
                    .putExtra("sms_body", reportText).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
        }
        actions.take(MAX_ACTIONS).forEach { (label, intent) -> b.addAction(0, label, intent) }
    }

    private const val MAX_ACTIONS = 3
}
