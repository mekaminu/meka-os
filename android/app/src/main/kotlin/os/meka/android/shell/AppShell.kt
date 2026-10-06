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
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.ListsRoute
import os.meka.android.goals.GoalsRoute
import os.meka.android.today.ConnectHook
import os.meka.android.today.NeedsYouRoute
import os.meka.android.today.TodayRoute
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
    val go: (ShellDestination) -> Unit = { d -> if (d != current) { haptics.tick(); current = d } }
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
        openDestination?.let { current = it; app.openDestination.value = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background).safeDrawingPadding()) {
        val layout = ShellNav.layoutFor(maxWidth.value)
        val content: @Composable (Modifier) -> Unit = { m ->
            DestinationHost(current, m) { d -> states.SaveableStateProvider(d.name) { Destination(d, core, connect, go) } }
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

@Composable
private fun DestinationHost(current: ShellDestination, modifier: Modifier, body: @Composable (ShellDestination) -> Unit) {
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
    ) { d -> body(d) }
}

@Composable
private fun Destination(d: ShellDestination, core: MekaCore, connect: ConnectHook?, go: (ShellDestination) -> Unit) {
    when (d) {
        ShellDestination.TODAY -> TodayRoute(core, connect)
        ShellDestination.NEEDS_YOU -> NeedsYouRoute(core, openLists = { go(ShellDestination.LISTS) })
        ShellDestination.LISTS -> ListsRoute(core)
        ShellDestination.GOALS -> GoalsRoute(core)
        ShellDestination.REVIEW -> Upcoming(d, "Your weekly review and north-star numbers land here.")
        ShellDestination.VAULT -> Upcoming(d, "Encrypted documents, with expiry dates sent to your plan, land here.")
    }
}

/** A calm placeholder for destinations whose feature hasn't landed yet: says what's coming, nothing to tap. */
@Composable
private fun Upcoming(d: ShellDestination, line: String) {
    Column(Modifier.fillMaxSize().padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl)) {
        Text(d.label, style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(0)))
        Spacer(Modifier.height(MekaSpace.m))
        Text(line, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
    }
}

/** Closed Fold: five labels, a pill springs to the lit one. */
@Composable
private fun BottomBar(current: ShellDestination, needsYou: Int, go: (ShellDestination) -> Unit) {
    val items = ShellNav.destinations(ShellLayout.BOTTOM_BAR)
    val lit = ShellNav.barSelection(current, ShellLayout.BOTTOM_BAR)
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(Meka.colors.surface).padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
    ) {
        val slot = maxWidth / items.size
        val x by animateDpAsState(slot * (lit?.let { items.indexOf(it) } ?: 0), MekaMotion.replan(Meka.reducedMotion), label = "bar-pill")
        AnimatedVisibility(lit != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
            Box(Modifier.offset(x = x).width(slot).height(44.dp).padding(horizontal = MekaSpace.xxs)
                .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised))
        }
        Row(Modifier.fillMaxWidth()) {
            items.forEach { d ->
                NavItem(d, d == current, needsYou, Modifier.weight(1f).height(44.dp), go)
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
        val y by animateDpAsState(rowHeight * items.indexOf(current), MekaMotion.replan(Meka.reducedMotion), label = "rail-pill")
        Box(Modifier.offset(y = y).fillMaxWidth().height(rowHeight).padding(vertical = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised))
        Column(verticalArrangement = Arrangement.Top) {
            items.forEachIndexed { i, d ->
                NavItem(d, d == current, needsYou, Modifier.fillMaxWidth().height(rowHeight).appear(rememberAppearance(i)), go)
            }
        }
    }
}

@Composable
private fun NavItem(d: ShellDestination, lit: Boolean, needsYou: Int, modifier: Modifier, go: (ShellDestination) -> Unit) {
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
        BasicText(d.label, style = MekaType.caption.copy(color = color), maxLines = 1, softWrap = false)
        AnimatedVisibility(badge != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
            BasicText(
                badge.orEmpty(), maxLines = 1, softWrap = false,
                style = MekaType.caption.copy(color = Meka.colors.critical, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
}
