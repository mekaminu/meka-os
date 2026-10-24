package os.meka.android.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.LocalPlaceTitleKey
import os.meka.android.designsystem.LocalSharedKeyPrefix
import os.meka.android.designsystem.LocalShellContent
import os.meka.android.designsystem.MekaSharedLayout
import androidx.compose.runtime.CompositionLocalProvider
import os.meka.android.review.ReviewRoute
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.calendar.CalendarRoute
import os.meka.android.export.YourData
import os.meka.android.lists.ListsRoute
import os.meka.android.goals.GoalsRoute
import os.meka.android.today.ConnectHook
import os.meka.android.today.NeedsYouRoute
import os.meka.android.today.TodayRoute
import os.meka.android.ask.AskRoute
import os.meka.android.fold.BedsideClock
import os.meka.android.fold.rememberFoldState
import os.meka.core.domain.BedsideEntryRules
import os.meka.core.domain.FoldMode
import os.meka.core.domain.FoldModeRules
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import os.meka.core.facade.MekaCore
import os.meka.android.ask.DockedHelloOrb
import os.meka.android.ask.HelloStage
import os.meka.core.domain.EntranceKind
import os.meka.core.domain.HelloOpen
import os.meka.android.ask.StageCards
import os.meka.core.domain.TalkPhase
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import os.meka.core.domain.DockTap
import os.meka.core.domain.HelloDock
import os.meka.core.domain.TalkStart
import kotlinx.coroutines.delay
import os.meka.android.MekaApplication
import androidx.compose.ui.platform.LocalContext

