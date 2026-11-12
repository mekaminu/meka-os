package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import os.meka.android.lists.Field
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.CountdownAdded
import os.meka.core.domain.CountdownIcon
import os.meka.core.domain.CountdownRow
import os.meka.core.domain.CountdownRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls. */
const val COUNTDOWNS_LIST_TAG = "countdowns-list"

/**
 * Ask → Life → Countdowns (Countdowns, slice 1; the approved design `docs/design/countdowns/Countdowns.dc.html`): the
 * days, or sleeps for the boys, to a holiday, a birthday, an anniversary or Christmas. The one on Today (else the
 * soonest) sits large with a ring and its number; the rest are two-column cards, soonest first. + Add opens a field
 * ("Holiday 13 Feb", "… every year"); birthdays in Gifts, trips and Christmas appear by themselves. A tap shows one full
 * screen (its name, a huge number, twinkling dots) with Show on Today, Remove (or Hide for an automatic one) and Done.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the hero, switch and cards stagger in 40 ms
 * apart (cards two to a row); the ring sweeps from the top to its fill on the replan spring, and the numbers cross-fade
 * when Days · Sleeps flips (the lit half blends across); the field unfolds on the expand spring; the full-screen view
 * scale-fades in and its dots twinkle (2.4 s, staggered); Add, Show on Today and the switch give a light haptic (a tick
 * when refused), Remove and Hide a tick. Reduced motion: cross-fades, the ring shown filled, the dots still.
 */
@Composable
fun CountdownsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.countdownsView.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<CountdownAdded?>(null) }
    var big by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "countdowns-said",
    )
    val all = listOfNotNull(v.hero) + v.rows

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(COUNTDOWNS_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            item(key = "close") {
                Text("‹ Ask", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(CountdownRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                        modifier = Modifier.weight(1f).sharedTitleInPane(SharedMotion.paneKey(MoreItem.COUNTDOWNS)).appear(rememberAppearance(0)))
                    Text(
                        "+ Add", style = MekaType.metaStrong, color = Meka.colors.onAccent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                            .clickable(role = Role.Button) { haptics.light(); adding = !adding; said = null }
                            .semantics { contentDescription = "Add a countdown" }
                            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    )
                }
            }
            item(key = "field") {
                AnimatedVisibility(
                    visible = adding,
                    enter = if (reduced) fadeIn() else fadeIn() + expandVertically(MekaMotion.expand(reduced)),
                    exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(MekaMotion.expand(reduced)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Field(CountdownRules.ADD_HINT, description = CountdownRules.ADD_DESCRIPTION) { text ->
                            scope.launch {
                                val r = runCatching { core.addCountdown(text) }.getOrNull()
                                if (r?.refused == false) haptics.light() else haptics.tick()
                                said = r
                            }
                        }
                        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "countdowns-added") { r ->
                            r?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                        }
                    }
                }
            }
            item(key = "units") {
                UnitSwitch(v.sleeps, Modifier.appear(rememberAppearance(1))) { sleeps ->
                    haptics.light()
                    scope.launch { runCatching { core.setCountdownSleeps(sleeps) } }
                }
            }
            v.hero?.let { hero ->
                item(key = "hero") {
                    HeroCard(hero, v.heroOnToday, Modifier.animateItem().appear(rememberAppearance(2))) { haptics.light(); big = hero.key }
                }
            }
            itemsIndexed(v.rows.chunked(2), key = { _, pair -> "countdowns-" + pair.joinToString("+") { it.key } }) { i, pair ->
                Row(Modifier.fillMaxWidth().animateItem().appear(rememberAppearance(3 + i)), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    pair.forEach { r -> SmallCard(r, Modifier.weight(1f)) { haptics.light(); big = r.key } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (v.hiddenCount > 0) {
                item(key = "hidden") {
                    Text(
                        "Show hidden · ${v.hiddenCount}", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.animateItem().clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) { haptics.light(); scope.launch { runCatching { core.showHiddenCountdowns() } } }
                            .minTouch().padding(vertical = MekaSpace.xs),
                    )
                }
            }
            item(key = "note") {
                Text(CountdownRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.xs))
            }
        }

        val shown = all.firstOrNull { it.key == big }
        // Keeps the last one drawn while it fades out.
        var last by remember { mutableStateOf<CountdownRow?>(null) }
        if (shown != null && shown != last) last = shown
        AnimatedVisibility(
            visible = shown != null,
            enter = if (reduced) fadeIn() else fadeIn(MekaMotion.appear(reduced)) + scaleIn(MekaMotion.appear(reduced), initialScale = 0.96f),
            exit = if (reduced) fadeOut() else fadeOut(MekaMotion.appear(reduced)) + scaleOut(MekaMotion.appear(reduced), targetScale = 0.96f),
        ) {
            val r = last ?: return@AnimatedVisibility
            BigCountdown(
                row = r, onToday = v.heroOnToday && v.hero?.key == r.key,
                onShow = {
                    haptics.light(); big = null
                    scope.launch { runCatching { core.showCountdownOnToday(if (v.heroOnToday && v.hero?.key == r.key) "" else r.key) } }
                },
                onRemove = { haptics.tick(); big = null; scope.launch { runCatching { core.removeCountdown(r.key) } } },
                onDone = { big = null },
            )
        }
    }
}

