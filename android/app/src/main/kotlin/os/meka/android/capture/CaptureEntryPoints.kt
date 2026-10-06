package os.meka.android.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import os.meka.android.R

/** Intents that open the capture sheet from MEKA's own entry points. */
internal object CaptureIntents {
    fun open(context: Context, voice: Boolean): Intent =
        Intent(context, CaptureActivity::class.java)
            .setAction(CaptureRequests.ACTION_CAPTURE)
            .putExtra(CaptureRequests.EXTRA_VOICE, voice)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun pending(context: Context, voice: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context, if (voice) 2 else 1, open(context, voice),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Quick-settings tile: pull down, tap "Capture", type. Collapses the shade and opens the sheet. */
class CaptureTileService : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(CaptureIntents.pending(this, voice = false))
        } else {
            startActivityAndCollapse(CaptureIntents.open(this, voice = false))
        }
    }
}

/**
 * Home-screen widget: a "Capture anything…" pill and a mic. Plain RemoteViews (no new libraries); the widget has no
 * data of its own, so it never needs refreshing.
 */
class CaptureWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_capture).apply {
            setOnClickPendingIntent(R.id.capture_pill, CaptureIntents.pending(context, voice = false))
            setOnClickPendingIntent(R.id.capture_mic, CaptureIntents.pending(context, voice = true))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
