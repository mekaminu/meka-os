package os.meka.android.today

import os.meka.android.calendar.EventDetailPane
import os.meka.android.calendar.opensEvent
import os.meka.core.domain.CalendarEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaSharedLayout
import os.meka.android.designsystem.rememberPaneMorph
import os.meka.android.designsystem.sharedTitle
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.SharedMotion
import os.meka.android.notify.NotificationsPane
import os.meka.android.search.SearchPane
import os.meka.android.MekaApplication
import os.meka.android.activity.ActivityPane
import os.meka.android.export.YourData
import os.meka.android.update.UpdateCard
import os.meka.android.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import os.meka.android.shell.OpenItem
import os.meka.android.work.WorkPane
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.TimelineKind
import os.meka.core.domain.NeedsYouReason
import os.meka.core.domain.RepeatChoice
import os.meka.core.domain.MorningBriefView
import os.meka.core.domain.ShutdownView
import os.meka.core.domain.SomedayKind
import os.meka.core.domain.Task
import os.meka.core.domain.Today
import os.meka.core.facade.ConflictChoice
import os.meka.core.facade.MekaCore
import os.meka.core.domain.ReviewCard
import os.meka.android.review.ReviewCardTile
import os.meka.core.domain.GoalsView
import kotlinx.coroutines.flow.StateFlow
import os.meka.core.sync.SyncStatus
import java.time.Instant
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
fun TodayRoute(core: MekaCore, connect: ConnectHook? = null, openReview: () -> Unit = {}, openItem: (OpenItem) -> Unit = {}) {
    val today by core.today.collectAsState()
    val sync by core.syncStatus.collectAsState()
    val conflicts by core.conflicts.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCalendars by rememberSaveable { mutableStateOf(false) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showWork by rememberSaveable { mutableStateOf(false) }
    var showShutdown by rememberSaveable { mutableStateOf(false) }
    var showBrief by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showData by rememberSaveable { mutableStateOf(false) }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    // An event's detail (calendar redesign, slice 3); the last one is kept while the pane leaves.
    var eventOpen by remember { mutableStateOf<CalendarEvent?>(null) }
    var eventShown by remember { mutableStateOf<CalendarEvent?>(null) }
    if (eventOpen != null) eventShown = eventOpen
    val work by core.workMode.collectAsState()
    val shutdown by core.shutdownView.collectAsState()
    val brief by core.briefView.collectAsState()
    val review by core.reviewView.collectAsState()
    // Tasks Plan Apply is sending into Today: their rows hide while the plan is up, then catch the flying titles.
    var landing by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(landing, showPlan) {
        if (landing.isNotEmpty() && !showPlan) {
            delay(SharedMotion.LANDED_MS.toLong())
            landing = emptySet()
        }
    }
    val scope = rememberCoroutineScope()
    val openCalendars: (() -> Unit)? = if (connect == null) ({ showCalendars = true }) else null
    val openPlan: () -> Unit = { showPlan = true }
    // App open: greeting fades up, then each section 40 ms apart. Plays once per launch (not again on fold/unfold);
    // anything arriving later uses animateItem.
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!introPlayed) {
            delay((MotionMath.staggerSpanMs(TODAY_SECTIONS, false) + MekaMotion.appearDurationMs).toLong())
            introPlayed = true
        }
    }

    val actions = todayActions(core, scope, { selectedId }) { selectedId = it }

    // Insets are applied once, by the app shell.
    MekaSharedLayout(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
            val twoPane = maxWidth >= 600.dp
            val all = (today.needsYou.map { it.task } + listOfNotNull(today.upNext) + today.yourDay)
            val selected = all.firstOrNull { it.id == selectedId }
            val rowMotion: (String) -> RowMotion = { id ->
                RowMotion(
                    shareTitle = true,
                    titleVisible = SharedMotion.rowTitleVisible(id, selectedId, !twoPane, showPlan, landing),
                    landed = SharedMotion.highlightLanded(id, showPlan, landing),
                )
            }
            TwoPaneMorph(
                twoPane,
                list = { m ->
                    TodayPane(today, sync, actions, m, connect, openCalendars, openPlan, !introPlayed, rowMotion,
                        workLabel = if (work.atWork) "At work" else "Off work", openWork = { showWork = true },
                        shutdown = shutdown, openShutdown = { showShutdown = true }, openNotifications = { showNotifications = true },
                        openData = { showData = true },
                        openActivity = { showActivity = true },
                        openSearch = { showSearch = true },
                        brief = brief, openBrief = { showBrief = true },
                        reviewCard = review.card, openReviewCard = { scope.launch { runCatching { core.showReviewCardWeek() }; openReview() } },
                        openEvent = { eventOpen = it })
                },
                detail = { m -> DetailPane(selected, conflicts.filter { it.taskId == selected?.id }, actions, m) },
            )
            // Closed Fold: detail springs up over Today. The last task is kept so it stays visible while leaving.
            var shown by remember { mutableStateOf<Task?>(null) }
            if (selected != null) shown = selected
            MekaPane(visible = selected != null && !twoPane) {
                shown?.let { s ->
                    DetailPane(s, conflicts.filter { it.taskId == s.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
            MekaPane(visible = showPlan) {
                PlanPane(core, landing = landing, onApplying = { landing = it }, onClose = { showPlan = false })
            }
            MekaPane(visible = showCalendars) { CalendarsPane(core, onClose = { showCalendars = false }) }
            MekaPane(visible = showWork) { WorkPane(core, onClose = { showWork = false }) }
            MekaPane(visible = showShutdown) { ShutdownPane(core, onClose = { showShutdown = false }) }
            MekaPane(visible = showBrief) { BriefPane(core, onClose = { showBrief = false }) }
            MekaPane(visible = showNotifications) { NotificationsPane(core, onClose = { showNotifications = false }) }
            MekaPane(visible = showData) { YourData(core, onClose = { showData = false }) }
            MekaPane(visible = showActivity) { ActivityPane(core, onClose = { showActivity = false }) }
            MekaPane(visible = eventOpen != null) {
                eventShown?.let { e -> EventDetailPane(core, e, onClose = { eventOpen = null }) }
            }
            MekaPane(visible = showSearch) {
                SearchPane(core, onClose = { showSearch = false }, openItem = { item -> showSearch = false; openItem(item) })
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
) {
    val fraction by rememberPaneMorph(twoPane)
    val total = maxWidth
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

/** Stagger groups on Today: greeting, needs you, up next, your day (header), your day (rows), done. */
private const val TODAY_SECTIONS = 6

/** Present only while the device isn't enrolled for sync. */
data class ConnectHook(val defaultUrl: String, val connect: suspend (url: String, code: String) -> String?)

/** The commands every task list (Today, Needs you) offers, wired to [core]. Selection is owned by the caller. */
internal fun todayActions(core: MekaCore, scope: CoroutineScope, selected: () -> String?, setSelected: (String?) -> Unit) = TodayActions(
    add = { title -> scope.launch { runCatching { core.capture(title, null) } } },
    complete = { id -> scope.launch { core.complete(id); if (selected() == id) setSelected(null) } },
    select = { id -> setSelected(id) },
    rename = { id, t -> scope.launch { runCatching { core.rename(id, t) } } },
    delete = { id -> scope.launch { core.delete(id); setSelected(null) } },
    resolve = { c, v -> scope.launch { core.resolve(c, v) } },
    repeatChoices = { id -> core.repeatChoices(id) },
    setRepeat = { id, rule -> scope.launch { runCatching { core.setRepeat(id, rule) } } },
    skip = { id -> scope.launch { runCatching { core.skipOccurrence(id) }; if (selected() == id) setSelected(null) } },
    snooze = { id -> scope.launch { runCatching { core.snooze(id, 1) }; if (selected() == id) setSelected(null) } },
    addStep = { id, text -> scope.launch { runCatching { core.addStep(id, text) } } },
    setStepDone = { stepId, done -> scope.launch { core.setStepDone(stepId, done) } },
    removeStep = { stepId -> scope.launch { core.removeStep(stepId) } },
    someday = { id -> scope.launch { runCatching { core.moveToSomeday(id, SomedayKind.IDEA) }; if (selected() == id) setSelected(null) } },
    goals = core.goalsView,
    setGoal = { id, goalId -> scope.launch { runCatching { core.setTaskGoal(id, goalId) } } },
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
)

@Composable
private fun TodayPane(
    today: Today, sync: SyncStatus, actions: TodayActions, modifier: Modifier, connect: ConnectHook?, openCalendars: (() -> Unit)?,
    openPlan: () -> Unit, play: Boolean, rowMotion: (String) -> RowMotion, workLabel: String, openWork: () -> Unit,
    shutdown: ShutdownView, openShutdown: () -> Unit, openNotifications: () -> Unit, openSearch: () -> Unit, openData: () -> Unit,
    brief: MorningBriefView, openBrief: () -> Unit,
    reviewCard: ReviewCard, openReviewCard: () -> Unit,
    openEvent: (CalendarEvent) -> Unit = {},
    openActivity: () -> Unit = {},
) {
    // "3 earlier" unfolds the finished events in place.
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    val updater = (LocalContext.current.applicationContext as? MekaApplication)?.updater
    val update by remember(updater) { updater?.state ?: MutableStateFlow<UpdateState>(UpdateState.None) }.collectAsState()
    Column(modifier.imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "greeting") {
                Column(Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0, play))) {
                    Text(greeting(), style = MekaType.greeting, color = Meka.colors.textPrimary)
                    if (today.timeline.dateLabel.isNotEmpty()) {
                        Text(today.timeline.dateLabel, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                            modifier = Modifier.padding(top = MekaSpace.xxs))
                    }
                    SyncLine(sync)
                    if (connect != null) ConnectCard(connect.defaultUrl, connect.connect, Modifier.padding(top = MekaSpace.xs))
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
                        if (openCalendars != null) {
                            Text(
                                "Calendars", style = MekaType.caption, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                    .clickable(role = Role.Button) { openCalendars() }.padding(vertical = MekaSpace.xxs),
                            )
                        }
                        Text(
                            workLabel, style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openWork() }.padding(vertical = MekaSpace.xxs),
                        )
                        Text(
                            "Brief", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openBrief() }.padding(vertical = MekaSpace.xxs),
                        )
                        Text(
                            "Shut down", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openShutdown() }.padding(vertical = MekaSpace.xxs),
                        )
                        Text(
                            "Notifications", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openNotifications() }.padding(vertical = MekaSpace.xxs),
                        )
                        Text(
                            "Activity", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openActivity() }.padding(vertical = MekaSpace.xxs),
                        )
                        Text(
                            "Your data", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openData() }.padding(vertical = MekaSpace.xxs),
                        )
                        val theme = Meka.theme
                        Text(
                            "Theme: ${theme.choice.label}", style = MekaType.caption, color = Meka.colors.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { theme.set(theme.choice.next()) }.padding(vertical = MekaSpace.xxs),
                        )
                    }
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
                    BriefCard(brief, openBrief, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)))
                }
            }
            // Weekly review: the card rises in on Sunday evening and stays through Monday until reviewed.
            if (reviewCard.offered) {
                item(key = "review") {
                    ReviewCardTile(reviewCard, openReviewCard, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)))
                }
            }
            // Evening shutdown: the card rises in when the evening starts; once done, one quiet line stays.
            if (shutdown.offered) {
                item(key = "shutdown") {
                    ShutdownCard(shutdown, openShutdown, Modifier.padding(bottom = MekaSpace.l).animateItem().appear(rememberAppearance(1, play)))
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
            if (today.isClear) {
                item(key = "clear") {
                    Text("You're clear.", style = MekaType.upNextTitle, color = Meka.colors.textSecondary,
                        modifier = Modifier.animateItem().appear(rememberAppearance(1, play)))
                }
            }
            if (today.needsYou.isNotEmpty()) {
                item(key = "h-needs") { SectionLabel("Needs you", Modifier.animateItem().appear(rememberAppearance(1, play))) }
                items(today.needsYou, key = { "n-" + it.task.id }) { n ->
                    TaskRow(n.task, actions, reason = n.reason, motion = rowMotion(n.task.id),
                        modifier = Modifier.animateItem().appear(rememberAppearance(1, play)))
                }
                item(key = "s-needs") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            if (today.upNext != null || today.timeline.nextEvent != null) {
                item(key = "h-next") { SectionLabel("Up next", Modifier.animateItem().appear(rememberAppearance(2, play))) }
            }
            // The next event within the hour: "Call with Tunde in 25 min".
            today.timeline.nextEvent?.let { e ->
                item(key = "nextevent") {
                    NextEventCard(e, Modifier.padding(bottom = MekaSpace.xs).animateItem().appear(rememberAppearance(2, play)), openEvent)
                }
                if (today.upNext == null) item(key = "s-nextevent") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            today.upNext?.let { t ->
                // One stable slot: when up next changes, the card's content cross-slides instead of the row swapping.
                item(key = "upnext") { UpNextCard(t, actions, rowMotion, Modifier.animateItem().appear(rememberAppearance(2, play))) }
                item(key = "s-next") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            // One timeline: all-day chips, finished events folded, events and planned tasks in time order with the
            // now line and free gaps; then tasks with no time.
            val tl = today.timeline
            if (tl.hasTimedOrAllDay) {
                item(key = "h-day") { SectionLabel("Your day", Modifier.animateItem().appear(rememberAppearance(3, play))) }
                if (tl.allDay.isNotEmpty()) {
                    item(key = "allday") { AllDayChips(tl.allDay, Modifier.animateItem().appear(rememberAppearance(3, play)), openEvent) }
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
                        TimelineKind.EVENT -> TimelineEventRow(r, past = false, modifier = m.opensEvent(r.event, openEvent))
                        TimelineKind.TASK -> r.task?.let { t ->
                            // The Up next task's title travels from its card, so its timeline row doesn't share it.
                            val motion = if (t.id == today.upNext?.id) RowMotion() else rowMotion(t.id)
                            TaskRow(t, actions, motion = motion, modifier = m, time = r.time, timelineLine = r.detail)
                        }
                        TimelineKind.GAP -> GapRow(r, m)
                        TimelineKind.NOW -> NowLine(r, m)
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
        QuickCapture(actions.add)
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
private fun UpNextCard(t: Task, actions: TodayActions, rowMotion: (String) -> RowMotion, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    // Up next changes: the new item slides in from the right as the old one slides out left (cross-fade when reduced).
    AnimatedContent(
        targetState = t,
        contentKey = { it.id },
        transitionSpec = {
            if (reduced) fadeIn(MekaMotion.replan(true)) togetherWith fadeOut(MekaMotion.replan(true))
            else (slideInHorizontally(MekaMotion.replan(false)) { it / 4 } + fadeIn(MekaMotion.appear(false))) togetherWith
                (slideOutHorizontally(MekaMotion.replan(false)) { -it / 4 } + fadeOut(MekaMotion.appear(false)))
        },
        label = "upnext",
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised),
    ) { task ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { actions.select(task.id) }
                .padding(MekaSpace.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val m = rowMotion(task.id)
                Text(task.title, style = MekaType.upNextTitle, color = Meka.colors.textPrimary,
                    modifier = if (m.shareTitle) Modifier.sharedTitle(SharedMotion.taskKey(task.id), m.titleVisible) else Modifier)
                meta(task)?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
            }
            CompleteButton(task, actions.complete)
        }
    }
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
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(glow).clickable { actions.select(t.id) }
            .padding(vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (time != null) TimeColumn(time, past = false)
        CompleteButton(t, actions.complete)
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary,
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

/** Completion motion (brief §3): ring fills → check → row compresses and leaves via animateItem. */
@Composable
internal fun CompleteButton(t: Task, onComplete: (String) -> Unit) {
    var pressed by remember(t.id) { mutableStateOf(false) }
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (pressed) Meka.colors.accent else Meka.colors.background, MekaMotion.complete(reduced), label = "fill")
    val scale by animateFloatAsState(if (pressed && !reduced) 0.86f else 1f, MekaMotion.complete(reduced), label = "scale",
        finishedListener = { if (pressed) onComplete(t.id) })
    Box(
        Modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, if (pressed) Meka.colors.accent else Meka.colors.textTertiary, CircleShape)
            .semantics { contentDescription = "Complete ${t.title}" }
            .clickable(role = Role.Checkbox) {
                if (!pressed) {
                    pressed = true
                    haptics.light()
                    if (reduced) onComplete(t.id)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pressed) Text("✓", color = Meka.colors.onAccent, style = MekaType.caption)
    }
}

@Composable
private fun QuickCapture(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.s)
            .clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
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
}

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
    var title by remember(task.id, task.title) { mutableStateOf(task.title) }
    BasicTextField(
        value = title,
        onValueChange = { title = it },
        textStyle = MekaType.upNextTitle.copy(color = Meka.colors.textPrimary),
        cursorBrush = SolidColor(Meka.colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.rename(task.id, title) }),
        // On the closed Fold the title arrives from the row that was tapped (a no-op outside a pane).
        modifier = Modifier.fillMaxWidth().padding(vertical = MekaSpace.m).sharedTitleInPane(SharedMotion.taskKey(task.id)),
    )
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
        RepeatSection(task, actions)
        StepsSection(task, actions)
        GoalSection(task, actions)
    }

    Spacer(Modifier.height(MekaSpace.m))
    DetailActions(task, actions)
}

internal fun providerLabel(p: String) = when (p) { "google" -> "Google"; "microsoft" -> "Outlook"; "fixtures" -> "Fixtures"; "news" -> "Headlines"; else -> p }

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
