package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneyForecastTiles
import os.meka.core.domain.MoneyGoalsEmpty
import os.meka.core.domain.MoneyPlanBar
import os.meka.core.domain.MoneyPlanMore
import os.meka.core.domain.MoneyPlanTile

/**
 * Plan's lower half (approved mockup `docs/design/money/Phone-PlanMore.dc.html`), slice 1: Goals' empty card with its
 * start tiles, and the forecast as In / Out / Left over tiles, a 6 · 12 months toggle, month bars that grow to the
 * total (the 6th and 12th tagged in gold), By tiles and the What if I cut… slider. Everything fades up and the bars
 * grow from the floor (motion catalogue: Lists and cards, Charts); reduced motion shows them at once.
 */
@Composable
internal fun MoneyGoalsEmptyCard(e: MoneyGoalsEmpty, modifier: Modifier = Modifier, onStart: (String) -> Unit) {
    MoneyCard(modifier, tag = "money-goals-empty") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Box(
                Modifier.size(44.dp).border(BorderStroke(2.dp, Meka.colors.accent.copy(alpha = 0.5f)), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("◎", style = MekaType.body, color = Meka.colors.accent) }
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(e.title, style = MekaType.upNextCardTitle, color = Meka.colors.textPrimary)
                Text(e.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            e.tiles.forEach { t ->
                Column(
                    Modifier.weight(1f).heightIn(min = 72.dp).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                        .clickable(role = Role.Button) { onStart(t.key) }
                        .semantics(mergeDescendants = true) { contentDescription = t.spoken }
                        .testTag("money-goal-start-${t.key}")
                        .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) {
                    Text(t.icon, style = MekaType.body, color = Meka.colors.accent)
                    Text(t.name, style = MekaType.caption, color = Meka.colors.textPrimary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** A small-caps tile: the label, the figure (gold when [MoneyPlanTile.lit], the alarm tone when below zero). */
@Composable
internal fun MoneyPlanTileView(t: MoneyPlanTile, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val ink by animateColorAsState(
        when { t.alarm -> Meka.colors.critical; t.lit -> Meka.colors.accent; else -> Meka.colors.textPrimary },
        MekaMotion.appear(reduced), label = "money-plan-tile-${t.label}",
    )
    val fill = if (t.lit) Meka.colors.accent.copy(alpha = 0.10f) else Meka.colors.surfaceRaised
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(fill)
            .semantics(mergeDescendants = true) { contentDescription = t.spoken }
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(t.label, style = MekaType.micro, color = if (t.lit) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1)
        Crossfade(t.value, animationSpec = MekaMotion.appear(reduced), label = "money-plan-tile-value-${t.label}") { v ->
            Text(v, style = MekaType.metaStrong, color = ink, maxLines = 1)
        }
    }
}

/** The forecast card's content (inside [MoneyCard]); the What if… row follows it in the same card. */
@Composable
internal fun ColumnScope.MoneyForecastTilesBlock(
    t: MoneyForecastTiles, onSpan: (Int) -> Unit, onCut: (Int) -> Unit, onTick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().testTag("money-forecast-tiles"), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        t.tiles.forEach { MoneyPlanTileView(it, Modifier.weight(1f)) }
    }
    Row(
        Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised).padding(MekaSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        t.spans.forEach { n ->
            val on = n == t.span
            val fill by animateColorAsState(if (on) Meka.colors.accent.copy(alpha = 0.18f) else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "money-span-$n")
            Text(
                "$n months", style = MekaType.caption, color = if (on) Meka.colors.textPrimary else Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                    .clickable(role = Role.Tab) { if (!on) { onTick(); onSpan(n) } }
                    .semantics { contentDescription = "$n months" + if (on) ", chosen" else "" }
                    .testTag("money-forecast-span-$n")
                    .minTouch().padding(horizontal = MekaSpace.m),
            )
        }
    }
    ForecastBars(t.bars)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        t.by.forEach { MoneyPlanTileView(it, Modifier.weight(1f)) }
    }
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(MoneyPlanMore.CUT_HEAD, style = MekaType.captionStrong, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Crossfade(t.cutLabel, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "money-cut-label") { l ->
                Text(l, style = MekaType.captionStrong, color = Meka.colors.accent, modifier = Modifier.testTag("money-cut-label"))
            }
        }
        MoneySlider(
            value = (t.cutPence / 100).toFloat(),
            onValueChange = { f ->
                val pounds = (f.toInt() / MoneyPlanMore.CUT_STEP_POUNDS) * MoneyPlanMore.CUT_STEP_POUNDS
                if (pounds * 100L != t.cutPence) { onTick(); onCut(pounds) }
            },
            range = 0f..MoneyPlanMore.CUT_MAX_POUNDS.toFloat(),
            tag = "money-cut-slider", spoken = "What if I cut, ${t.cutLabel}",
        )
        Text(t.cutNote, style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

/** One bar a month, growing from the floor in turn; the 6th and 12th in gold with their totals over them. */
@Composable
private fun ForecastBars(bars: List<MoneyPlanBar>) {
    val reduced = Meka.reducedMotion
    Row(
        Modifier.fillMaxWidth().height(140.dp).testTag("money-forecast-bars")
            .semantics { contentDescription = "Month by month, " + bars.filter { it.key }.joinToString(", ") { "${it.month} ${it.tag}" } },
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs), verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEachIndexed { i, b ->
            var grown by remember { mutableStateOf(reduced) }
            LaunchedEffect(Unit) { grown = true }
            val h by animateFloatAsState(if (grown) b.share else 0f, if (reduced) tween(0) else tween(600, delayMillis = i * 40), label = "money-bar-$i")
            val last = i == bars.lastIndex
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs, Alignment.Bottom)) {
                b.tag?.let { tag ->
                    Text(tag, style = MekaType.micro, color = if (b.below) Meka.colors.critical else Meka.colors.accent, maxLines = 1, softWrap = false,
                        modifier = Modifier.wrapContentWidth(if (last) Alignment.End else Alignment.CenterHorizontally, unbounded = true))
                }
                Box(
                    Modifier.fillMaxWidth().height((92 * h).dp)
                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(
                            when {
                                b.below && b.key -> Meka.colors.critical
                                b.below -> Meka.colors.critical.copy(alpha = 0.35f)
                                b.key -> Meka.colors.accent
                                else -> Meka.colors.accent.copy(alpha = 0.28f)
                            },
                        ),
                )
                Text(b.month, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1, softWrap = false,
                    modifier = Modifier.wrapContentWidth(unbounded = true))
            }
        }
    }
}

/** Add a £ goal's sheet: what for and how much, then Add goal. */
@Composable
internal fun ColumnScope.MoneyAddGoalFields(onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    os.meka.android.lists.Field(MoneyPlanMore.ADD_NAME_HINT, value = name, onValue = { name = it }, keep = true) { name = it }
    os.meka.android.lists.Field(MoneyPlanMore.ADD_AMOUNT_HINT, value = amount, onValue = { amount = it }, keep = true) { amount = it; onAdd(name, it) }
    Text(
        MoneyPlanMore.ADD, style = MekaType.captionStrong, color = Meka.colors.onAccent, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
            .clickable(role = Role.Button) { onAdd(name, amount) }
            .testTag("money-add-goal").minTouch().padding(horizontal = MekaSpace.m),
    )
}