/**
 * The app shell (build plan M1). Closed Fold: content above a bottom bar. Open Fold: a rail on the left and the
 * destination beside it (Today keeps its own two panes). Switching slides the content the way you moved along the
 * bar while the lit pill springs across; reduced motion cross-fades. Each destination keeps its own state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppShell(core: MekaCore, connect: ConnectHook?) {
    var current by rememberSaveable { mutableStateOf(ShellDestination.TODAY) }
    val today by core.today.collectAsState()
    val lists by core.listsView.collectAsState()
    val approvals by core.approvals.collectAsState()
    val requests by core.requests.collectAsState()
    val triage by core.triage.collectAsState()
    val school by core.schoolView.collectAsState()
    val dateNight by core.dateNightView.collectAsState()
    val appointments by core.appointmentsView.collectAsState()
    // Due chases and decision reviews wait on you too, so they count in the badge; so do requests from people and
    // messages waiting on a reply (the messages assistant; FYIs don't nag).
    val needsYou = today.needsYou.size + lists.dueCount + approvals.size + requests.size + os.meka.core.domain.TriageChatRules.replyChats(triage) + school.covers.size +
        (if (dateNight.nudge != null) 1 else 0) + appointments.nudges.size
    val haptics = rememberMekaHaptics()
    // How the current place behind Ask was opened (a More row, a Today card): its title travels from there and back.
    var arrival by rememberSaveable { mutableStateOf<String?>(null) }
    val go: (ShellDestination) -> Unit = { d ->
        if (d != current) { haptics.tick(); arrival = SharedMotion.arrivalAfterGo(d, arrival); current = d }
    }
    val openPlace: (ShellDestination, PlaceVia) -> Unit = { d, via ->
        if (d != current) { haptics.tick(); arrival = SharedMotion.placeKey(d, via); current = d }
    }
    val states = rememberSaveableStateHolder()
    // Work mode and Today move with the clock: re-evaluate every half minute while the app is on screen.
    LaunchedEffect(core) {
        while (true) { delay(30_000); core.tick() }
    }
    // Tapping the after-work nudge lands on Needs you, which then opens the summary.
    val app = LocalContext.current.applicationContext as MekaApplication
    val openAfterWork by app.openAfterWork.collectAsState()
    LaunchedEffect(openAfterWork) { if (openAfterWork) current = ShellDestination.NEEDS_YOU }
    // The News widget's tap lands on Today, which then opens News on the story.
    val openNewsStory by app.openNewsStory.collectAsState()
    LaunchedEffect(openNewsStory) {
        if (openNewsStory != null && current != ShellDestination.TODAY) {
            arrival = SharedMotion.arrivalAfterGo(ShellDestination.TODAY, arrival); current = ShellDestination.TODAY
        }
    }
    // Dismissing the wake alarm lands on Today, which then springs the brief up.
    val openBrief by app.openBrief.collectAsState()
    LaunchedEffect(openBrief) {
        if (openBrief && current != ShellDestination.TODAY) {
            arrival = SharedMotion.arrivalAfterGo(ShellDestination.TODAY, arrival); current = ShellDestination.TODAY
        }
    }
    // "Plan my day" said to Talk lands on Today, which then springs the planner up.
    val openPlan by app.openPlan.collectAsState()
    // "Shut down the day" said to Talk lands on Today too, which then opens Shut down the day.
    val openShutdown by app.openShutdown.collectAsState()
    LaunchedEffect(openPlan, openShutdown) {
        if ((openPlan || openShutdown) && current != ShellDestination.TODAY) {
            arrival = SharedMotion.arrivalAfterGo(ShellDestination.TODAY, arrival); current = ShellDestination.TODAY
        }
    }
    // Tapping a MEKA notification lands where it belongs (a digest on Needs you, a cancel-by date on Lists…).
    val openDestination by app.openDestination.collectAsState()
    LaunchedEffect(openDestination) {
        openDestination?.let { arrival = SharedMotion.arrivalAfterGo(it, arrival); current = it; app.openDestination.value = null }
    }

    // "Hello, Navilife" slice 5: the docked orb in the bar says hello from anywhere (Ask comes forward and greets), or
    // takes him back to the conversation that's running.
    val talkPhase by app.talkPhase.collectAsState()
    // Slice 7, the entrance: a tap at rest plays the big entrance over the shell, which greets once the points re-form
    // (and stays as the conversation's stage); the first open of the morning plays the short one, once.
    var entrance by remember { mutableStateOf<EntranceKind?>(null) }
    var dockAt by remember { mutableStateOf(Offset.Unspecified) }
    val dockTap: () -> Unit = {
        if (HelloDock.tap(talkPhase) == DockTap.HELLO && entrance == null) { app.talkAnswerCards.value = emptyList(); app.talkNeedsAsk.value = false; app.helloGreeting.value = null; entrance = EntranceKind.BIG }
        if (current != ShellDestination.ASK) { arrival = SharedMotion.arrivalAfterGo(ShellDestination.ASK, arrival); current = ShellDestination.ASK }
    }
    // Speaking up: while Obi says a moment on his own, the docked orb shows Speaking (a tap still says hello as ever).
    val speakingUp by app.speakUp.speaking.collectAsState()
    val dockPhase = if (speakingUp && talkPhase == TalkPhase.ENDED) TalkPhase.SPEAKING else talkPhase
    val dock: @Composable (Modifier) -> Unit = { m -> DockedHelloOrb(dockPhase, needsYou, { if (speakingUp) app.speakUp.stop(); dockTap() }, m, onPlaced = { dockAt = it }) }
    val shellContext = LocalContext.current
    val reducedShell = Meka.reducedMotion
    // Obi's face, slice 3: keep the face's files on this phone (asked about weekly; nothing shows until all seven are here).
    LaunchedEffect(Unit) { os.meka.android.ask.ObiFaceStore.refresh(shellContext, core) }
    // Slice 3b: while the face is drawn, Obi's clips come with their mouth sounds (so his lips follow each word).
    LaunchedEffect(Unit) {
        os.meka.android.ask.ObiFaceStore.load(shellContext)
        kotlinx.coroutines.flow.combine(os.meka.android.ask.ObiFaceStore.ready, os.meka.android.ask.ObiFaceStore.on) { ready, on ->
            os.meka.core.domain.FaceFileRules.shows(ready, on)
        }.collect { core.speechFaceMarks(it) }
    }
    LaunchedEffect(Unit) {
        val prefs = shellContext.getSharedPreferences(HELLO_PREFS, android.content.Context.MODE_PRIVATE)
        val last = prefs.getLong(MORNING_KEY, -1L).takeIf { it >= 0 }
        if (core.morningEntrancePlays(last, reducedShell)) {
            prefs.edit().putLong(MORNING_KEY, core.todayEpochDay()).apply()
            delay(350) // after the shell's own opening has begun
            if (entrance == null && app.talkPhase.value == TalkPhase.ENDED) entrance = EntranceKind.SHORT
        }
    }

    // A place reached from Ask's More list sits behind Ask: back returns there.
    BackHandler(enabled = ShellNav.parent(current) != null) { ShellNav.parent(current)?.let(go) }

    // Fold modes: half folded on a table, the bedside clock takes the screen; opening flat cross-fades back to the
    // app where it was (each destination keeps its state in [states]).
    val fold = rememberFoldState()
    val mode = FoldModeRules.mode(LocalConfiguration.current.screenWidthDp.toFloat(), fold.posture)
    val reducedMode = Meka.reducedMotion
    // Bedside clock slice 1: also opened by hand (Ask → Daily, Talk); ‹ Back on a table stand steps out of it until the
    // Fold stops standing.
    val autoBedside = mode == FoldMode.BEDSIDE
    val manualBedside by app.openBedside.collectAsState()
    var bedsideDismissed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoBedside) { bedsideDismissed = BedsideEntryRules.dismissedAfterPosture(autoBedside, bedsideDismissed) }
    val leaveBedside = {
        bedsideDismissed = BedsideEntryRules.dismissedAfterBack(autoBedside)
        app.openBedside.value = false
    }
    AnimatedContent(
        targetState = BedsideEntryRules.shows(autoBedside, manualBedside, bedsideDismissed),
        transitionSpec = { fadeIn(MekaMotion.appear(reducedMode)) togetherWith fadeOut(MekaMotion.appear(reducedMode)) },
        label = "fold-mode",
    ) { bedside ->
    // Speaking up: the bedside clock in the evening counts as "at home".
    DisposableEffect(bedside) { if (bedside) app.speakUp.bedsideShowing = true; onDispose { if (bedside) app.speakUp.bedsideShowing = false } }
    if (bedside) BedsideClock(core, fold, onBack = leaveBedside) else
    // One shared-transition layout for the whole shell: titles travel within a screen and, from cards and More rows,
    // across the shell's slide into the place they open (Four tabs, slice 3).
    MekaSharedLayout(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background).safeDrawingPadding()) {
        val layout = ShellNav.layoutFor(maxWidth.value)
        val content: @Composable (Modifier) -> Unit = { m ->
            DestinationHost(current, arrival, m) { d -> states.SaveableStateProvider(d.name) { Destination(d, core, connect, go, openPlace) } }
        }
        when (layout) {
            ShellLayout.RAIL -> Row(Modifier.fillMaxSize()) {
                Rail(current, needsYou, go, dock)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Meka.colors.hairline))
                content(Modifier.weight(1f).fillMaxHeight())
            }
            ShellLayout.BOTTOM_BAR -> Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                // The keyboard belongs to capture: the bar steps aside while it's up.
                val reduced = Meka.reducedMotion
                AnimatedVisibility(
                    visible = !WindowInsets.isImeVisible,
                    enter = if (reduced) fadeIn(MekaMotion.appear(true)) else slideInVertically(MekaMotion.expand(false)) { it } + fadeIn(MekaMotion.appear(false)),
                    exit = if (reduced) fadeOut(MekaMotion.appear(true)) else slideOutVertically(MekaMotion.expand(false)) { it } + fadeOut(MekaMotion.appear(false)),
                ) { BottomBar(current, needsYou, go, dock) }
            }
        }
        entrance?.let { kind ->
            val said by app.talkSaid.collectAsState()
            val heard by app.talkHeard.collectAsState()
            val level by app.talkLevel.collectAsState()
            val greeting by app.helloGreeting.collectAsState()
            val answerCards by app.talkAnswerCards.collectAsState()
            val cardsDone by app.talkCardsDone.collectAsState()
            val needsAsk by app.talkNeedsAsk.collectAsState()
            key(kind) {
                HelloStage(
                    kind, dockAt, talkPhase, level, heard, said, needsYou,
                    cards = StageCards(greeting, answerCards, cardsDone, needsAsk),
                    onGreet = { app.talkNow.value = TalkStart.HELLO },
                    onStop = { app.talkStopNow.value = true },
                    onClosed = { entrance = null },
                    // Slice 8: a greeting card opens its screen (the stage shrinks back); an answer card is done on Ask.
                    onOpen = { open ->
                        val d = when (open) {
                            HelloOpen.NEEDS_YOU -> ShellDestination.NEEDS_YOU
                            HelloOpen.CALENDAR -> ShellDestination.CALENDAR
                            HelloOpen.COMMUTE, HelloOpen.TODAY -> ShellDestination.TODAY
                        }
                        app.openDestination.value = d
                    },
                    onCard = { app.talkCardTap.value = it },
                )
            }
        }
    }
    }
    }
}

@Composable
private fun DestinationHost(current: ShellDestination, arrival: String?, modifier: Modifier, body: @Composable (ShellDestination) -> Unit) {
    val reduced = Meka.reducedMotion
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            val dir = ShellNav.direction(initialState, targetState)
            if (reduced || dir == 0) fadeIn(MekaMotion.replan(true)) togetherWith fadeOut(MekaMotion.replan(true))
            else (slideInHorizontally(MekaMotion.replan(false)) { dir * it / 8 } + fadeIn(MekaMotion.appear(false))) togetherWith
                (slideOutHorizontally(MekaMotion.replan(false)) { -dir * it / 8 } + fadeOut(MekaMotion.appear(false)))
        },
        label = "shell",
        modifier = modifier,
    ) { d ->
        // Each destination's keys are its own; a place's title wears the key of the row or card it was opened from.
        CompositionLocalProvider(
            LocalShellContent provides this,
            LocalSharedKeyPrefix provides d.name + ":",
            LocalPlaceTitleKey provides SharedMotion.placeTitleKey(d, arrival),
        ) { body(d) }
    }
}

@Composable
private fun Destination(
    d: ShellDestination, core: MekaCore, connect: ConnectHook?, go: (ShellDestination) -> Unit,
    openPlace: (ShellDestination, PlaceVia) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MekaApplication
    val openItem: (OpenItem) -> Unit = { item ->
        // Lists or Goals picks the item up when it appears (tab and unfolded row).
        app.openItem.value = item
        SearchNav.destination(item.target)?.let(go)
    }
    when (d) {
        ShellDestination.TODAY -> TodayRoute(
            core, connect, openReview = { openPlace(ShellDestination.REVIEW, PlaceVia.CARD) }, openItem = openItem,
            openLists = { go(ShellDestination.LISTS) }, openCalendar = { go(ShellDestination.CALENDAR) },
        )
        ShellDestination.NEEDS_YOU -> NeedsYouRoute(core, openLists = { go(ShellDestination.LISTS) })
        ShellDestination.CALENDAR -> CalendarRoute(core)
        ShellDestination.ASK -> AskRoute(
            core, connected = connect == null, openPlace = { openPlace(it, PlaceVia.MORE) }, openItem = openItem,
            playOpening = { app.playOpening.value = true; go(ShellDestination.TODAY) },
        )
        ShellDestination.LISTS -> BehindAsk(go) { ListsRoute(core) }
        ShellDestination.GOALS -> BehindAsk(go) { GoalsRoute(core) }
        ShellDestination.REVIEW -> BehindAsk(go) { ReviewRoute(core) }
        // The Vault: the documents and when they run out (V2, slice 1), then the export of everything (build plan M1).
        ShellDestination.VAULT -> BehindAsk(go) {
            YourData(core, vaultLine = os.meka.core.domain.DocumentRules.VAULT_LINE)
        }
    }
}

/** A place reached from Ask's More list: a quiet "‹ Ask" above it (back does the same). */
@Composable
private fun BehindAsk(go: (ShellDestination) -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "‹ Ask", style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.padding(start = MekaSpace.gutter - MekaSpace.xxs, top = MekaSpace.xs)
                .clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button) { go(ShellDestination.ASK) }
                .clearAndSetSemantics { contentDescription = "Back to Ask" }
                .padding(horizontal = MekaSpace.xxs, vertical = MekaSpace.xxs),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
    }
}

