package os.meka.android.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.R
import os.meka.core.domain.DayListWidgetRules
import os.meka.core.domain.WidgetDayList

/**
 * The Day list's home-screen widget (the Day list, slice 6; `docs/design/day-list/Today.dc.html`: "the home-screen
 * widget"): "Today" with the done line and the gold bar, up to four of today's open tasks (tick · time · title ·
 * length), "+n more" and **+ Add tasks**, which opens today's Day list with the add bar ready and the keyboard up (as
 * the app icon's Add tasks). A tick marks that task done, the same as in the Day list; the header or a task opens the
 * Day list to look at. Plain RemoteViews, following the phone's light/dark setting like MEKA's other widgets; redrawn
 * when the Week board changes (MekaApplication), never on a timer.
 */
object DayListWidget {
    /** Apart from the other widgets' codes (1, 2, 300+, 400s, the Talk and Brief widgets'). */
    const val OPEN_CODE = 360
    const val ADD_CODE = 361
    const val TICK_CODE_BASE = 370
    const val EXTRA_TASK = "os.meka.android.widgets.DAY_LIST_TASK"

    private val ROW = intArrayOf(R.id.day_row_0, R.id.day_row_1, R.id.day_row_2, R.id.day_row_3)
    private val TICK = intArrayOf(R.id.day_tick_0, R.id.day_tick_1, R.id.day_tick_2, R.id.day_tick_3)
    private val TIME = intArrayOf(R.id.day_time_0, R.id.day_time_1, R.id.day_time_2, R.id.day_time_3)
    private val TITLE = intArrayOf(R.id.day_title_0, R.id.day_title_1, R.id.day_title_2, R.id.day_title_3)
    private val LENGTH = intArrayOf(R.id.day_length_0, R.id.day_length_1, R.id.day_length_2, R.id.day_length_3)

    /** The layout has [DayListWidgetRules.ROWS] row slots. */
    val slots: Int get() = ROW.size

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, DayListWidgetProvider::class.java)) ?: IntArray(0)

    /** Redraws every placed Day list widget with [w]; nothing happens with none placed. */
    fun update(context: Context, w: WidgetDayList) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = ids(context)
        if (ids.isEmpty()) return
        val views = views(context, w)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun views(context: Context, w: WidgetDayList) = RemoteViews(context.packageName, R.layout.widget_day_list).apply {
        setTextViewText(R.id.widget_title, w.title)
        setTextViewText(R.id.widget_line, if (w.empty) "" else w.line)
        setProgressBar(R.id.widget_progress, 100, w.progressPercent, false)
        setViewVisibility(R.id.widget_progress, if (w.empty) View.GONE else View.VISIBLE)
        val open = openIntent(context, MainActivity.OPEN_DAY_LIST, OPEN_CODE)
        setOnClickPendingIntent(R.id.day_header, open)
        setOnClickPendingIntent(R.id.day_add, openIntent(context, MainActivity.OPEN_DAY_LIST_ADD, ADD_CODE))
        ROW.indices.forEach { i ->
            val r = w.rows.getOrNull(i)
            setViewVisibility(ROW[i], if (r == null) View.GONE else View.VISIBLE)
            if (r == null) return@forEach
            setTextViewText(TITLE[i], r.title)
            text(TIME[i], r.time)
            text(LENGTH[i], r.length)
            setContentDescription(TICK[i], DayListWidgetRules.tickLabel(r))
            setContentDescription(TITLE[i], r.spoken)
            setOnClickPendingIntent(TICK[i], tickIntent(context, i, r.id))
            setOnClickPendingIntent(TITLE[i], open)
        }
        setViewVisibility(R.id.day_empty, if (w.empty) View.VISIBLE else View.GONE)
        text(R.id.day_more, w.more)
        setContentDescription(R.id.day_header, DayListWidgetRules.describe(w))
        setContentDescription(R.id.day_add, DayListWidgetRules.ADD)
    }

    private fun RemoteViews.text(id: Int, value: String?) {
        setTextViewText(id, value.orEmpty())
        setViewVisibility(id, if (value.isNullOrEmpty()) View.GONE else View.VISIBLE)
    }

    private fun openIntent(context: Context, open: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, open)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** One code per slot; the task rides in the intent (its data keeps each slot's intent apart). */
    private fun tickIntent(context: Context, slot: Int, taskId: String): PendingIntent = PendingIntent.getBroadcast(
        context, tickCode(slot),
        Intent(context, DayListWidgetTickReceiver::class.java)
            .setData(Uri.parse("navilife://day-list-widget/tick/$slot"))
            .putExtra(EXTRA_TASK, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun tickCode(slot: Int): Int = TICK_CODE_BASE + slot
}

/** Placed, resized or asked by the launcher: draws the widget from the current board. */
class DayListWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as MekaApplication
        DayListWidget.update(context, app.core.dayListWidget())
    }
}

/** A tick on the widget: that task is done (the board changes, so the widget redraws without it). */
class DayListWidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DayListWidget.EXTRA_TASK) ?: return
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runCatching { app.core.completeFromWidget(id) }
                DayListWidget.update(context, app.core.dayListWidget())
            } finally { pending.finish() }
        }
    }
}
