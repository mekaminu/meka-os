package os.meka.android.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import os.meka.android.MainActivity
import os.meka.android.shell.ShellDestination
import os.meka.core.domain.DiscordAlertNotice
import os.meka.core.domain.DiscordRules

/**
 * Discord channel watch, slice 2: the Fold's "Discord alerts" — a post in a channel he follows from a person he
 * picked, or with a word he picked (a ticker, "entry", "stop"), shows straight away. One notification per channel,
 * updated in place as more arrive (tagged with the channel's key); a tap opens Needs you, where the channel's card
 * shows the post with its alert chip. Information only: no action posts to Discord, and nothing here trades.
 * Its own high-importance channel, so he can tune or silence it apart from everything else.
 */
object DiscordAlerts {
    const val CHANNEL = "discord_alerts"
    private const val ID = 0x0D15C0

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, DiscordRules.ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = DiscordRules.ALERT_CHANNEL_WHY
            },
        )
    }

    fun post(context: Context, notice: DiscordAlertNotice) {
        if (!WorkAlerts.canPost(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, notice.key.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DESTINATION_PREFIX + ShellDestination.NEEDS_YOU.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = if (notice.lines.size > 1) {
            NotificationCompat.InboxStyle().also { s -> notice.lines.forEach { s.addLine(it) } }
                .setSummaryText("${notice.count} alerts")
        } else NotificationCompat.BigTextStyle().bigText(notice.text)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(os.meka.android.R.drawable.ic_stat_meka)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setSubText(notice.why)
            .setStyle(style)
            .setWhen(notice.latestMs)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            os.meka.android.notify.MekaPost.notify(context, notice.key, ID, n)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the post: the post is still on the channel's card.
        }
    }

    /** Done on the channel's card: its alert has done its job. */
    fun cancel(context: Context, key: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(key, ID) }
    }
}
