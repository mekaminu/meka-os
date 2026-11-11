package os.meka.android.work

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.android.MekaApplication
import os.meka.core.domain.Capture
import os.meka.core.domain.CaptureApp
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.People
import os.meka.core.domain.TriageReplyRules
import os.meka.core.domain.WhatsAppCallRules
import os.meka.core.domain.WhatsAppCallSettings
import os.meka.core.domain.WhatsAppCallVerdict
import os.meka.core.domain.WhatsAppIncomingCall
import os.meka.core.domain.WhatsAppReplyOutcome
import os.meka.core.domain.WorkModeState
import java.util.concurrent.ConcurrentHashMap

/**
 * Meka's choice for WhatsApp calls in Work mode (Work mode → Call assistant → WhatsApp calls) and who already had the
 * at-work message today. A small file on this phone only: the Mac has no WhatsApp calls. Off until he turns it on.
 */
class WhatsAppCallStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        WhatsAppCallSettings(prefs.getBoolean(KEY_ON, false), prefs.getString(KEY_MESSAGE, null) ?: WhatsAppCallRules.DEFAULT_MESSAGE),
    )
    val settings: StateFlow<WhatsAppCallSettings> = _settings.asStateFlow()

    fun setOn(on: Boolean) {
        prefs.edit().putBoolean(KEY_ON, on).apply()
        _settings.value = _settings.value.copy(on = on)
    }

    /** His words; blank puts the suggested message back. */
    fun setMessage(text: String) {
        val words = WhatsAppCallRules.cleaned(text) ?: WhatsAppCallRules.DEFAULT_MESSAGE
        prefs.edit().putString(KEY_MESSAGE, words).apply()
        _settings.value = _settings.value.copy(message = words)
    }

    fun repliedToday(today: Long): Set<String> = WhatsAppCallRules.repliedToday(log(), today)

    fun markReplied(caller: String, today: Long) = synchronized(this) {
        prefs.edit().putString(KEY_LOG, WhatsAppCallRules.encode(WhatsAppCallRules.recordReplied(log(), caller, today))).apply()
    }

    private fun log() = WhatsAppCallRules.decode(prefs.getString(KEY_LOG, null))

    private companion object {
        const val PREFS = "whatsapp_calls"
        const val KEY_ON = "on"
        const val KEY_MESSAGE = "message"
        const val KEY_LOG = "replied"
    }
}

/**
 * WhatsApp calls in Work mode (Meka approved 2026-11-11), on the work-mode listener. Official notification access only:
 * the call is declined through its notification's own Decline (CallStyle's decline intent, else the Decline action);
 * if it offers none, the notification is cleared so it stops showing and the call is left to go missed. Nothing
 * answers the call, touches its audio, or uses any WhatsApp library.
 *
 * The at-work message ([WhatsAppCallRules.message], Meka's words) goes only when [WhatsAppCallRules.decide] says so,
 * through Android's own Reply on the call's notification, the missed-call notification that follows it, or a chat
 * notification from that person still showing; never to a group. Without any Reply nothing is sent and the card says so.
 */
object WhatsAppCalls {
    /** When each caller's ringing call was last handled, so WhatsApp's re-posts while it rings are one call. */
    private val handled = ConcurrentHashMap<String, Long>()

