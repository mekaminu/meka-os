package os.meka.android.ask

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.moneyTone
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.work.SwipeToDone
import os.meka.core.domain.MoneyIcon
import os.meka.core.domain.MoneyLook
import os.meka.core.domain.MoneyLookRow
import os.meka.core.domain.MoneySorter
import os.meka.core.domain.MoneyUnsortedView
import os.meka.core.domain.MoneyWatch
import os.meka.core.domain.MoneyPayDay
import os.meka.core.domain.MoneySpendRow
import os.meka.core.domain.MoneySpending
import os.meka.core.domain.MoneySpendingView
import os.meka.core.domain.MoneyTile
import os.meka.core.domain.MoneyTone

/**
 * Money redesign (B2), the Spending tab in the approved mockup's look (docs/design/money/Main.dc.html): the ring with
 * the month's total in the middle, the three tiles, the category rows and the latest payments by day. Everything it says
 * comes from the core's [MoneySpendingView]; this file only draws it.
 *
 * Motion: the ring sweeps round from the top on first show (MekaMotion.appear; Motion → Off draws it at once), a picked
 * category's slice stays full while the others fade to a quarter and the middle cross-fades to it; each row's bar grows to
 * its length; the tones blend when the month changes.
 */

/** The mockup's 24-unit stroke icons (the Mac uses the nearest system symbol). */
private val ICON_PATHS: Map<MoneyIcon, String> = mapOf(
    MoneyIcon.CART to "M3 4h2l2.4 11h11l2-8H6.2M9 20a1 1 0 1 0 0-.1M18 20a1 1 0 1 0 0-.1",
    MoneyIcon.BILL to "M6 3h12v18l-3-2-3 2-3-2-3 2zM9 8h6M9 12h6",
    MoneyIcon.BAG to "M5 8h14l-1 12H6zM9 8V6a3 3 0 0 1 6 0v2",
    MoneyIcon.TRAIN to "M7 3h10a2 2 0 0 1 2 2v9a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2zM5 10h14M8 21l2-4M16 21l-2-4",
    MoneyIcon.CUP to "M5 8h11v6a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5zM16 10h2a2 2 0 0 1 0 4h-2M8 3v2M12 3v2",
    MoneyIcon.FORK to "M7 3v8a2 2 0 0 0 4 0V3M9 11v10M16 3c-2 2-2 6 0 8v10",
    MoneyIcon.REPEAT to "M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4",
    MoneyIcon.DOTS to "M5 12h.01M12 12h.01M19 12h.01",
    MoneyIcon.IN to "M12 19V5M5 12l7-7 7 7",
    MoneyIcon.TAKEAWAY to "M4 9h16l-2 11H6zM8 9l2-5h4l2 5",
    MoneyIcon.FUEL to "M5 21V5a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v16M3 21h14M5 10h10M15 8l3 3v6a2 2 0 0 0 4 0V9l-3-3",
    MoneyIcon.SHIRT to "M8 3l-5 4 3 3 2-1v12h8V9l2 1 3-3-5-4a4 4 0 0 1-8 0z",
    MoneyIcon.HOME to "M3 11l9-8 9 8M5 10v10h14V10",
    MoneyIcon.HEART to "M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z",
    MoneyIcon.TICKET to "M4 7h16v3a2 2 0 0 0 0 4v3H4v-3a2 2 0 0 0 0-4zM14 7v10",
    MoneyIcon.PLANE to "M21 4L3 11l7 3 3 7zM10 14l11-10",
    MoneyIcon.KID to "M12 7a2 2 0 1 0 0-.1M8 21l1-8-3-2M16 21l-1-8 3-2M9 13h6",
    MoneyIcon.GIFT to "M4 10h16v10H4zM3 7h18v3H3zM12 7v13M12 7c-2-4-6-3-5 0M12 7c2-4 6-3 5 0",
    MoneyIcon.CASH to "M3 7h18v10H3zM12 12a2 2 0 1 0 0-.1M6 10v4M18 10v4",
    MoneyIcon.PERCENT to "M5 19L19 5M7 7h.01M17 17h.01",
    MoneyIcon.MOVE to "M4 8h14l-3-3M20 16H6l3 3",
)

