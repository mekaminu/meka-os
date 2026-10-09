package os.meka.android.fold

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.news.NewsPane
import os.meka.android.news.NewsTickerStrip
import os.meka.android.news.rememberTickerMode
import os.meka.android.today.BriefPane
import os.meka.android.today.DayRingSheet
import os.meka.android.today.WatchFaceDial
import os.meka.android.today.ShutdownPane
import os.meka.core.domain.BedsideOpens
import os.meka.core.domain.BedsideTickerRules
import os.meka.core.domain.BedsideView
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.WatchFace
import os.meka.core.domain.WatchFaceRules
import os.meka.core.domain.FoldMode
import os.meka.core.domain.FoldModeRules
import os.meka.core.domain.NewsTicker
import os.meka.core.domain.TickerMode
import os.meka.core.domain.TickerRules
import os.meka.core.domain.TalkStartRules
import os.meka.core.facade.MekaCore

/**
 * Fold modes: the bedside clock (build plan M1). Half folded on a table ("Flex mode"), the top half is a large clock
 * with the date and the bottom half the phone's next alarm and one short section for this part of the day (the brief
 * in the morning, what's next in the day, tomorrow in the evening, what today holds after midnight); tapping it opens
 * the brief or the shutdown. In quiet hours the colours quieten and the screen dims right down; the screen stays on
 * only while charging. Opening the Fold flat goes back to the app where it was.
 *
 * Under the section, Today's news strip drifts slower and dimmer (news ticker, slice 4; [BedsideTickerRules]): it keeps
 * drifting on the charger and is calm on battery, follows Appearance → News ticker, and fades away in quiet hours; a
 * story springs the News pane up on it (the match opens News itself).
 *
 * Beside the time sits Today's watch face, large (Fold review 2026-10-09 07:26, item 2, slice 2;
 * [FoldModeRules.bedsideRingDp], a heavier rim from [WatchFaceRules.LARGE_MIN_DP]): the gold hour and minute hands,
 * the next 12 hours as arcs on the rim, the sweeping second hand, breathing slower ([DayRingLive.bedsideGlow], 8 s);
 * in quiet hours it quietens with the clock ([WatchFaceRules.bedsideAlpha]) and the second hand, breath and shimmer
 * stop ([WatchFaceRules.liveMode]), the face redrawn once a minute so the hands keep time. Tapping it springs the whole
 * 24-hour Day ring up over the clock ([DayRingSheet], "‹ Clock" or Back drops it away).
 *
 * Talk without tapping the mic (slice 3): a mic beside the alarm line springs the Talk pane up over the clock already
 * listening ([BedsideTalkPane]: Ask's field and orb, the same on-device recogniser and yes-before-anything); "‹ Clock"
 * or Back drops it away. While it is up the screen leaves quiet hours' dimming so the answer can be read.
 *
 * Motion: the clock fades up and the lower lines stagger in; the face draws itself in (the quick draw) as the clock
 * appears; changed digits roll up each minute; dimming blends the colours across; the strip fades in and out. Reduced
 * motion: cross-fades, the ring drawn at once and still, the strip a still card with ‹ ›.
 */
