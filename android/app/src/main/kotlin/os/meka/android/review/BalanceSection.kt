package os.meka.android.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.goals.fold
import os.meka.android.goals.unfold
import os.meka.android.today.SectionLabel
import os.meka.core.domain.BalanceArea
import os.meka.core.domain.BalanceRow
import os.meka.core.domain.BalanceRules
import os.meka.core.domain.BalanceView
import os.meka.core.domain.DurationGlue
import os.meka.core.domain.LoneWordGlue

/** A grey line as drawn: durations and the last words kept together (quality gate slice 62). */
private fun shown(line: String): String = LoneWordGlue.kept(DurationGlue.kept(line))

/**
 * Balance view in the weekly review (V2, slice 1): the reviewed week's time across Work, Family, Health and Self, each
 * with its bar against his target, a status ("Met" in the accent, "2 h short", "3 h over" for work in the accent) and
 * what counted; "Targets ›" unfolds a row of chips per area (synced). The sources line says what is counted.
 *
 * Motion (catalogue "Weekly review"): the section staggers in with the review's others; each bar fills on appear and
 * glides to a new target; statuses cross-fade and blend to the accent; Targets unfolds in place (expand spring, the
 * chevron turns, tick haptic); a chip blends to the accent with a light haptic. Reduced motion: cross-fades, bars set.
 *
 * Slice 5: "Count as …": each event with no clear area is listed under the balance with chips (Family, Health, Self,
 * Work, Don't count); a tap counts every event with that title (synced), the row folds away and the line says so with
 * Undo. His choices are listed under Targets, each with Forget.
 */
internal fun LazyListScope.balanceItems(
    balance: BalanceView,
    said: String?,
    index: Int,
    onTarget: (BalanceArea, Int) -> Unit,
    onToggle: () -> Unit,
    countSaid: String? = null,
    onCountAs: (String, String) -> Unit = { _, _ -> },
    onUndoCount: () -> Unit = {},
) {
    if (balance.rows.isEmpty()) return
    item(key = "h-balance") {
        Column(Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(index))) {
            SectionLabel(BalanceRules.TITLE)
            Crossfade(balance.line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "balance-line") { l ->
                Text(shown(l), style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xs))
            }
        }
    }
    items(balance.rows, key = { "bal-" + it.area.name }) { r -> BalanceLine(r, Modifier.appear(rememberAppearance(index))) }
    item(key = "balance-foot") {
        Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            balance.uncounted?.let { Text(shown(it), style = MekaType.caption, color = Meka.colors.textTertiary) }
            CountAs(balance, countSaid, onCountAs, onUndoCount)
            Text(shown(balance.sources), style = MekaType.caption, color = Meka.colors.textTertiary)
            Targets(balance, said, onTarget, onToggle, onCountAs)
        }
    }
}

