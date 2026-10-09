package os.meka.android.today

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import os.meka.android.work.AfterWorkHost
import os.meka.core.domain.CommandCentreRules
import os.meka.core.domain.NowKind
import os.meka.core.domain.NowView
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
import androidx.compose.runtime.rememberUpdatedState
import os.meka.android.designsystem.CheckRing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.launch
import os.meka.android.news.NewsPane
import os.meka.android.news.NewsTickerStrip
import os.meka.android.news.rememberTickerMode
import os.meka.core.domain.NewsTicker
import os.meka.core.domain.TickerMode
import os.meka.core.domain.TickerRules
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
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.TimelineKind
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
import os.meka.android.review.ReviewCardTile
import os.meka.core.domain.GoalsView
import os.meka.core.domain.HabitChipRules
import kotlinx.coroutines.flow.StateFlow
import os.meka.core.sync.SyncStatus
import java.time.Instant
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.WatchFace
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.DayArc
import os.meka.core.domain.DayRing
import os.meka.core.domain.DayRingHeader
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * TODAY (brief §4). Closed Fold: one calm column. Open Fold / wide windows (≥ 600dp): Today | selected item.
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
        if (alarmBrief) { briefReadAloud = !showBrief; showBrief = true; widgetApp?.openBrief?.value = false }
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
        val away = if (pausedAt == 0L) 0L else SystemClock.elapsedRealtime() - pausedAt
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
    val nowHandlers = NowHandlers(
        complete = actions.complete, tomorrow = actions.snooze, openTask = actions.select,
        openEvent = { eventOpen = it }, openNeedsYou = null, // Needs you is listed just above it on the cover screen
        went = { id -> scope.launch { runCatching { core.sessionWent(id, null) } } },
        didntGo = { id -> scope.launch { runCatching { core.sessionMissed(id) } } },
    )
    // The mic sits at the capture bar's end on every screen (Fold review 2026-10-09 07:26, item 5), not on a card:
    // Ask, already listening.
    val talkFromCapture: () -> Unit = { os.meka.android.ask.talkFromCover(talkApp) }
    // The open Fold's Up next card: the same taps and no Needs you line.
    val upNextHandlers = NowHandlers(
        complete = actions.complete, tomorrow = actions.snooze, openTask = actions.select,
        openEvent = { eventOpen = it }, openNeedsYou = null,
    )

    // Insets are applied once, by the app shell.
    // Where each task row sits, so its detail grows out of it on the closed Fold (container transform).
    val origins = remember { ContainerOrigins() }
    MekaSharedLayout(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalContainerOrigins provides origins) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
            val layout = CommandCentreRules.layout(maxWidth.value)
            val twoPane = layout != CommandLayout.SINGLE
            val all = (today.needsYou.map { it.task } + listOfNotNull(today.upNext) + today.yourDay)
            val selected = all.firstOrNull { it.id == selectedId }
            val columns = CommandCentreRules.columns(layout, taskOpen = selected != null)
            // In the command centre an open task stands in for Needs you; back (or Close) brings Needs you back.
            BackHandler(enabled = twoPane && selected != null) { selectedId = null }
            val rowMotion: (String) -> RowMotion = { id ->
                RowMotion(
                    shareTitle = true,
                    titleVisible = SharedMotion.rowTitleVisible(id, selectedId, !twoPane, showPlan, landing),
                    landed = SharedMotion.highlightLanded(id, showPlan, landing),
                )
            }
            TwoPaneMorph(
                twoPane,
                detailShare = if (twoPane) CommandCentreRules.sideShare(layout) else CommandCentreRules.SIDE_SHARE_TWO,
                list = { m ->
                    key(openings) { TodayPane(today, sync, actions, m, connect, openPlan, !introPlayed, rowMotion,
                        ringPlay = ringPlay, ringPlayed = { ringPlay = DayRingPlay.STILL },
                        openDayRing = { showDayRing = true },
                        listsNeedsYou = CommandCentreRules.todayListsNeedsYou(layout),
                        shutdown = shutdown, openShutdown = { showShutdown = true }, shutdownOpen = showShutdown,
                        openSearch = { showSearch = true },
                        brief = brief, openBrief = { showBrief = true }, briefOpen = showBrief,
                        reviewCard = review.card, openReviewCard = { scope.launch { runCatching { core.showReviewCardWeek() }; openReview() } },
                        openEvent = { eventOpen = it }, eventHandlers = eventHandlers,
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
                        needsYou = { nm -> CommandNeedsYou(core, stack, moves, nm, openAfterWork = { showAfterWork = true }) },
                        comingUp = { cm, shared ->
                            ComingUpColumn(
                                calendar, shared, cm, openEvent = { eventOpen = it }, openCalendar = openCalendar,
                                news = newsPlace, openNews = { id -> newsOpen = id ?: "" },
                            )
                        },
                    )
                },
            )
            // Closed Fold: detail springs up over Today. The last task is kept so it stays visible while leaving.
            var shown by remember { mutableStateOf<Task?>(null) }
            if (selected != null) shown = selected
            MekaPane(visible = selected != null && !twoPane, origin = { origins[shown?.id] }) {
                shown?.let { s ->
                    DetailPane(s, conflicts.filter { it.taskId == s.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
            MekaPane(visible = showPlan) {
                PlanPane(core, landing = landing, onApplying = { landing = it }, undo = eventUndo, onClose = { showPlan = false })
            }
            MekaPane(visible = showShutdown) { ShutdownPane(core, onClose = { showShutdown = false }) }
            MekaPane(visible = showBrief) {
                BriefPane(core, onClose = { showBrief = false; briefReadAloud = false }, readAloud = briefReadAloud)
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
            MekaPane(visible = showAfterWork) { AfterWorkHost(onClose = { showAfterWork = false }) }
            MekaPane(visible = showSearch) {
                SearchPane(core, onClose = { showSearch = false }, openItem = { item -> showSearch = false; openItem(item) })
            }
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

/** Stagger groups on Today: greeting, needs you, up next, the timeline (header and all day), the timeline (rows), done. */
private const val TODAY_SECTIONS = 6

/** Present only while the device isn't enrolled for sync. */
data class ConnectHook(val defaultUrl: String, val connect: suspend (url: String, code: String) -> String?)

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
    select = { id -> setSelected(id) },
    rename = { id, t -> TypingSaves.launch { core.rename(id, t) } },
    delete = { id ->
        scope.launch {
            val title = core.today.value.let { t -> (t.needsYou.map { it.task } + listOfNotNull(t.upNext) + t.yourDay) }.firstOrNull { it.id == id }?.title
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
    addStep = { id, text -> TypingSaves.launch { core.addStep(id, text) } },
    setStepDone = { stepId, done -> scope.launch { core.setStepDone(stepId, done) } },
    removeStep = { stepId -> scope.launch { core.removeStep(stepId) } },
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
    val removeStep: (String) -> Unit,
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
 * watch face on the right (Fold review 2026-10-09 07:26, item 2) — [DayRingHeader.WIDE_DP] on the open Fold, a compact
 * [DayRingHeader.COMPACT_DP] face on the closed Fold ([compact]). Tapping it opens the full 24-hour Day ring
 * ([onOpenFace]). No [face] (no day yet): the left side takes the whole row.
 */
@Composable
internal fun TodayHeaderRow(
    face: WatchFace?, compact: Boolean, play: DayRingPlay, played: () -> Unit,
    onOpenFace: (() -> Unit)? = null,
    left: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) { left() }
        if (face != null) {
            WatchFaceDial(face, play, played, Modifier.padding(start = MekaSpace.m),
                size = DayRingHeader.sizeDp(compact).dp, onOpen = onOpenFace)
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
    openEvent: (CalendarEvent) -> Unit = {},
    eventHandlers: EventActionHandlers? = null,
    /** False in the command centre, where the Needs you column beside Today shows them. */
    listsNeedsYou: Boolean = true,
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
    /** The capture bar's mic (Talk): Ask, already listening. Null: no mic. */
    onTalk: (() -> Unit)? = null,
) {
    // "3 earlier" unfolds the finished events in place.
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    // "+2 more" unfolds the rest of the all-day group.
    var allDayOpen by rememberSaveable { mutableStateOf(false) }
    val updater = (LocalContext.current.applicationContext as? MekaApplication)?.updater
    val update by remember(updater) { updater?.state ?: MutableStateFlow<UpdateState>(UpdateState.None) }.collectAsState()
    // Weather for home (weather item, slice 1): a quiet line under the date.
    val weatherFlow = remember(core) { core?.weatherView ?: MutableStateFlow(WeatherView.EMPTY) }
    val weather by weatherFlow.collectAsState()
    val motion = Meka.motion
    // Not on the closed Fold's cover screen: the card waits for the main screen.
    val motionCard = if (now == null) motion.card else null
    val pull = rememberPullToSync { core?.syncNow() }
    // Today's habits as chips under the ticker (Fold review 2026-10-09 07:26, item 3); the tiles strip drops its habits tile.
    val goalsNow by actions.goals.collectAsState()
    val habitChips = remember(goalsNow) { HabitChipRules.build(goalsNow) }
    val stripTiles = remember(today.dayTiles) { HabitChipRules.stripTiles(today.dayTiles) }
    val chipHaptics = rememberMekaHaptics()
    val chipScope = rememberCoroutineScope()
    Column(modifier.imePadding()) {
        // Pull to sync (motion pass 2, slice 3): pulling Today down past its top fills a brass ring; letting go syncs.
        PullToSyncBox(pull, Modifier.weight(1f).fillMaxWidth()) {
        // The foot fades into the capture bar rather than cutting a row in half (Fold review item 4); the bottom
        // padding (xl) is more than the fade, so the last row still scrolls fully clear.
        LazyColumn(
            modifier = Modifier.fillMaxSize().footFade(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "greeting") {
                Column(Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0, play))) {
                    // The watch face sits in the header, beside the greeting (Fold reviews 2026-10-09, 00:10 item 1
                    // and 07:26 item 2): greeting, date and links on the left, the 12-hour face on the right — 150 dp
                    // on the open Fold, a compact 96 dp on the closed one. The first-open moment draws it in place.
                    TodayHeaderRow(
                        face = today.watchFace.takeIf { today.timeline.dateLabel.isNotEmpty() }, compact = now != null,
                        play = ringPlay, played = ringPlayed,
                        // Tap the face: the full 24-hour Day ring as a sheet, its arcs opening their events and tasks.
                        onOpenFace = openDayRing,
                    ) {
                    // The opening moment, part 2: on the first open of the day the greeting's letters fade in.
                    GreetingText(greeting(), if (now == null) ringPlay else DayRingPlay.STILL)
                    if (today.timeline.dateLabel.isNotEmpty()) {
                        Text(today.timeline.dateLabel, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                            modifier = Modifier.padding(top = MekaSpace.xxs))
                    }
                    // "14° · light rain from 16:00": cross-fades as the day moves on; nothing until a forecast arrives.
                    if (weather.nowLine != null) {
                        val spoken = "Weather: " + (weather.nowSpoken ?: weather.nowLine)
                        Crossfade(weather.nowLine, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "weather-line") { line ->
                            Text(line.orEmpty(), style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = MekaSpace.xxs).semantics { contentDescription = spoken })
                        }
                    }
                    SyncLine(sync)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
                        modifier = Modifier.padding(top = MekaSpace.xs).horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "Search", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openSearch() }.padding(vertical = MekaSpace.xxs),
                        )
                        Text(
                            "Plan my day", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openPlan() }.padding(vertical = MekaSpace.xxs),
                        )
                    }
                    }
                    if (connect != null) ConnectCard(connect.defaultUrl, connect.connect, Modifier.padding(top = MekaSpace.xs))
                    // News ticker (news ticker, slice 2): one line of drifting cards under the header; calm by default
                    // (two loops, then it rests). Off in Appearance hides it.
                    if (core != null && TickerRules.shown(tickerMode, ticker)) {
                        NewsTickerStrip(core, ticker, tickerMode, Modifier.padding(top = MekaSpace.s), openStory = openStory, openMatch = openMatch)
                    }
                    // Today's habits: compact chips to tick, hidden when there are none.
                    if (core != null && habitChips.isNotEmpty()) {
                        HabitChipsRow(
                            habitChips, play,
                            tick = { chip ->
                                chipHaptics.light()
                                chipScope.launch { runCatching { core.setHabitDone(chip.id, !chip.done) } }
                            },
                            modifier = Modifier.padding(top = MekaSpace.s),
                        )
                    }
                    // The ring's live tiles, a slim row under the ticker (they left the dial with the move).
                    if (today.timeline.dateLabel.isNotEmpty()) {
                        DayTilesStrip(stripTiles, ringPlay, today.watchFace.arcs.size, Modifier.padding(top = MekaSpace.s))
                    }
                }
            }
            // Motion pass 2: with the phone's animations off and nothing chosen in Appearance → Motion, a one-time card.
            motionCard?.let { card ->
                item(key = "motion") {
                    MotionSystemCard(card, motion, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)))
                }
            }
            // Self-updating phone app: the card rises in when the Mac has published a newer build.
            if (update !is UpdateState.None && updater != null) {
                item(key = "update") {
                    UpdateCard(update, updater, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)))
                }
            }
            // Morning brief: the card rises in when the morning starts and goes at noon or once read.
            if (brief.offered) {
                item(key = "brief") {
                    // Its title travels into the brief pane's (Four tabs, slice 3).
                    BriefCard(brief, openBrief, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)),
                        titleModifier = Modifier.sharedTitle(SharedMotion.paneKey(SharedMotion.BRIEF), !briefOpen))
                }
            } else if (brief.readElsewhereLine != null) {
                // Read on the Mac this morning: a slim line in the card's place until noon (Fold review 2026-10-08).
                item(key = "brief-read") {
                    BriefReadLine(brief.readElsewhereLine!!, openBrief, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)))
                }
            }
            // Weekly review: the card rises in on Sunday evening and stays through Monday until reviewed.
            if (reviewCard.offered) {
                item(key = "review") {
                    // Its title travels across the shell into the Review tab's.
                    ReviewCardTile(reviewCard, openReviewCard, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)),
                        titleModifier = Modifier.sharedPlace(SharedMotion.placeKey(ShellDestination.REVIEW, PlaceVia.CARD)))
                }
            }
            // Evening shutdown: the card rises in when the evening starts; once done, one quiet line stays.
            if (shutdown.offered) {
                item(key = "shutdown") {
                    ShutdownCard(shutdown, openShutdown, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)),
                        titleModifier = Modifier.sharedTitle(SharedMotion.paneKey(SharedMotion.SHUTDOWN), !shutdownOpen))
                }
            } else if (shutdown.evening || shutdown.doneLine != null) {
                // Tomorrow at a glance once the evening starts (after shutting down, or while still at work); tapping
                // opens the shutdown pane with tomorrow in full.
                item(key = "shutdown-done") {
                    TomorrowGlance(
                        shutdown.doneLine, shutdown.tomorrow.glance.takeIf { shutdown.evening }, openShutdown,
                        Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)),
                    )
                }
            }
            // "You're clear." only when nothing at all is left today; "Nothing else timed today" beside all-day items.
            today.clearLine?.let { line ->
                item(key = "clear") {
                    // All clear: the brass ring breathes beside it (catalogue "Empty states"); Off: still.
                    Row(Modifier.animateItem().appear(rememberAppearance(1, play)), verticalAlignment = Alignment.CenterVertically) {
                        if (today.isAllClear) BreathingRing(Modifier.padding(end = MekaSpace.s))
                        Text(line, style = if (today.isAllClear) MekaType.upNextTitle else MekaType.body, color = Meka.colors.textSecondary)
                    }
                }
            }
            if (listsNeedsYou && today.needsYou.isNotEmpty()) {
                item(key = "h-needs") { SectionLabel("Needs you", Modifier.animateItem().appear(rememberAppearance(1, play))) }
                items(today.needsYou, key = { "n-" + it.task.id }) { n ->
                    TaskRow(n.task, actions, reason = n.reason, motion = rowMotion(n.task.id),
                        modifier = Modifier.animateItem().appear(rememberAppearance(1, play)))
                }
                item(key = "s-needs") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            val nowCard = now?.takeIf { it.kind != NowKind.CLEAR }
            if (nowCard != null && nowHandlers != null) {
                // One stable slot: when the thing changes, the card's content cross-slides.
                item(key = "now") {
                    NowCard(
                        nowCard, nowHandlers, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(2, play)),
                        titleModifier = { id -> rowMotion(id).let { m -> if (m.shareTitle) Modifier.sharedTitle(SharedMotion.taskKey(id), m.titleVisible) else Modifier } },
                    )
                }
            } else if (today.timeline.nextEvent != null && (upNext == null || upNextHandlers == null)) {
                // The Up next card carries its own label, so the section label only heads a lone next event.
                item(key = "h-next") { SectionLabel("Up next", Modifier.animateItem().appear(rememberAppearance(2, play))) }
            }
            val upNextCard = upNext?.takeIf { nowCard == null && upNextHandlers != null }
            if (upNextCard != null && upNextHandlers != null) {
                // One stable slot: when up next changes, the card's content cross-slides instead of the row swapping.
                // The closed Fold's card (UP NEXT, title, line, Done · Tomorrow · Open) on every screen.
                item(key = "upnext") {
                    NowCard(
                        upNextCard, upNextHandlers,
                        Modifier.padding(bottom = if (today.timeline.nextEvent != null) MekaSpace.xs else 0.dp)
                            .animateItem().appear(rememberAppearance(2, play)),
                        titleModifier = { id -> rowMotion(id).let { m -> if (m.shareTitle) Modifier.sharedTitle(SharedMotion.taskKey(id), m.titleVisible) else Modifier } },
                    )
                }
            }
            // The next event within the hour: "Call with Tunde in 25 min", under Up next's card as the cover screen's
            // "Then: …" is (the "now" card carries it there).
            if (nowCard == null) today.timeline.nextEvent?.let { e ->
                item(key = "nextevent") {
                    NextEventCard(e, Modifier.animateItem().appear(rememberAppearance(2, play)), openEvent)
                }
            }
            if (nowCard == null && (upNextCard != null || today.timeline.nextEvent != null)) {
                item(key = "s-next") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            // The Gym (booked habits): today's session, "Did you go?" once it's over, or where it was rebooked.
            if (core != null) item(key = "session") {
                os.meka.android.goals.SessionCards(core, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(2, play)))
            }
            // Quick alarms and timers typed into capture ("alarm 6:30", "timer 20 min"), each with a cancel ✕.
            if (core != null) item(key = "quick-alarms") {
                QuickAlarmRows(core, Modifier.animateItem().appear(rememberAppearance(2, play)))
            }
            // One timeline under "Today": the "All day" group first (one row each, at most 3 then "+2 more"), finished
            // events folded, events and planned tasks in time order with the now line and free gaps; then tasks with no time.
            val tl = today.timeline
            if (tl.hasTimedOrAllDay) {
                item(key = "h-day") { SectionLabel("Today", Modifier.animateItem().appear(rememberAppearance(3, play))) }
                if (tl.allDayItems.isNotEmpty()) item(key = "allday-label") {
                    AllDayLabel(tl.allDayLabel, Modifier.animateItem().appear(rememberAppearance(3, play)))
                }
                items(AllDayRules.shown(tl.allDayItems, allDayOpen), key = { "a-" + it.event.id }) { a ->
                    AllDayRow(a, Modifier.animateItem().appear(rememberAppearance(3, play)), openEvent, eventHandlers)
                }
                AllDayRules.moreLabel(tl.allDayItems, allDayOpen)?.let { more ->
                    item(key = "allday-more") {
                        AllDayMore(more, { allDayOpen = true }, Modifier.animateItem().appear(rememberAppearance(3, play)))
                    }
                }
                tl.earlierLabel?.let { label ->
                    item(key = "earlier") {
                        EarlierToggle(label, earlierOpen, { earlierOpen = !earlierOpen }, Modifier.animateItem().appear(rememberAppearance(3, play)))
                    }
                    if (earlierOpen) {
                        items(tl.earlier, key = { "x-" + it.id }) { r ->
                            TimelineEventRow(r, past = true, modifier = Modifier.animateItem().opensEvent(r.event, openEvent))
                        }
                    }
                }
                items(tl.rows, key = { "r-" + it.id }) { r ->
                    val m = Modifier.animateItem().appear(rememberAppearance(4, play))
                    when (r.kind) {
                        TimelineKind.EVENT -> SwipeableEvent(r.event, eventHandlers, m, onOpen = openEvent) { sm ->
                            TimelineEventRow(r, past = false, modifier = sm)
                        }
                        TimelineKind.TASK -> r.task?.let { t ->
                            // The Up next task's title travels from its card, so its timeline row doesn't share it.
                            val motion = if (t.id == today.upNext?.id) RowMotion() else rowMotion(t.id)
                            TaskRow(t, actions, motion = motion, modifier = m, time = r.time, timelineLine = r.detail)
                        }
                        TimelineKind.GAP -> GapRow(r, m)
                        TimelineKind.NOW -> NowLine(r, m)
                        TimelineKind.SESSION -> SessionTimelineRow(r, m)
                        TimelineKind.WORK -> WorkTimelineRow(r, m)
                    }
                }
                item(key = "s-day") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            if (tl.anytime.isNotEmpty()) {
                item(key = "h-any") { SectionLabel("Anytime today", Modifier.animateItem().appear(rememberAppearance(4, play))) }
                items(tl.anytime, key = { "d-" + it.id }) { t ->
                    TaskRow(t, actions, motion = rowMotion(t.id), modifier = Modifier.animateItem().appear(rememberAppearance(4, play)))
                }
            }
            if (today.doneToday.isNotEmpty()) {
                item(key = "done") {
                    Text("${today.doneToday.size} done today", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(5, play)))
                }
            }
        }
        }
        QuickCapture(actions.add, onTalk)
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

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = modifier.padding(bottom = MekaSpace.xxs))
}

