package os.meka.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import os.meka.android.MainActivity
import os.meka.android.R

/**
 * The home screen's brief widget (Spoken morning brief): a brass play mark and "Hear the brief". One tap opens MEKA on
 * Today with the brief springing up and reading itself aloud in MEKA's voice, exactly as after the wake alarm
 * ([MainActivity.OPEN_BRIEF]). Plain RemoteViews with no data of its own, so it never needs refreshing.
 * Motion: the launcher's own; then Today's brief pane springs up as for the alarm.
 */
class BriefWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_brief).apply {
            setOnClickPendingIntent(R.id.widget_root, BriefWidget.tapIntent(context))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

object BriefWidget {
    /** What the tap asks MainActivity for: the same as dismissing the wake alarm. */
    const val OPEN = MainActivity.OPEN_BRIEF

    /** Clear of the capture widget's (1, 2), the data widgets' (300+), the news widget's (400+, 410+) and Talk's (450). */
    const val REQUEST_CODE = 451

    fun tapIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, OPEN),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
