package os.meka.android.today

import os.meka.android.activity.ActivityPane
import os.meka.android.ask.TrainQueuePane
import os.meka.android.ask.WeekAheadPane
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.TickSize
import os.meka.android.designsystem.bleedStart
import os.meka.android.designsystem.wordsTap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import os.meka.android.work.AfterWorkHost
import os.meka.core.domain.HeaderTileKind
import os.meka.core.domain.TodayHeaderRules
import os.meka.core.domain.CommandCentreRules
import os.meka.core.domain.NowKind
import os.meka.core.domain.NowView
import os.meka.core.domain.EveningCardRules
import os.meka.core.domain.TodayOrderRules
import os.meka.core.domain.TodayColumn
import os.meka.core.domain.TodayColumnsRules
import os.meka.core.domain.TodaySlot
import os.meka.android.fold.NowCard
import os.meka.android.fold.NowHandlers
import os.meka.core.domain.CommandLayout

import os.meka.android.calendar.EventDetailPane
import os.meka.android.calendar.opensEvent
import os.meka.android.calendar.EventActionHandlers
import os.meka.android.calendar.EventUndo
import os.meka.android.calendar.EventUndoBar
import os.meka.android.calendar.SwipeableEvent
import os.meka.android.calendar.eventActionHandlers
import os.meka.android.calendar.rememberEventUndo
import os.meka.core.domain.CalendarEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.rememberUpdatedState
import os.meka.android.designsystem.CheckRing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import os.meka.core.domain.WeatherView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import os.meka.android.designsystem.mekaMenuBorder
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import os.meka.core.domain.HeaderCollapseRules
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import os.meka.core.domain.DayHead
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import os.meka.android.goals.unfold as studyUnfold
import os.meka.android.goals.fold as studyFold
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import os.meka.android.news.NewsPane
import os.meka.android.news.TodayNewsRow
import os.meka.android.news.rememberTickerMode
import os.meka.core.domain.NewsTicker
import os.meka.core.domain.TickerMode
import os.meka.core.domain.TickerRules
import os.meka.core.domain.TodayNewsRules
import os.meka.android.designsystem.BreathingRing
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.footFade
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.PullToSyncBox
import os.meka.android.designsystem.rememberPullToSync
import os.meka.android.designsystem.MekaSharedLayout
import os.meka.android.designsystem.rememberPaneMorph
import os.meka.android.designsystem.ContainerOrigins
import os.meka.android.designsystem.LocalContainerOrigins
import os.meka.android.designsystem.containerOrigin
import os.meka.android.designsystem.sharedTitle
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.SharedMotion
import os.meka.android.shell.PlaceVia
import os.meka.android.shell.ShellDestination
import os.meka.android.designsystem.sharedPlace
import os.meka.android.search.SearchPane
import os.meka.android.MekaApplication
import os.meka.android.update.UpdateCard
import os.meka.android.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import os.meka.android.shell.OpenItem
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaChoreography
import os.meka.core.domain.WatchFaceRules
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.TimelineKind
import os.meka.core.domain.ScheduleCardRules
import os.meka.core.domain.AllDayRules
import os.meka.core.domain.NeedsYouReason
import os.meka.core.domain.RepeatChoice
import os.meka.core.domain.MorningBriefView
import os.meka.core.domain.ShutdownView
import os.meka.core.domain.SomedayKind
import os.meka.core.domain.Task
import os.meka.core.domain.TaskWhenRules
import os.meka.core.domain.TaskReminderView
import os.meka.core.domain.TaskWhenView
import os.meka.core.domain.Today
import os.meka.core.facade.ConflictChoice
import os.meka.core.facade.MekaCore
import os.meka.core.domain.ReviewCard
import os.meka.core.domain.GoalsView
import os.meka.core.domain.HabitChipRules
import os.meka.core.domain.HabitDot
import os.meka.core.domain.HabitDotRules
import os.meka.core.domain.WeekGoals
import kotlinx.coroutines.flow.StateFlow
import os.meka.core.sync.SyncStatus
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.WatchFace
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.DayArc
import os.meka.core.domain.DayRing
import os.meka.core.domain.DayRingHeader
import java.time.LocalDate

/**
 * TODAY (brief §4). Closed Fold: one calm column. Open Fold / wide windows (≥ 600dp): Today in two columns under a
 * header across the top (the Today rebuild, slice 2c; core TodayColumnsRules); an open task springs up over it.
 * State survives fold/unfold because selection is saveable and everything else comes from MekaCore flows.
 *
 * Motion (App shell): opening the Fold grows the detail pane out beside the list; on the closed Fold a task's title
 * travels from its row into the detail pane and back; Plan Apply sends each planned block's title into its place in
 * Today, where it is softly lit for a moment. Reduced motion: cross-fades only.
 */
