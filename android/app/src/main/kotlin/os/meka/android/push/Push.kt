package os.meka.android.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import os.meka.android.MekaApplication
import os.meka.android.sync.SyncWorker

/**
 * Push via Firebase (build plan M1). The server sends one kind of message, a data-only "sync now" (`t=sync`) when
 * another device stored a change; it carries nothing about Meka's data. Receiving it runs a sync straight away, so an
 * edit on the Mac reaches Today, the widgets, the ongoing notifications and the governor in seconds instead of at the
 * next 15-minute background sync. Nothing is shown for it. Without push (no Play services, server not set up yet)
 * the periodic sync carries on as before.
 */
class MekaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as? MekaApplication)?.registerPush(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Notifications never seen (slice 1): each arrival goes in the trace, so Health shows the push path works.
        os.meka.android.notify.NotifyTraceStore.record(
            applicationContext,
            os.meka.core.domain.NotifyTraceEntry(System.currentTimeMillis(), os.meka.core.domain.NotifyTraceKind.PUSH, message.data["t"].orEmpty().take(20)),
        )
        if (PushMessages.isSyncWake(message.data)) SyncWorker.syncSoon(applicationContext)
        // Send a test (slice 2): the server's test push shows its own notification.
        if (PushMessages.isTest(message.data)) os.meka.android.notify.NotifyTester.pushArrived(applicationContext)
    }
}

/** Pure rules, unit-tested. */
object PushMessages {
    fun isSyncWake(data: Map<String, String>): Boolean = data["t"] == "sync"

    /** Send a test's push (notifications never seen, slice 2). */
    fun isTest(data: Map<String, String>): Boolean = data["t"] == "test"

    /** Send the token to the server unless it already has this very one. */
    fun needsSending(current: String?, sent: String?): Boolean = !current.isNullOrBlank() && current != sent
}

/** Remembers which token the server has, so it's sent once per new token (FCM rotates them now and then). */
class PushTokenPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("meka.push", Context.MODE_PRIVATE)
    var sent: String?
        get() = prefs.getString(KEY_SENT, null)
        set(v) { prefs.edit().putString(KEY_SENT, v).apply() }

    /** After re-enrolment the server knows nothing about this device; send again. */
    fun forget() = prefs.edit().remove(KEY_SENT).apply()

    private companion object { const val KEY_SENT = "sent" }
}

object PushTokens {
    /** Asks Firebase for this install's token and hands it to [onToken]. Quietly does nothing without Play services. */
    fun fetch(onToken: (String) -> Unit) {
        runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> if (t != null) onToken(t) } }
    }
}