@Composable
private fun BalanceLine(r: BalanceRow, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val fill = remember(r.area) { Animatable(if (reduced) r.pct / 100f else 0f) }
    LaunchedEffect(r.pct) { fill.animateTo(r.pct / 100f, MekaMotion.replan(reduced)) }
    val statusColor by animateColorAsState(
        if (r.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "balance-status",
    )
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs).semantics(mergeDescendants = true) { contentDescription = r.spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.label, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            r.status?.let { s ->
                Crossfade(s, animationSpec = MekaMotion.appear(reduced), label = "balance-status-text") {
                    Text(it, style = MekaType.itemMeta, color = statusColor)
                }
            }
        }
        Box(Modifier.padding(vertical = MekaSpace.xs).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(fill.value.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        }
        Text(shown(r.line), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        r.items.forEach { Text(shown(it), style = MekaType.caption, color = Meka.colors.textTertiary) }
        if (r.moreItems > 0) Text("and ${r.moreItems} more", style = MekaType.caption, color = Meka.colors.textTertiary)
        r.trendLine?.let { line ->
            TrendBars(r)
            Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "balance-trend") {
                Text(shown(it), style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
    }
}

internal val TREND_BAR_WIDTH = 18.dp
internal val TREND_BAR_CORNER = 2.dp
internal val TREND_BAR_MIN = 2.dp

/**
 * Slice 2: the last four weeks as four small bars (oldest first), the met ones (work: over) in the accent, a hairline at
 * the target, each week's Monday under its bar. Bars rise one after another on appear and glide to a new week; reduced
 * motion sets them.
 */
@Composable
private fun TrendBars(r: BalanceRow) {
    val reduced = Meka.reducedMotion
    val barHeight = 24.dp
    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        r.trend.forEachIndexed { i, w ->
            val rise = remember(r.area, i) { Animatable(if (reduced) w.pct / 100f else 0f) }
            LaunchedEffect(w.weekStart, w.pct) {
                if (!reduced && rise.value == 0f) kotlinx.coroutines.delay(40L * i)
                rise.animateTo(w.pct / 100f, MekaMotion.replan(reduced))
            }
            val color by animateColorAsState(
                if (w.met) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.themeBlend(reduced), label = "trend-bar",
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(barHeight), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(bottom = barHeight * (r.trendTargetPct.coerceAtMost(95) / 100f)).height(1.dp)
                            .background(Meka.colors.textTertiary.copy(alpha = 0.5f)),
                    )
                    // Quality gate slice 64: a slim 18 dp bar with 2 dp corners (as the Mac's); the 10 dp corners on a
                    // half-lane-wide bar drew the short ones as domes. A week with any time keeps a 2 dp sliver.
                    val shown = barHeight * rise.value.coerceIn(0f, 1f)
                    Box(
                        Modifier.testTag("trend-bar").width(TREND_BAR_WIDTH)
                            .height(if (w.pct > 0 && shown > 0.dp) maxOf(shown, TREND_BAR_MIN) else shown)
                            .clip(RoundedCornerShape(topStart = TREND_BAR_CORNER, topEnd = TREND_BAR_CORNER)).background(color),
                    )
                }
                Text(if (w.current) "So far" else w.label, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
}

/**
 * Slice 5: "Count as …" for each event with no clear area: its title, when ("Tue, Thu · 2 h") and a chip per area. A
 * row folds away once chosen (the chosen chip blends to the accent first); the line after a tap cross-fades in with Undo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CountAs(balance: BalanceView, said: String?, onCountAs: (String, String) -> Unit, onUndo: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
        balance.unclear.forEachIndexed { i, u ->
            androidx.compose.runtime.key(u.key) {
                var picked by remember { mutableStateOf<String?>(null) }
                Column(Modifier.appear(rememberAppearance(i)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                        Text(shown(u.line), style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                    Text("Count as", style = MekaType.caption, color = Meka.colors.textTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        BalanceRules.COUNT_AS.forEach { (code, label) ->
                            TargetChip(label, picked == code) { picked = code; onCountAs(u.title, code) }
                        }
                    }
                }
            }
        }
        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "balance-count-said") { l ->
            l?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shown(it), style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(
                        "Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClickLabel = "Undo") { onUndo() }
                            .minTouch().padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
                    )
                }
            }
        }
    }
}

/** "Targets ›": a row of chips per area, unfolding in place; the line the last tap said under them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Targets(
    balance: BalanceView,
    said: String?,
    onTarget: (BalanceArea, Int) -> Unit,
    onToggle: () -> Unit,
    onCountAs: (String, String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val reduced = Meka.reducedMotion
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) MekaMotion.appear<Float>(true) else MekaMotion.expand<Float>(false), label = "balance-chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide the targets" else "Show the targets") { onToggle(); open = !open }
                .minTouch().padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Targets", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.weight(1f))
            Text("›", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                balance.rows.forEachIndexed { i, r ->
                    Column(Modifier.appear(rememberAppearance(i)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text("${r.label} · a week", style = MekaType.caption, color = Meka.colors.textTertiary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            r.choices.forEach { c ->
                                val chosen = when {
                                    c == BalanceRules.YOUR_HOURS -> r.defaultTarget
                                    r.area == BalanceArea.WORK -> !r.defaultTarget && c == r.targetMin
                                    else -> c == r.targetMin
                                }
                                TargetChip(if (c == BalanceRules.YOUR_HOURS) "Your hours" else BalanceRules.hours(c), chosen) { onTarget(r.area, c) }
                            }
                        }
                    }
                }
                // Slice 5: his "Count as …" choices, each with Forget.
                if (balance.choices.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text("Counted as you chose", style = MekaType.caption, color = Meka.colors.textTertiary)
                        balance.choices.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${c.title} · ${c.label}", style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                                Text(
                                    "Forget", style = MekaType.caption, color = Meka.colors.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                        .clickable(role = Role.Button, onClickLabel = "Forget how ${c.title} counts") { onCountAs(c.title, "CLEAR") }
                                        .minTouch().padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
                                )
                            }
                        }
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "balance-said") { l ->
                    l?.let { Text(shown(it), style = MekaType.caption, color = Meka.colors.textSecondary) }
                }
            }
        }
    }
}

@Composable
private fun TargetChip(label: String, chosen: Boolean, choose: () -> Unit) {
    val bg by animateColorAsState(if (chosen) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.themeBlend(Meka.reducedMotion), label = "target-bg")
    val fg by animateColorAsState(if (chosen) Meka.colors.onAccent else Meka.colors.textPrimary, MekaMotion.themeBlend(Meka.reducedMotion), label = "target-fg")
    Text(
        label, style = MekaType.caption, color = fg,
        // Quality gate slice 62: the pill drawn slim (it was the whole 48 dp tall), the 48 dp touch outside it.
        modifier = Modifier.semantics { selected = chosen }.clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.RadioButton) { if (!chosen) choose() }
            .minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