@Composable
fun TodayRoute(
    core: MekaCore, connect: ConnectHook? = null, openReview: () -> Unit = {}, openItem: (OpenItem) -> Unit = {},
    openLists: () -> Unit = {}, openCalendar: () -> Unit = {},
) {
    // The header's watch face reads the core's clock and zone, like the greeting (quality gate, 2026-10-22: a shot at
    // the core's 17:15 showed the phone's time on the face). On a real phone the shift is nothing.
    val faceClock = remember(core) { FaceClock(core.clockShiftMs()) { core.zoneOffsetMs(it) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalFaceClock provides faceClock) {
        TodayRouteContent(core, connect, openReview, openItem, openLists, openCalendar)
    }
}

@Composable
private fun TodayRouteContent(
    core: MekaCore, connect: ConnectHook?, openReview: () -> Unit, openItem: (OpenItem) -> Unit,
    openLists: () -> Unit, openCalendar: () -> Unit,
) {
    val today by core.today.collectAsState()
    val sync by core.syncStatus.collectAsState()
    val conflicts by core.conflicts.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showShutdown by rememberSaveable { mutableStateOf(false) }
    var showBrief by rememberSaveable { mutableStateOf(false) }
    /** The full 24-hour Day ring as a sheet, opened by tapping the watch face (Fold review 2026-10-09 07:26, item 2). */
    var showDayRing by rememberSaveable { mutableStateOf(false) }
    /** The brief was opened by dismissing the wake alarm, so it reads itself aloud (Weather and a voice, slice 8). */
    var briefReadAloud by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    /** The train queue over Today, from the "For the train" line on the commute (train-time queue, slice 1). */
    var showQueue by rememberSaveable { mutableStateOf(false) }
    /** The Fasting screen over Today, from the live Fasting card (Fasting rebuild slice 4). */
    var showFasting by rememberSaveable { mutableStateOf(false) }
    /** Study plan, slice 2b rest: a course over Today, from the Sunday check-in's Change plan. */
    var studyOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var studyShown by remember { mutableStateOf<String?>(null) }
    if (studyOpen != null) studyShown = studyOpen
    var showWeekAhead by rememberSaveable { mutableStateOf(false) }
    // The Tomorrow sheet: from the Tomorrow peek (17:00 on) or the date under the greeting (any time).
    var showTomorrow by rememberSaveable { mutableStateOf(false) }
    // The Day list (approved 2026-11-08): today's, from the Anytime today heading's "Day list ›".
    var showDayList by rememberSaveable { mutableStateOf(false) }
    // Opened from the app icon's Add tasks (slice 2): the add bar takes the keyboard as the list arrives.
    var dayListReady by rememberSaveable { mutableStateOf(false) }
    val pageHaptics = rememberMekaHaptics()
    /** Activity over Today, from the evening's "MEKA handled 12 things today" (daily receipt). */
    var showActivity by rememberSaveable { mutableStateOf(false) }
    // News over Today from the command centre (news ticker, slice 2): "" = the place, an id = that story; null = closed.
    var newsOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var newsShown by remember { mutableStateOf<String?>(null) }
    if (newsOpen != null) newsShown = newsOpen
    // The News home-screen widget (slice 3a): a tapped story springs the News pane up over Today on it.
    val widgetApp = LocalContext.current.applicationContext as? MekaApplication
    val widgetStory = widgetApp?.openNewsStory?.collectAsState()?.value
    LaunchedEffect(widgetStory) {
        if (widgetStory != null) { newsOpen = widgetStory; widgetApp?.openNewsStory?.value = null }
    }
    // Dismissing the wake alarm (Alarms, slice 1): the brief springs up over Today.
    val alarmBrief = widgetApp?.openBrief?.collectAsState()?.value ?: false
    LaunchedEffect(alarmBrief) {
        if (alarmBrief) {
            briefReadAloud = !showBrief; showBrief = true; widgetApp?.openBrief?.value = false
            // The wake alarm's read is the morning's read-out (Meka 2026-11-11): Today's first open doesn't read it again.
            if (briefReadAloud) widgetApp?.let { MorningReadOutStore.markRead(it, core.todayEpochDay()) }
        }
    }
    // "Plan my day" said to Talk (morning brief, slice 5): the planner springs up over Today, as its button does.
    val talkPlan = widgetApp?.openPlan?.collectAsState()?.value ?: false
    LaunchedEffect(talkPlan) {
        if (talkPlan) { showBrief = false; showPlan = true; widgetApp?.openPlan?.value = false }
    }
    // Add tasks on the app icon (the Day list, slice 2): today's Day list springs up with its add bar ready.
    val iconDayList = widgetApp?.openDayList?.collectAsState()?.value ?: false
    LaunchedEffect(iconDayList) {
        if (iconDayList) { showBrief = false; showPlan = false; dayListReady = true; showDayList = true; widgetApp?.openDayList?.value = false }
    }
    // The Day list widget's header or a task (the Day list, slice 6): today's Day list to look at, keyboard down.
    val widgetDayList = widgetApp?.openDayListToLook?.collectAsState()?.value ?: false
    LaunchedEffect(widgetDayList) {
        if (widgetDayList) { showBrief = false; showPlan = false; dayListReady = false; showDayList = true; widgetApp?.openDayListToLook?.value = false }
    }
    // Done for the day, completeness 2: "shut down the day" said to Talk opens the shutdown pane over Today.
    val talkShutdown = widgetApp?.openShutdown?.collectAsState()?.value ?: false
    LaunchedEffect(talkShutdown) {
        if (talkShutdown) { showBrief = false; showPlan = false; showShutdown = true; widgetApp?.openShutdown?.value = false }
    }
    val newsPlace by core.newsPlace.collectAsState()
    // The news ticker under the header (news ticker, slice 2): the per-device choice from Appearance.
    val ticker = remember(newsPlace) { TickerRules.ticker(newsPlace) }
    val tickerMode = rememberTickerMode()
    // An event's detail (calendar redesign, slice 3); the last one is kept while the pane leaves.
    var eventOpen by remember { mutableStateOf<CalendarEvent?>(null) }
    var eventShown by remember { mutableStateOf<CalendarEvent?>(null) }
    if (eventOpen != null) eventShown = eventOpen
    val shutdown by core.shutdownView.collectAsState()
    val brief by core.briefView.collectAsState()
    // The morning read-out (Meka 2026-11-11 06:40): the first morning open of Today reads the brief once, card or not.
    val morningReadOut = rememberMorningReadOut(core, briefReady = brief.dateLabel.isNotEmpty(), briefPaneReading = { showBrief })
    val review by core.reviewView.collectAsState()
    // The command centre beside Today on the open Fold (Fold modes, slice 2): Needs you and Coming up.
    val stack by core.needsYouStack.collectAsState()
    val calendar by core.calendarView.collectAsState()
    var showAfterWork by rememberSaveable { mutableStateOf(false) }
    // Tasks Plan Apply is sending into Today: their rows hide while the plan is up, then catch the flying titles.
    var landing by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(landing, showPlan) {
        if (landing.isNotEmpty() && !showPlan) {
            delay(SharedMotion.LANDED_MS.toLong())
            landing = emptySet()
        }
    }
    val scope = rememberCoroutineScope()
    val openPlan: () -> Unit = { showPlan = true }
    // App open: greeting fades up, then each section 40 ms apart. Plays once per launch (not again on fold/unfold);
    // anything arriving later uses animateItem.
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    // Appearance → Play the opening bumps this: Today's list is rebuilt so its stagger plays again.
    var openings by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openings) {
        if (!introPlayed) {
            // The longest (Expressive) span, so the intro never ends before its last section has started.
            delay((MotionMath.staggerSpanMs(TODAY_SECTIONS, false, expressive = true) + MekaMotion.appearExpressiveDurationMs).toLong())
            introPlayed = true
        }
    }

    // The opening moment's Day ring: decided once per launch (in full the first time today on this phone, quickly
    // after, at once with Motion → Off); once it has landed it stays still, so scrolling it away doesn't replay it.
    val ringContext = LocalContext.current
    val ringReduced = Meka.reducedMotion
    var ringPlay by rememberSaveable {
        mutableStateOf(DayRingOpen.claim(ringContext, LocalDate.now().toEpochDay(), ringReduced))
    }
    // Every open is a moment (Living Today, slice 2): coming back to Today after a while (another app, the screen
    // off) draws the ring in again and replays Today's stagger; the first return on a new day plays the full opening
    // even when MEKA stayed in memory overnight. A fingerprint prompt or a quick glance elsewhere leaves Today as it was.
    var pausedAt by remember { mutableLongStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pausedAt = SystemClock.elapsedRealtime() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // "Where I am now" (Places item 3): with the switch on, one approximate fix as Today opens (the core decides,
        // at most once per 30 minutes); away from home and work the weather line then reads "Near you · …".
        scope.launch { HereLocation.refresh(ringContext, core, null) }
        // Health Connect, slice 1: with Workouts on, today's workouts tick their habits (at most every 5 minutes).
        scope.launch { os.meka.android.goals.HealthWorkouts.refresh(ringContext, core) }
        val away =if (pausedAt == 0L) 0L else SystemClock.elapsedRealtime() - pausedAt
        val back = DayRingOpen.onReturn(ringContext, LocalDate.now().toEpochDay(), away, ringReduced)
        if (back != null && pausedAt != 0L) {
            ringPlay = back
            introPlayed = false
            openings++
        } else if (back == DayRingPlay.FULL) {
            ringPlay = DayRingPlay.FULL
        }
    }
    // Appearance → Play the opening: the Day ring, its tiles, the greeting and Today's stagger play again on demand.
    val replayApp = ringContext.applicationContext as? MekaApplication
    val replay = replayApp?.playOpening?.collectAsState()?.value ?: false
    LaunchedEffect(replay) {
        if (replay) {
            replayApp?.playOpening?.value = false
            ringPlay = if (ringReduced) DayRingPlay.STILL else DayRingPlay.FULL
            introPlayed = false
            openings++
        }
    }

    // Calendar actions: swipe an event right for a prep task, left to hide it from my day; an undo bar rises.
    val eventUndo = rememberEventUndo()
    val actions = todayActions(core, scope, { selectedId }, { selectedId = it }, eventUndo)
    val eventHandlers = remember(core, scope, eventUndo) { eventActionHandlers(core, scope, eventUndo) }
    val moves = rememberDecisionMoves(core, eventUndo, openTask = { selectedId = it }, openLists = openLists)
    // The cover screen's "now" card (Fold modes, slice 3): read from Today, which refreshes every minute.
    // Also keyed on the booked sessions: answering "Did you go?" changes the card but not Today.
    val sessions by core.sessionsView.collectAsState()
    val nowView = remember(today, sessions) { core.coverNow() }
    // Up next as the same card on the open Fold (Fold review 2026-10-09, item 3).
    val upNextView = remember(today) { core.upNextCard() }
    val talkApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? os.meka.android.MekaApplication
    // "Move to later" on a late planned task (Fold review 2026-10-09 13:45, item 3): re-planned, Undo on the bar.
    val moveLater: (String) -> Unit = { id ->
        scope.launch {
            runCatching { core.moveLater(id) }.getOrNull()?.let { mv -> eventUndo.show(mv.line) { core.undoMoveLater(mv) } }
        }
    }
    // "Break it down" on a task carried over 2+ days (Fold review 2026-10-12 17:56, item 5): its detail opens with the
    // checklist's add field focused, ready for the steps.
    val breakDown: (String) -> Unit = { id -> StepFocus.request(id); actions.select(id) }
    val nowHandlers = NowHandlers(
        complete = actions.complete, tomorrow = actions.snooze, openTask = actions.select,
        openEvent = { eventOpen = it }, openNeedsYou = null, // Needs you is listed just above it on the cover screen
        went = { id -> scope.launch { runCatching { core.sessionWent(id, null) } } },
        didntGo = { id -> scope.launch { runCatching { core.sessionMissed(id) } } },
        later = moveLater,
        breakDown = breakDown,
    )
    // The mic sits at the capture bar's end on every screen (Fold review 2026-10-09 07:26, item 5), not on a card:
    // Ask, already listening.
    val talkFromCapture: () -> Unit = { os.meka.android.ask.talkFromCover(talkApp) }
    // The open Fold's Up next card: the same taps and no Needs you line.
    val upNextHandlers = NowHandlers(
        complete = actions.complete, tomorrow = actions.snooze, openTask = actions.select,
        openEvent = { eventOpen = it }, openNeedsYou = null, later = moveLater,
        breakDown = breakDown,
    )

    // Insets are applied once, by the app shell.
    // Where each task row sits, so its detail grows out of it on the closed Fold (container transform).
    val origins = remember { ContainerOrigins() }
    MekaSharedLayout(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalContainerOrigins provides origins) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
            val layout = CommandCentreRules.layout(maxWidth.value)
            val twoPane = layout != CommandLayout.SINGLE
            // The Today rebuild, slice 2c: on the open Fold Today itself is two columns across the whole width (core
            // TodayColumnsRules), in place of Today | Needs you over Coming up; an open task springs up over it as on the
            // closed Fold. Needs you keeps its tab in the rail.
            val split = TodayColumnsRules.splits(layout)
            val sideBySide = twoPane && !split
            val all = (today.needsYou.map { it.task } + today.fromYesterday.map { it.task } + listOfNotNull(today.upNext) + today.yourDay)
            // A reminder under Coming up in the command centre opens its task too (rememberOpenTask).
            val selected = rememberOpenTask(core, selectedId, all, today)
            val columns = CommandCentreRules.columns(layout, taskOpen = selected != null)
            // In the command centre an open task stands in for Needs you; back (or Close) brings Needs you back.
            BackHandler(enabled = sideBySide && selected != null) { selectedId = null }
            val rowMotion: (String) -> RowMotion = { id ->
                RowMotion(
                    shareTitle = true,
                    titleVisible = SharedMotion.rowTitleVisible(id, selectedId, !sideBySide, showPlan, landing),
                    landed = SharedMotion.highlightLanded(id, showPlan, landing),
                )
            }
            TwoPaneMorph(
                sideBySide,
                detailShare = if (sideBySide) CommandCentreRules.sideShare(layout) else CommandCentreRules.SIDE_SHARE_TWO,
                list = { m ->
                    // Swipe left across Today for Tomorrow, any time of day (Meka 2026-10-26); right on the sheet comes back.
                    key(openings) { TodayPane(today, sync, actions, m.swipePages(toTomorrow = true) { pageHaptics.tick(); showTomorrow = true }, connect, openPlan, !introPlayed, rowMotion,
                        ringPlay = ringPlay, ringPlayed = { ringPlay = DayRingPlay.STILL },
                        openDayRing = { showDayRing = true },
                        openQueue = { showQueue = true },
                        openFasting = { showFasting = true },
                        openStudy = { id -> studyOpen = id },
                        openWeekAhead = { showWeekAhead = true },
                        openRenewal = { id -> openItem(OpenItem(os.meka.core.domain.SearchTarget.LISTS_RENEWALS, id)) },
                        openTomorrow = { showTomorrow = true },
                        openDayList = { pageHaptics.tick(); showDayList = true },
                        openActivity = { showActivity = true },
                        listsNeedsYou = split || CommandCentreRules.todayListsNeedsYou(layout),
                        carriesSetup = CommandCentreRules.todayCarriesSetup(layout),
                        split = split,
                        shutdown = shutdown, openShutdown = { showShutdown = true }, shutdownOpen = showShutdown,
                        openSearch = { showSearch = true },
                        brief = brief, openBrief = { morningReadOut.stop(); showBrief = true }, briefOpen = showBrief,
                        reviewCard = review.card, openReviewCard = { scope.launch { runCatching { core.showReviewCardWeek() }; openReview() } },
                        laterReviewCard = { scope.launch { runCatching { core.reviewLater(null) } } },
                        laterShutdownCard = { scope.launch { runCatching { core.shutdownLater(null) } } },
                        openEvent = { eventOpen = it }, eventHandlers = eventHandlers, undo = eventUndo,
                        now = if (twoPane) null else nowView, nowHandlers = nowHandlers,
                        upNext = upNextView, upNextHandlers = upNextHandlers,
                        ticker = ticker, tickerMode = tickerMode, core = core,
                        openStory = { id -> newsOpen = id }, openMatch = { eventOpen = it },
                        onTalk = talkFromCapture) }
                },
                detail = { m ->
                    CommandSide(
                        columns, m,
                        detail = { dm -> DetailPane(selected, conflicts.filter { it.taskId == selected?.id }, actions, dm, onClose = { selectedId = null }) },
                        needsYou = { nm ->
                            // Walk-through week 3, A1: on the open Fold the health line and the setup card head this
                            // column, so Up next and the timeline lead Today's own column above the capture bar.
                            if (CommandCentreRules.todayCarriesSetup(layout)) {
                                CommandNeedsYou(core, stack, moves, nm, openAfterWork = { showAfterWork = true })
                            } else {
                                Column(nm) {
                                    SideHousekeeping(core)
                                    CommandNeedsYou(core, stack, moves, Modifier.weight(1f).fillMaxWidth(), openAfterWork = { showAfterWork = true })
                                }
                            }
                        },
                        comingUp = { cm, shared ->
                            ComingUpColumn(
                                calendar, shared, cm, openEvent = { eventOpen = it }, openCalendar = openCalendar,
                                news = newsPlace, openNews = { id -> newsOpen = id ?: "" },
                            )
                        },
                    )
                },
            )
            // "Reading your morning brief · Stop" over the top of Today while the morning read-out speaks.
            MorningReadOutLine(morningReadOut, Modifier.align(Alignment.TopCenter))
            // Closed Fold: detail springs up over Today. The last task is kept so it stays visible while leaving.
            var shown by remember { mutableStateOf<Task?>(null) }
            if (selected != null) shown = selected
            MekaPane(visible = selected != null && !sideBySide, origin = { origins[shown?.id] }) {
                shown?.let { s ->
                    DetailPane(s, conflicts.filter { it.taskId == s.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
            MekaPane(visible = showPlan) {
                PlanPane(core, landing = landing, onApplying = { landing = it }, undo = eventUndo, onClose = { showPlan = false })
            }
            MekaPane(visible = showShutdown) { ShutdownPane(core, onClose = { showShutdown = false }) }
            MekaPane(visible = showBrief) {
                BriefPane(core, onClose = { showBrief = false; briefReadAloud = false }, readAloud = briefReadAloud,
                    // Slice 7: the read ran to its end: Ask listens for 8 s, then Today comes back if nothing is said.
                    onTalkAfter = {
                        showBrief = false; briefReadAloud = false
                        widgetApp?.talkAfterBrief?.value = os.meka.android.shell.ShellDestination.TODAY
                        widgetApp?.openDestination?.value = os.meka.android.shell.ShellDestination.ASK
                    })
            }
            // Tap the watch face: the full 24-hour Day ring springs up; an arc in it closes the sheet and opens its event or task.
            MekaPane(visible = showDayRing) {
                DayRingSheet(
                    today.dayRing, today.dayTiles,
                    onOpenArc = { arc ->
                        showDayRing = false
                        when (arc.kind) {
                            DayArcKind.EVENT -> today.events.firstOrNull { "e-" + it.id == arc.id }?.let { eventOpen = it }
                            DayArcKind.TASK -> { selectedId = arc.id.removePrefix("t-") }
                            DayArcKind.SESSION -> Unit // the session's row in the timeline carries its actions
                        }
                    },
                    onClose = { showDayRing = false },
                )
            }
            MekaPane(visible = eventOpen != null) {
                eventShown?.let { e -> EventDetailPane(core, e, onClose = { eventOpen = null }, undo = eventUndo) }
            }
            MekaPane(visible = newsOpen != null) {
                // A fresh pane each time it opens, so it starts on the story that was tapped.
                newsShown?.let { start ->
                    key(start) { NewsPane(core, onClose = { newsOpen = null }, backLabel = "‹ Today", startStoryId = start.ifEmpty { null }) }
                }
            }
            BackHandler(enabled = showFasting) { showFasting = false }
            MekaPane(visible = studyOpen != null) {
                studyShown?.let { id -> os.meka.android.goals.StudyPane(core, id, onClose = { studyOpen = null }, onRemoved = { studyOpen = null }) }
            }
            MekaPane(visible = showFasting) { os.meka.android.goals.FastingPane(core, onClose = { showFasting = false }, backLabel = "‹ Today") }
            MekaPane(visible = showQueue) { TrainQueuePane(core, onClose = { showQueue = false }, closeLabel = "‹ Today") }
            MekaPane(visible = showWeekAhead) { WeekAheadPane(core, onClose = { showWeekAhead = false }, closeLabel = "‹ Today") }
            BackHandler(enabled = showTomorrow) { showTomorrow = false }
            MekaPane(visible = showTomorrow) { TomorrowPane(core, onClose = { showTomorrow = false }) }
            BackHandler(enabled = showDayList) { showDayList = false; dayListReady = false }
            MekaPane(visible = showDayList) {
                DayListPane(core, onClose = { showDayList = false; dayListReady = false }, readyToType = dayListReady)
            }
            MekaPane(visible = showActivity) { ActivityPane(core, onClose = { showActivity = false }, closeLabel = "‹ Today") }
            MekaPane(visible = showAfterWork) { AfterWorkHost(onClose = { showAfterWork = false }) }
            MekaPane(visible = showSearch) {
                SearchPane(core, onClose = { showSearch = false }, openItem = { item -> showSearch = false; openItem(item) })
            }
            // Change on a request card in the open Fold's Needs you column.
            RequestChangePane(core, moves)
            // "Archived for you" shows which emails, slice 3: All tidied mail from the card in that column.
            ArchivedAllHost(core, moves)
            // Review opens its own list, slice 1: the Empty Junk review from the card in that column.
            JunkReviewHost(core, moves)
            EventUndoBar(eventUndo, Modifier.align(Alignment.BottomCenter))
        }
      }
    }
}

/**
 * The list beside its detail, morphing between the closed and open Fold: unfolding grows the detail pane out from
 * the right edge while the list narrows; folding shrinks it away. The list keeps its place in the tree, so its scroll
 * position survives.
 */
@Composable
internal fun BoxWithConstraintsScope.TwoPaneMorph(
    twoPane: Boolean,
    list: @Composable (Modifier) -> Unit,
    detail: @Composable (Modifier) -> Unit,
    /** The detail's share of the width once open; the morph scales to it (the command centre's three columns take more). */
    detailShare: Float = SharedMotion.DETAIL_FRACTION,
) {
    val fraction by rememberPaneMorph(twoPane)
    val total = maxWidth * (detailShare / SharedMotion.DETAIL_FRACTION)
    Row(Modifier.fillMaxSize()) {
        list(Modifier.weight(1f).fillMaxHeight())
        if (fraction > 0.001f) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Meka.colors.hairline))
            detail(
                Modifier.width(total * fraction).fillMaxHeight().clipToBounds()
                    .graphicsLayer { alpha = SharedMotion.detailAlpha(fraction) },
            )
        }
    }
}

