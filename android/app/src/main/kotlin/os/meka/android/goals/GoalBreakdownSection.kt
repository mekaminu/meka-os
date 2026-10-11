package os.meka.android.goals

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.GoalBreakdown
import os.meka.core.domain.GoalBreakdownRules
import os.meka.core.domain.GoalItem
import os.meka.core.facade.BreakdownKept
import os.meka.core.facade.BreakdownOutcome

/** Where Break it down is: the button, MEKA thinking, the proposals to tick, or a line (with Undo after Keep). */
private sealed interface BreakdownStage {
    data object Idle : BreakdownStage
    data object Reading : BreakdownStage
    data class Offered(val breakdown: GoalBreakdown, val line: String) : BreakdownStage
    data class Said(val line: String, val kept: BreakdownKept? = null) : BreakdownStage
}

/** What Break it down needs from the route: ask MEKA, keep what was ticked (null if it failed), and Undo. */
internal class BreakdownActions(
    val ask: suspend () -> BreakdownOutcome,
    val keep: suspend (List<String>, List<String>) -> BreakdownKept?,
    val undo: (BreakdownKept) -> Unit,
)

/**
 * Goals, properly, slice 4: **Break it down** (approval only). "✦ Break it down" asks MEKA once; "Thinking it through…"
 * cross-fades in, then the proposed milestones and first steps, each with an empty tick ring Meka ticks (light haptic,
 * the ring draws its check). Nothing is added until Keep (light haptic; the kept ones fade into Milestones and the next
 * step); "Added … · Undo" cross-fades in where the proposals were. Not now folds them away. Reduced motion: cross-fades.
 */
@Composable
internal fun BreakItDown(g: GoalItem, actions: BreakdownActions) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val light = { haptics.light() }
    val tick = { haptics.tick() }
    val scope = rememberCoroutineScope()
    var stage by remember(g.id) { mutableStateOf<BreakdownStage>(BreakdownStage.Idle) }
    var ticked by remember(g.id) { mutableStateOf(emptySet<String>()) }
    AnimatedContent(
        stage,
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        label = "breakdown",
    ) { s ->
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            when (s) {
                BreakdownStage.Idle -> {
                    Action("✦ " + GoalBreakdownRules.ACTION) {
                        light()
                        stage = BreakdownStage.Reading
                        scope.launch {
                            val out = runCatching { actions.ask() }.getOrElse { BreakdownOutcome.Unavailable(GoalBreakdownRules.NOT_CONNECTED_LINE) }
                            ticked = emptySet()
                            stage = when (out) {
                                is BreakdownOutcome.Proposals -> BreakdownStage.Offered(out.breakdown, out.line)
                                is BreakdownOutcome.NothingNew -> BreakdownStage.Said(out.line)
                                is BreakdownOutcome.Unavailable -> BreakdownStage.Said(out.line)
                            }
                        }
                    }
                    Text(GoalBreakdownRules.HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                BreakdownStage.Reading -> Text(GoalBreakdownRules.READING_LINE, style = MekaType.caption, color = Meka.colors.textSecondary)
                is BreakdownStage.Offered -> {
                    Text(s.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    if (s.breakdown.milestones.isNotEmpty()) {
                        Text(GoalBreakdownRules.MILESTONES_LABEL, style = MekaType.caption, color = Meka.colors.textTertiary)
                        s.breakdown.milestones.forEach { m -> Proposal(m, "m:$m" in ticked) { light(); ticked = ticked.toggle("m:$m") } }
                    }
                    if (s.breakdown.steps.isNotEmpty()) {
                        Text(GoalBreakdownRules.STEPS_LABEL, style = MekaType.caption, color = Meka.colors.textTertiary)
                        s.breakdown.steps.forEach { t -> Proposal(t, "s:$t" in ticked) { light(); ticked = ticked.toggle("s:$t") } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
                        Action(GoalBreakdownRules.keepLabel(ticked.size)) {
                            if (ticked.isEmpty()) { tick(); return@Action }
                            val ms = s.breakdown.milestones.filter { "m:$it" in ticked }
                            val ss = s.breakdown.steps.filter { "s:$it" in ticked }
                            scope.launch {
                                val kept = actions.keep(ms, ss)
                                if (kept != null) light() else tick()
                                stage = if (kept == null) BreakdownStage.Idle else BreakdownStage.Said(kept.line, kept.takeIf { !it.isEmpty })
                            }
                        }
                        Action(GoalBreakdownRules.NOT_NOW) { tick(); stage = BreakdownStage.Idle }
                    }
                }
                is BreakdownStage.Said -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                    val kept = s.kept
                    if (kept != null) Action("Undo") { tick(); actions.undo(kept); stage = BreakdownStage.Idle }
                    else Action("OK") { stage = BreakdownStage.Idle }
                }
            }
        }
    }
}

private fun Set<String>.toggle(k: String) = if (k in this) this - k else this + k

/** One proposal: an empty tick ring Meka taps to keep it (the ring draws its check), and its words. */
@Composable
private fun Proposal(text: String, on: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.clickable(role = Role.Checkbox) { toggle() }.semantics { contentDescription = (if (on) "Don't keep " else "Keep ") + text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickRing(on, Modifier.size(24.dp).clip(CircleShape))
        Spacer(Modifier.width(MekaSpace.m))
        Text(text, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
    }
}