/** A 40 dp rounded chip in the category's colour (13% wash) with its icon in that colour. */
@Composable
internal fun MoneyIconChip(icon: MoneyIcon, tone: Int) {
    val reduced = Meka.reducedMotion
    val c by animateColorAsState(Meka.colors.moneyTone(tone), MekaMotion.appear(reduced), label = "money-chip")
    val path = remember(icon) { PathParser().parsePathString(ICON_PATHS.getValue(icon)).toPath() }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(MekaRadius.s)).background(c.copy(alpha = 0.13f)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val k = size.width / 24f
            scale(k, k, pivot = Offset.Zero) {
                drawPath(path, c, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun toneColor(t: MoneyTone, good: Color, over: Color, quiet: Color) = when (t) {
    MoneyTone.GOOD -> good
    MoneyTone.OVER -> over
    MoneyTone.QUIET -> quiet
}

/** The ring (236 dp, an 18 dp band) with the middle; a tap on the middle lets go of a picked category. */
@Composable
internal fun MoneyRing(s: MoneySpendingView, focus: String?, modifier: Modifier = Modifier, onClear: () -> Unit) {
    val reduced = Meka.reducedMotion
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, MekaMotion.appear(reduced)) }
    val track = Meka.colors.surfaceRaised
    val colors = Meka.colors
    val alphas = s.slices.map { sl ->
        androidx.compose.runtime.key(sl.key) {
            animateFloatAsState(if (MoneySpending.lit(sl, focus, s)) 1f else 0.25f, MekaMotion.appear(reduced), label = "money-slice-${sl.key}").value
        }
    }
    val centre = MoneySpending.focus(s, focus)
    Box(
        modifier.size(236.dp).clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(enabled = focus != null, role = Role.Button, onClick = onClear)
            .semantics(mergeDescendants = true) { contentDescription = centre.spoken },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val band = 18.dp.toPx()
            val r = size.minDimension / 2 - band / 2 - 2.dp.toPx()
            val tl = Offset(center.x - r, center.y - r)
            val box = Size(r * 2, r * 2)
            drawArc(track, 0f, 360f, false, tl, box, style = Stroke(band))
            val gap = if (s.slices.size > 1) 1.2f else 0f
            val p = sweep.value
            s.slices.forEachIndexed { i, sl ->
                val start = -90f + sl.start * 360f * p
                val len = (sl.sweep * 360f * p - gap).coerceAtLeast(0.4f)
                drawArc(colors.moneyTone(sl.tone).copy(alpha = alphas[i]), start, len, false, tl, box, style = Stroke(band))
            }
        }
        androidx.compose.animation.Crossfade(centre, animationSpec = MekaMotion.appear(reduced), label = "money-centre") { c ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(moneyShown(c.top), style = MekaType.caption, color = Meka.colors.textSecondary, textAlign = TextAlign.Center)
                Text(c.big, style = MekaType.greeting, color = Meka.colors.textPrimary)
                c.sub?.let { sub ->
                    Text(moneyShown(sub), style = MekaType.caption, textAlign = TextAlign.Center,
                        color = toneColor(c.tone, Meka.colors.success, Meka.colors.accent, Meka.colors.textSecondary),
                        modifier = Modifier.padding(horizontal = MekaSpace.l))
                }
            }
        }
    }
}

/**
 * Came in · Bills to come · Left, side by side in raised tiles. Walk-through 2026-11-08 A19: each label stepped down
 * on its own, so on the closed Fold "Bills to come" read smaller than "Came in" and "Left"; now the three labels share
 * one size (the largest at which every one fits its tile, never below 10 sp), and so do the three figures (never below
 * 11 sp), found by measuring.
 */
