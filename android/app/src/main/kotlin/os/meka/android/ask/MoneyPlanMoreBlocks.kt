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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.unit.sp
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
import os.meka.core.domain.MoneyChallengeTiles
import os.meka.core.domain.MoneyAbroadTiles

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
        // Walk-through A11 (2026-11-08): the label whole ("OUT / MONTH", never "OUT /") and the figure whole, stepping
        // down to fit three tiles on the closed Fold rather than cutting.
        BasicText(
            t.label, style = MekaType.micro.copy(color = if (t.lit) Meka.colors.accent else Meka.colors.textSecondary), maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MekaType.micro.fontSize, stepSize = 0.5.sp), // type: ok (shrinks to fit, never below 9)
        )
        Crossfade(t.value, animationSpec = MekaMotion.appear(reduced), label = "money-plan-tile-value-${t.label}") { v ->
            BasicText(
                v, style = MekaType.metaStrong.copy(color = ink), maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MekaType.metaStrong.fontSize, stepSize = 0.5.sp), // type: ok (shrinks to fit, never below 11)
            )
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

/**
 * Plan's lower half, slice 2: CHALLENGES as two tiles side by side — No-spend days as this week's dots (gold = no
 * spending, dashed = not reached yet) with the count and Start / Stop counting; Fun money as a pot with what's left, a
 * bar and £40 / £60 / £80 (a tap on the chosen one takes it off) — then any caps as bars and "Cap a category for the
 * month · Set ›". Dots and bars change on the appear spring; reduced motion shows them at once.
 */
