package os.meka.android.work

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.core.domain.CallNoticeRules
import os.meka.core.domain.InCallWarning
import os.meka.core.domain.InCallWarningRules

/**
 * The in-call scam warning on the Fold (scam call fix, slice 2; Meka 2026-10-22 19:49). While an unknown caller rings
 * through, a heads-up notification lies across the top of the incoming-call screen, in full on the lock screen (public
 * visibility): who ("Unknown number", or "Says it's the police · unknown number") and the
 * advice. It makes no sound of its own (the ringtone is playing) and goes when the after-call look finds the call over
 * ([UnknownCallWorker]) or after [InCallWarningRules.SHOW_FOR_MS]. Obi says one line in his earbuds when they're in.
 *
 * Android lets only the phone app draw on the call screen itself, so the warning is the system's heads-up, which shows
 * over it.
 */
object InCallWarningNotice {
    const val CHANNEL = "call_warning"

    private fun id(number: String?): Int = ("warn:" + (CallNoticeRules.tag(number) ?: "withheld")).hashCode()

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, InCallWarningRules.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = InCallWarningRules.CHANNEL_ABOUT
                setSound(null, null) // the ringtone is already playing
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /** Posts the warning and has Obi say it into his earbuds. Never throws: a warning must not disturb the call. */
    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost(), and a late revoke is caught below
    fun show(context: Context, number: String?, warning: InCallWarning) {
        runCatching { (context.applicationContext as MekaApplication).speakUp.sayCallWarning(warning.spoken) }
        if (!WorkAlerts.canPost(context)) return
        runCatching {
            ensureChannel(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(warning.title)
                .setContentText(warning.line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(warning.big))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(InCallWarningRules.SHOW_FOR_MS)
                .setContentIntent(open)
                .setAutoCancel(true)
            os.meka.android.notify.MekaPost.notify(context, id(number), b.build())
        }
    }

    /** The call is over: the warning goes (the after-call note takes over). */
    fun clear(context: Context, number: String?) {
        runCatching { NotificationManagerCompat.from(context).cancel(id(number)) }
    }
}