@Composable
fun BedsideClock(core: MekaCore, fold: FoldState) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = LocalActivity.current
    var v by remember { mutableStateOf(core.bedside(nextAlarm(context))) }
    var charging by remember { mutableStateOf(isCharging(context)) }
    LaunchedEffect(core) {
        while (true) {
            charging = isCharging(context)
            v = core.bedside(nextAlarm(context))
            // Wake on the minute so the clock turns over on time, and at least every 5 s for the alarm and charger.
            val now = System.currentTimeMillis()
            delay((60_000 - now % 60_000).coerceIn(250, 5_000))
        }
    }
    // The screen stays on at the bedside only while charging; it never holds the screen on battery.
    DisposableEffect(view, charging) {
        view.keepScreenOn = FoldModeRules.keepScreenOn(FoldMode.BEDSIDE, charging)
        onDispose { view.keepScreenOn = false }
    }
    // Quiet hours: as dim as the screen goes; otherwise the phone's own brightness.
    var talkOpen by remember { mutableStateOf(false) }
    // The whole day's 24-hour ring, sprung up by tapping the watch face; it lifts the quiet-hours dimming like Talk.
    var dayOpen by remember { mutableStateOf(false) }
    val dimScreen = v.dim && !talkOpen && !dayOpen
    DisposableEffect(activity, dimScreen) {
        val window = activity?.window
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = if (dimScreen) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }

    var open by remember { mutableStateOf<BedsideOpens?>(null) }
    // The bedside news strip (slice 4): Today's ticker, cut short; "" = News itself, an id = that story; null = closed.
    val newsPlace by core.newsPlace.collectAsState()
    val ticker = remember(newsPlace) { BedsideTickerRules.ticker(TickerRules.ticker(newsPlace)) }
    val todayMode = rememberTickerMode()
    var newsOpen by remember { mutableStateOf<String?>(null) }
    var newsShown by remember { mutableStateOf<String?>(null) }
    if (newsOpen != null) newsShown = newsOpen
    BackHandler(enabled = newsOpen != null) { newsOpen = null }
    BackHandler(enabled = talkOpen && newsOpen == null) { talkOpen = false }
    BackHandler(enabled = dayOpen && !talkOpen && newsOpen == null) { dayOpen = false }
    val talkApp = context.applicationContext as? os.meka.android.MekaApplication
    val density = LocalDensity.current
    var topInWindow by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    var widthPx by remember { mutableIntStateOf(0) }
    // Today's watch face and Day ring, kept current by the shell's minute tick.
    val today by core.today.collectAsState()
    Box(
        Modifier.fillMaxSize().background(Meka.colors.background)
            .onGloballyPositioned { topInWindow = it.positionInWindow().y.toInt(); heightPx = it.size.height; widthPx = it.size.width },
    ) {
        // Split at the hinge (window coordinates), else in half.
        val hinge = fold.hinge
        val splitPx = if (hinge != null && heightPx > 0) (hinge.top - topInWindow).coerceIn(heightPx / 4, heightPx * 3 / 4) else heightPx / 2
        val hingePx = hinge?.height() ?: 0
        val upper = with(density) { splitPx.toDp() }
        val gap = with(density) { hingePx.toDp() }
        Column(Modifier.fillMaxSize()) {
            val ringSize = FoldModeRules.bedsideRingDp(upper.value, with(density) { widthPx.toDp() }.value)?.dp
            ClockHalf(v, today.watchFace.takeIf { today.timeline.dateLabel.isNotEmpty() }, ringSize, onOpenDay = { dayOpen = true }, modifier = Modifier.fillMaxWidth().height(upper).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)))
            Box(Modifier.height(gap))
            DayHalf(
                v, Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                onTalk = { os.meka.android.ask.talkAtBedside(talkApp); talkOpen = true },
            ) {
                open = it
            }
            BedsideNews(
                core, ticker, todayMode, v.dim, charging,
                Modifier.fillMaxWidth().padding(bottom = MekaSpace.l)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
            ) { id -> newsOpen = id }
        }
        MekaPane(visible = open == BedsideOpens.BRIEF) { BriefPane(core, onClose = { open = null }) }
        MekaPane(visible = open == BedsideOpens.SHUTDOWN) { ShutdownPane(core, onClose = { open = null }) }
        MekaPane(visible = dayOpen) {
            DayRingSheet(today.dayRing, today.dayTiles, onOpenArc = null, onClose = { dayOpen = false }, backLabel = WatchFaceRules.BEDSIDE_SHEET_BACK)
        }
        MekaPane(visible = talkOpen) { BedsideTalkPane(core, onClose = { talkOpen = false }) }
        MekaPane(visible = newsOpen != null) {
            newsShown?.let { start ->
                key(start) { NewsPane(core, onClose = { newsOpen = null }, backLabel = "‹ Clock", startStoryId = start.ifEmpty { null }) }
            }
        }
    }
}