@Composable
internal fun TaskRow(
    t: Task, actions: TodayActions, reason: NeedsYouReason? = null, motion: RowMotion = RowMotion(), modifier: Modifier = Modifier,
    /** On the timeline: the time column on the left and the core's line ("30 min · ↻ Every weekday") under the title. */
    time: String? = null, timelineLine: String? = null,
) {
    // Just landed from the plan: lit softly, then settles.
    val glow by animateColorAsState(
        if (motion.landed) Meka.colors.surfaceRaised else Color.Transparent, MekaMotion.appear(Meka.reducedMotion), label = "landed",
    )
    Row(
        modifier.containerOrigin(t.id).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(glow).clickable { actions.select(t.id) }
            .padding(vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (time != null) TimeColumn(time, past = false)
        CompleteButton(t, actions.complete)
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MekaType.body, color = Meka.colors.textPrimary,
                modifier = if (motion.shareTitle) Modifier.sharedTitle(SharedMotion.taskKey(t.id), motion.titleVisible) else Modifier)
            val line = when (reason) {
                NeedsYouReason.CONFLICT -> "Edited on two devices — choose a version"
                NeedsYouReason.OVERDUE -> "Overdue"
                NeedsYouReason.DUE_TODAY_UNSCHEDULED -> "Due today · not scheduled"
                null -> if (time != null) timelineLine else meta(t)
            }
            line?.let {
                val color = if (reason == NeedsYouReason.CONFLICT || reason == NeedsYouReason.OVERDUE) Meka.colors.critical else Meka.colors.textSecondary
                Text(it, style = MekaType.itemMeta, color = color)
            }
        }
    }
}

