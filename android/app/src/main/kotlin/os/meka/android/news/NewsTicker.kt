package os.meka.android.news

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.NewsItem
import os.meka.core.domain.NewsMatchday
import os.meka.core.domain.NewsTicker
import os.meka.core.domain.TickerDrift
import os.meka.core.domain.TickerMode
import os.meka.core.domain.TickerRules
import os.meka.core.facade.MekaCore
import kotlin.math.roundToInt

/**
 * The per-device ticker choice (Appearance → News ticker), kept beside the theme. Calm by default.
 */
object TickerChoice {
    private const val PREFS = "meka_ui"
    private const val KEY = "newsTicker"
    private var loaded: MutableState<TickerMode>? = null

    fun state(context: Context): MutableState<TickerMode> = loaded ?: mutableStateOf(
        TickerRules.mode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)),
    ).also { loaded = it }

    fun set(context: Context, mode: TickerMode) {
        state(context).value = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.id).apply()
    }
}

/** The ticker choice for this composition (recomposes when Appearance changes it). */
@Composable
fun rememberTickerMode(): TickerMode = TickerChoice.state(LocalContext.current.applicationContext).value

private sealed interface TickerCard {
    val key: String
    data class Match(val m: NewsMatchday) : TickerCard { override val key get() = "match:" + m.eventId }
    data class Story(val item: NewsItem) : TickerCard { override val key get() = item.id }
}

private fun cards(t: NewsTicker): List<TickerCard> =
    listOfNotNull(t.matchday?.let { TickerCard.Match(it) }) + t.items.map { TickerCard.Story(it) }

private val CARD_GAP = 12.dp
private val STRIP_HEIGHT = 64.dp

/**
 * The news ticker (news ticker, slice 2): picture-and-headline cards drifting right to left at a slow constant speed
 * ([TickerRules.SPEED_DP_PER_S]) in a seamless loop, today's match first in Barça's colour. A finger on it holds it;
 * dragging scrubs; tapping a story springs the News detail up on it, the match opens its own detail. In calm mode it
 * drifts [TickerRules.CALM_LOOPS] times each time it appears and then rests, with ▸ to set it going again (tick haptic).
 * It only moves while it is composed (on screen) and frames are drawn (the screen is on). Reduced motion: no drift,
 * one still card with ‹ › paging. The bedside clock (slice 4) runs it slower with [speedDpPerS].
 */
@Composable
fun NewsTickerStrip(
    core: MekaCore, ticker: NewsTicker, mode: TickerMode, modifier: Modifier = Modifier,
    openStory: (String) -> Unit, openMatch: (CalendarEvent) -> Unit,
    speedDpPerS: Float = TickerRules.SPEED_DP_PER_S,
) {
    val list = remember(ticker) { cards(ticker) }
    if (list.isEmpty() || mode == TickerMode.OFF) return
    val open: (TickerCard) -> Unit = { c -> when (c) { is TickerCard.Match -> openMatch(c.m.event); is TickerCard.Story -> openStory(c.item.id) } }
    if (Meka.reducedMotion) StillTicker(core, list, modifier, open) else DriftingTicker(core, list, mode, modifier, open, speedDpPerS)
}

@Composable
private fun DriftingTicker(core: MekaCore, list: List<TickerCard>, mode: TickerMode, modifier: Modifier, open: (TickerCard) -> Unit, speedDpPerS: Float) {
    val density = LocalDensity.current.density
    val haptics = rememberMekaHaptics()
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var held by remember { mutableStateOf(false) }
    // One set of cards' width plus the gap after it: the loop. Three sets are laid out and the strip stays in the
    // middle one, so it can wrap (and be scrubbed either way) without a seam.
    // Not keyed on the stories: the place refreshes as ages tick on, and that must not restart calm mode's loops.
    var loopPx by remember { mutableIntStateOf(0) }
    var drift by remember { mutableStateOf(TickerDrift.START) }
    BoxWithConstraints(modifier.fillMaxWidth().height(STRIP_HEIGHT)) {
        val viewportPx = constraints.maxWidth
        // A set narrower than the strip can't loop without a gap: it stands still.
        val fits = loopPx > 0 && loopPx >= viewportPx
        val moving = TickerRules.moving(mode, reducedMotion = false, onScreen = true, held = held || dragged, loopsDone = drift.loops, hasItems = fits)
        LaunchedEffect(loopPx) {
            if (loopPx > 0) scroll.scrollTo(loopPx + (drift.offsetDp * density).roundToInt())
        }
        // After a scrub, land on the same picture in the middle set (identical, so the jump can't be seen).
        LaunchedEffect(dragged, held) {
            if (!dragged && !held && loopPx > 0) {
                drift = TickerRules.scrubbed(drift, (scroll.value - loopPx) / density, loopPx / density)
                scroll.scrollTo(loopPx + (drift.offsetDp * density).roundToInt())
            }
        }
        LaunchedEffect(moving) {
            if (!moving) return@LaunchedEffect
            var last = withFrameNanos { it }
            while (isActive) {
                val now = withFrameNanos { it }
                val next = TickerRules.stepAt(drift, (now - last) / 1_000_000L, loopPx / density, speedDpPerS)
                last = now
                drift = next
                scroll.scrollTo(loopPx + (next.offsetDp * density).roundToInt())
            }
        }
        Row(
            Modifier.fillMaxWidth().height(STRIP_HEIGHT)
                .fadedEdges()
                .pointerInput(Unit) {
                    // Watch the finger without taking it, so taps and drags still reach the cards and the scroll.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        held = true
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                        } while (e.changes.any { it.pressed })
                        held = false
                    }
                }
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { copy ->
                Row(
                    if (copy == 0) Modifier.onSizeChanged { loopPx = it.width + (CARD_GAP.value * density).roundToInt() } else Modifier,
                    horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    list.forEach { c -> Card(core, c, Modifier, open, hidden = copy != 1) }
                }
            }
        }
        // Calm mode, rested: ▸ sets it drifting again.
        AnimatedVisibility(
            visible = fits && TickerRules.offersPlay(mode, reducedMotion = false, loopsDone = drift.loops, hasItems = true),
            enter = fadeIn(MekaMotion.appear(false)), exit = fadeOut(MekaMotion.appear(false)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, onClickLabel = "Play the news ticker") { haptics.tick(); drift = drift.copy(loops = 0) }
                    .semantics { contentDescription = "Play the news ticker" },
                contentAlignment = Alignment.Center,
            ) { Text("▸", style = MekaType.itemMeta, color = Meka.colors.accent) }
        }
    }
}

