package os.meka.android.widgets

import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.android.MainActivity
import os.meka.android.MekaApplication
import os.meka.android.R
import os.meka.core.domain.NewsWidgetCard
import os.meka.core.domain.NewsWidgetView

/** The News widget's two sizes: a 4×1 strip and a 4×2 card. */
enum class NewsWidgetSize { STRIP, CARD }

/**
 * The News home-screen widget (news ticker slice 3a): picture-and-headline cards the launcher flips through on its own
 * every five seconds (`AdapterViewFlipper` with `autoStart`, so nothing of MEKA's runs to move it, and the launcher
 * stops it while the home screen isn't showing). Plain RemoteViews (no new libraries), following the phone's
 * light/dark setting like the other widgets. With the phone's animations turned off it stands still on one card with
 * ‹ › to page (reduced motion).
 *
 * The cards come from `MekaCore.newsWidget` (Today's ticker); pictures from MEKA's own server through the core's
 * cache, fetched by [NewsWidgetService] when the launcher asks for the cards. Redrawn when what it says changes and on
 * its own windowed alarm (ADR-007: a soft change) so "2 h ago" moves on. With no News widget placed nothing runs.
 */
class NewsWidgetUpdater(private val context: Context, private val app: MekaApplication) {
    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        val manager = AppWidgetManager.getInstance(context) ?: return@withLock
        val ids = NewsWidgetSize.entries.associateWith { manager.getAppWidgetIds(ComponentName(context, NewsWidgetRouting.provider(it))) }
        if (ids.values.all { it.isEmpty() }) { schedule(null); return@withLock }
        val v = app.core.newsWidget()
        val still = !ValueAnimator.areAnimatorsEnabled()
        ids.forEach { (size, list) ->
            list.forEach { id -> manager.updateAppWidget(id, views(size, id, v, still)) }
            if (list.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(list, R.id.news_flipper)
        }
        schedule(v.nextChangeMs)
    }