/** How a task row takes part in shared transitions: whether its title travels, whether it draws it, a soft light on landing. */
internal data class RowMotion(val shareTitle: Boolean = false, val titleVisible: Boolean = true, val landed: Boolean = false)

/** Stagger groups on Today, in the order they sit (Calm Today, slice 3): the header, then core TodayOrderRules' steps. */
private const val TODAY_SECTIONS = TodayOrderRules.SECTIONS

/** Present only while the device isn't enrolled for sync. */
data class ConnectHook(
    val defaultUrl: String,
    val connect: suspend (url: String, code: String) -> String?,
    /** Recovery key, slice 2: connect with the 24 words instead of the code (null: not offered). */
    val recover: (suspend (url: String, words: String) -> String?)? = null,
    /** Clean-ups (2026-10-19): the ask while this new phone waits for Allow on the household's other device. */
    val waiting: kotlinx.coroutines.flow.StateFlow<os.meka.core.facade.EnrolmentResult.Waiting?>? = null,
)

/** The commands every task list (Today, Needs you) offers, wired to [core]. Selection is owned by the caller. */
internal fun todayActions(
    core: MekaCore, scope: CoroutineScope, selected: () -> String?, setSelected: (String?) -> Unit,
    /** The screen's undo bar: Delete in the detail offers Undo there (no dialog). */
    undo: EventUndo? = null,
) = TodayActions(
    // Typed into MEKA's own capture bar: "alarm 6:30" / "timer 20 min" set an alarm or timer (Alarms, slice 2), with
    // Undo on the bar; anything else is a task.
    add = { title ->
        scope.launch {
            val outcome = runCatching { core.captureTyped(title) }.getOrNull()
            if (outcome is os.meka.core.domain.CaptureOutcome.AlarmSet && undo != null) {
                undo.show(outcome.line) { core.cancelAlarm(outcome.alarmId) }
            }
        }
    },
    complete = { id -> scope.launch { core.complete(id); if (selected() == id) setSelected(null) } },
    reopen = { id ->
        scope.launch {
            val title = core.today.value.doneToday.firstOrNull { it.id == id }?.title
            runCatching { core.reopen(id) }
            if (undo != null && title != null) undo.show(os.meka.core.domain.TodayTickRules.untickedLine(title)) { core.complete(id) }
        }
    },
    select = { id -> setSelected(id) },
    rename = { id, t -> TypingSaves.launch { core.rename(id, t) } },
    delete = { id ->
        scope.launch {
            val title = core.today.value.let { t -> (t.needsYou.map { it.task } + t.fromYesterday.map { it.task } + listOfNotNull(t.upNext) + t.yourDay) }.firstOrNull { it.id == id }?.title
            runCatching { core.delete(id) }
            setSelected(null)
            if (undo != null && title != null) undo.show(TaskWhenRules.deletedLine(title)) { core.restore(id) }
        }
    },
    resolve = { c, v -> scope.launch { core.resolve(c, v) } },
    repeatChoices = { id -> core.repeatChoices(id) },
    setRepeat = { id, rule -> scope.launch { runCatching { core.setRepeat(id, rule) } } },
    skip = { id -> scope.launch { runCatching { core.skipOccurrence(id) }; if (selected() == id) setSelected(null) } },
    snooze = { id -> scope.launch { runCatching { core.snooze(id, 1) }; if (selected() == id) setSelected(null) } },
    // Checklists (slice 1): one typed or pasted line adds each of its items.
    addStep = { id, text -> TypingSaves.launch { core.addSteps(id, text) } },
    setStepDone = { stepId, done -> scope.launch { core.setStepDone(stepId, done) } },
    removeStep = { item ->
        scope.launch {
            runCatching { core.removeStep(item.id) }
            undo?.show(os.meka.core.domain.ChecklistRules.removedLine(item.text)) { core.restoreStep(item.id) }
        }
    },
    moveStep = { id, stepId, to -> scope.launch { runCatching { core.moveStepInGroup(id, stepId, to) } } },
    untickAll = { id ->
        scope.launch {
            val ids = runCatching { core.untickAllSteps(id) }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) undo?.show(os.meka.core.domain.ChecklistRules.untickedLine(ids.size)) { core.tickSteps(ids) }
        }
    },
    templates = { core.checklistTemplates() },
    templateExists = { name -> runCatching { core.templateExists(name) }.getOrDefault(false) },
    saveTemplate = { id, name ->
        scope.launch {
            val saved = runCatching { core.saveChecklistTemplate(id, name) }.getOrNull() ?: return@launch
            val tid = saved.id
            if (tid != null && !saved.replaced) {
                undo?.show(saved.line) { core.removeChecklistTemplate(tid) }
            } else undo?.show(saved.line, null)
        }
    },
    startTemplate = { id, templateId ->
        scope.launch {
            val applied = runCatching { core.startFromTemplate(id, templateId) }.getOrNull() ?: return@launch
            if (applied.stepIds.isNotEmpty()) undo?.show(applied.line) { applied.stepIds.forEach { core.removeStep(it) } }
            else undo?.show(applied.line, null)
        }
    },
    removeTemplate = { t ->
        scope.launch {
            val removed = runCatching { core.removeChecklistTemplate(t.id) }.getOrNull() ?: return@launch
            undo?.show(os.meka.core.domain.ChecklistTemplateRules.removedLine(removed.name)) { core.restoreChecklistTemplate(removed) }
        }
    },
    someday = { id -> scope.launch { runCatching { core.moveToSomeday(id, SomedayKind.IDEA) }; if (selected() == id) setSelected(null) } },
    goals = core.goalsView,
    setGoal = { id, goalId -> scope.launch { runCatching { core.setTaskGoal(id, goalId) } } },
    whenOf = { t -> core.taskWhen(t) },
    setWhen = { id, day, minute -> scope.launch { runCatching { core.setWhen(id, day, minute) } } },
    setNotes = { id, notes -> TypingSaves.launch { core.setNotes(id, notes) } },
    reminderOf = { t -> core.taskReminder(t) },
    setReminder = { id, at -> scope.launch { runCatching { core.setReminder(id, at) } } },
)

data class TodayActions(
    val add: (String) -> Unit,
    val complete: (String) -> Unit,
    /** A done row's circle on Today (Week board by hand, slice 6): open again, with Undo on the bar. */
    val reopen: (String) -> Unit = {},
    val select: (String) -> Unit,
    val rename: (String, String) -> Unit,
    val delete: (String) -> Unit,
    val resolve: (ConflictChoice, String) -> Unit,
    val repeatChoices: suspend (String) -> List<RepeatChoice>,
    val setRepeat: (String, String?) -> Unit,
    val skip: (String) -> Unit,
    val snooze: (String) -> Unit,
    val addStep: (String, String) -> Unit,
    val setStepDone: (String, Boolean) -> Unit,
    /** Swipe or Remove on a checklist item: it goes, with Undo on the screen's bar. */
    val removeStep: (os.meka.core.domain.ChecklistItem) -> Unit,
    /** Drag to reorder: (task, item, index among the rows of the item's own group). */
    val moveStep: (String, String, Int) -> Unit,
    /** Untick all on a checklist, to reuse it (Undo ticks them again). */
    val untickAll: (String) -> Unit,
    /** Checklist templates (slice 3a): Start from template's choices, loaded when the picker opens. */
    val templates: suspend () -> os.meka.core.domain.ChecklistTemplatesView = { os.meka.core.domain.ChecklistTemplateRules.view(emptyList()) },
    /** Whether a template of this name exists already (Save reads "Update"). */
    val templateExists: suspend (String) -> Boolean = { false },
    /** Save as template: (task, name); the bar says so (Undo removes a new one). */
    val saveTemplate: (String, String) -> Unit = { _, _ -> },
    /** Start from template: (task, template id); its items join the list (Undo takes them off). */
    val startTemplate: (String, String) -> Unit = { _, _ -> },
    /** Remove a saved template (Undo brings it back). */
    val removeTemplate: (os.meka.core.domain.ChecklistTemplate) -> Unit = {},
    /** Out of Today and the planner, into Lists → Someday. */
    val someday: (String) -> Unit,
    /** Open goals, for linking a task to one (its progress then counts the task). */
    val goals: StateFlow<GoalsView>,
    val setGoal: (String, String?) -> Unit,
    /** When: the row as shown (day, optional time, chips). */
    val whenOf: (Task) -> TaskWhenView,
    /** Puts the task on a local epoch day, at a minute of the day or with no time. */
    val setWhen: (String, Long, Int?) -> Unit,
    val setNotes: (String, String) -> Unit,
    /** Remind me: the row as shown ("Off" or when) and the chips still ahead. */
    val reminderOf: (Task) -> TaskReminderView,
    /** Reminds at epoch ms (a heads-up through the governor); null turns it off. */
    val setReminder: (String, Long?) -> Unit,
)

/**
 * Today's header row (Fold review 2026-10-09, item 1): the greeting, date, weather and links on the left ([left]), the
 * watch face on the right (Fold review 2026-10-09 07:26, item 2) — [DayRingHeader.WIDE_DP] on the open Fold, a smaller
 * [WatchFaceRules.COVER_DP] face on the closed Fold ([compact]; watch face item 2026-10-12). Tapping it opens the full 24-hour Day ring
 * ([onOpenFace]). No [face] (no day yet): the left side takes the whole row.
 */
@Composable
internal fun TodayHeaderRow(
    face: WatchFace?, compact: Boolean, play: DayRingPlay, played: () -> Unit,
    onOpenFace: (() -> Unit)? = null,
    /** Today's habits as dots on the face (Calm Today, slice 2); a tap on one ticks it. */
    dots: List<HabitDot> = emptyList(),
    onTick: ((HabitDot) -> Unit)? = null,
    /**
     * Done for the day: once the day is shut down the face dims to night ([WatchFaceRules.nightAlpha]) over the
     * `nightFall` token as the pane drops away, its second hand and breath at rest. Reduced motion: the short cross-fade.
     */
    night: Boolean = false,
    /** This week's goals as arcs on the face's outer ring (Goals, properly, slice 7). */
    week: WeekGoals = WeekGoals.EMPTY,
    /**
     * Watch face item, part 2: on the cover screen the face is drawn by [CollapsingHeader], pinned over this row, so
     * the row keeps only an empty slot its size (the lines wrap exactly as before).
     */
    slotOnly: Boolean = false,
    /** The Today rebuild: the weather chip and ⋯ between the greeting and the clock, as the mockup's header. */
    trailing: @Composable () -> Unit = {},
    left: @Composable () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val faceAlpha by animateFloatAsState(
        WatchFaceRules.nightAlpha(night),
        if (reduced) MekaMotion.appear<Float>(true) else tween<Float>(MekaChoreography.nightFallMs),
        label = "watch-face-night",
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) { left() }
        Row(
            Modifier.padding(start = MekaSpace.xs).heightIn(min = minOf(WatchFaceRules.headerDp(cover = compact), HeaderCollapseRules.COMPACT_DP).dp),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) { trailing() }
        if (face != null && slotOnly) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = MekaSpace.m)
                .size(WatchFaceRules.headerDp(cover = compact).dp))
        } else if (face != null) {
            WatchFaceDial(face, play, played, Modifier.padding(start = MekaSpace.m).graphicsLayer { alpha = faceAlpha },
                size = WatchFaceRules.headerDp(cover = compact).dp, onOpen = onOpenFace, dots = dots, onTick = onTick, night = night,
                week = week)
        }
    }
}

