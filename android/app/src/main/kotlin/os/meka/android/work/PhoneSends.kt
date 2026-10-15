package os.meka.android.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.android.MekaApplication
import os.meka.core.domain.CardFinish
import os.meka.core.domain.ChatShadeRules
import java.util.Collections

/**
 * Send (via your phone) from the Mac (WhatsApp replies that always have a way to send, slice 2). Meka's tap on the Mac
 * writes a request on the synced card; once it reaches this phone (a push wakes [os.meka.android.sync.SyncWorker], or
 * the open app's live sync), the words go out through the message's own notification Reply ([LiveReplies.send], the
 * official RemoteInput route, as on the Fold's own Send), and the answer goes back: Sent (the card leaves both devices)
 * or "the message was opened on your phone" (nothing sent; the Mac offers Reply in WhatsApp).
 *
 * Still "ask me, then send" (Level 3): only a request Meka's tap wrote, only within five minutes of it
 * ([os.meka.core.domain.TriageCards.PHONE_SEND_TTL_MS]), each once. No WhatsApp library, nothing automated in WhatsApp.
 */
object PhoneSends {
    private val lock = Mutex()
    /** Requests already tried in this process (message id and when asked), so a second emission never sends twice. */
    private val tried = Collections.synchronizedSet(HashSet<String>())

    suspend fun handle(app: MekaApplication) = lock.withLock {
        val due = runCatching { app.core.phoneSendsDue() }.getOrDefault(emptyList())
        due.forEach { r ->
            if (!tried.add("${r.messageId}@${r.atMs}")) return@forEach
            // The keys first: the Reply re-posts (or clears) the notification.
            val keys = LiveReplies.keysFor(listOf(r.messageId))
            val sent = LiveReplies.send(app, r.messageId, r.text)
            runCatching { app.core.phoneSendDone(r.messageId, r.atMs, sent) }
            // As after the Fold's own Send: the answered message leaves the shade (hidden only).
            if (sent && ChatShadeRules.clearsAfter(CardFinish.SENT)) WorkCaptureService.clear(keys)
        }
    }
}