    private fun views(size: NewsWidgetSize, widgetId: Int, v: NewsWidgetView, still: Boolean): RemoteViews {
        val layout = NewsWidgetRouting.layout(size, still)
        return RemoteViews(context.packageName, layout).apply {
            val service = Intent(context, NewsWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(NewsWidgetService.EXTRA_SIZE, size.name)
            // Each widget its own adapter (the extras alone don't make intents differ).
            service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            setRemoteAdapter(R.id.news_flipper, service)
            setEmptyView(R.id.news_flipper, R.id.news_empty)
            setTextViewText(R.id.news_empty_title, v.emptyTitle)
            setTextViewText(R.id.news_empty_line, v.emptyLine)
            setPendingIntentTemplate(R.id.news_flipper, openTemplate(size))
            setOnClickPendingIntent(R.id.news_empty, openIntent(size))
            if (still) {
                setOnClickPendingIntent(R.id.news_previous, pageIntent(widgetId, layout, -1))
                setOnClickPendingIntent(R.id.news_next, pageIntent(widgetId, layout, +1))
                val paging = if (v.cards.size > 1) View.VISIBLE else View.GONE
                setViewVisibility(R.id.news_previous, paging)
                setViewVisibility(R.id.news_next, paging)
            }
            setContentDescription(R.id.news_root, os.meka.core.domain.NewsWidgetRules.spoken(v))
        }
    }

    /** Each card fills in which story it is ([NewsWidgetRouting.openExtra]); collections need a mutable template. */
    private fun openTemplate(size: NewsWidgetSize): PendingIntent = PendingIntent.getActivity(
        context, NewsWidgetRouting.requestCode(size),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "No news yet" opens News, where the topics are chosen. */
    private fun openIntent(size: NewsWidgetSize): PendingIntent = PendingIntent.getActivity(
        context, NewsWidgetRouting.requestCode(size) + 10,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, NewsWidgetRouting.openExtra(null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun pageIntent(widgetId: Int, layout: Int, delta: Int): PendingIntent = PendingIntent.getBroadcast(
        context, NewsWidgetRouting.pageRequestCode(widgetId, delta),
        Intent(context, NewsWidgetPageReceiver::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(NewsWidgetPageReceiver.EXTRA_LAYOUT, layout)
            .putExtra(NewsWidgetPageReceiver.EXTRA_DELTA, delta),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (atMs == null || atMs == Long.MAX_VALUE) { am.cancel(alarmIntent()); return }
        am.setWindow(AlarmManager.RTC, atMs, WidgetRouting.WINDOW_MS, alarmIntent())
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 2, Intent(context, NewsWidgetAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Pure mapping for the News widget, unit-tested. */
object NewsWidgetRouting {
    /** Pictures are decoded no larger than this (px, longer side): the card shows them at about 120 dp. */
    const val CARD_PICTURE_PX = 240
    const val STRIP_PICTURE_PX = 120
    /** How long the launcher's request for the cards may wait for pictures before showing tiles. */
    const val PICTURES_TIMEOUT_MS = 8_000L

    fun provider(size: NewsWidgetSize): Class<out AppWidgetProvider> = when (size) {
        NewsWidgetSize.STRIP -> NewsStripWidgetProvider::class.java
        NewsWidgetSize.CARD -> NewsCardWidgetProvider::class.java
    }

    fun layout(size: NewsWidgetSize, still: Boolean): Int = when (size) {
        NewsWidgetSize.STRIP -> if (still) R.layout.widget_news_strip_still else R.layout.widget_news_strip
        NewsWidgetSize.CARD -> if (still) R.layout.widget_news_card_still else R.layout.widget_news_card
    }

    fun itemLayout(size: NewsWidgetSize): Int = when (size) {
        NewsWidgetSize.STRIP -> R.layout.widget_news_item_strip
        NewsWidgetSize.CARD -> R.layout.widget_news_item_card
    }

    fun picturePx(size: NewsWidgetSize): Int = when (size) {
        NewsWidgetSize.STRIP -> STRIP_PICTURE_PX
        NewsWidgetSize.CARD -> CARD_PICTURE_PX
    }

    /** Apart from the capture widget's (1, 2) and the data widgets' (300+). */
    fun requestCode(size: NewsWidgetSize): Int = 400 + size.ordinal

    /** The still widget's ‹ › for each placed widget, apart from everything else. */
    fun pageRequestCode(widgetId: Int, delta: Int): Int = 1_000_000 + widgetId * 2 + if (delta > 0) 1 else 0

    /** What a card's tap carries to MEKA: "news:<story id>", or "news:" for News itself (the match leads it). */
    fun openExtra(storyId: String?): String = MainActivity.OPEN_NEWS_PREFIX + storyId.orEmpty()

    fun openExtra(card: NewsWidgetCard): String = openExtra(card.openStoryId)

    /** The story to open from an [openExtra]: "" for News itself, null when it isn't one. */
    fun storyFrom(open: String): String? = if (open.startsWith(MainActivity.OPEN_NEWS_PREFIX)) open.removePrefix(MainActivity.OPEN_NEWS_PREFIX) else null

    /** The power-of-two step that brings a [width]×[height] picture down to no less than [maxPx] on its longer side. */
    fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var s = 1
        val longer = maxOf(width, height)
        while (longer / (s * 2) >= maxPx) s *= 2
        return s
    }

    /** A stable id per card for the launcher (it keeps a card's view while flipping). */
    fun itemId(card: NewsWidgetCard): Long = card.id.hashCode().toLong()

    fun signature(v: NewsWidgetView): List<Any?> = os.meka.core.domain.NewsWidgetRules.signature(v)
}

/**
 * Feeds the launcher the cards: the core's current News widget view, with pictures fetched once per change (through
 * the core's cache, from MEKA's own server only) and shrunk to the widget's size. Runs on the launcher's binder thread,
 * where waiting is allowed; pictures that take longer than [NewsWidgetRouting.PICTURES_TIMEOUT_MS] show their tile.
 */
class NewsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val size = NewsWidgetSize.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SIZE) } ?: NewsWidgetSize.CARD
        return Factory(applicationContext as MekaApplication, size)
    }

    private class Factory(private val app: MekaApplication, private val size: NewsWidgetSize) : RemoteViewsFactory {
        private var cards: List<NewsWidgetCard> = emptyList()
        private var pictures: Map<String, Bitmap> = emptyMap()

        override fun onCreate() {}
        override fun onDestroy() { pictures = emptyMap() }

        override fun onDataSetChanged() {
            val v = runCatching { app.core.newsWidget() }.getOrNull() ?: return
            cards = v.cards
            val maxPx = NewsWidgetRouting.picturePx(size)
            val got = HashMap<String, Bitmap>()
            runBlocking {
                withTimeoutOrNull(NewsWidgetRouting.PICTURES_TIMEOUT_MS) {
                    cards.mapNotNull { it.imageKey }.distinct().forEach { key ->
                        val bytes = runCatching { app.core.newsImage(key) }.getOrNull() ?: return@forEach
                        decode(bytes, maxPx)?.let { got[key] = it }
                    }
                }
            }
            pictures = got
        }

        private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = NewsWidgetRouting.sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
                inPreferredConfig = Bitmap.Config.RGB_565 // JPEGs have no transparency: half the memory
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()

        override fun getCount(): Int = cards.size
        override fun getViewTypeCount(): Int = 1
        override fun hasStableIds(): Boolean = true
        override fun getItemId(position: Int): Long = cards.getOrNull(position)?.let { NewsWidgetRouting.itemId(it) } ?: position.toLong()
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            val card = cards.getOrNull(position)
            return RemoteViews(app.packageName, NewsWidgetRouting.itemLayout(size)).apply {
                if (card == null) return@apply
                setTextViewText(R.id.news_label, card.label)
                setColorStateList(R.id.news_label, "setTextColor", if (card.isBarca) R.color.widget_barca else R.color.widget_accent)
                setTextViewText(R.id.news_title, card.title)
                setTextViewText(R.id.news_line, card.line)
                setTextViewText(R.id.news_tile, card.tileInitial)
                setInt(R.id.news_tile, "setBackgroundResource", if (card.isBarca) R.drawable.widget_tile_barca else R.drawable.widget_tile)
                val picture = card.imageKey?.let { pictures[it] }
                if (picture != null) {
                    setImageViewBitmap(R.id.news_picture, picture)
                    setViewVisibility(R.id.news_picture, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.news_picture, View.GONE)
                }
                setContentDescription(R.id.news_item, card.spoken)
                setOnClickFillInIntent(R.id.news_item, Intent().putExtra(MainActivity.EXTRA_OPEN, NewsWidgetRouting.openExtra(card)))
            }
        }
    }

    companion object {
        const val EXTRA_SIZE = "os.meka.news.size"
    }
}

/** Both sizes: any update (placed, resized, the launcher asking) redraws MEKA's News widgets. */
abstract class MekaNewsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onDisabled(context: Context) = refresh(context)

    private fun refresh(context: Context) {
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { app.newsWidgets.run() } finally { pending.finish() }
        }
    }
}

class NewsStripWidgetProvider : MekaNewsWidgetProvider()
class NewsCardWidgetProvider : MekaNewsWidgetProvider()

/** The still widget's ‹ ›: one card back or on, in the launcher, without redrawing the rest. */
class NewsWidgetPageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val layout = intent.getIntExtra(EXTRA_LAYOUT, 0)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || layout == 0) return
        val views = RemoteViews(context.packageName, layout).apply {
            if (intent.getIntExtra(EXTRA_DELTA, 1) < 0) showPrevious(R.id.news_flipper) else showNext(R.id.news_flipper)
        }
        AppWidgetManager.getInstance(context)?.partiallyUpdateAppWidget(id, views)
    }

    companion object {
        const val EXTRA_LAYOUT = "os.meka.news.layout"
        const val EXTRA_DELTA = "os.meka.news.delta"
    }
}

/** The News widget's own windowed alarm: "2 h ago" and "in 3 h" move on. */
class NewsWidgetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MekaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try { app.core.tick(); app.newsWidgets.run() } finally { pending.finish() }
        }
    }
}