/**
 * Completion motion (catalogue "Complete a task"; motion pass 2, slice 4): the accent ring sweeps round, fills, the
 * check strokes in ([CheckRing], [MekaChoreography.checkDrawMs]) with a light haptic, then the row leaves via
 * animateItem. Motion → Off: shown done at once and completed straight away.
 */
@Composable
internal fun CompleteButton(t: Task, onComplete: (String) -> Unit) {
    var pressed by remember(t.id) { mutableStateOf(false) }
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val draw = remember(t.id) { Animatable(0f) }
    val done by rememberUpdatedState(onComplete)
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        val ms = MotionMath.checkDrawMs(reduced)
        if (ms == 0) draw.snapTo(1f) else draw.animateTo(1f, tween(ms, easing = LinearEasing))
        done(t.id)
    }
    CheckRing(
        fraction = draw.value,
        rest = Meka.colors.textTertiary, accent = Meka.colors.accent, onAccent = Meka.colors.onAccent,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .semantics { contentDescription = "Complete ${t.title}" }
            .clickable(role = Role.Checkbox) {
                if (!pressed) {
                    pressed = true
                    haptics.light()
                }
            },
    )
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
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.s)
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
            Spacer(Modifier.width(MekaSpace.s))
            os.meka.android.ask.TalkMic(onTalk, size = 36.dp)
        }
    }
}