/**
 * Today's news strip under the bedside clock (news ticker, slice 4): slower ([BedsideTickerRules.SPEED_DP_PER_S]) and
 * dimmer ([BedsideTickerRules.ALPHA]); always drifting on the charger, calm on battery; Off in Appearance hides it, and
 * it fades away when quiet hours start. [openNews] gets a story's id, or "" for the match (News leads with it).
 */
@Composable
private fun BedsideNews(
    core: MekaCore, ticker: NewsTicker, todayMode: TickerMode, quiet: Boolean, charging: Boolean, modifier: Modifier,
    openNews: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        visible = BedsideTickerRules.shown(todayMode, quiet, ticker),
        enter = fadeIn(MekaMotion.themeBlend(reduced)), exit = fadeOut(MekaMotion.themeBlend(reduced)),
        modifier = modifier,
    ) {
        NewsTickerStrip(
            core, ticker, BedsideTickerRules.mode(todayMode, charging), Modifier.alpha(BedsideTickerRules.ALPHA),
            openStory = { openNews(it) }, openMatch = { openNews("") },
            speedDpPerS = BedsideTickerRules.SPEED_DP_PER_S,
        )
    }
}

/**
 * The clock half: the time and date, with the watch face beside them when the half holds it ([ringSize] non-null). The
 * face draws itself in once as the clock appears (the quick draw; reduced motion: at once), then lives; a tap (tick
 * haptic, the press) opens the whole day ([onOpenDay]).
 */
@Composable
private fun ClockHalf(v: BedsideView, face: WatchFace?, ringSize: Dp?, onOpenDay: () -> Unit, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val primary by animateColorAsState(if (v.dim) Meka.colors.textTertiary else Meka.colors.textPrimary, MekaMotion.themeBlend(reduced), label = "clock")
    val secondary by animateColorAsState(if (v.dim) Meka.colors.textTertiary else Meka.colors.textSecondary, MekaMotion.themeBlend(reduced), label = "date")
    val ringAlpha by animateFloatAsState(WatchFaceRules.bedsideAlpha(v.dim), MekaMotion.themeBlend(reduced), label = "ringDim")
    var play by remember { mutableStateOf(if (reduced) DayRingPlay.STILL else DayRingPlay.QUICK) }
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (face != null && ringSize != null) {
            WatchFaceDial(
                face, play, played = { play = DayRingPlay.STILL },
                modifier = Modifier.graphicsLayer { alpha = ringAlpha },
                size = ringSize, onOpen = onOpenDay, bedside = true, quiet = v.dim,
            )
            Spacer(Modifier.width(MekaSpace.xl))
        }
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.appear(rememberAppearance(0)).semantics { contentDescription = "${v.time}, ${v.dateLabel}" }) {
                RollingTime(v.time, primary, reduced)
            }
            Text(v.dateLabel, style = MekaType.itemMeta, color = secondary, modifier = Modifier.appear(rememberAppearance(1)))
        }
    }
}

