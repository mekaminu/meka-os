package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.android.work.SettingChip
import os.meka.android.designsystem.TickRing
import os.meka.core.domain.WeekAheadDay
import os.meka.core.domain.WeekAheadGoals
import os.meka.core.domain.WeekAheadKind
import os.meka.core.domain.WeekAheadLine
import os.meka.core.domain.WeekAheadSettingView
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Week ahead (Sunday week ahead, slice 1), and the pane Sunday's 18:00 heads-up and Today's "Your week
 * ahead · …" line open: the coming week day by day — what kind of day (office or from home with the hours, annual
 * leave, a bank holiday), the weather while the forecast reaches it, then events, fixtures, school, date night,
 * appointments and renewals in time order — and FAMILY SYNC, the things that need both Meka and Jeanette.
 *
 * Slice 2: **▶ Listen** reads the week aloud in MEKA's voice ([MekaSpeaker], the phone's own if the voice can't be
 * reached); Listen on Sunday's heads-up opens the pane already reading ([listenNow]). **Sunday heads-up** at the end:
 * on or off and its time (16:00–20:00, default 18:00), synced with the Mac.
 *
 * Goals, properly, item 6: **Goals for the week** — tap up to three of Meka's open goals to make them the week's goals
 * (Today's "This week: …" line and the watch face's goal ring); a fourth waits, dimmed, until one is unpicked. Nothing
 * else here changes anything.
 *
 * Type weight (Meka, 2026-10-06): everything here is context, so the `body` style; a day's label is a section label.
 *
 * Motion: the pane springs up (MekaPane) with the More row's title travelling in; the summary cross-fades as it changes;
 * the days and the family sync stagger in 40 ms apart (the list's item motion as a day's lines change). Reduced motion:
 * cross-fades. Listen's label cross-fades to "■ Stop" while it reads (light haptic to start, tick to stop); the heads-up
 * chips blend with a tick and the times fold away when it's off. A goal's tick ring draws its check with a light haptic
 * as it's picked (unpicked: back to the outline with a tick); the line above cross-fades; the goals not picked dim when
 * three are (alpha cross-fade). Reduced motion: no draw, cross-fades.
 */
@Composable
fun WeekAheadPane(
    core: MekaCore, onClose: () -> Unit, closeLabel: String = "Close",
    listenNow: Boolean = false, onListened: () -> Unit = {},
) {
    val v by core.weekAheadView.collectAsState()
    val setting by core.weekAheadSettingView.collectAsState()
    val reduced = Meka.reducedMotion
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val context = LocalContext.current
    val speaker = remember { MekaSpeaker(context, core, scope) }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    fun listen() = speaker.say(core.weekAheadView.value.spoken, reading = true)
    // Listen on the heads-up: read once the week is in.
    LaunchedEffect(listenNow, v.spoken) {
        if (listenNow && v.spoken.isNotEmpty()) {
            onListened()
            if (!speaker.speaking) listen()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text(closeLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Column(Modifier.appear(rememberAppearance(0))) {
                Text("Week ahead", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WEEK_AHEAD)))
                Text("${v.title} · ${v.rangeLabel}", style = MekaType.caption, color = Meka.colors.textTertiary)
                if (v.spoken.isNotEmpty()) {
                    WeekListenPill(speaker.speaking, Modifier.padding(top = MekaSpace.m)) {
                        if (speaker.speaking) { haptics.tick(); speaker.stop() } else { haptics.light(); listen() }
                    }
                }
            }
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "week-ahead-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        itemsIndexed(v.days, key = { _, d -> "day-${d.epochDay}" }) { i, d ->
            DayBlock(d, Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(2 + i)))
        }
        if (v.sync.isNotEmpty()) {
            item(key = "sync") {
                Column(Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(2 + v.days.size)),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    SectionLabel("Family sync", Modifier.semantics { heading() })
                    Text("To sort out with Jeanette", style = MekaType.caption, color = Meka.colors.textTertiary)
                    v.sync.forEach { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = MekaSpace.xs).size(6.dp).background(Meka.colors.accent, CircleShape))
                            Text(line, style = MekaType.body, color = Meka.colors.textPrimary,
                                modifier = Modifier.padding(start = MekaSpace.xs).weight(1f))
                        }
                    }
                }
            }
        }
        if (v.goals.goals.isNotEmpty()) {
            item(key = "goals") {
                GoalsPick(v.goals, Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(3 + v.days.size))) { id, on ->
                    if (on) haptics.light() else haptics.tick()
                    scope.launch { runCatching { core.pickWeekGoal(id, on) } }
                }
            }
        }
        item(key = "heads-up") {
            HeadsUpSetting(setting, Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(4 + v.days.size))) { on, minute ->
                haptics.tick()
                scope.launch { runCatching { core.setWeekAhead(on, minute) } }
            }
        }
    }
}

