package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.TouchPill
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.TeamAgent
import os.meka.core.domain.TeamAskRules
import os.meka.core.domain.TeamCard
import os.meka.core.domain.TeamRules
import os.meka.core.domain.TeamSimplerRules
import os.meka.core.domain.TeamWaitRow
import os.meka.core.domain.TeamTarget
import os.meka.core.domain.TeamTile
import os.meka.core.domain.TeamView
import os.meka.core.facade.MekaCore

const val TEAM_PANE_TAG = "team-pane"
const val TEAM_ASK_TAG = "team-ask-bar"
const val TEAM_ASK_MIC_TAG = "team-ask-mic"

/**
 * Ask → Daily → Your team (slice 1; mockup `docs/design/routines/team.dc.html`): WORKING · NEED YOU · DONE TODAY tiles,
 * a card per helper (its icon in its colour, name and role, a status with a dot that breathes while it is working or
 * watching, TODAY and WAITS ON YOU side by side, the wait box lit when something waits), the foot line, and a tap on a
 * card raises its sheet: name and how much it may do by itself, TODAY's log, what waits with Review (Needs you or
 * Money), Close. The words are the core's [TeamRules.view], the same as the Mac's sheet. Nothing here acts.
 * Slice 3: **Ask the team…** at the foot (the Money bar's look): a tap closes the pane into Ask's field, the mic into
 * Obi already listening, and for a few minutes what's asked carries the team in full ([TeamAskRules]).
 * **Team, simpler** (approved 2026-11-11 21:21, `docs/design/routines/team-simpler.dc.html`): the three small number
 * tiles, one **Waiting on you** box (only what needs him, a row each, a tap opens it; hidden when nothing waits) and one
 * **Helpers** box, a line per helper (icon, name, status dot, what it did today) that unfolds in place to its role, its
 * last three things and Open · Pause · Whole day › (Whole day raises the sheet with the day's log). Rows unfold on the
 * expand spring with the chevron turning; reduced motion cross-fades.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the tiles, then the cards, stagger in
 * 40 ms apart; a card's tap gives a tick haptic and the sheet rises on the expand spring over a fading scrim; the live
 * dot breathes on the frame clock. Reduced motion: cross-fades and a still dot.
 */
@Composable
fun TeamPane(
    core: MekaCore, onClose: () -> Unit, onReview: (TeamTarget) -> Unit,
    /** Slice 3: Ask the team… — Obi in Ask's field ([listen]: already listening), the team in what he sees. */
    onAsk: (listen: Boolean) -> Unit = {},
) {
    val haptics = rememberMekaHaptics()
    var view by remember { mutableStateOf<TeamView?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    /** "Team, simpler": the helper whose row is unfolded (one at a time), by its code. */
    var unfolded by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The day's log grows as the helpers work and sync brings the other device's in: read again while open.
    LaunchedEffect(Unit) {
        while (true) {
            view = runCatching { core.teamView() }.getOrNull() ?: view
            delay(REFRESH_MS)
        }
    }
    Box(Modifier.fillMaxSize().testTag(TEAM_PANE_TAG)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            Text(MoreItem.TEAM.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.TEAM)).appear(rememberAppearance(0)))
            val v = view ?: return@Column
            Text(LoneWordGlue.kept(v.subtitle), style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.appear(rememberAppearance(0)))
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = MekaSpace.xs).appear(rememberAppearance(1)),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                v.tiles.forEach { Tile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
            // "Team, simpler": one Waiting on you box (hidden when nothing waits), then one Helpers box.
            val waiting = v.waiting
            if (waiting.isNotEmpty()) {
                WaitingBox(waiting, Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2))) { row ->
                    haptics.tick(); onReview(row.target)
                }
            }
            HelpersBox(
                v.cards, unfolded, Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(3)),
                onToggle = { c -> haptics.tick(); unfolded = if (unfolded == c.agent.code) null else c.agent.code },
                onOpen = { c -> haptics.tick(); onOpenHelper(c, onReview, { scope.launch { runCatching { core.askTeamStarted() }; onAsk(false) } }) },
                onPause = { c ->
                    haptics.light()
                    scope.launch { view = runCatching { core.setTeamPause(c.agent.code, !c.paused) }.getOrNull() ?: view }
                },
                onWholeDay = { c -> haptics.tick(); open = c.agent.code },
            )
            // Slice 3: Ask the team… talks to Obi: Ask's field (or the mic, listening), with the team in what he sees.
            MoneyAskBar(
                Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(4)),
                onMic = { haptics.light(); scope.launch { runCatching { core.askTeamStarted() }; onAsk(true) } },
                text = TeamAskRules.BAR, spoken = TeamAskRules.BAR_LABEL, micSpoken = TeamAskRules.MIC_LABEL,
                tag = TEAM_ASK_TAG, micTag = TEAM_ASK_MIC_TAG,
            ) { haptics.tick(); scope.launch { runCatching { core.askTeamStarted() }; onAsk(false) } }
            Text(LoneWordGlue.kept(TeamRules.FOOT), style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(5)))
        }
        AgentSheet(
            card = open?.let { code -> view?.card(code) },
            onClose = { open = null },
            onReview = { target -> haptics.tick(); open = null; onReview(target) },
            onPause = { c ->
                haptics.light()
                scope.launch { view = runCatching { core.setTeamPause(c.agent.code, !c.paused) }.getOrNull() ?: view }
            },
        )
    }
}

