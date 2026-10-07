package os.meka.android.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.R
import os.meka.android.shell.ShellDestination
import os.meka.core.domain.HomeWidgetsView
import os.meka.core.domain.WidgetFast
import os.meka.core.domain.WidgetNeedsYou
import os.meka.core.domain.WidgetNext

/** MEKA's three data widgets. */
enum class HomeWidget { NEXT_UP, NEEDS_YOU, FAST }

/**
 * Home-screen widgets (Outside the app, slice 2): Next up, Needs you and Fast, from the core's `HomeWidgetRules`.
 * Plain RemoteViews (no new libraries), following the phone's light/dark setting like the capture widget.
 *
 * What moves with the clock is ticked by the launcher itself (a `Chronometer` counting down to an event or up from a
 * fast's start), so nothing redraws every minute. The widgets are redrawn when what they say changes (Today, the
 * lists, the fast, Needs you; see [WidgetRouting.signature]) and on their own windowed alarm (ADR-007: a soft change,
 * `setWindow`) at the view's `nextChangeMs`. With no MEKA widget on the home screen nothing is scheduled.
 */
class HomeWidgetUpdater(private val context: Context, private val app: MekaApplication) {
    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        val manager = AppWidgetManager.getInstance(context) ?: return@withLock
        val ids = HomeWidget.entries.associateWith { manager.getAppWidgetIds(ComponentName(context, WidgetRouting.provider(it))) }
        if (ids.values.all { it.isEmpty() }) { schedule(null); return@withLock }
        val v = app.core.homeWidgets()
        val nowMs = System.currentTimeMillis()
        val elapsedMs = SystemClock.elapsedRealtime()
        ids.forEach { (widget, list) ->
            if (list.isEmpty()) return@forEach
            val views = when (widget) {
                HomeWidget.NEXT_UP -> nextUp(v.next, nowMs, elapsedMs)
                HomeWidget.NEEDS_YOU -> needsYou(v.needsYou)
                HomeWidget.FAST -> fast(v.fast, nowMs, elapsedMs)
            }
            views.setOnClickPendingIntent(R.id.widget_root, openIntent(widget))
            list.forEach { manager.updateAppWidget(it, views) }
        }
        schedule(v.nextChangeMs)
    }

    private fun nextUp(n: WidgetNext, nowMs: Long, elapsedMs: Long) = RemoteViews(context.packageName, R.layout.widget_next).apply {
        setTextViewText(R.id.widget_label, n.label)
        setColorStateList(R.id.widget_label, "setTextColor", if (n.lit) R.color.widget_accent else R.color.widget_hint)
        val countdown = n.countdownToMs
        if (countdown != null) {
            setChronometer(R.id.widget_clock, WidgetRouting.chronometerBase(countdown, nowMs, elapsedMs), null, true)
            setChronometerCountDown(R.id.widget_clock, true)
            setViewVisibility(R.id.widget_clock, View.VISIBLE)
        } else {
            setChronometer(R.id.widget_clock, elapsedMs, null, false)
            setViewVisibility(R.id.widget_clock, View.GONE)
        }
        setTextViewText(R.id.widget_title, n.title)
        text(R.id.widget_line, n.line)
        text(R.id.widget_then, n.thenLine)
        setContentDescription(R.id.widget_root, WidgetRouting.describe(n))
    }

    private fun needsYou(n: WidgetNeedsYou) = RemoteViews(context.packageName, R.layout.widget_needs_you).apply {
        text(R.id.widget_count, n.countText)
        setTextViewText(R.id.widget_label, n.label)
        text(R.id.widget_title, n.top)
        text(R.id.widget_line, n.why)
        setColorStateList(R.id.widget_line, "setTextColor", if (n.urgent) R.color.widget_critical else R.color.widget_hint)
        setContentDescription(R.id.widget_root, WidgetRouting.describe(n))
    }

    private fun fast(f: WidgetFast, nowMs: Long, elapsedMs: Long) = RemoteViews(context.packageName, R.layout.widget_fast).apply {
        setTextViewText(R.id.widget_label, f.title)
        setColorStateList(R.id.widget_label, "setTextColor", if (f.reached) R.color.widget_accent else R.color.widget_hint)
        val started = f.startedAtMs
        if (started != null) {
            setChronometer(R.id.widget_clock, WidgetRouting.chronometerBase(started, nowMs, elapsedMs), null, true)
            setChronometerCountDown(R.id.widget_clock, false)
            setViewVisibility(R.id.widget_clock, View.VISIBLE)
            setProgressBar(R.id.widget_progress, 100, f.progressPercent, false)
            setViewVisibility(R.id.widget_progress, View.VISIBLE)
        } else {
            setChronometer(R.id.widget_clock, elapsedMs, null, false)
            setViewVisibility(R.id.widget_clock, View.GONE)
            setViewVisibility(R.id.widget_progress, View.GONE)
        }
        text(R.id.widget_line, f.line)
        setContentDescription(R.id.widget_root, WidgetRouting.describe(f))
    }

    private fun RemoteViews.text(id: Int, value: String?) {
        setTextViewText(id, value.orEmpty())
        setViewVisibility(id, if (value.isNullOrEmpty()) View.GONE else View.VISIBLE)
    }

    private fun openIntent(widget: HomeWidget): PendingIntent = PendingIntent.getActivity(
        context, WidgetRouting.requestCode(widget),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DESTINATION_PREFIX + WidgetRouting.destination(widget).name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (atMs == null || atMs == Long.MAX_VALUE) { am.cancel(alarmIntent()); return }
        am.setWindow(AlarmManager.RTC, atMs, WidgetRouting.WINDOW_MS, alarmIntent())
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, HomeWidgetAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Pure mapping for the home-screen widgets, unit-tested. */
object WidgetRouting {
    /** A soft change (ADR-007): within five minutes is fine; the clocks themselves are ticked by the launcher. */
    const val WINDOW_MS = 5 * 60_000L

    fun provider(widget: HomeWidget): Class<out AppWidgetProvider> = when (widget) {
        HomeWidget.NEXT_UP -> NextUpWidgetProvider::class.java
        HomeWidget.NEEDS_YOU -> NeedsYouWidgetProvider::class.java
        HomeWidget.FAST -> FastWidgetProvider::class.java
    }

    fun destination(widget: HomeWidget): ShellDestination = when (widget) {
        HomeWidget.NEXT_UP -> ShellDestination.TODAY
        HomeWidget.NEEDS_YOU -> ShellDestination.NEEDS_YOU
        HomeWidget.FAST -> ShellDestination.GOALS // the Fasting card heads Goals
    }

    /** Apart from the capture widget's (1 and 2). */
    fun requestCode(widget: HomeWidget): Int = 300 + widget.ordinal

    /** `Chronometer` counts on the elapsed-realtime clock: the wall-clock [targetMs] moved onto it. */
    fun chronometerBase(targetMs: Long, nowMs: Long, elapsedNowMs: Long): Long = elapsedNowMs + (targetMs - nowMs)

    /**
     * What the widgets show; they are redrawn only when this changes (Today itself moves every minute). Leaves out
     * `nextChangeMs`, which moves with the clock; every redraw re-arms the alarm from the fresh view anyway.
     */
    fun signature(v: HomeWidgetsView): List<Any?> = listOf(v.next, v.needsYou, v.fast)

    fun describe(n: WidgetNext): String =
        listOfNotNull("Next up", n.label.takeUnless { n.countdownToMs != null }, n.title, n.line, n.thenLine).joinToString(". ")

    fun describe(n: WidgetNeedsYou): String =
        listOfNotNull(if (n.count == 0) n.label else "${n.count} ${n.label}", n.top, n.why).joinToString(". ")

    fun describe(f: WidgetFast): String = listOfNotNull(f.title, f.line).joinToString(". ")
}

/** Shared by the three providers: any update (placed, resized, the launcher asking) redraws all of MEKA's widgets. */
abstract class MekaDataWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onDisabled(context: Context) = refresh(context)

    private fun refresh(context: Context) {
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { app.widgets.run() } finally { pending.finish() }
        }
    }
}

class NextUpWidgetProvider : MekaDataWidgetProvider()
class NeedsYouWidgetProvider : MekaDataWidgetProvider()
class FastWidgetProvider : MekaDataWidgetProvider()

/** The widgets' own windowed alarm: the next time what they say changes by itself. */
class HomeWidgetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { app.core.tick(); app.widgets.run() } finally { pending.finish() }
        }
    }
}
