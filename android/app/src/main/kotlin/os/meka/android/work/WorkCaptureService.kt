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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.android.MekaApplication
import os.meka.core.domain.Capture
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.GroupDigestRules
import os.meka.core.domain.MessageTriageRules
import os.meka.core.domain.RequestWatchRules
import os.meka.core.facade.RequestRead
import os.meka.core.facade.TriageRead

/**
 * Work mode's listener (build plan M1). Reads WhatsApp, SMS and missed-call notifications through Android's official
 * notification access. While MEKA is in work mode it keeps them for the after-work summary (a sealed copy on this
 * phone, and synced through Meka's own server so the Mac shows the same summary); urgent messages ("urgent",
 * "emergency") and people on the always-notify list alert straight away.
 *
 * All day, a new message from the Family list or someone on "Watch for requests from" (V1, requests slice 3) is read
 * for requests through [os.meka.core.facade.MekaCore.readRequest]: only that message's text, the sender's name and its
 * time go to MEKA's AI, and what comes back is a Needs you card, never an action. Each message is read once.
 *
 * All day too, the messages assistant (V1, slice 2) triages every new message through
 * [os.meka.core.facade.MekaCore.triageMessage]: a 1:1 message, or a group message naming Meka, goes alone to MEKA's AI
 * and comes back as a Needs you card (Needs a reply with a drafted reply, an Action, FYI); a busy group's chatter is kept
 * on this phone, sealed, for the digest, with no AI. Each message is triaged once.
 *
 * It never replies on its own and never marks anything read. The only notifications it dismisses are a Digest group's
 * chatter once every message in it is kept for the digest (slice 4: the group stops sitting in the shade). While a
 * message's notification shows, its Reply action is held in memory ([LiveReplies]) so Send on its Needs you card (Meka's
 * tap, slice 3) answers through Android's own Reply.
 */
class WorkCaptureService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requestLock = Mutex()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val app = NotificationRules.appFor(sbn.packageName, n.category, defaultSms) ?: return
        val meka = application as MekaApplication

        scope.launch {
            val items = runCatching { read(app, sbn, n) }.getOrDefault(emptyList())
            if (items.isEmpty()) return@launch
            // The notification's own Reply, held in memory while it shows, for Send on a Needs you card (slice 3).
            if (app != CaptureApp.PHONE) LiveReplies.remember(sbn, items.map { it.id })
            if (meka.core.currentWorkMode().atWork) {
                val fresh = meka.captures.add(items)
                val lists = meka.captures.lists.value
                // Into the synced summary too (Needs Meka #10), so the Mac shows it and Done on either clears both.
                if (fresh.isNotEmpty()) runCatching { meka.core.holdCaptured(fresh, lists) }
                fresh.forEach { item -> Capture.breakThrough(item, lists)?.let { WorkAlerts.post(this@WorkCaptureService, item, it) } }
            }
            // After the work path, so an urgent alert never waits on the AI.
            readRequests(meka, items)
            triage(meka, items)
            // A notification wholly from Digest groups, every message kept on this phone: cleared, so the group
            // stops sitting in the shade (slice 4). Mentions, 1:1 chats and Normal groups are never cleared.
            if (GroupDigestRules.clearsNotification(items, meka.captures.triageSettings.value, meka.captures.digestKept())) {
                runCatching { cancelNotification(sbn.key) }
            }
        }
    }

    /** One at a time, so two re-posts of the same message never both reach the AI. */
    private suspend fun readRequests(meka: MekaApplication, items: List<CapturedItem>) = requestLock.withLock {
        val store = meka.captures
        val lists = store.lists.value
        val watch = store.watch.value
        val toRead = RequestWatchRules.toRead(items, lists, watch, store.requestSeen(), System.currentTimeMillis())
        toRead.forEach { item ->
            val result = runCatching { meka.core.readRequest(item, lists, watch.people, watch.groups) }.getOrNull()
            // Offline or AI unavailable: not marked, so WhatsApp's next re-post tries again.
            if (result is RequestRead.Read || result is RequestRead.Skipped) store.markRequestSeen(listOf(item.id))
        }
    }

    /** The messages assistant: one message at a time (under the same lock, so a re-post is never triaged twice). */
    private suspend fun triage(meka: MekaApplication, items: List<CapturedItem>) = requestLock.withLock {
        val store = meka.captures
        // Each group's mode as switched on its digest card (slice 4); the never-to-AI list comes with slice 5's screen.
        val settings = store.triageSettings.value
        val toTriage = MessageTriageRules.toTriage(items, settings, store.triageSeen(), System.currentTimeMillis())
        val digest = mutableListOf<CapturedItem>()
        val done = mutableListOf<String>()
        toTriage.forEach { item ->
            when (runCatching { meka.core.triageMessage(item, settings) }.getOrNull()) {
                is TriageRead.Digest -> { digest += item; done += item.id }
                is TriageRead.Read, TriageRead.Skipped -> done += item.id
                // Offline or AI unavailable: not marked, so WhatsApp's next re-post tries again.
                is TriageRead.Unavailable, null -> Unit
            }
        }
        store.keepForDigest(digest)
        store.markTriageSeen(done)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName) LiveReplies.forget(sbn)
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