/**
 * Closed Fold: the four tabs with the docked Hello orb in the middle (Today · Needs you · ◉ · Calendar · Ask; labels
 * shrink a little if a narrow screen needs it), a pill springs to the lit tab. The orb sits a little above the bar's line.
 */
@Composable
private fun BottomBar(current: ShellDestination, needsYou: Int, go: (ShellDestination) -> Unit, dock: @Composable (Modifier) -> Unit) {
    val items = ShellNav.destinations(ShellLayout.BOTTOM_BAR)
    val lit = ShellNav.barSelection(current)
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(Meka.colors.surface).padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
    ) {
        val slot = maxWidth / HelloDock.SLOTS
        val x by animateDpAsState(slot * HelloDock.slot(items.indexOf(lit)), MekaMotion.replan(Meka.reducedMotion), label = "bar-pill")
        Box(Modifier.offset(x = x).width(slot).height(BAR_ITEM_HEIGHT).padding(horizontal = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, d ->
                if (i == HelloDock.SLOT) {
                    Box(Modifier.weight(1f).height(BAR_ITEM_HEIGHT), contentAlignment = Alignment.Center) {
                        dock(Modifier.offset(y = -HelloDock.RAISE.dp))
                    }
                }
                NavItem(d, d == lit, needsYou, Modifier.weight(1f).height(BAR_ITEM_HEIGHT), go, fit = true)
            }
        }
    }
}

