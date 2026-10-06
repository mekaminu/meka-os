package os.meka.android.work

import android.app.Notification
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.core.domain.Capture
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem

/**
 * Work mode's listener (build plan M1). Reads WhatsApp, SMS and missed-call notifications through Android's official
 * notification access, only while MEKA is in work mode, and keeps them on this phone for the after-work summary.
 * Urgent messages ("urgent", "emergency") and people on the always-notify list alert straight away.
 *
 * It never replies, never marks anything read and never dismisses the original notification.
 */
class WorkCaptureService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val app = NotificationRules.appFor(sbn.packageName, n.category, defaultSms) ?: return
        val meka = application as MekaApplication

        scope.launch {
            if (!meka.core.currentWorkMode().atWork) return@launch
            val items = runCatching { read(app, sbn, n) }.getOrDefault(emptyList())
            if (items.isEmpty()) return@launch
            val fresh = meka.captures.add(items)
            val lists = meka.captures.lists.value
            fresh.forEach { item -> Capture.breakThrough(item, lists)?.let { WorkAlerts.post(this@WorkCaptureService, item, it) } }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun read(app: CaptureApp, sbn: StatusBarNotification, n: Notification): List<CapturedItem> {
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val at = n.`when`.takeIf { it > 0 } ?: sbn.postTime

        if (app == CaptureApp.PHONE) {
            if (!NotificationRules.looksLikeMissedCall(n.category, title)) return emptyList()
            val who = NotificationRules.missedCallPerson(title, text) ?: return emptyList()
            return listOf(item(app, CaptureKind.MISSED_CALL, who, null, null, at))
        }

        // Messaging apps: prefer the structured messages (each with its own sender and time).
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val group = if (style.isGroupConversation) style.conversationTitle?.toString() else null
            return style.messages.mapNotNull { m ->
                val sender = m.person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null // null = Meka himself
                val body = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                item(app, CaptureKind.MESSAGE, sender, body, group, m.timestamp.takeIf { it > 0 } ?: at)
            }
        }
        if (NotificationRules.isSummaryOnly(title, text)) return emptyList()
        val (group, sender) = NotificationRules.splitGroupTitle(title!!.trim())
        return listOf(item(app, CaptureKind.MESSAGE, sender, text!!.trim(), group, at))
    }

    private fun item(app: CaptureApp, kind: CaptureKind, who: String, text: String?, group: String?, at: Long) =
        CapturedItem(Capture.itemId(app, kind, who, group, at, text), app, kind, who.take(200), text?.take(2_000), group?.take(200), at)
}