@Composable
private fun TodayPane(
    today: Today, sync: SyncStatus, actions: TodayActions, modifier: Modifier, connect: ConnectHook?,
    openPlan: () -> Unit, play: Boolean, rowMotion: (String) -> RowMotion,
    shutdown: ShutdownView, openShutdown: () -> Unit, shutdownOpen: Boolean, openSearch: () -> Unit,
    brief: MorningBriefView, openBrief: () -> Unit, briefOpen: Boolean,
    reviewCard: ReviewCard, openReviewCard: () -> Unit,
    /** Weekly reflection, slice 4: "Later tonight" on the review card (Sunday only): it leaves and comes back in two hours. */
    laterReviewCard: () -> Unit = {},
    laterShutdownCard: () -> Unit = {},
    openEvent: (CalendarEvent) -> Unit = {},
    eventHandlers: EventActionHandlers? = null,
    /** False in the command centre, where the Needs you column beside Today shows them. */
    listsNeedsYou: Boolean = true,
    /** False in the command centre, where the column beside Today carries the health line and the setup card (A1). */
    carriesSetup: Boolean = true,
    /** The closed Fold's cover screen (Fold modes, slice 3): the "now" card heads Today in place of Up next. */
    now: NowView? = null,
    nowHandlers: NowHandlers? = null,
    /** Up next as the closed Fold's card (Fold review 2026-10-09, item 3); shown where the "now" card isn't. */
    upNext: NowView? = null,
    upNextHandlers: NowHandlers? = null,
    ticker: NewsTicker = NewsTicker.EMPTY,
    tickerMode: TickerMode = TickerMode.OFF,
    core: MekaCore? = null,
    openStory: (String) -> Unit = {},
    openMatch: (CalendarEvent) -> Unit = {},
    ringPlay: DayRingPlay = DayRingPlay.STILL,
    ringPlayed: () -> Unit = {},
    /** Tapping the watch face opens the full 24-hour Day ring. */
    openDayRing: () -> Unit = {},
    /** Tapping "For the train" opens the train queue. */
    openQueue: () -> Unit = {},
    /** The Tomorrow peek and the date under the greeting open the Tomorrow sheet. */
    openTomorrow: () -> Unit = {},
    /** The Day list: "Day list ›" beside the Anytime today heading (or under Today when nothing is unplanned). */
    openDayList: () -> Unit = {},
    /** Tapping the live Fasting card opens the Fasting screen. */
    openFasting: () -> Unit = {},
    /** Study plan, slice 2b rest: Change plan on the Sunday check-in opens the course. */
    openStudy: (String) -> Unit = {},
    /** Tapping "Your week ahead · …" (Sunday from 18:00) opens the week ahead. */
    openWeekAhead: () -> Unit = {},
    /** The Today rebuild, slice 2b-2: Also today's renewal tile opens Renewals at that one. */
    openRenewal: (String) -> Unit = {},
    openActivity: () -> Unit = {},
    /** The capture bar's mic (Talk): Ask, already listening. Null: no mic. */
    onTalk: (() -> Unit)? = null,
    /** The screen's undo bar: a habit ticked on the watch face says which one, with Undo. */
    undo: EventUndo? = null,
    /** The Today rebuild, slice 2c: the open Fold's two columns under a header across the top ([TodayColumnsRules]). */
    split: Boolean = false,
) {
    // "3 earlier" unfolds the finished events in place.
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    // Calmer Today, slice 1: Done today is his choice (Folded by default · Hidden · Shown), synced with the Mac. A tap on
    // the fold line opens or closes it in the same frame; the core's write (open for the rest of today) follows.
    val doneChoiceFlow = remember(core) { core?.doneTodayChoice ?: MutableStateFlow(os.meka.core.domain.DoneTodayChoiceView.DEFAULT) }
    val doneChoiceCore by doneChoiceFlow.collectAsState()
    var doneFoldTapped by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(doneChoiceCore) { if (doneFoldTapped == doneChoiceCore.openToday) doneFoldTapped = null }
    val doneChoice = doneFoldTapped?.let { doneChoiceCore.copy(openToday = it) } ?: doneChoiceCore
    val doneScope = rememberCoroutineScope()
    val toggleDoneFold: () -> Unit = {
        val open = !doneChoice.openToday
        doneFoldTapped = open
        if (core != null) doneScope.launch { runCatching { core.setDoneTodayOpen(open) } }
    }
    // "+2 more" unfolds the rest of the all-day group.
    var allDayOpen by rememberSaveable { mutableStateOf(false) }
    val updater = (LocalContext.current.applicationContext as? MekaApplication)?.updater
    // Samsung battery care (Reliability first, slice 1): re-read on every open, since Meka fixes it in Settings.
    val batteryContext = LocalContext.current
    val batteryWatching = remember(batteryContext) {
        (batteryContext.applicationContext as? MekaApplication)?.let { runCatching { it.identity.deviceSecret() != null }.getOrDefault(false) } ?: false
    }
    var battery by remember { mutableStateOf(BatteryCare.view(batteryContext, batteryWatching)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        BatteryCare.beat(batteryContext)
        battery = BatteryCare.view(batteryContext, batteryWatching)
    }
    val update by remember(updater) { updater?.state ?: MutableStateFlow<UpdateState>(UpdateState.None) }.collectAsState()
    // Weather for home (weather item, slice 1): a quiet line under the date.
    val weatherFlow = remember(core) { core?.weatherView ?: MutableStateFlow(WeatherView.EMPTY) }
    // One name everywhere (2026-10-21): the greeting's name is About you's, as the orb's hello and the brief say it.
    val aboutFlow = remember(core) { core?.aboutYouView ?: MutableStateFlow(os.meka.core.domain.AboutYouView.EMPTY) }
    val callName = aboutFlow.collectAsState().value.callName
    val weather by weatherFlow.collectAsState()
    // Calendar sign-ins about to end or expired (Reliability first, item 2): one line with Reconnect.
    val signInFlow = remember(core) { core?.signInLine ?: MutableStateFlow(emptyList<os.meka.core.domain.SignInLine>()) }
    val signIn by signInFlow.collectAsState()
    // Roll-over's prompt: Break it down opens the task with its steps ready; Someday and Drop leave with Undo on the bar.
    val carryScope = rememberCoroutineScope()
    // The Today rebuild, slice 2b-2b-2: which Also today tile has its detail unfolded under the row (prep, stacking up).
    var alsoUnfolded by remember { mutableStateOf<os.meka.core.domain.AlsoKind?>(null) }
    // Study plan, slice 2b rest: the nudge card's taps, shown at once (Start → Done; Done and Not now fold it away).
    val nudgeStarted = remember { mutableStateListOf<String>() }
    val nudgeGone = remember { mutableStateListOf<String>() }
    var nudgeLast by remember { mutableStateOf<os.meka.core.domain.StudyNudge?>(null) }
    // Slice 2b rest: the Sunday check-in, folded away in the same frame as Looks good.
    val checkinGone = remember { mutableStateListOf<String>() }
    LaunchedEffect(today.studyNudge) {
        today.studyNudge?.let { nudgeLast = it }
        // Once the core says it's on, the tap's no longer needed.
        today.studyNudge?.takeIf { it.on }?.let { nudgeStarted.remove(it.key) }
    }
    val carryChoice: (String, os.meka.core.domain.CarryChoice) -> Unit = { id, choice ->
        if (choice == os.meka.core.domain.CarryChoice.BREAK_DOWN) { StepFocus.request(id); actions.select(id) }
        if (core != null) carryScope.launch {
            val line = runCatching { core.answerCarried(id, choice) }.getOrNull()
            if (line != null) undo?.show(line) { core.undoCarried(id) }
        }
    }
    val motion = Meka.motion
    // Not on the closed Fold's cover screen: the card waits for the main screen.
    val motionCard = if (now == null) motion.card else null
    val pull = rememberPullToSync { core?.syncNow() }
    // Today's habits as chips under the ticker (Fold review 2026-10-09 07:26, item 3); the tiles strip drops its habits tile.
    // Taps feel instant: a habit's tick on the dots or chips shows in the same frame ([HabitTaps]).
    val goalsNow = os.meka.android.goals.shownGoals(actions.goals)
    val habitChips = remember(goalsNow) { HabitChipRules.build(goalsNow) }
    // Calm Today, slice 2: they sit on the watch face as dots; the chips only when the face is hidden or they don't fit.
    // The Today rebuild (Meka 2026-11-09 08:09 (a)): the folded phone's small 40 dp clock carries no dots, so its habits
    // are the chips; the open Fold's face keeps them.
    // Slice 2c: the open Fold's header across the top is the same compact one, so its clock is small and carries no dots.
    val smallClock = now != null || split
    val faceShown = today.timeline.dateLabel.isNotEmpty() && WatchFaceRules.carriesDots(cover = smallClock)
    val faceWeek = if (WatchFaceRules.carriesDots(cover = smallClock)) goalsNow.week else os.meka.core.domain.WeekGoals.EMPTY
    val habitDots = remember(habitChips, faceShown) { if (faceShown) HabitDotRules.dots(habitChips) else emptyList() }
    val chipsShown = HabitDotRules.chipsShown(faceShown, habitChips)
    // Fasting rebuild slice 4: a running fast has its own card on Today, so the strip drops its fast tile meanwhile.
    val fastCard = core?.let { os.meka.android.goals.rememberFastingTodayCard(it) }
    val endFast = core?.let { os.meka.android.goals.rememberFastingTodayEnd(it, undo) }
    val stripTiles = remember(today.dayTiles, fastCard == null) {
        os.meka.core.domain.FastingScreenRules.stripTiles(HabitChipRules.stripTiles(today.dayTiles), fastCard)
            // The Today rebuild: the countdown is the NEXT tile and the renewals figure is Also today's renewal tile.
            .filter { it.kind != os.meka.core.domain.DayTileKind.NEXT_EVENT && it.kind != os.meka.core.domain.DayTileKind.RENEWALS }
    }
    val chipHaptics = rememberMekaHaptics()
    val chipScope = rememberCoroutineScope()
    // Session cards and quick alarms take a list item only while there is something to show.
    val sessionsFlow = remember(core) { core?.sessionsView ?: MutableStateFlow(os.meka.core.domain.SessionsView.EMPTY) }
    val sessionCards = sessionsFlow.collectAsState().value.cards.isNotEmpty()
    val alarmsFlow = remember(core) { core?.quickAlarms ?: MutableStateFlow<List<os.meka.core.domain.QuickAlarmItem>>(emptyList()) }
    val quickAlarms = alarmsFlow.collectAsState().value.isNotEmpty()
    Column(modifier.imePadding()) {
        // Pull to sync (motion pass 2, slice 3): pulling Today down past its top fills a brass ring; letting go syncs.
        PullToSyncBox(pull, Modifier.weight(1f).fillMaxWidth()) {
        // Watch face item, part 2: on the cover screen the header collapses as Today scrolls ([HeaderCollapseRules]).
        val headerFace = today.watchFace.takeIf { today.timeline.dateLabel.isNotEmpty() }
        val collapses = HeaderCollapseRules.collapses(cover = now != null) && headerFace != null
        val listState = rememberLazyListState()
        val collapseScope = rememberCoroutineScope()
        val density = LocalDensity.current
        var headerPx by remember { mutableIntStateOf(0) }
        val barPx = with(density) { COMPACT_BAR_HEIGHT.toPx() }
        val reducedCollapse = Meka.reducedMotion
        val collapse by remember(collapses, barPx, reducedCollapse) {
            derivedStateOf {
                if (!collapses) 0f else HeaderCollapseRules.shown(
                    HeaderCollapseRules.progress(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset.toFloat(),
                        HeaderCollapseRules.rangePx(headerPx.toFloat(), barPx)),
                    reducedCollapse,
                )
            }
        }
        val habitTick: (HabitDot) -> Unit = { dot ->
            chipHaptics.light()
            val was = dot.done
            os.meka.android.goals.HabitTaps.tick(chipScope, core, dot.id, !was) { line -> undo?.show(line, null) }
            undo?.show(HabitDotRules.tickedLine(dot, !was)) { os.meka.android.goals.HabitTaps.tick(chipScope, core, dot.id, was) }
        }
        // The Today rebuild, slice 2a: one "This evening · 1 of 3" card under Now from 17:00, in place of the review,
        // shutdown and tomorrow cards; Later puts it away until 21:00 (this phone only).
        val eveningCard = remember(shutdown, reviewCard) { EveningCardRules.evening(shutdown, reviewCard) }
        var eveningLaterDay by rememberSaveable { mutableStateOf<Long?>(null) }
        val clockDay = java.time.LocalDate.now().toEpochDay()
        val clockMinute = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val eveningShown = EveningCardRules.shown(eveningCard, eveningLaterDay, clockDay, clockMinute)
        val todayHead = remember(today.timeline.anytime, today.doneToday) { EveningCardRules.head(today.timeline.anytime, today.doneToday) }
        // The foot fades into the capture bar rather than cutting a row in half (Fold review item 4); the bottom
        // padding (xl) is more than the fade, so the last row still scrolls fully clear.
        // The Today rebuild, slice 2c: the header and the parts under it are built once and laid out either as one
        // list (the closed Fold) or as the header across the top over two lists (the open Fold; core TodayColumnsRules).
        val headerBlock: @Composable () -> Unit = {
                Column(Modifier.padding(bottom = MekaSpace.l).onSizeChanged { headerPx = it.height }
                    .appear(rememberAppearance(0, play))) {
                    // The Today rebuild, the compact header (Meka 2026-11-09 08:09 (a), mockup TodayNew.dc.html): a 13 sp
                    // date over a 24 sp greeting on the left; the weather chip, ⋯ (Search, Plan my day) and the clock on
                    // the right: 40 dp folded, 150 dp on the open Fold. Tap the clock: the full 24-hour Day ring.
                    TodayHeaderRow(
                        face = headerFace, compact = smallClock, slotOnly = collapses,
                        play = ringPlay, played = ringPlayed,
                        onOpenFace = openDayRing,
                        night = shutdown.night,
                        week = faceWeek,
                        dots = if (core != null) habitDots else emptyList(),
                        onTick = habitTick,
                        trailing = {
                            // The weather as a small chip ("14° light rain"); a screen reader hears the whole line.
                            TodayHeaderRules.weatherChip(weather.nowLine)?.let { chip ->
                                WeatherChip(chip, "Weather: " + (weather.nowSpoken ?: weather.nowLine.orEmpty()))
                            }
                            HeaderMoreMenu(openSearch = openSearch, openPlan = openPlan)
                        },
                    ) {
                    if (today.timeline.dateLabel.isNotEmpty()) {
                        // Tap the date: tomorrow, any time of day (Meka 2026-10-26).
                        Text(today.timeline.dateLabel, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.wordsTap(onClickLabel = "See tomorrow") { openTomorrow() })
                    }
                    // The opening moment, part 2: on the first open of the day the greeting's letters fade in.
                    GreetingText(greeting(core, callName), if (now == null) ringPlay else DayRingPlay.STILL,
                        Modifier.padding(top = MekaSpace.xxs), base = MekaType.greetingCompact)
                    // The Today rebuild, slice 2b-2b: the trains, the school's note, a clash with work, this week's goals,
                    // For the train, trading and the replies waiting are Also today's tiles now (core AlsoTodayRules), so
                    // the header is the date, greeting and weather, as the mockup.
                    // Slice 2b-2b-2: meeting prep and a day stacking up are Also today's tiles now too; a tap unfolds
                    // their detail (who's coming and what's open; the one-tap fixes) under the row.
                    SyncLine(sync)
                    // Meka 2026-11-09 19:16: after Navilife closes on its own, one line to copy what went wrong.
                    CrashNoteLine(Modifier.padding(top = MekaSpace.xxs))
                    }
                    // The Today rebuild, slice 2b: TASKS · NEXT · MAIL across the page under the greeting, in place of the
                    // unread-mail lines and the countdown tile. TASKS opens the Day list, NEXT its event, MAIL the inbox.
                    if (today.timeline.dateLabel.isNotEmpty()) {
                        val headerTiles = remember(today.timeline, today.doneToday, today.mailUnread) {
                            TodayHeaderRules.tiles(today.timeline, today.doneToday, today.mailUnread)
                        }
                        val tilesApp = LocalContext.current.applicationContext as? MekaApplication
                        HeaderTilesRow(headerTiles, onOpen = { kind ->
                            when (kind) {
                                HeaderTileKind.TASKS -> openDayList()
                                HeaderTileKind.NEXT -> TodayHeaderRules.nextRow(today.timeline)?.event?.let(openEvent)
                                HeaderTileKind.MAIL -> tilesApp?.let { app ->
                                    app.openMail.value = true
                                    app.openDestination.value = os.meka.android.shell.ShellDestination.ASK
                                }
                            }
                        }, modifier = Modifier.padding(top = MekaSpace.m))
                    }
                    // The study cards run the page's full width under the header, not beside the face (Meka 2026-11-08 22:09:
                    // the study week card ended at about 80% of the width, narrower than every other card).
                    // Study plan, slice 2b rest: from 10 min before a study session, Obi's quiet card ("Study in 8 min ·
                    // Data analytics", the time, lessons and where) with Start, then Done (its lessons ticked, Undo on the
                    // bar) and Not now. A tap shows in the same frame; the card unfolds, cross-fades and folds away.
                    val nudge = today.studyNudge?.takeIf { it.key !in nudgeGone }
                    AnimatedVisibility(nudge != null, enter = studyUnfold(), exit = studyFold()) {
                        val n = nudge ?: nudgeLast
                        if (n != null) StudyNudgeCard(
                            n, started = n.key in nudgeStarted, modifier = Modifier.padding(top = MekaSpace.xs),
                            onStart = {
                                nudgeStarted.add(n.key)
                                if (core != null) carryScope.launch { if (runCatching { core.startStudySession(n.key) }.getOrNull() == null) nudgeStarted.remove(n.key) }
                            },
                            onDone = {
                                nudgeGone.add(n.key)
                                if (core != null) carryScope.launch {
                                    val r = runCatching { core.finishStudySession(n.key) }.getOrNull()
                                    if (r == null) nudgeGone.remove(n.key)
                                    else if (r.token.isNotEmpty()) undo?.show(r.line) { core.undoStudySessionDone(r.token); nudgeGone.remove(n.key) }
                                }
                            },
                            onNotNow = {
                                nudgeGone.add(n.key)
                                if (core != null) carryScope.launch { runCatching { core.putAwayStudyNudge(n.key) } }
                            },
                        )
                    }
                    // The Sunday study check-in is Also today's STUDY WEEK tile now (Meka 2026-11-09 08:09 (b)); a tap unfolds it.
                    if (connect != null) ConnectCard(connect.defaultUrl, connect.connect, Modifier.padding(top = MekaSpace.xs), recover = connect.recover, waiting = connect.waiting)
                    if (battery.line != null) {
                        BatteryCareLine(battery, onDismiss = {
                            battery.gap?.let { BatteryCare.dismiss(batteryContext, it.endMs) }
                            battery = BatteryCare.view(batteryContext, batteryWatching)
                        }, Modifier.padding(top = MekaSpace.xs))
                    }
                    if (core != null) signIn.firstOrNull()?.let { SignInLineView(it, core, Modifier.padding(top = MekaSpace.xxs)) }
                    // "What Navilife may do" completeness: while paused, one brass line with Resume.
                    if (core != null) os.meka.android.ask.AutonomyTodayLine(core, Modifier.padding(top = MekaSpace.xxs))
                    // Anything else MEKA depends on that needs a look (Reliability first, item 3): one quiet line.
                    if (core != null && now == null && carriesSetup) os.meka.android.ask.HealthTodayLine(core, Modifier.padding(top = MekaSpace.xxs))
                    // Setup checklist: a card while something is left to set up ("Not today" folds it away until tomorrow).
                    if (core != null && now == null && carriesSetup) os.meka.android.ask.SetupTodayCard(core, Modifier.padding(top = MekaSpace.xs))
                    // The news moved below Also today (Meka 2026-11-09 08:09 (d)): it was above Now.
                    // Today's habits: compact chips to tick, only when the face can't carry them as dots.
                    if (core != null && chipsShown) {
                        HabitChipsRow(
                            habitChips, play,
                            tick = { chip ->
                                chipHaptics.light()
                                os.meka.android.goals.HabitTaps.tick(chipScope, core, chip.id, !chip.done) { line -> undo?.show(line, null) }
                            },
                            modifier = Modifier.padding(top = MekaSpace.xs),
                        )
                    }
                    // The ring's live tiles, a slim row under the ticker (they left the dial with the move).
                    if (today.timeline.dateLabel.isNotEmpty()) {
                        DayTilesStrip(stripTiles, ringPlay, today.watchFace.arcs.size, Modifier.padding(top = MekaSpace.xs))
                    }
                    // Fasting rebuild slice 4: while a fast runs, its live card (the approved today-card design) under the
                    // header, on the cover screen and unfolded; it rises in and leaves with a fade.
                    AnimatedVisibility(
                        fastCard != null && endFast != null,
                        enter = fadeIn(MekaMotion.expand(Meka.reducedMotion)),
                        exit = fadeOut(MekaMotion.expand(Meka.reducedMotion)),
                    ) {
                        var shownCard by remember { mutableStateOf(fastCard) }
                        if (fastCard != null) shownCard = fastCard
                        shownCard?.let { c ->
                            os.meka.android.goals.FastingTodayCardView(c, openFasting, { endFast?.invoke(it) }, Modifier.padding(top = MekaSpace.m))
                        }
                    }
                }
        }
        val slotItems: LazyListScope.((TodaySlot) -> Boolean) -> Unit = { holds ->
            // Calm Today, slice 3: Up next and the timeline lead, the moment cards follow them (core TodayOrderRules), and
            // the opening stagger arrives in the same order.
            val nowCard = now?.takeIf { it.kind != NowKind.CLEAR }
            val upNextCard = upNext?.takeIf { nowCard == null && upNextHandlers != null }
            val tl = today.timeline
            val head = tl.head
            val cardsStep = TodayOrderRules.stagger(TodaySlot.CARDS)
            val nextStep = TodayOrderRules.stagger(TodaySlot.UP_NEXT)
            val dayStep = TodayOrderRules.stagger(TodaySlot.TIMELINE)
            val rowsStep = TodayOrderRules.TIMELINE_ROWS_STEP
            for (slot in TodayOrderRules.ORDER) if (holds(slot)) when (slot) {
            TodaySlot.CARDS -> {
            // The Today rebuild, slice 2b-2: ALSO TODAY's side-swiping tiles below the schedule, before the moment cards.
            // Slice 2b-2b: with the trains (from the weather) and this week's goals, the header's last notes.
            val alsoTiles = os.meka.core.domain.AlsoTodayRules.withHeader(
                today.alsoToday, weather.route, goalsNow.week.line, goalsNow.week.spoken,
                prep = today.meetingPrep, pressure = today.pressure,
                // Meka 2026-11-09 08:09 (b, f): the study check-in and Monday's review are tiles, not big cards.
                checkin = today.studyCheckin?.takeIf { it.key !in checkinGone },
                review = EveningCardRules.reviewForTile(reviewCard, eveningCard),
            )
            if (alsoTiles.isNotEmpty()) {
                item(key = "also-today") {
                    val alsoApp = LocalContext.current.applicationContext as? MekaApplication
                    val paneFor = { k: os.meka.core.domain.AlsoKind ->
                        when (k) {
                            os.meka.core.domain.AlsoKind.BINS -> os.meka.android.shell.MoreItem.BINS
                            os.meka.core.domain.AlsoKind.TRIP -> os.meka.android.shell.MoreItem.TRIPS
                            os.meka.core.domain.AlsoKind.GIFTS -> os.meka.android.shell.MoreItem.GIFTS
                            os.meka.core.domain.AlsoKind.DINNER -> os.meka.android.shell.MoreItem.DINNERS
                            os.meka.core.domain.AlsoKind.DATE_NIGHT -> os.meka.android.shell.MoreItem.DATE_NIGHT
                            os.meka.core.domain.AlsoKind.LIFE_ADMIN -> os.meka.android.shell.MoreItem.LIFE_ADMIN
                            os.meka.core.domain.AlsoKind.SCHOOL -> os.meka.android.shell.MoreItem.SCHOOL
                            os.meka.core.domain.AlsoKind.REPLIES -> os.meka.android.shell.MoreItem.MAIL
                            // Countdowns, slice 2: the one on Today opens Countdowns.
                            os.meka.core.domain.AlsoKind.COUNTDOWN -> os.meka.android.shell.MoreItem.COUNTDOWNS
                            else -> null
                        }
                    }
                    AlsoTodayRow(
                        alsoTiles,
                        opens = { t ->
                            when (t.kind) {
                                os.meka.core.domain.AlsoKind.STUDY, os.meka.core.domain.AlsoKind.RENEWAL -> t.targetId != null
                                os.meka.core.domain.AlsoKind.WEEK_AHEAD, os.meka.core.domain.AlsoKind.TRAIN_QUEUE -> true
                                os.meka.core.domain.AlsoKind.PREP, os.meka.core.domain.AlsoKind.STUDY_WEEK,
                                os.meka.core.domain.AlsoKind.REVIEW -> true
                                // Meka 2026-11-11 07:30: the Trains tile's tap shows what the trains are doing.
                                os.meka.core.domain.AlsoKind.TRAINS -> weather.trains != null
                                os.meka.core.domain.AlsoKind.PRESSURE -> core != null && today.pressure?.fixes?.isNotEmpty() == true
                                // Meka 2026-11-11: no tile is a dead end. Parcels unfold every parcel; goals, trading,
                                // this week's one thing and a clash open Goals, Money's Trading, Review and the Calendar.
                                os.meka.core.domain.AlsoKind.PARCELS -> today.parcelDetail != null
                                os.meka.core.domain.AlsoKind.GOALS, os.meka.core.domain.AlsoKind.TRADING,
                                os.meka.core.domain.AlsoKind.REFLECTION, os.meka.core.domain.AlsoKind.CLASH,
                                os.meka.core.domain.AlsoKind.DONE_FOR_YOU -> alsoApp != null
                                else -> alsoApp != null && paneFor(t.kind) != null
                            }
                        },
                        onOpen = { t ->
                            when (t.kind) {
                                os.meka.core.domain.AlsoKind.STUDY -> t.targetId?.let(openStudy)
                                os.meka.core.domain.AlsoKind.RENEWAL -> t.targetId?.let(openRenewal)
                                os.meka.core.domain.AlsoKind.WEEK_AHEAD -> openWeekAhead()
                                os.meka.core.domain.AlsoKind.TRAIN_QUEUE -> openQueue()
                                os.meka.core.domain.AlsoKind.REVIEW -> openReviewCard()
                                os.meka.core.domain.AlsoKind.PREP, os.meka.core.domain.AlsoKind.PRESSURE,
                                os.meka.core.domain.AlsoKind.STUDY_WEEK, os.meka.core.domain.AlsoKind.TRAINS,
                                os.meka.core.domain.AlsoKind.PARCELS ->
                                    alsoUnfolded = if (alsoUnfolded == t.kind) null else t.kind
                                os.meka.core.domain.AlsoKind.GOALS ->
                                    alsoApp?.openDestination?.value = os.meka.android.shell.ShellDestination.GOALS
                                os.meka.core.domain.AlsoKind.REFLECTION ->
                                    alsoApp?.openDestination?.value = os.meka.android.shell.ShellDestination.REVIEW
                                os.meka.core.domain.AlsoKind.CLASH ->
                                    alsoApp?.openDestination?.value = os.meka.android.shell.ShellDestination.CALENDAR
                                // Handle her requests for me: the Done for you tile opens Needs you, where its rows are.
                                os.meka.core.domain.AlsoKind.DONE_FOR_YOU ->
                                    alsoApp?.openDestination?.value = os.meka.android.shell.ShellDestination.NEEDS_YOU
                                os.meka.core.domain.AlsoKind.TRADING -> alsoApp?.let { app ->
                                    app.openMoneyTab.value = os.meka.core.domain.MoneyTab.TRADING
                                    app.openMoney.value = true
                                    app.openDestination.value = os.meka.android.shell.ShellDestination.ASK
                                }
                                else -> paneFor(t.kind)?.let { item ->
                                    alsoApp?.let { app ->
                                        app.openPane.value = item
                                        app.openDestination.value = os.meka.android.shell.ShellDestination.ASK
                                    }
                                }
                            }
                        },
                        unfolded = alsoUnfolded,
                        detail = { kind ->
                            when (kind) {
                                os.meka.core.domain.AlsoKind.PREP -> today.meetingPrep?.let { p -> PrepDetail(p) }
                                os.meka.core.domain.AlsoKind.TRAINS -> weather.trains?.let { t -> TrainsDetail(t) }
                                os.meka.core.domain.AlsoKind.PARCELS -> today.parcelDetail?.let { d -> ParcelsDetail(d) }
                                os.meka.core.domain.AlsoKind.PRESSURE -> today.pressure?.let { p ->
                                    PressureDetail(p) { fix ->
                                        if (core != null) carryScope.launch {
                                            val line = runCatching { core.easePressure(fix.taskId, fix.toDay) }.getOrNull()
                                            if (line != null) { alsoUnfolded = null; undo?.show(line) { core.undoBoardMove() } }
                                        }
                                    }
                                }
                                os.meka.core.domain.AlsoKind.STUDY_WEEK -> today.studyCheckin?.takeIf { it.key !in checkinGone }?.let { c ->
                                    StudyCheckinCard(
                                        c, modifier = Modifier,
                                        onLooksGood = {
                                            checkinGone.add(c.key)
                                            alsoUnfolded = null
                                            if (core != null) carryScope.launch { if (runCatching { core.seeStudyCheckin(c.key) }.getOrNull() == null) checkinGone.remove(c.key) }
                                        },
                                        onChange = { id -> openStudy(id) },
                                    )
                                }
                                else -> Unit
                            }
                        },
                        modifier = Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(cardsStep, play)),
                    )
                }
            }
            // News below Also today, as the approved mockup has it (Meka 2026-11-09 08:09 (d)): NEWS · swipe ›, a row of
            // picture cards you swipe yourself, starting at the first card (slice 2b-2b-2b-3). Off in Appearance hides it.
            if (core != null && TodayNewsRules.shown(tickerMode, ticker)) {
                item(key = "news") {
                    TodayNewsRow(core, ticker, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(cardsStep, play)),
                        openStory = openStory, openMatch = openMatch, mode = tickerMode)
                }
            }
            // Motion pass 2: with the phone's animations off and nothing chosen in Appearance → Motion, a one-time card.
            motionCard?.let { card ->
                item(key = "motion") {
                    MotionSystemCard(card, motion, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(cardsStep, play)))
                }
            }
            // Self-updating phone app: the card rises in when the Mac has published a newer build.
            if (update !is UpdateState.None && updater != null) {
                item(key = "update") {
                    UpdateCard(update, updater, Modifier.padding(bottom = MekaSpace.m).animateItem().appear(rememberAppearance(cardsStep, play)))
                }
            }
            // The Morning brief card is gone from Today (Meka 2026-11-09 08:09 (f): its weather is the chip, its tasks the
            // tiles, its focus Now); the brief still opens from the wake alarm, Talk and Plan my day. Monday's "Review last
            // week" is Also today's REVIEW tile, and Sunday's review rides on the evening card.
            // Evening shutdown: the card rises in when the evening starts; once done, one quiet line stays.
            if (eveningCard != null) {
                // The evening card under Now carries the shutdown and tomorrow (the Today rebuild, slice 2a).
            } else if (shutdown.offered) {
                item(key = "shutdown") {
                    ShutdownCard(shutdown, openShutdown, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(cardsStep, play)),
                        titleModifier = Modifier.sharedTitle(SharedMotion.paneKey(SharedMotion.SHUTDOWN), !shutdownOpen), onLater = laterShutdownCard)
                }
            } else if ((shutdown.evening && shutdown.peek == null) || shutdown.doneLine != null) {
                // Tomorrow at a glance once the evening starts (after shutting down, or while still at work); tapping
                // opens the shutdown pane with tomorrow in full. With the Tomorrow peek up, only "Day shut down" stays.
                item(key = "shutdown-done") {
                    TomorrowGlance(
                        shutdown.doneLine, shutdown.tomorrow.glance.takeIf { shutdown.evening && shutdown.peek == null }, openShutdown,
                        Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(cardsStep, play)),
                    )
                }
            }
            // The Tomorrow peek (Meka 2026-10-26): from 17:00 or once shut down, "Tomorrow · Tue 27 Oct · 2 tasks ·
            // first 07:40 Gym ›", opening the Tomorrow sheet.
            shutdown.peek?.takeIf { eveningCard == null }?.let { peek ->
                item(key = "tomorrow-peek") {
                    TomorrowPeekCard(peek, openTomorrow, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(cardsStep, play)))
                }
            }
            // Daily receipt: in the evening, what MEKA handled today; tapping opens Activity over Today.
            // Meka 2026-11-09 08:09 (h): the one grey line ends Today all day, not only in the evening.
            val receipt = shutdown.receipt?.takeIf { it.count > 0 }
            if (receipt != null) {
                item(key = "receipt") {
                    DailyReceiptLine(receipt, openActivity,
                        Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(cardsStep, play)))
                }
            }
            }
            TodaySlot.CLEAR -> {
            // "You're clear." only when nothing at all is left today; "Nothing else timed today" beside all-day items.
            today.clearLine?.let { line ->
                item(key = "clear") {
                    // All clear: the brass ring breathes beside it (catalogue "Empty states"); Off: still.
                    Row(Modifier.padding(bottom = MekaSpace.m).animateItem().appear(rememberAppearance(TodayOrderRules.stagger(TodaySlot.CLEAR), play)), verticalAlignment = Alignment.CenterVertically) {
                        if (today.isAllClear) BreathingRing(Modifier.padding(end = MekaSpace.xs))
                        Text(line, style = if (today.isAllClear) MekaType.upNextTitle else MekaType.body, color = Meka.colors.textSecondary)
                    }
                }
            }
            }
            TodaySlot.NEEDS_YOU -> {
            if (listsNeedsYou && today.needsYou.isNotEmpty()) {
                val needsStep = TodayOrderRules.stagger(TodaySlot.NEEDS_YOU)
                item(key = "h-needs") { SectionLabel("Needs you", Modifier.animateItem().appear(rememberAppearance(needsStep, play))) }
                items(today.needsYou, key = { "n-" + it.task.id }) { n ->
                    TaskRow(n.task, actions, reason = n.reason, motion = rowMotion(n.task.id),
                        modifier = Modifier.animateItem().appear(rememberAppearance(needsStep, play)))
                }
                item(key = "s-needs") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            }
            TodaySlot.FROM_YESTERDAY -> {
            // Roll-over (Week board, slice 1): open tasks planned for an earlier day lead Today, oldest first, each
            // saying how long it has carried; from three carries a one-time prompt under it.
            if (today.fromYesterday.isNotEmpty()) {
                val carriedStep = TodayOrderRules.stagger(TodaySlot.FROM_YESTERDAY)
                itemsIndexed(today.fromYesterday, key = { _, c -> "y-" + c.task.id }) { i, c ->
                    Headed(os.meka.core.domain.RollOverRules.LABEL.takeIf { i == 0 }, Modifier.animateItem().appear(rememberAppearance(carriedStep, play))) { m ->
                        CarriedRow(c, actions, rowMotion(c.task.id), m, onChoice = carryChoice)
                    }
                }
                item(key = "s-yesterday") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            }
            TodaySlot.UP_NEXT -> {
            if (nowCard != null && nowHandlers != null) {
                // One stable slot: when the thing changes, the card's content cross-slides.
                item(key = "now") {
                    NowCard(
                        nowCard, nowHandlers, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(nextStep, play)),
                        titleModifier = { id -> rowMotion(id).let { m -> if (m.shareTitle) Modifier.sharedTitle(SharedMotion.taskKey(id), m.titleVisible) else Modifier } },
                    )
                }
            } else if (today.timeline.nextEvent != null && (upNext == null || upNextHandlers == null)) {
                // The Up next card carries its own label, so the section label only heads a lone next event.
                item(key = "h-next") { SectionLabel("Up next", Modifier.animateItem().appear(rememberAppearance(nextStep, play))) }
            }
            if (upNextCard != null && upNextHandlers != null) {
                // One stable slot: when up next changes, the card's content cross-slides instead of the row swapping.
                // The closed Fold's card (UP NEXT, title, line, Done · Tomorrow · Open) on every screen.
                item(key = "upnext") {
                    NowCard(
                        upNextCard, upNextHandlers,
                        Modifier.padding(bottom = if (today.timeline.nextEvent != null) MekaSpace.xs else 0.dp)
                            .animateItem().appear(rememberAppearance(nextStep, play)),
                        titleModifier = { id -> rowMotion(id).let { m -> if (m.shareTitle) Modifier.sharedTitle(SharedMotion.taskKey(id), m.titleVisible) else Modifier } },
                    )
                }
            }
            // The next event within the hour: "Call with Tunde in 25 min", under Up next's card as the cover screen's
            // "Then: …" is (the "now" card carries it there).
            if (nowCard == null) today.timeline.nextEvent?.let { e ->
                item(key = "nextevent") {
                    NextEventCard(e, Modifier.animateItem().appear(rememberAppearance(nextStep, play)), openEvent)
                }
            }
            if (nowCard == null && (upNextCard != null || today.timeline.nextEvent != null)) {
                item(key = "s-next") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            // The Gym (booked habits): today's session, "Did you go?" once it's over, or where it was rebooked. Only when
            // there is one: an empty item still costs the list's spacing, pushing the timeline further down.
            if (core != null && sessionCards) item(key = "session") {
                os.meka.android.goals.SessionCards(core, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(nextStep, play)))
            }
            // Quick alarms and timers typed into capture ("alarm 6:30", "timer 20 min"), each with a cancel ✕.
            if (core != null && quickAlarms) item(key = "quick-alarms") {
                QuickAlarmRows(core, Modifier.animateItem().appear(rememberAppearance(nextStep, play)))
            }
            }
            TodaySlot.EVENING -> {
            if (eveningShown != null) {
                item(key = "evening") {
                    EveningCardView(
                        eveningShown,
                        onStep = { kind ->
                            when (kind) {
                                os.meka.core.domain.EveningStepKind.REVIEW -> openReviewCard()
                                os.meka.core.domain.EveningStepKind.SHUT_DOWN -> openShutdown()
                                os.meka.core.domain.EveningStepKind.PLAN_TOMORROW -> openTomorrow()
                            }
                        },
                        onLater = if (EveningCardRules.laterOffered(clockMinute)) ({ eveningLaterDay = clockDay }) else null,
                        modifier = Modifier.padding(bottom = MekaSpace.l).testTag(EVENING_CARD_TAG).animateItem()
                            .appear(rememberAppearance(TodayOrderRules.stagger(TodaySlot.EVENING), play)),
                    )
                }
            }
            }
            TodaySlot.TIMELINE -> {
            // One timeline under "Today": the "All day" group first (one row each, at most 3 then "+2 more"), finished
            // events folded, events and planned tasks in time order with the now line and free gaps.
            // The label says what the section holds ("Today · Work 09:00–17:30 · 2 events") and rides with the first
            // thing under it (Fold review 2026-10-09 07:26, item 6), so it never stands alone over the capture bar.
            // The Today rebuild, point (e): the whole schedule is the mockup's one compact card (core ScheduleCardRules):
            // "All day" in the narrow time column of the first all-day row, the earlier fold, then the rows without the
            // free gaps; each row draws its slice of the card, so rows still glide in and out on their own.
            if (head != null) {
                val scheduleLabel = EveningCardRules.scheduleLabel(tl.sectionLabel)
                val allShown = AllDayRules.shown(tl.allDayItems, allDayOpen)
                val allMore = AllDayRules.moreLabel(tl.allDayItems, allDayOpen)
                val cardRows = ScheduleCardRules.rows(tl.rows)
                val earlierShown = if (earlierOpen) tl.earlier else emptyList()
                val cardCount = ScheduleCardRules.count(allShown.size, allMore != null, tl.earlierLabel != null, earlierShown.size, cardRows.size)
                val moreAt = allShown.size
                val earlierAt = moreAt + (if (allMore != null) 1 else 0)
                val rowsAt = earlierAt + (if (tl.earlierLabel != null) 1 + earlierShown.size else 0)
                fun edge(i: Int) = ScheduleCardRules.edge(i, cardCount)
                itemsIndexed(allShown, key = { _, a -> "a-" + a.event.id }) { i, a ->
                    Headed(scheduleLabel.takeIf { i == 0 }, Modifier.animateItem().appear(rememberAppearance(dayStep, play))) { m ->
                        InScheduleCard {
                            AllDayRow(a, m.scheduleSegment(edge(i)), openEvent, eventHandlers, timeLabel = ScheduleCardRules.allDayTime(i))
                        }
                    }
                }
                allMore?.let { more ->
                    item(key = "allday-more") {
                        InScheduleCard {
                            AllDayMore(more, { allDayOpen = true }, Modifier.animateItem().appear(rememberAppearance(dayStep, play)).scheduleSegment(edge(moreAt)))
                        }
                    }
                }
                tl.earlierLabel?.let { label ->
                    item(key = "earlier") {
                        Headed(scheduleLabel.takeIf { head == DayHead.EARLIER }, Modifier.animateItem().appear(rememberAppearance(dayStep, play))) { m ->
                            InScheduleCard { EarlierToggle(label, earlierOpen, { earlierOpen = !earlierOpen }, m.scheduleSegment(edge(earlierAt))) }
                        }
                    }
                    itemsIndexed(earlierShown, key = { _, r -> "x-" + r.id }) { i, r ->
                        InScheduleCard {
                            TimelineEventRow(r, past = true, modifier = Modifier.animateItem().scheduleSegment(edge(earlierAt + 1 + i)).opensEvent(r.event, openEvent))
                        }
                    }
                }
                itemsIndexed(cardRows, key = { _, r -> "r-" + r.id }) { i, r ->
                    Headed(scheduleLabel.takeIf { i == 0 && head == DayHead.ROW }, Modifier.animateItem().appear(rememberAppearance(rowsStep, play))) { m0 ->
                        val m = m0.scheduleSegment(edge(rowsAt + i))
                        InScheduleCard {
                        when (r.kind) {
                            TimelineKind.EVENT -> SwipeableEvent(r.event, eventHandlers, m, onOpen = openEvent, fill = Meka.colors.surface) { sm ->
                                TimelineEventRow(r, past = false, modifier = sm)
                            }
                            TimelineKind.TASK -> r.task?.let { t ->
                                // The Up next task's title travels from its card, so its timeline row doesn't share it.
                                val motion = if (t.id == today.upNext?.id) RowMotion() else rowMotion(t.id)
                                TaskRow(t, actions, motion = motion, modifier = m, time = r.time, timelineLine = r.detail, late = r.late)
                            }
                            TimelineKind.GAP -> GapRow(r, m)
                            TimelineKind.NOW -> NowLine(r, m)
                            TimelineKind.SESSION -> SessionTimelineRow(r, m)
                            TimelineKind.WORK -> WorkTimelineRow(r, m)
                            TimelineKind.COMMUTE -> CommuteTimelineRow(r, m)
                        }
                        }
                    }
                }
                item(key = "s-day") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            }
            TodaySlot.ANYTIME -> {
            if (tl.anytime.isEmpty()) {
                // Nothing unplanned today: the Day list is still one tap away, to type a day's tasks fast.
                item(key = "day-list-way-in") {
                    Text(
                        DAY_LIST_EMPTY_LINK, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.animateItem().wordsTap(onClickLabel = "Open the Day list") { openDayList() }.padding(bottom = MekaSpace.l),
                    )
                }
            }
            if (tl.anytime.isNotEmpty()) {
                val anyStep = TodayOrderRules.stagger(TodaySlot.ANYTIME)
                // Beside the calmer mockup (2026-10-29): the label counts the open tasks, each row is the 48 dp target
                // with a hairline in the list's gap under it (never under the last).
                // The Today rebuild, slice 2a: "Today · 2 left · 40m" with the gold done bar, right under Now.
                itemsIndexed(tl.anytime, key = { _, t -> "d-" + t.id }) { i, t ->
                    Headed(todayHead.label.takeIf { i == 0 }, Modifier.animateItem().appear(rememberAppearance(anyStep, play)),
                        link = DAY_LIST_LINK, onLink = openDayList, progress = todayHead.progress) { m ->
                        val rule = os.meka.core.domain.TodayBesideRules.dividerAfter(i, tl.anytime.size)
                        TaskRow(t, actions, motion = rowMotion(t.id), modifier = if (rule) m.hairlineInGapBelow() else m)
                    }
                }
                // "+ Add for today · Enter for the next" opens the Day list, where Enter adds one after another.
                item(key = "today-add") {
                    Text(
                        EveningCardRules.ADD_LINE, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                        modifier = Modifier.animateItem().appear(rememberAppearance(anyStep, play))
                            .wordsTap(onClickLabel = "Add tasks for today") { openDayList() }.minTouch(),
                    )
                }
                if (today.doneToday.isNotEmpty() && doneChoice.show != os.meka.core.domain.DoneShow.HIDDEN) item(key = "s-anytime") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            }
            TodaySlot.DONE -> {
            if (today.doneToday.isNotEmpty()) {
                // Last, straight under what's above: every section before it already ends with its own spacing, so a
                // second gap left a large empty stretch above it (Meka's 10:48 screenshots, 2026-10-09).
                // The Sunsama tick (Week board by hand, slice 6): the day's done tasks stay on Today, ticked and struck
                // through, the latest first; a tap on a circle un-ticks it. The row keeps its "d-" key so a ticked
                // Anytime row glides down into the group.
                val doneStep = TodayOrderRules.stagger(TodaySlot.DONE)
                // Calmer Today, slice 1: Folded (the default) is one line, "2 done today ›", that opens the rows.
                os.meka.core.domain.DoneTodayRules.foldLine(today.doneToday, doneChoice)?.let { line ->
                    item(key = "done-fold") {
                        DoneFoldLine(line, os.meka.core.domain.DoneTodayRules.foldLabel(today.doneToday, doneChoice), toggleDoneFold,
                            Modifier.animateItem().appear(rememberAppearance(doneStep, play)))
                    }
                }
                val doneRows = os.meka.core.domain.DoneTodayRules.rows(today.doneToday, doneChoice, doneOpen)
                val doneHeading = os.meka.core.domain.DoneTodayRules.heading(today.doneToday, doneChoice)
                itemsIndexed(doneRows, key = { _, t -> "d-" + t.id }) { i, t ->
                    Headed(doneHeading.takeIf { i == 0 },
                        Modifier.animateItem().appear(rememberAppearance(doneStep, play))) { m ->
                        DoneTodayRow(t, actions, m)
                    }
                }
                os.meka.core.domain.DoneTodayRules.moreLine(today.doneToday, doneChoice, doneOpen)?.let { more ->
                    item(key = "done-more") {
                        Text(more, style = MekaType.caption, color = Meka.colors.textTertiary,
                            modifier = Modifier.animateItem().wordsTap(role = null) { doneOpen = true }
                                .minTouch().padding(vertical = MekaSpace.xs))
                    }
                }
            }
            }
            }
        }
        if (!split) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().footFade().testTag(TODAY_LIST_TAG),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "greeting") { headerBlock() }
            slotItems { true }
        }
        } else {
            SplitToday(listState, headerBlock, housekeeping = core?.takeIf { !carriesSetup },
                left = { slotItems { TodayColumnsRules.column(it) == TodayColumn.LEFT } },
                right = { slotItems { TodayColumnsRules.column(it) == TodayColumn.RIGHT } })
        }
        if (collapses && headerFace != null) {
            CollapsingHeader(
                shown = collapse, face = headerFace, fullDp = WatchFaceRules.headerDp(cover = true),
                greeting = greeting(core, callName),
                line = HeaderCollapseRules.compactLine(today.timeline.dateLabel, weather.nowLine),
                play = ringPlay, played = ringPlayed, onOpenFace = openDayRing,
                night = shutdown.night, week = faceWeek,
                dots = if (core != null) habitDots else emptyList(), onTick = habitTick,
                onTop = { collapseScope.launch { if (reducedCollapse) listState.scrollToItem(0) else listState.animateScrollToItem(0) } },
            )
        }
        }
        QuickCapture(actions.add, onTalk)
    }
}