/** Open Fold: a quiet vertical rail; the lit pill springs between rows. */
@Composable
private fun Rail(current: ShellDestination, needsYou: Int, go: (ShellDestination) -> Unit, dock: @Composable (Modifier) -> Unit) {
    val items = ShellNav.destinations(ShellLayout.RAIL)
    val rowHeight = 60.dp
    Box(Modifier.width(112.dp).fillMaxHeight().background(Meka.colors.surface).padding(vertical = MekaSpace.xl, horizontal = MekaSpace.xs)) {
        val lit = ShellNav.barSelection(current)
        val y by animateDpAsState(rowHeight * HelloDock.slot(items.indexOf(lit)), MekaMotion.replan(Meka.reducedMotion), label = "rail-pill")
        Box(Modifier.offset(y = y).fillMaxWidth().height(rowHeight).padding(vertical = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised))
        Column(verticalArrangement = Arrangement.Top) {
            items.forEachIndexed { i, d ->
                // "Hello, Navilife" slice 5: the docked orb between Needs you and Calendar, as in the closed Fold's bar.
                if (i == HelloDock.SLOT) {
                    Box(Modifier.fillMaxWidth().height(rowHeight).appear(rememberAppearance(i)), contentAlignment = Alignment.Center) {
                        dock(Modifier)
                    }
                }
                NavItem(d, d == lit, needsYou, Modifier.fillMaxWidth().height(rowHeight).appear(rememberAppearance(HelloDock.slot(i))), go)
            }
        }
    }
}

