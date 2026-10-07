package os.meka.android.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import os.meka.android.MainActivity
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.AppUpdateRules

/**
 * The quiet "MEKA update ready" notification (build plan: hands-free phone updates). GitHub now publishes each new
 * phone build by itself, so Meka hears about it without opening the app: one low-importance note per build, only
 * while MEKA isn't on screen (the Today card says it there). Tapping opens MEKA, where Install is still his tap and
 * Android's own prompt; this notification installs nothing.
 */
object UpdateNotice {
    const val CHANNEL = "app_update"
    private const val NOTIFICATION_ID = 0x55504454 // "UPDT"
    private const val PREFS = "meka.update"
    private const val KEY_TOLD = "told"

    /** Called after each check with the build on offer (null: nothing to offer, so any note is withdrawn). */
    fun onOffer(context: Context, offered: AppUpdateRules.Build?, onScreen: Boolean) {
        if (offered == null) { cancel(context); return }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val told = prefs.getLong(KEY_TOLD, -1).takeIf { it > 0 }
        if (AppUpdateRules.shouldNotify(offered, told, onScreen)) post(context, offered)
        AppUpdateRules.told(offered, told)?.let { if (it != told) prefs.edit().putLong(KEY_TOLD, it).apply() }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission") // checked by WorkAlerts.canPost(), and a late revoke is caught below
    private fun post(context: Context, build: AppUpdateRules.Build) {
        if (!WorkAlerts.canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A quiet note when a new MEKA build is ready to install. Nothing installs without your tap."
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(AppUpdateRules.TITLE)
            .setContentText(AppUpdateRules.notificationLine(build))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post: Today's card still offers the update.
        }
    }
}