@Composable
internal fun ColumnScope.MoneyChallengeTilesBlock(
    t: MoneyChallengeTiles, onNoSpend: (Boolean) -> Unit, onFun: (Int, Boolean) -> Unit, onCap: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Row(Modifier.fillMaxWidth().testTag("money-challenge-tiles"), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .testTag("money-nospend-tile").padding(MekaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Text(MoneyPlanMore.NO_SPEND, style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1)
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = t.noSpend.spoken },
                horizontalArrangement = Arrangement.spacedBy(3.dp)) { // rhythm: ok (the week's dots are one mark)
                t.noSpend.dots.forEachIndexed { i, d ->
                    val fill by animateColorAsState(
                        when {
                            d.noSpend -> Meka.colors.accent
                            d.spent -> Meka.colors.surface
                            else -> Meka.colors.surfaceRaised
                        }, MekaMotion.appear(reduced), label = "money-week-dot-$i",
                    )
                    val dashed = d.pending
                    Box(
                        Modifier.weight(1f).height(20.dp).clip(CircleShape).background(fill)
                            .then(if (dashed) Modifier.border(BorderStroke(1.dp, Meka.colors.textTertiary.copy(alpha = 0.4f)), CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(d.letter, style = MekaType.micro, maxLines = 1, softWrap = false,
                            color = if (d.noSpend) Meka.colors.onAccent else Meka.colors.textTertiary)
                    }
                }
            }
            if (t.noSpend.on) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(t.noSpend.count, animationSpec = MekaMotion.appear(reduced), label = "money-nospend-count") { c ->
                    Text(c, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.testTag("money-nospend-count"))
                }
                Text(MoneyPlanMore.THIS_WEEK, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1)
            }
            Text(
                t.noSpend.button, style = MekaType.captionStrong, textAlign = TextAlign.Center, maxLines = 1,
                color = if (t.noSpend.on) Meka.colors.accent else Meka.colors.onAccent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .background(if (t.noSpend.on) Meka.colors.surface else Meka.colors.accent)
                    .clickable(role = Role.Switch) { onNoSpend(!t.noSpend.on) }
                    .testTag("money-nospend-toggle").minTouch().padding(horizontal = MekaSpace.xxs),
            )
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .testTag("money-fun-tile").padding(MekaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            val f = t.funMoney
            Text(MoneyPlanMore.FUN, style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1)
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = f.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(f.value, animationSpec = MekaMotion.appear(reduced), label = "money-fun-value") { v ->
                    Text(v, style = MekaType.metaStrong, maxLines = 1, modifier = Modifier.testTag("money-fun-value"),
                        color = when { f.over -> Meka.colors.critical; f.hasPot -> Meka.colors.textPrimary; else -> Meka.colors.textSecondary })
                }
                Text(f.of, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2)
            }
            var shown by remember { mutableStateOf(if (reduced) f.share else 0f) }
            LaunchedEffect(f.share) { shown = f.share }
            val share by animateFloatAsState(shown, MekaMotion.appear(reduced), label = "money-fun-pot")
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                f.chips.forEach { c ->
                    val fill by animateColorAsState(if (c.chosen) Meka.colors.accent.copy(alpha = 0.18f) else Meka.colors.surface, MekaMotion.appear(reduced), label = "money-fun-chip-${c.pounds}")
                    Text(
                        c.label, style = MekaType.caption, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                        color = if (c.chosen) Meka.colors.textPrimary else Meka.colors.textSecondary,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                            .clickable(role = Role.Button) { onFun(c.pounds, c.chosen) }
                            .semantics { contentDescription = "Fun money ${c.label} a week" + if (c.chosen) ", chosen, tap to take it off" else "" }
                            .testTag("money-fun-chip-${c.pounds}").minTouch(),
                    )
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onCap() }
            .semantics(mergeDescendants = true) { contentDescription = "${t.capRow}, set" }
            .testTag("money-cap-row").minTouch().padding(horizontal = MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.capRow, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
        Text(t.capSet, style = MekaType.body, color = Meka.colors.accent)
    }
}

/**
 * Plan's lower half, slice 2: PAYING ABROAD's card — the year's foreign fees in a big tile with what it could be, a bar
 * per account (its fees against the biggest; one with no fees is a full gold bar), and See the payments behind it, which
 * unfolds each fee with what it was for. Bars grow on the appear spring; the list unfolds on the expand spring (reduced
 * motion: fades).
 */
@Composable
internal fun ColumnScope.MoneyAbroadTilesBlock(t: MoneyAbroadTiles, onTick: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(Modifier.fillMaxWidth().testTag("money-abroad-fees").semantics(mergeDescendants = true) { contentDescription = "${t.feesLabel}, ${t.fees}. ${t.line}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(
            Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .background(if (t.lit) Meka.colors.critical.copy(alpha = 0.16f) else Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(t.feesLabel, style = MekaType.micro, color = if (t.lit) Meka.colors.critical else Meka.colors.textSecondary, maxLines = 1)
            Crossfade(t.fees, animationSpec = MekaMotion.appear(reduced), label = "money-abroad-fees-value") { v ->
                Text(v, style = MekaType.upNextCardTitle, color = if (t.lit) Meka.colors.critical else Meka.colors.textPrimary, maxLines = 1)
            }
        }
        Text(t.line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
    }
    t.bars.forEach { b ->
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = b.spoken }.testTag("money-abroad-bar-${b.id}"),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(b.name, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                Text(b.amount, style = MekaType.metaStrong, color = if (b.good) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1)
            }
            var shown by remember { mutableStateOf(if (reduced) b.share else 0f) }
            LaunchedEffect(b.share) { shown = b.share }
            val share by animateFloatAsState(shown, MekaMotion.appear(reduced), label = "money-abroad-bar-${b.id}")
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill))
                    .background(if (b.good) Meka.colors.accent else Meka.colors.critical))
            }
            Text(b.sub, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
    }
    if (t.payments.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        Text(
            if (open) MoneyPlanMore.HIDE_PAYMENTS else MoneyPlanMore.SHOW_PAYMENTS,
            style = MekaType.captionStrong, color = Meka.colors.textPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .clickable(role = Role.Button) { onTick(); open = !open }
                .testTag("money-abroad-payments-toggle").minTouch(),
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = if (reduced) androidx.compose.animation.fadeIn(MekaMotion.appear(true))
                else androidx.compose.animation.expandVertically(MekaMotion.expand(false)) + androidx.compose.animation.fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) androidx.compose.animation.fadeOut(MekaMotion.appear(true))
                else androidx.compose.animation.shrinkVertically(MekaMotion.expand(false)) + androidx.compose.animation.fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.fillMaxWidth().testTag("money-abroad-payments"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                t.payments.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised)
                            .semantics(mergeDescendants = true) { contentDescription = p.spoken }
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.what, style = MekaType.caption, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                        Text(p.fee, style = MekaType.caption, color = Meka.colors.critical, maxLines = 1)
                    }
                }
                t.more?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
        }
    }
}