/** Reduced motion: one card at a time, ‹ 2 of 12 › to page (wrapping), cross-fading between cards. */
@Composable
private fun StillTicker(core: MekaCore, list: List<TickerCard>, modifier: Modifier, open: (TickerCard) -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val haptics = rememberMekaHaptics()
    Row(modifier.fillMaxWidth().height(STRIP_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = index.coerceIn(0, list.size - 1),
            transitionSpec = { fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true)) },
            label = "ticker-page", modifier = Modifier.weight(1f),
        ) { i -> Card(core, list[i], Modifier.fillMaxWidth(), open, hidden = false) }
        if (list.size > 1) {
            Text("‹", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Previous headline") {
                    haptics.tick(); index = TickerRules.page(index, -1, list.size)
                }.padding(MekaSpace.m))
            Text(TickerRules.pageLabel(index, list.size), style = MekaType.caption, color = Meka.colors.textTertiary)
            Text("›", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Next headline") {
                    haptics.tick(); index = TickerRules.page(index, 1, list.size)
                }.padding(MekaSpace.m))
        }
    }
}

/** One card: a story's picture, title (regular weight: news is context) and "Sport · 2 h ago"; or the match line. */
@Composable
private fun Card(core: MekaCore, c: TickerCard, modifier: Modifier, open: (TickerCard) -> Unit, hidden: Boolean) {
    // The copies either side of the middle set are for the seamless loop only: screen readers hear one set.
    val sem = if (hidden) Modifier.clearAndSetSemantics { }
    else Modifier.semantics(mergeDescendants = true) {
        contentDescription = when (c) { is TickerCard.Match -> c.m.line; is TickerCard.Story -> TickerRules.spoken(c.item) }
    }
    Row(
        modifier.then(sem).height(STRIP_HEIGHT - 8.dp).widthIn(max = 300.dp)
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button, onClickLabel = if (c is TickerCard.Match) "Open the match" else "Open story") { open(c) }
            .padding(horizontal = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (c) {
            is TickerCard.Match -> {
                Box(Modifier.padding(end = MekaSpace.xs).size(8.dp).clip(CircleShape).background(Meka.colors.barca))
                AnimatedContent(
                    targetState = c.m.line,
                    transitionSpec = { fadeIn(MekaMotion.appear(false)) togetherWith fadeOut(MekaMotion.appear(false)) },
                    label = "ticker-matchday",
                ) { line -> Text(line, style = MekaType.itemMeta, color = Meka.colors.barca, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            is TickerCard.Story -> {
                NewsThumb(core, c.item, Modifier.size(width = 56.dp, height = 42.dp))
                Column(Modifier.padding(start = MekaSpace.xs).width(200.dp)) {
                    Text(c.item.title, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.item.topic == "barca") {
                            Box(Modifier.padding(end = MekaSpace.xxs).size(5.dp).clip(CircleShape).background(Meka.colors.barca))
                        }
                        Text(c.item.meta, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The strip fades out over [TickerRules.EDGE_FADE_DP] at both ends, so words fade in and out rather than cut. */
private fun Modifier.fadedEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = TickerRules.edgeFadeFraction(size.width / density)
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent, edge to Color.Black, (1f - edge) to Color.Black, 1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