/** How often the open pane reads the team again. */
private const val REFRESH_MS = 20_000L

/** Each helper's colour (the mockup's tints, from the theme so Light reads too). */
@Composable
private fun tint(a: TeamAgent): Color = when (a) {
    TeamAgent.OBI -> Meka.colors.accent
    TeamAgent.INBOX -> Meka.colors.approval
    TeamAgent.CALLS -> Meka.colors.success
    TeamAgent.MONEY -> Meka.colors.money2
    TeamAgent.KESTREL -> Meka.colors.calendar3
}

/** The mockup's icons, 24-unit outlines. */
private fun iconPath(a: TeamAgent): String = when (a) {
    TeamAgent.OBI -> "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M3 12h18M12 3c3 3 3 15 0 18"
    TeamAgent.INBOX -> "M3 6h18v12H3zM3 7l9 6 9-6"
    TeamAgent.CALLS -> "M5 4h4l2 5-3 2a11 11 0 0 0 5 5l2-3 5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"
    TeamAgent.MONEY -> "M4 7h16v10H4zM8 12h.01M16 12h.01M12 10a2 2 0 1 0 0 4a2 2 0 1 0 0-4"
    TeamAgent.KESTREL -> "M3 17l6-6 4 4 8-8M15 7h6v6"
}

@Composable
private fun AgentIcon(a: TeamAgent) {
    val ink = tint(a)
    val path = remember(a) { PathParser().parsePathString(iconPath(a)).toPath() }
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(MekaRadius.s)).background(ink.copy(alpha = 0.12f)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                drawPath(path, ink, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** The status dot: breathes on the frame clock while live (still with reduced motion). Decorative. */
@Composable
private fun StatusDot(live: Boolean) {
    val reduced = Meka.reducedMotion
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(live, reduced) {
        if (!live || reduced) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsed = it - start }
    }
    val ink = if (live) Meka.colors.success else Meka.colors.textTertiary
    Canvas(Modifier.size(8.dp).clearAndSetSemantics { }) {
        val glow = if (live) MotionMath.breathGlow(MotionMath.breath(elapsed, reduced)) else 1f
        drawCircle(ink.copy(alpha = ink.alpha * (0.35f + 0.65f * glow)))
    }
}

@Composable
private fun Tile(t: TeamTile, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = t.spoken }.testTag("team-tile"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        // "Team, simpler": the number over its small label.
        Text(t.value, style = MekaType.itemTitle, color = if (t.lit) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1)
        Text(t.label, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1)
    }
}

/** Where a helper's Open goes: Ask's field for Obi (with the team in what he sees), else its place. */
private fun onOpenHelper(c: TeamCard, onReview: (TeamTarget) -> Unit, onAskField: () -> Unit) {
    if (c.openTarget == TeamTarget.ASK) onAskField() else onReview(c.openTarget)
}