/**
 * The Today rebuild, slice 2c (Meka approved 2026-11-08 22:58): the open Fold's Today. The header and tiles run across
 * the top (at most [TodayColumnsRules.HEADER_MAX_SHARE] of the height, scrolling beyond); under them two lists side by
 * side, each scrolling on its own: what to do on the left, the shape of the day on the right, headed by the health line
 * and the setup card while there is something to set up (walk-through week 3, A1: they never push Now down).
 */
@Composable
private fun SplitToday(
    leftState: LazyListState,
    header: @Composable () -> Unit,
    housekeeping: MekaCore?,
    left: LazyListScope.() -> Unit,
    right: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerMax = maxHeight * TodayColumnsRules.HEADER_MAX_SHARE
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().heightIn(max = headerMax).verticalScroll(rememberScrollState())
                    .padding(start = MekaSpace.gutter, end = MekaSpace.gutter, top = MekaSpace.xl),
            ) { header() }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                val half = MekaSpace.gutter / 2
                LazyColumn(
                    state = leftState,
                    modifier = Modifier.weight(TodayColumnsRules.LEFT_SHARE).fillMaxHeight().footFade().testTag(TODAY_LIST_TAG),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = MekaSpace.gutter, end = half, bottom = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    content = left,
                )
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.weight(1f - TodayColumnsRules.LEFT_SHARE).fillMaxHeight().footFade().testTag(TODAY_RIGHT_LIST_TAG),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = half, end = MekaSpace.gutter, bottom = MekaSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    if (housekeeping != null) item(key = "housekeeping") {
                        Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.l).testTag(SIDE_HOUSEKEEPING_TAG)) {
                            os.meka.android.ask.HealthTodayLine(housekeeping, Modifier)
                            os.meka.android.ask.SetupTodayCard(housekeeping, Modifier.padding(top = MekaSpace.xs))
                        }
                    }
                    right()
                }
            }
        }
    }
}