/** The time with each changed character rolling up on its own, so 06:59 → 07:00 rolls three digits and 07:00 → 07:01 one. */
@Composable
private fun RollingTime(time: String, color: androidx.compose.ui.graphics.Color, reduced: Boolean) {
    val style = MekaType.greeting.copy(fontSize = 96.sp, fontWeight = FontWeight(300), letterSpacing = (-0.03).em, fontFeatureSettings = "tnum", lineHeight = 104.sp)
    androidx.compose.foundation.layout.Row {
        time.forEachIndexed { i, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    if (reduced) fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
                    else (slideInVertically(MekaMotion.replan(false)) { it / 2 } + fadeIn(MekaMotion.appear(false))) togetherWith
                        (slideOutVertically(MekaMotion.replan(false)) { -it / 2 } + fadeOut(MekaMotion.appear(false)))
                },
                label = "digit-$i",
            ) { c -> Text(c.toString(), style = style, color = color, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun DayHalf(v: BedsideView, modifier: Modifier, onTalk: () -> Unit, onOpen: (BedsideOpens) -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val text by animateColorAsState(if (v.dim) Meka.colors.textTertiary else Meka.colors.textPrimary, MekaMotion.themeBlend(reduced), label = "day")
    val alarmColor by animateColorAsState(
        when { v.dim -> Meka.colors.textTertiary; v.alarmSet -> Meka.colors.accent; else -> Meka.colors.textTertiary },
        MekaMotion.themeBlend(reduced), label = "alarm",
    )
    Column(modifier.padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
        Row(Modifier.fillMaxWidth().appear(rememberAppearance(2)), verticalAlignment = Alignment.CenterVertically) {
            Text(v.alarmLine, style = MekaType.itemMeta, color = alarmColor, modifier = Modifier.weight(1f))
            os.meka.android.ask.TalkMic(onTalk, size = 44.dp, dim = v.dim)
        }
        val opens = v.opens
        Column(
            Modifier.fillMaxWidth().appear(rememberAppearance(3)).clip(RoundedCornerShape(MekaRadius.m))
                .then(if (opens != null) Modifier.clickable(role = Role.Button) { haptics.tick(); onOpen(opens) } else Modifier)
                .padding(vertical = MekaSpace.xxs),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(v.heading.uppercase(), style = MekaType.sectionLabel, color = if (v.dim) Meka.colors.textTertiary else Meka.colors.textSecondary)
            AnimatedContent(
                targetState = v.lines,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "bedside-lines",
            ) { lines ->
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    lines.forEachIndexed { i, line ->
                        Text(line, style = if (i == 0) MekaType.body else MekaType.itemMeta, color = if (i == 0) text else Meka.colors.textTertiary)
                    }
                }
            }
            if (opens != null) {
                Text(
                    if (opens == BedsideOpens.BRIEF) "Open the brief ›" else "See tomorrow ›",
                    style = MekaType.caption, color = if (v.dim) Meka.colors.textTertiary else Meka.colors.accent,
                )
            }
        }
    }
}

/**
 * The Talk pane over the bedside clock (Talk without tapping the mic, slice 3): "‹ Clock", the title, and Ask's field
 * with the orb, which starts listening as it appears (the mic set [MekaApplication.talkNow]); answers and their cards
 * show under it as on Ask, with the undo bar at the foot. Motion: MekaPane's spring; the orb as on Ask.
 */
@Composable
private fun BedsideTalkPane(core: MekaCore, onClose: () -> Unit) {
    val undo = os.meka.android.calendar.rememberEventUndo()
    Box(Modifier.fillMaxSize().background(Meka.colors.background)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
        ) {
            Text(TalkStartRules.BEDSIDE_BACK, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
            Text(TalkStartRules.BEDSIDE_TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(0)))
            // Search isn't reachable at the bedside: when asking can't work, the field's tap just closes the pane.
            os.meka.android.ask.AskMekaSection(core, undo, openSearch = { _, _ -> onClose() }, modifier = Modifier.appear(rememberAppearance(1)), matches = false)
        }
        os.meka.android.calendar.EventUndoBar(undo, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

/** The phone's own next alarm (the Clock app's), if one is set. MEKA's own alarms join it with the Alarms item. */
private fun nextAlarm(context: Context): Long? =
    (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.nextAlarmClock?.triggerTime

/** Plugged in (or full on the charger). Reads the sticky battery broadcast; registers nothing. */
private fun isCharging(context: Context): Boolean {
    val status: Intent = ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ?: return false
    return status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
}
