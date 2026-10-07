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
import androidx.compose.ui.text.font.FontWeight
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
import os.meka.core.domain.FoldMode
import os.meka.core.domain.FoldModeRules
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import os.meka.core.facade.MekaCore
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
    // Due chases and decision reviews wait on you too, so they count in the badge.
    val needsYou = today.needsYou.size + lists.dueCount
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
    // Tapping a MEKA notification lands where it belongs (a digest on Needs you, a cancel-by date on Lists…).
    val openDestination by app.openDestination.collectAsState()
    LaunchedEffect(openDestination) {
        openDestination?.let { arrival = SharedMotion.arrivalAfterGo(it, arrival); current = it; app.openDestination.value = null }
    }

    // A place reached from Ask's More list sits behind Ask: back returns there.
    BackHandler(enabled = ShellNav.parent(current) != null) { ShellNav.parent(current)?.let(go) }

    // Fold modes: half folded on a table, the bedside clock takes the screen; opening flat cross-fades back to the
    // app where it was (each destination keeps its state in [states]).
    val fold = rememberFoldState()
    val mode = FoldModeRules.mode(LocalConfiguration.current.screenWidthDp.toFloat(), fold.posture)
    val reducedMode = Meka.reducedMotion
    AnimatedContent(
        targetState = mode == FoldMode.BEDSIDE,
        transitionSpec = { fadeIn(MekaMotion.appear(reducedMode)) togetherWith fadeOut(MekaMotion.appear(reducedMode)) },
        label = "fold-mode",
    ) { bedside ->
    if (bedside) BedsideClock(core, fold) else
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
                Rail(current, needsYou, go)
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
                ) { BottomBar(current, needsYou, go) }
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
        ShellDestination.ASK -> AskRoute(core, connected = connect == null, openPlace = { openPlace(it, PlaceVia.MORE) }, openItem = openItem)
        ShellDestination.LISTS -> BehindAsk(go) { ListsRoute(core) }
        ShellDestination.GOALS -> BehindAsk(go) { GoalsRoute(core) }
        ShellDestination.REVIEW -> BehindAsk(go) { ReviewRoute(core) }
        // Documents land here in V2; until then the Vault holds the export of everything (build plan M1).
        ShellDestination.VAULT -> BehindAsk(go) {
            YourData(core, vaultLine = "Encrypted documents, with expiry dates sent to your plan, land here.")
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

/** Closed Fold: the four tabs (labels shrink a little if a narrow screen needs it), a pill springs to the lit one. */
@Composable
private fun BottomBar(current: ShellDestination, needsYou: Int, go: (ShellDestination) -> Unit) {
    val items = ShellNav.destinations(ShellLayout.BOTTOM_BAR)
    val lit = ShellNav.barSelection(current)
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(Meka.colors.surface).padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
    ) {
        val slot = maxWidth / items.size
        val x by animateDpAsState(slot * items.indexOf(lit), MekaMotion.replan(Meka.reducedMotion), label = "bar-pill")
        Box(Modifier.offset(x = x).width(slot).height(44.dp).padding(horizontal = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised))
        Row(Modifier.fillMaxWidth()) {
            items.forEach { d ->
                NavItem(d, d == lit, needsYou, Modifier.weight(1f).height(44.dp), go, fit = true)
            }
        }
    }
}

/** Open Fold: a quiet vertical rail; the lit pill springs between rows. */
@Composable
private fun Rail(current: ShellDestination, needsYou: Int, go: (ShellDestination) -> Unit) {
    val items = ShellNav.destinations(ShellLayout.RAIL)
    val rowHeight = 48.dp
    Box(Modifier.width(112.dp).fillMaxHeight().background(Meka.colors.surface).padding(vertical = MekaSpace.xl, horizontal = MekaSpace.xs)) {
        val lit = ShellNav.barSelection(current)
        val y by animateDpAsState(rowHeight * items.indexOf(lit), MekaMotion.replan(Meka.reducedMotion), label = "rail-pill")
        Box(Modifier.offset(y = y).fillMaxWidth().height(rowHeight).padding(vertical = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised))
        Column(verticalArrangement = Arrangement.Top) {
            items.forEachIndexed { i, d ->
                NavItem(d, d == lit, needsYou, Modifier.fillMaxWidth().height(rowHeight).appear(rememberAppearance(i)), go)
            }
        }
    }
}

@Composable
private fun NavItem(d: ShellDestination, lit: Boolean, needsYou: Int, modifier: Modifier, go: (ShellDestination) -> Unit, fit: Boolean = false) {
    val color by animateColorAsState(if (lit) Meka.colors.textPrimary else Meka.colors.textTertiary, MekaMotion.appear(Meka.reducedMotion), label = "nav-text")
    // Calm badge: the count in the critical colour beside the label, no filled blob.
    val badge = if (d == ShellDestination.NEEDS_YOU) ShellNav.badge(needsYou) else null
    Row(
        modifier
            .clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Tab) { go(d) }
            .clearAndSetSemantics { contentDescription = ShellNav.accessibilityLabel(d, needsYou); selected = lit },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            d.label, style = MekaType.caption.copy(color = color), maxLines = 1, softWrap = false,
            // The tabs share the closed Fold's width: step down to 10 sp rather than clip "Needs you".
            autoSize = if (fit) TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MekaType.caption.fontSize, stepSize = 0.5.sp) else null,
            modifier = if (fit) Modifier.weight(1f, fill = false) else Modifier,
        )
        AnimatedVisibility(badge != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
            BasicText(
                badge.orEmpty(), maxLines = 1, softWrap = false,
                style = MekaType.caption.copy(color = Meka.colors.critical, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}