@Composable
internal fun MoneyTiles(tiles: List<MoneyTile>, modifier: Modifier = Modifier) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val n = tiles.size.coerceAtLeast(1)
        val room = if (constraints.hasBoundedWidth) with(density) { ((maxWidth - MekaSpace.xs * (n - 1)) / n - MekaSpace.m * 2).toPx() } else Float.MAX_VALUE
        val labelStyle = MekaType.caption
        val valueStyle = MekaType.metaStrong
        val labelSize = remember(room, tiles.map { it.label }, density) {
            sharedFitSize(labelStyle.fontSize, TILE_LABEL_MIN) { size ->
                tiles.all { measurer.measure(it.label, labelStyle.copy(fontSize = size), maxLines = 1, softWrap = false).size.width <= room } // type: ok (measuring at a candidate size)
            }
        }
        val valueSize = remember(room, tiles.map { it.value }, density) {
            sharedFitSize(valueStyle.fontSize, TILE_VALUE_MIN) { size ->
                tiles.all { measurer.measure(it.value, valueStyle.copy(fontSize = size), maxLines = 1, softWrap = false).size.width <= room } // type: ok (measuring at a candidate size)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            tiles.forEach { t ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                        .semantics(mergeDescendants = true) { contentDescription = "${t.label}, ${t.value}" }
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) {
                    // As the mockup: the label whole ("Bills to come", never "Bills to"), at the row's one size.
                    BasicText(
                        t.label, style = labelStyle.copy(color = Meka.colors.textSecondary), maxLines = 1, softWrap = false,
                        // Capped at the row's one size; still steps down if the drawn tile is narrower than measured.
                        autoSize = TextAutoSize.StepBased(minFontSize = TILE_LABEL_MIN, maxFontSize = labelSize, stepSize = 0.5.sp), // type: ok (caption at the size every tile's label fits)
                    )
                    // Walk-through A3 (2026-11-08): the figure whole ("£2,125.91", never "£2,125."), stepping down to
                    // fit three tiles on the closed Fold rather than cutting at the point; one size for the three.
                    BasicText(
                        t.value, style = valueStyle.copy(color = if (t.lit) Meka.colors.accent else Meka.colors.textPrimary), maxLines = 1, softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = TILE_VALUE_MIN, maxFontSize = valueSize, stepSize = 0.5.sp), // type: ok (metaStrong at the size every tile's figure fits)
                    )
                }
            }
        }
    }
}

private val TILE_LABEL_MIN = 10.sp // type: ok (the smallest a tile's label goes)
private val TILE_VALUE_MIN = 11.sp // type: ok (the smallest a tile's figure goes)

/** The largest size from [max] down to [min] in half steps at which [fits]; [min] when none does. */
internal fun sharedFitSize(max: androidx.compose.ui.unit.TextUnit, min: androidx.compose.ui.unit.TextUnit, fits: (androidx.compose.ui.unit.TextUnit) -> Boolean): androidx.compose.ui.unit.TextUnit {
    var size = max.value
    while (size > min.value) {
        if (fits(size.sp)) return size.sp
        size -= 0.5f
    }
    return min
}

/** A category row: chip, name and share, amount, a thin bar in its colour against the biggest, and the note. */
@Composable
internal fun MoneySpendRowView(r: MoneySpendRow, picked: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val reduced = Meka.reducedMotion
    var shown by remember { mutableStateOf(if (reduced) r.bar else 0f) }
    LaunchedEffect(r.bar) { shown = r.bar }
    val bar by animateFloatAsState(shown, MekaMotion.appear(reduced), label = "money-row-bar-${r.key}")
    val tone by animateColorAsState(Meka.colors.moneyTone(r.tone), MekaMotion.appear(reduced), label = "money-row-tone-${r.key}")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onPick)
            .semantics(mergeDescendants = true) { contentDescription = r.spoken; selected = picked }
            .minTouch().padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        MoneyIconChip(r.icon, r.tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(r.label, style = MekaType.taskTitle, color = Meka.colors.textPrimary, maxLines = 1)
                Text(r.pct, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
                Text(r.amount, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
            }
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
                Box(Modifier.fillMaxWidth(bar.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(tone))
            }
            Text(r.note, style = MekaType.caption, maxLines = 2,
                color = toneColor(r.noteTone, Meka.colors.success, Meka.colors.accent, Meka.colors.textSecondary))
        }
    }
}

