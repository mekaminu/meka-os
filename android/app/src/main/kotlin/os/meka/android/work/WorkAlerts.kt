package os.meka.android.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import os.meka.android.MainActivity
import os.meka.core.domain.BreakThrough
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.CapturedItem

/**
 * The only notifications work mode posts: something that shouldn't wait until after work. One high-importance
 * channel, so Meka can let it through Do Not Disturb in the phone's settings.
 */
object WorkAlerts {
    const val CHANNEL = "work_breakthrough"
    const val VOICE_LINE = "Left a message with your call assistant and said it's urgent"

    fun canPost(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Urgent while at work", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messages that say \"urgent\" or \"emergency\", and people on your always-notify list."
            },
        )
    }

    @SuppressLint("MissingPermission") // checked by canPost(), and a late revoke is caught below
    fun post(context: Context, item: CapturedItem, why: BreakThrough) {
        if (!canPost(context)) return
        ensureChannel(context)
        val title = when (item.kind) {
            CaptureKind.MISSED_CALL -> "Missed call · ${item.personName}"
            CaptureKind.MESSAGE -> "${item.personName} · ${item.app.label}"
            CaptureKind.VOICE_MESSAGE -> "Voice message · ${item.personName}"
        }
        // A voice message without its transcript yet still says why it rang through.
        val line = item.text ?: if (item.kind == CaptureKind.VOICE_MESSAGE) VOICE_LINE else why.label
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(line)
            .setSubText(why.label)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setCategory(if (item.kind == CaptureKind.MISSED_CALL) NotificationRules.CATEGORY_MISSED_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(item.id.hashCode(), n)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post: the item is still in the summary.
        }
    }
}