/** The open Fold's right-hand list (slice 2c): Schedule, Also today, News. The left one keeps [TODAY_LIST_TAG]. */
internal const val TODAY_RIGHT_LIST_TAG = "today-list-right"

/** The header's test tag for ⋯ (Search, Plan my day). */
const val HEADER_MORE_TAG = "today-header-more"

/**
 * The Today rebuild (Meka 2026-11-09 08:09 (a)): Search and Plan my day leave the header's loose gold links for one quiet
 * ⋯ beside the weather chip; a tap opens them as a menu.
 */
@Composable
internal fun HeaderMoreMenu(openSearch: () -> Unit, openPlan: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Button, onClickLabel = "Search or plan my day") { open = true }
                .testTag(HEADER_MORE_TAG).semantics { contentDescription = "More: Search, Plan my day" },
            contentAlignment = Alignment.Center,
        ) {
            Text("⋯", style = MekaType.metaStrong, color = Meka.colors.textSecondary)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            containerColor = Meka.colors.surfaceRaised, border = mekaMenuBorder(Meka.colors),
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Search", style = MekaType.itemMeta, color = Meka.colors.textPrimary) },
                onClick = { open = false; openSearch() }, modifier = Modifier.testTag("$HEADER_MORE_TAG-search"),
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Plan my day", style = MekaType.itemMeta, color = Meka.colors.textPrimary) },
                onClick = { open = false; openPlan() }, modifier = Modifier.testTag("$HEADER_MORE_TAG-plan"),
            )
        }
    }
}