    /** Callers just declined, waiting for a notification from them that offers Reply. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Notification>>()

    /**
     * Handles [sbn] when it is a ringing WhatsApp call (returns true: it is never read as a message). Any other WhatsApp
     * notification is offered to a declined caller waiting for a Reply (returns false: read as usual).
     */
    fun onPosted(service: NotificationListenerService, scope: CoroutineScope, sbn: StatusBarNotification, n: Notification): Boolean {
        if (sbn.packageName !in NotificationRules.WHATSAPP_PACKAGES) return false
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val actions = n.actions?.toList().orEmpty()
        val titles = actions.map { it.title?.toString().orEmpty() }
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val person = runCatching { BundleCompat.getParcelable(extras, Notification.EXTRA_CALL_PERSON, Person::class.java) }.getOrNull()
        val call = WhatsAppCallRules.incoming(true, n.category, title, text, titles, callType, person?.name?.toString())
        if (call == null) {
            offerReply(title, text, n)
            return false
        }
        val key = People.key(call.group ?: call.caller)
        val now = System.currentTimeMillis()
        if (WhatsAppCallRules.sameCall(handled[key], now)) return true
        val meka = service.application as MekaApplication
        val store = meka.whatsAppCalls
        val work = meka.core.workMode.value
        val today = os.meka.core.domain.LocalCalendar.fixedOffset(java.util.TimeZone.getDefault().getOffset(now).toLong()).epochDayOf(now)
        val decision = WhatsAppCallRules.decide(
            call, store.settings.value, work.atWork, meka.captures.lists.value, store.repliedToday(today), assistantCanPickUp(service, work),
        )
        if (decision.verdict == WhatsAppCallVerdict.RING) return true
        handled[key] = now
        // Registered before declining, so a missed-call notification WhatsApp posts at once is caught.
        val replying = decision.verdict == WhatsAppCallVerdict.DECLINE_AND_REPLY
        val wait = if (replying) CompletableDeferred<Notification>().also { waiting[People.key(call.caller)] = it } else null
        if (!decline(service, extras, actions, titles)) runCatching { service.cancelNotification(sbn.key) }
        scope.launch {
            val words = WhatsAppCallRules.message(store.settings.value)
            val outcome = if (!replying) WhatsAppReplyOutcome.NOT_TRIED else {
                val sent = LiveReplies.replyVia(service, n, words) || run {
                    val next = withTimeoutOrNull(WhatsAppCallRules.REPLY_WAIT_MS) { wait!!.await() }
                    next != null && LiveReplies.replyVia(service, next, words)
                } || LiveReplies.sendTo(service, call.caller, words)
                waiting.remove(People.key(call.caller))
                if (sent) { store.markReplied(call.caller, today); WhatsAppReplyOutcome.SENT } else WhatsAppReplyOutcome.NO_REPLY_ACTION
            }
            record(meka, call, now, outcome, decision.why, TriageReplyRules.chatNumber(call.caller, person?.uri, n.shortcutId))
        }
        return true
    }

    /** The call assistant is on, screening calls and not paused: a caller who rings his mobile reaches it. */
    fun assistantCanPickUp(context: Context, work: WorkModeState): Boolean =
        work.callAssistant && !work.callAssistantPaused && MekaCallScreeningService.roleHeld(context)

    /** Opens [item]'s WhatsApp chat (Call back): its number's chat when known, else WhatsApp. */
    fun callBack(context: Context, item: CapturedItem): Boolean =
        LiveReplies.open(context, item.id, CaptureApp.WHATSAPP, copy = null, link = WhatsAppCallRules.callBackLink(item.chatNumber))

    private fun offerReply(title: String?, text: String?, n: Notification) {
        if (waiting.isEmpty()) return
        val names = listOfNotNull(title, NotificationRules.missedCallPerson(title, text)).map(People::key)
        names.firstNotNullOfOrNull { waiting[it] }?.complete(n)
    }

    /** Fires the call's own Decline: CallStyle's decline intent, else the action titled Decline. False when neither. */
    private fun decline(context: Context, extras: android.os.Bundle, actions: List<Notification.Action>, titles: List<String>): Boolean {
        val styled = runCatching { BundleCompat.getParcelable(extras, Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java) }.getOrNull()
        val pi = styled ?: WhatsAppCallRules.declineIndex(titles)?.let { actions.getOrNull(it)?.actionIntent } ?: return false
        return runCatching { pi.send(context, 0, Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)) }.isSuccess
    }

    private suspend fun record(
        meka: MekaApplication, call: WhatsAppIncomingCall, atMs: Long, outcome: WhatsAppReplyOutcome,
        why: os.meka.core.domain.WhatsAppCallWhy, number: String?,
    ) {
        runCatching { meka.core.recordWhatsAppCall(call.group ?: call.caller, atMs, WhatsAppCallRules.activitySummary(call, outcome, why)) }
        // Held for after work like a missed call: "Called on WhatsApp · at-work message sent", with Call back.
        val note = WhatsAppCallRules.heldNote(outcome, why)
        val item = CapturedItem(
            Capture.itemId(CaptureApp.WHATSAPP, CaptureKind.MISSED_CALL, call.caller, call.group, atMs, note),
            CaptureApp.WHATSAPP, CaptureKind.MISSED_CALL, call.caller.take(200), note, call.group?.take(200), atMs,
        ).copy(chatNumber = number.takeIf { call.group == null })
        val fresh = meka.captures.add(listOf(item))
        if (fresh.isNotEmpty()) runCatching { meka.core.holdCaptured(fresh, meka.captures.lists.value) }
    }
}