/** A box on the team screen: the raised surface with its title row ("Waiting on you" and its count, or "Helpers"). */
@Composable
private fun TeamBox(title: String, modifier: Modifier, trailing: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.m).padding(top = MekaSpace.m, bottom = MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
            trailing()
        }
        content()
    }
}

/** A hairline between a box's rows. */
@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Meka.colors.surface))
}

/** "Waiting on you": only what needs him, one row each; a tap opens it (a trade's goes to Needs you). */
@Composable
private fun WaitingBox(rows: List<TeamWaitRow>, modifier: Modifier, onOpen: (TeamWaitRow) -> Unit) {
    TeamBox(TeamSimplerRules.WAITING, modifier.testTag("team-waiting"), trailing = {
        Text(rows.size.toString(), style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = MekaSpace.xs, vertical = 2.dp).clearAndSetSemantics { })
    }) {
        rows.forEach { w ->
            Hairline()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onOpen(w) }
                    .semantics(mergeDescendants = true) { contentDescription = w.spoken }.testTag("team-wait-row"),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentIcon(w.agent)
                Column(Modifier.weight(1f).padding(vertical = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(LoneWordGlue.kept(w.what), style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(LoneWordGlue.kept(w.who), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text("${w.cta} ›", style = MekaType.caption, color = Meka.colors.accent, maxLines = 1)
            }
        }
    }
}

/** "Helpers": one line per helper; a tap unfolds its role, its last three things and Open · Pause · Whole day ›. */
@Composable
private fun HelpersBox(
    cards: List<TeamCard>, unfolded: String?, modifier: Modifier,
    onToggle: (TeamCard) -> Unit, onOpen: (TeamCard) -> Unit, onPause: (TeamCard) -> Unit, onWholeDay: (TeamCard) -> Unit,
) {
    val reduced = Meka.reducedMotion
    TeamBox(TeamSimplerRules.HELPERS, modifier.testTag("team-helpers"), trailing = {
        Text(TeamSimplerRules.HELPERS_HINT, style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
    }) {
        cards.forEach { c ->
            val isOpen = unfolded == c.agent.code
            Hairline()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onToggle(c) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = c.rowSpoken
                        stateDescription = if (isOpen) "Unfolded" else "Folded"
                    }.testTag("team-card"),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentIcon(c.agent)
                Column(Modifier.weight(1f).padding(vertical = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, maxLines = 1)
                        StatusDot(c.status.live)
                        Text(c.status.label, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val turn by animateFloatAsState(if (isOpen) 180f else 0f, MekaMotion.expand(reduced), label = "team-chevron")
                Chevron(Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(
                isOpen,
                enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 34.dp + MekaSpace.xs, bottom = MekaSpace.xs).testTag("team-unfolded"),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) {
                    Text(LoneWordGlue.kept(c.roleLine), style = MekaType.caption, color = Meka.colors.textTertiary)
                    if (c.recent.isEmpty()) {
                        Text(LoneWordGlue.kept(TeamRules.LOG_EMPTY), style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                    c.recent.forEach { l ->
                        Row(Modifier.fillMaxWidth().testTag("team-recent"), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            Text(l.time, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, modifier = Modifier.widthIn(min = 40.dp))
                            Text(LoneWordGlue.kept(l.text), style = MekaType.caption, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(Modifier.padding(top = MekaSpace.xxs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        TouchPill(c.openLabel, Meka.colors.surface, Meka.colors.accent, spoken = "${c.openLabel}: ${c.title}",
                            pad = MekaSpace.s, modifier = Modifier.testTag("team-open")) { onOpen(c) }
                        c.pauseShort?.let { p ->
                            TouchPill(p, Meka.colors.surface, Meka.colors.accent, spoken = "$p: ${c.title}",
                                pad = MekaSpace.s, modifier = Modifier.testTag("team-pause-short")) { onPause(c) }
                        }
                        TouchPill("${TeamSimplerRules.WHOLE_DAY} ›", Color.Transparent, Meka.colors.accent,
                            spoken = "${TeamSimplerRules.WHOLE_DAY}: ${c.title}", pad = MekaSpace.s,
                            modifier = Modifier.testTag("team-whole-day")) { onWholeDay(c) }
                    }
                }
            }
        }
    }
}

/** The row's chevron, turning half a turn as it unfolds. Decorative. */
@Composable
private fun Chevron(modifier: Modifier) {
    val ink = Meka.colors.textSecondary
    Canvas(modifier.size(17.dp).clearAndSetSemantics { }) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawLine(ink, Offset(6f, 9f), Offset(12f, 15f), strokeWidth = 2f, cap = StrokeCap.Round)
            drawLine(ink, Offset(12f, 15f), Offset(18f, 9f), strokeWidth = 2f, cap = StrokeCap.Round)
        }
    }
}

/**
 * A helper's sheet: name and how much it may do by itself, TODAY's log (time and what), what waits with Review, the
 * Kestrel note on Kestrel's, and Close. Motion: the scrim fades and the sheet rises on the expand spring; reduced motion
 * cross-fades.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun AgentSheet(card: TeamCard?, onClose: () -> Unit, onReview: (TeamTarget) -> Unit, onPause: (TeamCard) -> Unit) {
    val reduced = Meka.reducedMotion
    BackHandler(enabled = card != null, onBack = onClose)
    // Keep the last card while the sheet leaves, so it doesn't empty as it goes.
    val last = remember { arrayOfNulls<TeamCard>(1) }
    if (card != null) last[0] = card
    AnimatedVisibility(visible = card != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        val c = card ?: last[0] ?: return@AnimatedVisibility
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close ${c.title}" },
            )
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .animateEnterExit(
                        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else slideInVertically(MekaMotion.expand(false)) { it / 3 },
                        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else slideOutVertically(MekaMotion.expand(false)) { it / 3 },
                    )
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = 560.dp)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(MekaSpace.m)
                    .testTag("team-sheet"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
                    AgentIcon(c.agent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text(c.title, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                        Text(LoneWordGlue.kept(c.level), style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
                Text(TeamRules.TODAY.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(top = MekaSpace.xs).semantics { heading() })
                if (c.log.isEmpty()) {
                    Text(LoneWordGlue.kept(TeamRules.LOG_EMPTY), style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                c.log.forEach { l ->
                    Row(Modifier.fillMaxWidth().testTag("team-log"), horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                        Text(l.time, style = MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 1)
                        Text(LoneWordGlue.kept(l.text), style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                    }
                }
                val wait = c.waitLine
                val target = c.review
                if (wait != null && target != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                            .background(Meka.colors.accent.copy(alpha = 0.14f)).padding(MekaSpace.xs),
                        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(LoneWordGlue.kept(wait), style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                        TouchPill("Review", Meka.colors.accent, Meka.colors.onAccent, spoken = "${target.label}: ${c.title}",
                            modifier = Modifier.testTag("team-review")) { onReview(target) }
                    }
                }
                val pause = c.pauseAction
                if (pause != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                            .background(Meka.colors.surfaceRaised).padding(MekaSpace.xs),
                        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(LoneWordGlue.kept(c.pauseLine.orEmpty()), style = MekaType.caption, color = Meka.colors.textSecondary,
                            modifier = Modifier.weight(1f).testTag("team-pause-line"))
                        TouchPill(pause, if (c.paused) Meka.colors.accent else Meka.colors.surface,
                            if (c.paused) Meka.colors.onAccent else Meka.colors.textPrimary, spoken = "$pause: ${c.title}",
                            modifier = Modifier.testTag("team-pause")) { onPause(c) }
                    }
                }
                if (c.readOnly) {
                    Text(LoneWordGlue.kept(TeamRules.KESTREL_FOOT), style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TouchPill(TeamRules.CLOSE, Meka.colors.surfaceRaised, Meka.colors.textSecondary, spoken = "${TeamRules.CLOSE} ${c.title}",
                        modifier = Modifier.testTag("team-sheet-close"), onClick = onClose)
                }
            }
        }
    }
}
