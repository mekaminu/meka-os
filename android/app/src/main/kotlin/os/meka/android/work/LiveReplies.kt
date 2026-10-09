package os.meka.android.work

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.meka.core.domain.CaptureApp
import java.util.concurrent.ConcurrentHashMap

/**
 * The messages assistant's way back (V1, messages slice 3): for each message the listener read, the live notification's
 * own **Reply** action (Android's RemoteInput, the official route; no WhatsApp library) and the notification's tap
 * target (the chat itself). Held in memory only, for as long as the notification is showing: nothing is stored, synced
 * or sent anywhere by holding it.
 *
 * Personal messages stay at "ask me, then send" (autonomy Level 3): [send] is only ever called from Meka's tap of Send
 * (or Send all) on a Needs you card, with the words on the card.
 */
object LiveReplies {
    private class Live(
        val sbnKey: String,
        val packageName: String,
        val reply: PendingIntent?,
        val inputs: Array<RemoteInput>?,
        val open: PendingIntent?,
    )

    private val byMessage = ConcurrentHashMap<String, Live>()
    private val _live = MutableStateFlow<Set<String>>(emptySet())

    /** The message ids whose notification still offers a Reply MEKA can fill. */
    val live: StateFlow<Set<String>> = _live.asStateFlow()

    /** Remembers [sbn]'s Reply action and tap target for each of the [messageIds] it carried. */
    fun remember(sbn: StatusBarNotification, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val n = sbn.notification ?: return
        val reply = replyAction(n)
        val entry = Live(sbn.key, sbn.packageName, reply?.actionIntent, reply?.remoteInputs, n.contentIntent)
        messageIds.forEach { byMessage[it] = entry }
        publish()
    }

    /** The notification went (read, replied to, swiped): its messages can only be answered in the app now. */
    fun forget(sbn: StatusBarNotification) {
        if (byMessage.values.removeIf { it.sbnKey == sbn.key }) publish()
    }

    /**
     * Sends [text] as the reply to [messageId] through its notification's own Reply action. False when the notification
     * is gone or the app refused; the caller then copies the reply and opens the chat instead.
     */
    fun send(context: Context, messageId: String, text: String): Boolean {
        val live = byMessage[messageId] ?: return false
        val pi = live.reply ?: return false
        val inputs = live.inputs?.takeIf { it.isNotEmpty() } ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fill, results)
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return runCatching { pi.send(context, 0, fill) }.isSuccess.also { ok ->
            // A reply action is good for one answer: WhatsApp reposts (or clears) the notification after it.
            if (ok) { byMessage.values.removeIf { it.sbnKey == live.sbnKey }; publish() }
        }
    }

    /**
     * Opens [messageId]'s chat: the notification's own tap target while it shows, else the app ([app]). [copy] goes to
     * the clipboard first so Meka only pastes and taps send. False when neither could be opened.
     */
    fun open(context: Context, messageId: String, app: CaptureApp?, copy: String?): Boolean {
        copy?.let { copyText(context, it) }
        val live = byMessage[messageId]
        live?.open?.let { pi ->
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                ).toBundle()
            } else null
            if (runCatching { pi.send(context, 0, null, null, null, null, options) }.isSuccess) return true
        }
        val pkg = live?.packageName ?: when (app) {
            CaptureApp.SMS -> runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            else -> "com.whatsapp"
        } ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("MEKA reply", text))
    }

    /** The notification's free-text Reply (the phone's own actions first, then the ones it offers watches). */
    private fun replyAction(n: Notification): Notification.Action? {
        val all = (n.actions?.toList() ?: emptyList()) + Notification.WearableExtender(n).actions
        return all.firstOrNull { a ->
            a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true &&
                a.semanticAction.let { it == Notification.Action.SEMANTIC_ACTION_REPLY || it == Notification.Action.SEMANTIC_ACTION_NONE }
        }
    }

    private fun publish() { _live.value = byMessage.keys.toSet() }
}