@Composable
private fun SyncLine(sync: SyncStatus) {
    // Silence is the default: only say something when the user would want to know.
    val text = when (sync) {
        is SyncStatus.Offline -> if (sync.pending > 0) "Offline · ${sync.pending} change(s) waiting to sync" else null
        is SyncStatus.Failing -> sync.reason
        else -> null
    }
    AnimatedVisibility(text != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
        Text(text.orEmpty(), style = MekaType.caption, color = Meka.colors.offline, modifier = Modifier.padding(top = MekaSpace.xs))
    }
}

/** The label over Today's tasks with no time. */
internal const val ANYTIME_LABEL = "Anytime today"
internal const val DAY_LIST_LINK = os.meka.core.domain.DayListRules.LINK
internal const val DAY_LIST_EMPTY_LINK = os.meka.core.domain.DayListRules.EMPTY_LINK

/**
 * A section's label riding with the first thing under it, in one list item (Fold review 2026-10-09 07:26, item 6), so
 * the label never sits alone at the foot of the screen with its rows out of sight. Null [label]: just the content.
 * The gap under the label is the list's own spacing, so the section looks as it did with the label as its own item.
 */
@Composable
internal fun Headed(
    label: String?, modifier: Modifier = Modifier,
    /** A link on the heading's right ("Day list ›"), tapped for [onLink]; null for none. */
    link: String? = null, onLink: () -> Unit = {},
    /** The Today rebuild: the gold done bar under the heading (Today's own tasks); null for none. */
    progress: Float? = null,
    content: @Composable (Modifier) -> Unit,
) {
    if (label == null) { content(modifier); return }
    Column(modifier) {
        if (link == null) SectionLabel(label)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label, Modifier.weight(1f))
            Text(link, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.wordsTap(onClickLabel = "Open the Day list") { onLink() })
        }
        if (progress != null) TodayDoneBar(progress, Modifier.padding(top = MekaSpace.xxs))
        Spacer(Modifier.height(MekaSpace.xs))
        content(Modifier)
    }
}

/** Today's Schedule card: the rows inside take its narrow time column (TimelineViews' [LocalScheduleCard]). */
@Composable
private fun InScheduleCard(content: @Composable () -> Unit) = CompositionLocalProvider(LocalScheduleCard provides true, content = content)

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = modifier.padding(bottom = MekaSpace.xxs))
}

