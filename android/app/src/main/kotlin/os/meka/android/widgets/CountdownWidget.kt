package os.meka.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.R
import os.meka.core.domain.CountdownWidgetRules
import os.meka.core.domain.WidgetCountdown

/**
 * The Countdowns home-screen widget (Countdowns, slice 4; `docs/design/countdowns/Countdowns.dc.html`: "Now on Today
 * and the home-screen widget"): the one chosen with Show on Today, else the soonest, with its ring and number ("43
 * days", "43 sleeps" with Sleeps on, "Today" lit on the day), its name, date and extra line ("turns 9"), then the next
 * two ("Holiday · 93 days"). A tap opens Countdowns. Plain RemoteViews, following the phone's light/dark setting;
 * redrawn when Countdowns changes (MekaApplication) and every six hours by the launcher.
 */
object CountdownWidget {
    /** Apart from the other widgets' codes (1, 2, 300+, 360s, 370s, 400s, the Talk and Brief widgets'). */
    const val OPEN_CODE = 380

    private val NEXT = intArrayOf(R.id.countdown_next_0, R.id.countdown_next_1)

    /** The layout has [CountdownWidgetRules.NEXT] lines for the next ones. */
    val nextSlots: Int get() = NEXT.size

    /** "Holiday · 93 days" */
    fun nextLine(name: String, count: String): String = "$name · $count"

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, CountdownWidgetProvider::class.java)) ?: IntArray(0)

    /** Redraws every placed Countdowns widget with [w]; nothing happens with none placed. */
    fun update(context: Context, w: WidgetCountdown) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = ids(context)
        if (ids.isEmpty()) return
        val views = views(context, w)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun views(context: Context, w: WidgetCountdown) = RemoteViews(context.packageName, R.layout.widget_countdown).apply {
        setViewVisibility(R.id.countdown_main, if (w.empty) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.countdown_empty, if (w.empty) View.VISIBLE else View.GONE)
        if (w.empty) {
            setTextViewText(R.id.countdown_empty_title, CountdownWidgetRules.EMPTY)
            setTextViewText(R.id.countdown_empty_hint, CountdownWidgetRules.emptyHint(desk = false))
        } else {
            setTextViewText(R.id.countdown_number, w.number)
            setTextColor(R.id.countdown_number, ContextCompat.getColor(context, if (w.lit) R.color.widget_accent else R.color.widget_text))
            text(R.id.countdown_unit, w.unit)
            setProgressBar(R.id.countdown_ring, 100, w.ringPercent, false)
            setTextViewText(R.id.countdown_name, w.name)
            text(R.id.countdown_date, w.dateLine.takeIf { !w.lit })
            text(R.id.countdown_extra, w.extra)
        }
        NEXT.indices.forEach { i -> text(NEXT[i], w.next.getOrNull(i)?.let { nextLine(it.name, it.count) }) }
        setContentDescription(R.id.countdown_root, w.spoken)
        setOnClickPendingIntent(R.id.countdown_root, openIntent(context))
    }

    private fun RemoteViews.text(id: Int, value: String?) {
        setTextViewText(id, value.orEmpty())
        setViewVisibility(id, if (value.isNullOrEmpty()) View.GONE else View.VISIBLE)
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, OPEN_CODE,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_COUNTDOWNS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Placed, resized or every six hours: draws the widget from Countdowns as worked out now. While the core is still
 * loading (Countdowns not worked out yet) it leaves the widget as it was; MekaApplication draws it once it is.
 */
class CountdownWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as MekaApplication
        app.core.countdownWidget()?.let { CountdownWidget.update(context, it) }
    }
}
