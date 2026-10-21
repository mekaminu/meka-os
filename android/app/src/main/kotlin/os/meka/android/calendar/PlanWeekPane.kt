package os.meka.android.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import os.meka.android.today.MailNeedsPill
import os.meka.core.domain.PlanWeekRules
import os.meka.core.domain.WeekPlan
import os.meka.core.facade.MekaCore

/**
 * Plan your week on the Fold (Week board item (6); core [PlanWeekRules]): from the Board's "Plan your week", a pane in
 * four steps — Left from last week, Fixed this week, Goals, Suggested spread. Next and Back slide the step an eighth of
 * the width the way he moved (tick haptic; reduced motion: cross-fade) and the step dots blend to the accent. On the last
 * step each task has its day chips, Navilife's pick lit, and Leave; a chip's tap gives a tick and its line cross-fades.
 * Nothing moves until Accept plan (light haptic): the pane leaves and the Board's undo bar says "Week planned · …".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanWeekPane(core: MekaCore, onClose: () -> Unit, onAccepted: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var plan by remember { mutableStateOf<WeekPlan?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { plan = runCatching { core.planWeek() }.getOrNull() }
    BackHandler { if (step > 0) { forward = false; step-- } else onClose() }

    Column(Modifier.fillMaxSize().padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.l)) {
        Text(
            "Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch(),
        )
        val p = plan
        Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(0))) {
            Text(p?.title ?: PlanWeekRules.OPEN, style = MekaType.greeting, color = Meka.colors.textPrimary)
            p?.let { Text(it.range, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
        }
        StepDots(step, Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(1)))
        if (p == null) return@Column

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (reduced) {
                    fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
                } else {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally { w -> dir * w / 8 } + fadeIn(MekaMotion.appear(false))) togetherWith
                        (slideOutHorizontally { w -> -dir * w / 8 } + fadeOut(MekaMotion.appear(false)))
                }
            },
            modifier = Modifier.weight(1f).padding(top = MekaSpace.m),
            label = "plan-week-step",
        ) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (s) {
                    0 -> LeftStep(p)
                    1 -> FixedStep(p)
                    2 -> GoalsStep(p)
                    else -> SpreadStep(p) { id, day -> haptics.tick(); plan = p.choose(id, day) }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            val backLabel = if (step == 0) PlanWeekRules.NOT_NOW else PlanWeekRules.BACK
            Text(
                backLabel, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) {
                    haptics.tick()
                    if (step == 0) onClose() else { forward = false; step-- }
                }.minTouch().padding(horizontal = MekaSpace.xs),
            )
            Spacer(Modifier.weight(1f))
            val last = step == PlanWeekRules.STEPS.size - 1
            val enabled = !last || p.canAccept
            Text(
                if (last) PlanWeekRules.ACCEPT else PlanWeekRules.NEXT,
                style = MekaType.itemTitle,
                color = if (enabled) Meka.colors.onAccent else Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                    .background(if (enabled) Meka.colors.accent else Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, enabled = enabled) {
                        if (!last) { haptics.tick(); forward = true; step++ } else {
                            haptics.light()
                            scope.launch {
                                val line = runCatching { core.acceptWeekPlan(p) }.getOrNull()
                                onClose()
                                if (line != null) onAccepted(line)
                            }
                        }
                    }
                    .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}

/** "1 of 4 · Left from last week" over four dots, the current one in the accent. */
@Composable
private fun StepDots(step: Int, modifier: Modifier) {
    val steps = PlanWeekRules.STEPS
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "Step ${step + 1} of ${steps.size}, ${steps[step]}" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            steps.indices.forEach { i ->
                Box(Modifier.size(width = MekaSpace.m, height = 4.dp).clip(CircleShape).background(if (i <= step) Meka.colors.accent else Meka.colors.hairline))
            }
        }
        Text(
            "${step + 1} of ${steps.size} · ${steps[step]}".uppercase(), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.xs),
        )
    }
}

@Composable
private fun LeftStep(p: WeekPlan) {
    // Item (8): the one line last week's review said to carry into this plan, in the accent.
    p.carryLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.padding(bottom = MekaSpace.xs)) }
    Text(p.leftLine, style = MekaType.body, color = Meka.colors.textPrimary)
    p.rows.forEach { r ->
        Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m).semantics(mergeDescendants = true) {}) {
            Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(r.why, style = MekaType.itemMeta, color = if (r.why.startsWith("Carried")) Meka.colors.accent else Meka.colors.textSecondary)
        }
    }
}

@Composable
private fun FixedStep(p: WeekPlan) {
    p.days.forEach { d ->
        Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.m).semantics(mergeDescendants = true) { contentDescription = d.spoken }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, style = MekaType.captionStrong, color = Meka.colors.textPrimary)
                d.placeLabel?.let {
                    Spacer(Modifier.width(MekaSpace.xs))
                    Text(it, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                Spacer(Modifier.weight(1f))
                d.freeLine?.let { Text(it, style = MekaType.caption, color = if (d.stacking) Meka.colors.calendar3 else Meka.colors.textSecondary) }
            }
            if (d.fixed.isEmpty()) {
                Text(PlanWeekRules.NOTHING_FIXED, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            } else {
                d.fixed.forEach { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
            }
        }
    }
}

@Composable
private fun GoalsStep(p: WeekPlan) {
    if (p.goals.isEmpty()) {
        Text(PlanWeekRules.NO_GOALS, style = MekaType.body, color = Meka.colors.textSecondary)
        return
    }
    p.goals.forEach { g ->
        Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.m).semantics(mergeDescendants = true) {}) {
            Text("◎ ${g.title}", style = MekaType.body, color = Meka.colors.textPrimary)
            Text(g.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpreadStep(p: WeekPlan, onChoose: (String, Long?) -> Unit) {
    Text(p.spreadLine, style = MekaType.body, color = Meka.colors.textPrimary)
    p.rows.forEach { r ->
        Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m)) {
            Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(PlanWeekRules.rowLine(p, r), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            FlowRow(
                Modifier.padding(top = MekaSpace.xxs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                r.days.forEach { d ->
                    val label = p.dayLabel(d)
                    MailNeedsPill(
                        if (r.chosen == d) "✓ $label" else label, spoken = "${r.title} on $label", quiet = r.chosen != d,
                    ) { onChoose(r.taskId, d) }
                }
                MailNeedsPill(PlanWeekRules.LEAVE, spoken = "Leave ${r.title} where it is", quiet = r.chosen != null) { onChoose(r.taskId, null) }
            }
        }
    }
}