@Composable
internal fun TaskRow(
    t: Task, actions: TodayActions, reason: NeedsYouReason? = null, motion: RowMotion = RowMotion(), modifier: Modifier = Modifier,
    /** On the timeline: the time column on the left and the core's line ("30 min · ↻ Every weekday") under the title. */
    time: String? = null, timelineLine: String? = null,
    /** A planned task whose time has gone by: its line ("Since 09:15 · 30 min") is lit in amber. */
    late: Boolean = false,
    /** Roll-over: a "From yesterday" row's line ("Carried 2 days"), lit in amber. */
    carriedLine: String? = null,
) {
    // The Sunsama tick (Week board by hand, slice 6): ticked, the row keeps its place struck through for a moment.
    var ticking by remember(t.id) { mutableStateOf(false) }
    val titleColor by animateColorAsState(
        if (ticking) Meka.colors.textTertiary else Meka.colors.textPrimary, MekaMotion.appear(Meka.reducedMotion), label = "ticking",
    )
    // Just landed from the plan: lit softly, then settles.
    val glow by animateColorAsState(
        if (motion.landed) Meka.colors.surfaceRaised else Color.Transparent, MekaMotion.appear(Meka.reducedMotion), label = "landed",
    )
    Row(
        // Beside the calmer mockup (2026-10-29): the row is the 48 dp target and no taller (it was 60 dp, the 44 dp
        // tick plus 8 dp above and below); only the words keep a little air, so a two-line row still breathes.
        modifier.containerOrigin(t.id).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(glow).clickable { actions.select(t.id) }
            .minTouch().testTag(TASK_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (time != null) TimeColumn(time, past = false)
        // Calmer Today (Meka 2026-10-25 15:03): a 22 dp circle in a 44 dp target, the text the target's inset after it.
        CompleteButton(t.id, t.title, actions.complete, size = TickSize.DRAWN, settle = true, onPressed = { ticking = true },
            touch = TickSize.TOUCH, bleed = time == null)
        Column(Modifier.weight(1f).padding(vertical = MekaSpace.xxs)) {
            Text(os.meka.core.domain.TaskTitleRules.display(t.title), style = MekaType.taskTitle, color = titleColor,
                textDecoration = if (ticking) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = if (motion.shareTitle) Modifier.sharedTitle(SharedMotion.taskKey(t.id), motion.titleVisible) else Modifier)
            val line = when (reason) {
                NeedsYouReason.CONFLICT -> "Edited on two devices — choose a version"
                NeedsYouReason.OVERDUE -> "Overdue"
                NeedsYouReason.DUE_TODAY_UNSCHEDULED -> "Due today · not scheduled"
                null -> carriedLine ?: if (time != null) timelineLine else meta(t, actions.whenOf(t))
            }
            line?.let {
                // Gold is for taps (the Today rebuild, point i): a late or carried line is amber, conflicts and overdue red.
                val flagged = reason == NeedsYouReason.CONFLICT || reason == NeedsYouReason.OVERDUE
                val color = os.meka.core.domain.TodayInkRules.taskLine(late = late && reason == null && time != null, carried = carriedLine != null && reason == null, flagged = flagged)
                    ?.color() ?: Meka.colors.critical
                // The calmer scale: a row's line in the 13 sp caption, as the day's times (was 15 sp beside a 16 sp title).
                Text(it, style = MekaType.caption, color = color)
            }
        }
    }
}

/** Every task row, for the quality gate's height check (Today beside its calmer mockup). */
internal const val TASK_ROW_TAG = "task-row"

/**
 * The mockup's 1 px rule between task rows, drawn in the middle of the list's 8 dp gap under the row (so it sits
 * between two rows, not on either), in the hairline colour.
 */
@Composable
internal fun Modifier.hairlineInGapBelow(): Modifier {
    val color = Meka.colors.hairline
    return drawBehind {
        val y = size.height + MekaSpace.xs.toPx() / 2f
        drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
}

/**
 * Calmer Today, slice 1 (the approved mockup): Done today folded into one quiet line, "2 done today ›", with a small
 * filled check; a tap opens the ticked rows under it (the line becomes "2 done today · hide") and they rise in with
 * the list's own motion; Motion → Off: they appear at once. Light haptic on the tap.
 */
@Composable
internal fun DoneFoldLine(line: String, label: String, toggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .clickable(role = Role.Button) { haptics.light(); toggle() }
            .minTouch(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.bleedStart(TickSize.BLEED).size(TickSize.TOUCH), contentAlignment = Alignment.Center) {
            TickRing(done = true, modifier = Modifier.size(TickSize.DRAWN))
        }
        AnimatedContent(
            targetState = line,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "done-fold",
        ) { t -> Text(t, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
    }
}

/**
 * A task done today, at Today's foot (Week board by hand, slice 6; catalogue "Complete a task"): the circle filled gold
 * with its tick, the title struck through and dimmed. A tap on the circle un-ticks it (light haptic; the ring empties and
 * the row rises back among the open ones), a tap on the row opens it as any task does.
 */
@Composable
internal fun DoneTodayRow(t: Task, actions: TodayActions, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val label = os.meka.core.domain.TodayTickRules.untickLabel(t.title)
    Row(
        // The 48 dp target and no taller, as the open rows above it (beside the calmer mockup, 2026-10-29).
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable { actions.select(t.id) }
            .minTouch().testTag(TASK_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.bleedStart(TickSize.BLEED).size(TickSize.TOUCH).clip(CircleShape)
                .semantics { contentDescription = label }
                .clickable(role = Role.Checkbox) { haptics.light(); actions.reopen(t.id) },
            contentAlignment = Alignment.Center,
        ) { TickRing(done = true, modifier = Modifier.size(TickSize.DRAWN)) }
        Text(
            os.meka.core.domain.TaskTitleRules.display(t.title), style = MekaType.taskTitle, color = Meka.colors.textTertiary,
            textDecoration = TextDecoration.LineThrough, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Completion motion (catalogue "Complete a task"; motion pass 2, slice 4): the accent ring sweeps round, fills, the
 * check strokes in ([CheckRing], [MekaChoreography.checkDrawMs]) with a light haptic, then the row leaves via
 * animateItem. Motion → Off: shown done at once and completed straight away.
 */
@Composable
internal fun CompleteButton(t: Task, onComplete: (String) -> Unit) = CompleteButton(t.id, t.title, onComplete)

/** [CompleteButton] for a task known by its id and title (the brief's Today rows), [size] across. */
@Composable
internal fun CompleteButton(
    id: String, title: String, onComplete: (String) -> Unit, size: androidx.compose.ui.unit.Dp = 28.dp,
    /** Sunsama-style (Week board by hand, slice 6): after the draw the row keeps its place a moment before it settles. */
    settle: Boolean = false,
    /** Told at the tap, so the row can strike its title through at once. */
    onPressed: (() -> Unit)? = null,
    /** Calmer Today: the tap target around the drawn [size] (a task row's 44 dp around 22); null: the drawn size. */
    touch: androidx.compose.ui.unit.Dp? = null,
    /** With [touch]: the target bleeds into the row's start so the drawn circle lines up with the label above. */
    bleed: Boolean = false,
) {
    var pressed by remember(id) { mutableStateOf(false) }
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val draw = remember(id) { Animatable(0f) }
    val done by rememberUpdatedState(onComplete)
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        val ms = MotionMath.checkDrawMs(reduced)
        if (ms == 0) draw.snapTo(1f) else draw.animateTo(1f, tween(ms, easing = LinearEasing))
        val wait = if (settle) os.meka.core.domain.TodayTickRules.settleWaitMs(ms, reduced) else 0L
        // The tick is his: leaving Today mid-settle still completes it.
        withContext(NonCancellable) {
            if (wait > 0) delay(wait)
            done(id)
        }
    }
    val tap = Modifier
        .then(if (touch != null && bleed) Modifier.bleedStart((touch - size) / 2) else Modifier)
        .size(touch ?: size)
        .clip(CircleShape)
        .semantics { contentDescription = "Complete $title" }
        .clickable(role = Role.Checkbox) {
            if (!pressed) {
                pressed = true
                haptics.light()
                onPressed?.invoke()
            }
        }
    Box(tap, contentAlignment = Alignment.Center) {
        CheckRing(
            fraction = draw.value,
            rest = Meka.colors.textTertiary, accent = Meka.colors.accent, onAccent = Meka.colors.onAccent,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * Capture bar: "Capture anything…" with the Talk mic at its right end (Fold review 2026-10-09 07:26, item 5: the mic
 * moved here from the Up next card). The mic presses in (0.97) with a light haptic and opens Ask already listening.
 */
@Composable
internal fun QuickCapture(onAdd: (String) -> Unit, onTalk: (() -> Unit)? = null) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xs)
            .clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.surfaceRaised)
            .padding(start = MekaSpace.l, end = if (onTalk != null) MekaSpace.xs else MekaSpace.l)
            .testTag(CAPTURE_BAR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = MekaSpace.m)) {
            if (text.isEmpty()) Text("Capture anything…", style = MekaType.body, color = Meka.colors.textTertiary)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                cursorBrush = SolidColor(Meka.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text); text = "" } }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Capture" },
            )
        }
        if (onTalk != null) {
            Spacer(Modifier.width(MekaSpace.xs))
            os.meka.android.ask.TalkMic(onTalk, size = 36.dp)
        }
    }
}

/** The capture bar on Today (UI tests). */
internal const val CAPTURE_BAR_TAG = "today-capture-bar"
/** Today's scrolling list, above the capture bar (the screen tests scroll it to its end). */
internal const val TODAY_LIST_TAG = "today-list"

@Composable
internal fun DetailPane(task: Task?, conflicts: List<ConflictChoice>, actions: TodayActions, modifier: Modifier, onClose: (() -> Unit)? = null) {
    Column(modifier.padding(MekaSpace.gutter)) {
        if (onClose != null) {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable { onClose() }.minTouch())
        }
        if (task == null) {
            Spacer(Modifier.weight(1f))
            Text("Select something to see it here.", style = MekaType.body, color = Meka.colors.textTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))
        } else {
            TaskDetail(task, conflicts, actions)
        }
    }
}

@Composable
private fun ColumnScope.TaskDetail(task: Task, conflicts: List<ConflictChoice>, actions: TodayActions) {
    // The title saves itself (Meka, 2026-10-08: an edit was lost on Close because only the keyboard's Done saved it).
    TaskTitleField(task.id, task.title, onSave = { id, t -> actions.rename(id, t) },
        // On the closed Fold the title arrives from the row that was tapped (a no-op outside a pane).
        modifier = Modifier.fillMaxWidth().padding(vertical = MekaSpace.m).sharedTitleInPane(SharedMotion.taskKey(task.id)))
    meta(task, actions.whenOf(task))?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }

    // The rows fade out above the action pills instead of being sliced under them (walk-through A10, the Tomorrow
    // sheet's edit); the foot's padding lets the last row scroll clear of the fade.
    Column(Modifier.weight(1f).fillMaxWidth().footFade().verticalScroll(rememberScrollState())) {
        conflicts.forEach { c ->
            Spacer(Modifier.height(MekaSpace.l))
            SectionLabel("Edited on two devices")
            c.options.forEach { option ->
                Text(
                    option, style = MekaType.itemTitle, color = Meka.colors.textPrimary,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .background(Meka.colors.surfaceRaised).clickable { actions.resolve(c, option) }.padding(MekaSpace.m),
                )
                Spacer(Modifier.height(MekaSpace.xs))
            }
        }

        Spacer(Modifier.height(MekaSpace.l))
        // Entrance: the rows stagger in, 40 ms apart (Motion setting; Off: at once). Fresh for each task.
        key(task.id) {
            Column(Modifier.appear(rememberAppearance(0))) { WhenSection(task, actions) }
            Column(Modifier.appear(rememberAppearance(1))) { ReminderSection(task, actions) }
            Column(Modifier.appear(rememberAppearance(2))) { RepeatSection(task, actions) }
            Column(Modifier.appear(rememberAppearance(3))) { NotesSection(task, actions) }
            Column(Modifier.appear(rememberAppearance(4))) { ChecklistSection(task, actions) }
            Column(Modifier.appear(rememberAppearance(5))) { GoalSection(task, actions) }
        }
        Spacer(Modifier.height(MotionMath.FOOT_FADE_DP.dp))
    }

    Spacer(Modifier.height(MekaSpace.m))
    key(task.id) { Box(Modifier.appear(rememberAppearance(6))) { DetailActions(task, actions) } }
}

internal fun providerLabel(p: String) = os.meka.core.domain.CalendarAccountRules.providerLabel(p)


/**
 * The line under the title. Its time is When's own minute, on the core's clock (walk-through 2026-11-02 A4: the
 * phone's zone drew "06:40" under the title while When said 07:40).
 */
private fun meta(t: Task, whenView: TaskWhenView): String? {
    val parts = buildList {
        if (t.scheduledAtMs != null) whenView.minute?.let { add(TaskWhenRules.timeLabel(it)) }
        t.estimateMinutes?.let { add("$it min") }
        t.checklist.takeIf { it.isNotEmpty() }?.let { cl -> add("${cl.count { it.checked }}/${cl.size}") }
        t.repeatMeta(LocalDate.now().toEpochDay())?.let { add("↻ $it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * "Good evening, Alex" with About you's name, "Good evening" without (00:00–04:59 is "Good night", Meka 2026-10-17), by
 * the core's clock (quality gate, 2026-10-22); the phone's clock only where no core is given.
 */
private fun greeting(core: MekaCore?, name: String?): String =
    core?.todayGreeting(name) ?: os.meka.core.domain.CallNameRules.todayGreeting(java.time.LocalTime.now().hour, name)

/**
 * Study plan, slice 2b rest: Obi's nudge card from 10 minutes before a study session ("A card from Obi, not out loud"):
 * the headline in the accent, the time, lessons and where in grey, then Start (Done once started) and Not now.
 */
@Composable
private fun StudyNudgeCard(
    n: os.meka.core.domain.StudyNudge, started: Boolean, modifier: Modifier,
    onStart: () -> Unit, onDone: () -> Unit, onNotNow: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val on = n.on || started
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("today-study-nudge"),
    ) {
        Crossfade(if (on && !n.on) n.headline.replaceBefore(" · ", "Studying") else n.headline, animationSpec = MekaMotion.appear(reduced), label = "study-nudge-head") { h ->
            Text(h, style = MekaType.captionStrong, color = Meka.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = n.spoken })
        }
        Text(n.line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MekaSpace.xxs))
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            val label = if (on) os.meka.core.domain.StudyWeekRules.DONE else os.meka.core.domain.StudyWeekRules.START
            Box(
                Modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = if (on) onDone else onStart)
                    .testTag("today-study-nudge-button"),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(label, animationSpec = MekaMotion.appear(reduced), label = "study-nudge-button") { l ->
                    Text(l, style = MekaType.captionStrong, color = if (on) Meka.colors.onAccent else Meka.colors.accent, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                            .background(if (on) Meka.colors.accent else Meka.colors.surfaceRaised)
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
                }
            }
            Box(
                Modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onNotNow)
                    .testTag("today-study-nudge-later"),
                contentAlignment = Alignment.Center,
            ) {
                Text(os.meka.core.domain.StudyNudgeRules.NOT_NOW, style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs))
            }
        }
    }
}

/**
 * Study plan, slice 2b rest: the Sunday check-in ("Sunday check-in · How the week went, and next week's sessions"): the
 * headline in the accent over the week's dates, then per course its name, what was ticked, on track (green) or behind (accent, as the course's plan card) and
 * next week's sessions; Obi's one line; Looks good and Change plan. Rows come in with the card's unfold.
 */
@Composable
internal fun StudyCheckinCard(
    c: os.meka.core.domain.StudyCheckin, modifier: Modifier, onLooksGood: () -> Unit, onChange: (String) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("today-study-checkin"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = c.spoken }) {
            Text(c.headline, style = MekaType.captionStrong, color = Meka.colors.accent, maxLines = 1, modifier = Modifier.weight(1f))
            Text(c.sub, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1)
        }
        c.rows.forEach { r ->
            Column(
                Modifier.padding(top = MekaSpace.xs).fillMaxWidth().wordsTap { onChange(r.courseId) }
                    .semantics(mergeDescendants = true) { contentDescription = r.spoken },
            ) {
                Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.week, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                r.status?.let { st ->
                    Text(st, style = MekaType.caption, color = if (r.behind) Meka.colors.accent else Meka.colors.success,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(r.next, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(c.obi, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onLooksGood)
                    .testTag("today-study-checkin-ok"),
                contentAlignment = Alignment.Center,
            ) {
                Text(os.meka.core.domain.StudyCheckinRules.LOOKS_GOOD, style = MekaType.captionStrong, color = Meka.colors.accent, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
            }
            Box(
                Modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button) { c.rows.firstOrNull()?.let { onChange(it.courseId) } }
                    .testTag("today-study-checkin-change"),
                contentAlignment = Alignment.Center,
            ) {
                Text(os.meka.core.domain.StudyCheckinRules.CHANGE, style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs))
            }
        }
    }
}