/** A tab in the bar or rail: its icon over its label (the icon morphs outline → filled as it lights). */
private val BAR_ITEM_HEIGHT = 56.dp

@Composable
private fun NavItem(d: ShellDestination, lit: Boolean, needsYou: Int, modifier: Modifier, go: (ShellDestination) -> Unit, fit: Boolean = false) {
    val color by animateColorAsState(if (lit) Meka.colors.textPrimary else Meka.colors.textTertiary, MekaMotion.appear(Meka.reducedMotion), label = "nav-text")
    // Calm badge: the count in the critical colour beside the label, no filled blob.
    val badge = if (d == ShellDestination.NEEDS_YOU) ShellNav.badge(needsYou) else null
    val iconColor by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.appear(Meka.reducedMotion), label = "nav-icon")
    Column(
        modifier
            .clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Tab) { go(d) }
            .clearAndSetSemantics { contentDescription = ShellNav.accessibilityLabel(d, needsYou); selected = lit },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShellNav.glyph(d)?.let { TabIcon(it, lit, iconColor, knockout = Meka.colors.surfaceRaised) }
        Spacer(Modifier.height(2.dp)) // rhythm: ok (the tab icon's gap to its label, one mark)
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                d.label, style = MekaType.caption.copy(color = color), maxLines = 1, softWrap = false,
                // The tabs share the closed Fold's width: step down to 10 sp rather than clip "Needs you".
                autoSize = if (fit) TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MekaType.caption.fontSize, stepSize = 0.5.sp) else null, // type: ok (shrinks to fit, never below 10)
                modifier = if (fit) Modifier.weight(1f, fill = false) else Modifier,
            )
            AnimatedVisibility(badge != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
                BasicText(
                    badge.orEmpty(), maxLines = 1, softWrap = false,
                    style = MekaType.captionStrong.copy(color = Meka.colors.critical),
                    modifier = Modifier.padding(start = 3.dp), // rhythm: ok (the badge sits against its label)
                )
            }
        }
    }
}

/** The short morning entrance's day, kept on this phone ("Hello, Navilife" slice 7). */
private const val HELLO_PREFS = "meka_hello"
private const val MORNING_KEY = "morning_entrance_day"