/** Goals for the week: tap a goal to pick it (up to three); the rest dim once three are picked. */
@Composable
private fun GoalsPick(g: WeekAheadGoals, modifier: Modifier, onPick: (String, Boolean) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        SectionLabel("Goals for the week", Modifier.semantics { heading() })
        Crossfade(g.line, animationSpec = MekaMotion.appear(reduced), label = "week-goals-line") { line ->
            Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        g.goals.forEach { goal ->
            val enabled = goal.picked || !g.full
            val dim by androidx.compose.animation.core.animateFloatAsState(
                if (enabled) 1f else 0.45f, MekaMotion.appear(reduced), label = "week-goal-dim",
            )
            Row(
                Modifier.fillMaxWidth()
                    .graphicsLayer { alpha = dim }
                    .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = if (goal.picked) "Unpick" else "Pick for the week") {
                        onPick(goal.goalId, !goal.picked)
                    }
                    .minTouch()
                    .semantics(mergeDescendants = true) {
                        contentDescription = goal.spoken
                        stateDescription = if (goal.picked) "Picked" else "Not picked"
                    }
                    .padding(vertical = MekaSpace.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TickRing(goal.picked, Modifier.size(24.dp).clip(CircleShape))
                Column(Modifier.padding(start = MekaSpace.m).weight(1f)) {
                    Text(goal.title, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(goal.meta, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
    }
}

/** "▶ Listen" / "■ Stop", the brief's pill: the label cross-fades as it changes. */
@Composable
private fun WeekListenPill(speaking: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .semantics { stateDescription = if (speaking) "Reading the week aloud" else "" }
            .clickable(role = Role.Button, onClickLabel = if (speaking) "Stop reading" else "Read the week aloud") { onClick() }
            .minTouch()
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = speaking, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "week-listen") { on ->
            Text(if (on) "■  Stop" else "▶  Listen", style = MekaType.itemMeta, color = Meka.colors.accent)
        }
    }
}

/** Sunday heads-up: on or off, then the time; synced (the Mac's sheet shows the same). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeadsUpSetting(s: WeekAheadSettingView, modifier: Modifier, onSet: (Boolean, Int) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        SectionLabel("Sunday heads-up", Modifier.semantics { heading() })
        Crossfade(s.summary, animationSpec = MekaMotion.appear(reduced), label = "week-heads-up-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        SettingChip(if (s.on) "On" else "Off", s.on, spoken = "Sunday heads-up, ${if (s.on) "on" else "off"}") { onSet(!s.on, s.minute) }
        AnimatedVisibility(
            s.on,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                s.choices.forEach { m ->
                    val label = os.meka.core.domain.LocalClock.formatMinute(m)
                    SettingChip(label, m == s.minute, spoken = "Sundays at $label") { if (m != s.minute) onSet(true, m) }
                }
            }
        }
    }
}

@Composable
private fun DayBlock(d: WeekAheadDay, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        SectionLabel(d.label, Modifier.semantics { heading() })
        val meta = listOfNotNull(d.dayLine, d.weather).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MekaType.caption, color = Meka.colors.textSecondary)
        if (d.lines.isEmpty() && d.dayLine == null) {
            Text("Nothing in the diary", style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        d.lines.forEach { WeekLine(it) }
        if (d.more > 0) Text("and ${d.more} more", style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun WeekLine(l: WeekAheadLine) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).semantics(mergeDescendants = true) { contentDescription = l.spoken },
        verticalAlignment = Alignment.Top,
    ) {
        Text(l.time ?: mark(l.kind), style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.width(56.dp))
        Text(l.text, style = MekaType.body, color = if (l.lit) Meka.colors.accent else Meka.colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

/** The lead mark for a line with no time: school, money due, the rest a dot. */
private fun mark(kind: WeekAheadKind): String = when (kind) {
    WeekAheadKind.SCHOOL -> "School"
    WeekAheadKind.DUE -> "Due"
    else -> "·"
}