/** The capture bar on Today (UI tests). */
internal const val CAPTURE_BAR_TAG = "today-capture-bar"

@Composable
internal fun DetailPane(task: Task?, conflicts: List<ConflictChoice>, actions: TodayActions, modifier: Modifier, onClose: (() -> Unit)? = null) {
    Column(modifier.padding(MekaSpace.gutter)) {
        if (onClose != null) {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable { onClose() }.padding(vertical = MekaSpace.s))
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
    meta(task)?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }

    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
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
            Column(Modifier.appear(rememberAppearance(4))) { StepsSection(task, actions) }
            Column(Modifier.appear(rememberAppearance(5))) { GoalSection(task, actions) }
        }
    }

    Spacer(Modifier.height(MekaSpace.m))
    key(task.id) { Box(Modifier.appear(rememberAppearance(6))) { DetailActions(task, actions) } }
}

internal fun providerLabel(p: String) = when (p) { "google" -> "Google"; "microsoft" -> "Outlook"; "fixtures" -> "Fixtures"; "news" -> "Headlines"; "bank_holidays" -> "Bank holidays"; "weather" -> "Weather"; else -> p }

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun meta(t: Task): String? {
    val parts = buildList {
        t.scheduledAtMs?.let { add(timeFmt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))) }
        t.estimateMinutes?.let { add("$it min") }
        t.checklist.takeIf { it.isNotEmpty() }?.let { cl -> add("${cl.count { it.checked }}/${cl.size}") }
        t.repeatMeta(LocalDate.now().toEpochDay())?.let { add("↻ $it") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning, Meka"
    in 12..17 -> "Good afternoon, Meka"
    else -> "Good evening, Meka"
}