@Composable
private fun UnitSwitch(sleeps: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface).padding(MekaSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        listOf(false to "Days", true to "Sleeps").forEach { (s, label) ->
            val on = s == sleeps
            val bg by animateColorAsState(if (on) Meka.colors.surfaceRaised else Color.Transparent, MekaMotion.appear(reduced), label = "unit-$label")
            val fg by animateColorAsState(if (on) Meka.colors.textPrimary else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "unit-fg-$label")
            Text(
                label, style = if (on) MekaType.captionStrong else MekaType.caption, color = fg,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                    .clickable(role = Role.Button) { if (!on) onChange(s) }
                    .semantics { contentDescription = "Count in ${label.lowercase()}" + if (on) ", on" else "" }
                    .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}

/** The ring: a track and the accent arc from the top, sweeping to [fill] (shown at once with reduced motion). */
@Composable
private fun Ring(fill: Double, modifier: Modifier, stroke: Float) {
    val reduced = Meka.reducedMotion
    var started by remember { mutableStateOf(reduced) }
    val sweep by animateFloatAsState(if (started) fill.toFloat() else 0f, MekaMotion.replan(reduced), label = "countdown-ring")
    androidx.compose.runtime.LaunchedEffect(Unit) { started = true }
    val track = Meka.colors.hairline
    val accent = Meka.colors.accent
    Canvas(modifier) {
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (sweep > 0f) drawArc(accent, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun HeroCard(row: CountdownRow, onToday: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .border(1.dp, Meka.colors.accent.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .background(Meka.colors.accent.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = row.spoken + if (onToday) ". On Today" else "" }
            .padding(MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Ring(row.fill, Modifier.fillMaxSize(), stroke = 18f)
            Crossfade(row.number to row.unit, animationSpec = MekaMotion.appear(reduced), label = "hero-number") { (n, u) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n, style = if (n.length > 3) MekaType.greetingCompact else MekaType.greeting, color = Meka.colors.textPrimary, maxLines = 1)
                    if (u.isNotEmpty()) Text(u, style = MekaType.micro, color = Meka.colors.textSecondary)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(if (onToday) "NEXT UP · ON TODAY" else "NEXT UP", style = MekaType.sectionLabel, color = Meka.colors.accent)
            Text(row.name, style = MekaType.nowTitle, color = Meka.colors.textPrimary)
            Text(row.dateLine, style = MekaType.caption, color = Meka.colors.textSecondary)
            row.extra?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
    }
}

@Composable
private fun SmallCard(row: CountdownRow, modifier: Modifier, onTap: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onTap)
            .semantics(mergeDescendants = true) { contentDescription = row.spoken }
            .padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Meka.colors.surfaceRaised), contentAlignment = Alignment.Center) {
                CountdownGlyph(row.icon, Modifier.size(14.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(row.dateLine, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
        }
        Crossfade(row.number to row.unit, animationSpec = MekaMotion.appear(reduced), label = "card-number") { (n, u) ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(n, style = if (n.length > 3) MekaType.nowTitle else MekaType.greetingCompact, color = Meka.colors.textPrimary, maxLines = 1)
                if (u.isNotEmpty()) Text(u, style = MekaType.micro, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xxs))
            }
        }
        Text(row.name, style = MekaType.captionStrong, color = Meka.colors.textPrimary, maxLines = 2)
    }
}

/** The card's small outline icon, drawn like the Life tiles' (LifeIcon.kt). */
@Composable
private fun CountdownGlyph(icon: CountdownIcon, modifier: Modifier) {
    val c = Meka.colors.accent
    Canvas(modifier) {
        val w = size.width
        val s = Stroke(w * 0.12f, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(w * x1, w * y1), Offset(w * x2, w * y2), w * 0.12f, StrokeCap.Round)
        when (icon) {
            CountdownIcon.GIFT -> {
                drawRect(c, Offset(w * 0.16f, w * 0.44f), Size(w * 0.68f, w * 0.44f), style = s)
                line(0.1f, 0.44f, 0.9f, 0.44f); line(0.5f, 0.3f, 0.5f, 0.88f)
                drawCircle(c, w * 0.1f, Offset(w * 0.4f, w * 0.26f), style = s); drawCircle(c, w * 0.1f, Offset(w * 0.6f, w * 0.26f), style = s)
            }
            CountdownIcon.CAKE -> {
                drawRect(c, Offset(w * 0.16f, w * 0.5f), Size(w * 0.68f, w * 0.36f), style = s)
                line(0.5f, 0.5f, 0.5f, 0.3f); drawCircle(c, w * 0.05f, Offset(w * 0.5f, w * 0.18f))
            }
            CountdownIcon.PLANE -> {
                line(0.1f, 0.5f, 0.9f, 0.16f); line(0.9f, 0.16f, 0.6f, 0.9f); line(0.6f, 0.9f, 0.46f, 0.56f); line(0.46f, 0.56f, 0.1f, 0.5f)
            }
            CountdownIcon.HEART -> heart(c, s)
            CountdownIcon.STAR -> {
                line(0.5f, 0.1f, 0.5f, 0.9f); line(0.12f, 0.36f, 0.88f, 0.64f); line(0.12f, 0.64f, 0.88f, 0.36f)
            }
        }
    }
}

private fun DrawScope.heart(c: Color, s: Stroke) {
    val w = size.width
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.5f, w * 0.86f)
        cubicTo(w * 0.1f, w * 0.6f, w * 0.06f, w * 0.3f, w * 0.28f, w * 0.2f)
        cubicTo(w * 0.4f, w * 0.15f, w * 0.48f, w * 0.24f, w * 0.5f, w * 0.32f)
        cubicTo(w * 0.52f, w * 0.24f, w * 0.6f, w * 0.15f, w * 0.72f, w * 0.2f)
        cubicTo(w * 0.94f, w * 0.3f, w * 0.9f, w * 0.6f, w * 0.5f, w * 0.86f)
    }
    drawPath(p, c, style = s)
}

/** Where the twinkling dots sit, as fractions of the screen (the design's eight). */
private val STARS = listOf(0.12f to 0.14f, 0.78f to 0.09f, 0.30f to 0.30f, 0.88f to 0.36f, 0.08f to 0.62f, 0.70f to 0.70f, 0.22f to 0.86f, 0.90f to 0.88f)

@Composable
private fun BigCountdown(row: CountdownRow, onToday: Boolean, onShow: () -> Unit, onRemove: () -> Unit, onDone: () -> Unit) {
    val reduced = Meka.reducedMotion
    val twinkle = rememberInfiniteTransition(label = "countdown-stars")
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Meka.colors.background)
            // Taps on the backdrop don't fall through to the list behind.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        STARS.forEachIndexed { i, (x, y) ->
            val a = if (reduced) 0.6f else twinkle.animateFloat(
                0.25f, 1f, infiniteRepeatable(tween(1_200, delayMillis = 0), RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 300)),
                label = "star-$i",
            ).value
            Box(Modifier.offset(maxWidth * x, maxHeight * y).size(4.dp).alpha(a).clip(RoundedCornerShape(2.dp)).background(Meka.colors.accent))
        }
        Column(
            Modifier.fillMaxSize().padding(MekaSpace.l)
                .semantics(mergeDescendants = true) { contentDescription = row.spoken },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs, Alignment.CenterVertically),
        ) {
            Text(row.name.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.accent, textAlign = TextAlign.Center)
            // The approved design's kid-sized number, read across the room; "Today" on the day sits smaller.
            val bigStyle = if (row.number.length > 3) MekaType.clock else MekaType.clock.copy(fontSize = 140.sp, fontWeight = FontWeight.Bold, lineHeight = 150.sp) // type: ok, the design's huge number
            Text(row.number, style = bigStyle, color = Meka.colors.textPrimary, maxLines = 1)
            if (row.unit.isNotEmpty()) Text("${row.unit} to go", style = MekaType.nowTitle, color = Meka.colors.textPrimary)
            Text(row.dateLine + (row.extra?.let { " · $it" } ?: ""), style = MekaType.caption, color = Meka.colors.textSecondary)
            Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(
                    if (onToday) "Take off Today" else "Show on Today", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                        .clickable(role = Role.Button, onClick = onShow).minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
                Text(
                    "Done", style = MekaType.metaStrong, color = Meka.colors.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                        .clickable(role = Role.Button, onClick = onDone).minTouch().padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
                )
            }
            Text(
                if (row.canRemove) "Remove" else "Hide", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = (if (row.canRemove) "Remove " else "Hide ") + row.name }
                    .minTouch().padding(MekaSpace.xs),
            )
        }
    }
}