/** A section's head in caps with an accent link on the right ("All 10 ›", "See all 42 ›"). */
@Composable
internal fun MoneyHeadWithLink(head: String, link: String?, modifier: Modifier = Modifier, onLink: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
        MoneyLabel(head, modifier = Modifier.weight(1f))
        link?.let { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onLink).minTouch()
                    .padding(horizontal = MekaSpace.xs))
        }
    }
}

/** A day's line over its payments: "Today" and the day's money out. */
@Composable
internal fun MoneyDayHead(d: MoneyPayDay, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
        Text(d.label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
        d.total?.let { Text(moneyShown(it), style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
}

/**
 * Needs and wants: the view chips over Spending's ring (the mockup's Categories · Needs vs wants). Each is a 44 dp pill,
 * the picked one lit; the ring and rows cross-fade between the views (they're keyed by category, so the list animates).
 */
@Composable
internal fun MoneyViewChips(needs: Boolean, modifier: Modifier = Modifier, onPick: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        for (c in os.meka.core.domain.MoneyNeeds.views(needs)) {
            TripPill(c.label, on = c.on, tag = "money-view-${if (c.needs) "needs" else "categories"}", description = c.spoken) { onPick(c.needs) }
        }
    }
}

/**
 * Money review (Meka 2026-10-26 22:12): one payment worth a look as a compact row on the raised surface — what it is
 * (one line), the amount and a chevron. A tap opens it in place on the expand spring (when and where, That's fine, and
 * that Navilife never acts) with a tick haptic; a swipe either way is That's fine (the brass layer, tick when armed,
 * light haptic on letting go). Reduced motion: it opens and goes at once. Screen readers get a "That's fine" action.
 */
@Composable
internal fun MoneyLookRowView(r: MoneyLookRow, open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit, onFine: () -> Unit) {
    val reduced = Meka.reducedMotion
    SwipeToDone(MoneyLook.FINE_SPOKEN, onFine, modifier.testTag("money-look-${r.id}"), action = MoneyWatch.FINE) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        ) {
            Row(
                Modifier.fillMaxWidth().minTouch().semantics(mergeDescendants = true) { contentDescription = r.spoken },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(r.name, style = MekaType.body, color = if (r.lit) Meka.colors.accent else Meka.colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (r.amount.isNotEmpty()) Text(r.amount, style = MekaType.body, color = Meka.colors.textPrimary)
                val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.expand(reduced), label = "money-look-chevron")
                Text("›", style = MekaType.body, color = Meka.colors.textTertiary,
                    modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(
                open,
                enter = if (reduced) fadeIn(MekaMotion.expand(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(),
                exit = if (reduced) fadeOut(MekaMotion.expand(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(),
            ) {
                Column(Modifier.padding(bottom = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(moneyShown(r.line), style = MekaType.caption, color = Meka.colors.textSecondary)
                    Text(MoneyWatch.FINE, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onFine)
                            .minTouch().testTag("money-look-fine-${r.id}"))
                    Text(moneyShown(MoneyWatch.FOOT), style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
    }
}

/**
 * Money review: under Spending's category rows, "52 payments without a category" with Sort them in the accent, as one
 * card row; a tap opens the quick sorter (the payment sheet, one payee at a time).
 */
@Composable
internal fun MoneyUnsortedRow(u: MoneyUnsortedView, modifier: Modifier = Modifier, onSort: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onSort).minTouch()
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = u.spoken }
            .testTag("money-unsorted"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(u.line, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(MoneySorter.ACTION + " ›", style = MekaType.itemMeta, color = Meka.colors.accent)
    }
}
