package os.meka.android.news

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.NewsItem
import os.meka.core.domain.NewsTicker
import os.meka.core.domain.TodayNewsCard
import os.meka.core.domain.TodayNewsRules
import os.meka.core.facade.MekaCore

internal const val TODAY_NEWS_TAG = "today-news"
internal const val TODAY_NEWS_ROW_TAG = "today-news-row"

/**
 * The Today rebuild, slice 2b-2b-2b-3 (Meka 2026-11-09 08:09 (d)): NEWS as the approved mockup's row of picture cards
 * (core [TodayNewsRules]), in place of the drifting ticker on Today. It starts at the first card, whole, and drifts
 * across continuously on its own (Meka 2026-11-10: as the old ticker did); a swipe of his settles on a card's start
 * (snap) and pauses the drift a few seconds. The app's own Motion → Off: it stays still. A tap presses the card in with a light haptic and opens the story
 * (or the match). The picture cross-fades in over its lettered tile ([NewsThumb]).
 */
@Composable
fun TodayNewsRow(
    core: MekaCore, ticker: NewsTicker, modifier: Modifier = Modifier,
    openStory: (String) -> Unit, openMatch: (CalendarEvent) -> Unit,
    mode: os.meka.core.domain.TickerMode = os.meka.core.domain.TickerMode.MOVING,
) {
    val cards = remember(ticker) { TodayNewsRules.cards(ticker) }
    if (cards.isEmpty()) return
    val haptics = rememberMekaHaptics()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = TodayNewsRules.START_INDEX)
    val snap = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val byId = remember(ticker) { ticker.items.associateBy { it.id } }
    // It drifts across continuously again (Meka 2026-11-10 07:38): a slow constant glide, no hops, looping without a
    // seam (the cards are laid out [TodayNewsRules.LOOP_COPIES] times and it jumps back a set when it passes the first),
    // for as long as Today is on screen, Calm or Always moving. His finger on the row pauses it; it picks up again a
    // few seconds after he lets go. Only the app's own Motion → Off stills it.
    val reduced = Meka.reducedMotion
    val drifting = TodayNewsRules.drifts(mode, cards.size, reduced)
    val count = if (drifting) cards.size * TodayNewsRules.LOOP_COPIES else cards.size
    var touching by remember { mutableStateOf(false) }
    // How many times he has let go of the row: each one restarts the few seconds' wait (counted on the frame clock's
    // delay, so it follows the screen tests' clock too).
    var letGo by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(drifting, cards.size) {
        if (!drifting) return@LaunchedEffect
        val pxPerMs = with(density) { TodayNewsRules.SPEED_DP_PER_S.dp.toPx() } / 1000f
        // An endless drift: run it as an infinite animation, so a screen test that waits for Today to settle isn't held
        // up by it (the test's policy stops it then; a test that drives the clock itself sees it move).
        val drift: suspend () -> Unit = {
            var waited = letGo
            while (currentCoroutineContext().isActive) {
                if (touching || state.isScrollInProgress) { delay(100); continue }
                if (letGo != waited) { waited = letGo; delay(TodayNewsRules.RESUME_AFTER_MS); continue }
                TodayNewsRules.wrapIndex(state.firstVisibleItemIndex, cards.size)?.let { back ->
                    state.scrollToItem(back, state.firstVisibleItemScrollOffset)
                }
                try {
                    state.scroll {
                        var last = withFrameNanos { it }
                        while (!touching) {
                            val now = withFrameNanos { it }
                            val dtMs = ((now - last) / 1_000_000L).coerceIn(0L, 100L)
                            last = now
                            scrollBy(pxPerMs * dtMs)
                            if (state.firstVisibleItemIndex >= cards.size) break
                        }
                    }
                } catch (e: CancellationException) {
                    // His swipe took the row over (a user scroll outranks the drift): wait, then pick up again.
                    if (!currentCoroutineContext().isActive) throw e
                }
            }
        }
        val policy = coroutineContext[InfiniteAnimationPolicy]
        try {
            if (policy != null) policy.onInfiniteOperation { drift() } else drift()
        } catch (e: CancellationException) {
            if (!isActive) throw e
        }
    }
    Column(modifier.fillMaxWidth().testTag(TODAY_NEWS_TAG), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(TodayNewsRules.LABEL, style = MekaType.cardLabel, color = Meka.colors.textSecondary, maxLines = 1,
                modifier = Modifier.weight(1f).semantics { heading(); contentDescription = TodayNewsRules.spokenLabel(cards.size) })
            val hint = TodayNewsRules.hint(cards.size)
            if (hint.isNotEmpty()) Text(hint, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1)
        }
        LazyRow(
            state = state, flingBehavior = snap,
            modifier = Modifier.fillMaxWidth().testTag(TODAY_NEWS_ROW_TAG).pointerInput(Unit) {
                // Watches his finger without taking it: the row still scrolls and the cards still open.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touching = true
                    try {
                        do {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                        } while (e.changes.any { it.pressed })
                    } finally {
                        touching = false
                        letGo++
                    }
                }
            },
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            items(count, key = { i -> if (i < cards.size) cards[i].key else cards[i % cards.size].key + "#" + i / cards.size }) { i ->
                val c = cards[i % cards.size]
                // The copies that make the loop seamless are for the eye only: a screen reader hears each story once.
                NewsCard(core, c, c.storyId?.let { byId[it] }, copy = i >= cards.size) {
                    haptics.light()
                    val m = c.match
                    if (m != null) openMatch(m.event) else c.storyId?.let(openStory)
                }
            }
        }
    }
}

@Composable
private fun NewsCard(core: MekaCore, c: TodayNewsCard, story: NewsItem?, copy: Boolean = false, onTap: () -> Unit) {
    Row(
        Modifier.width(TodayNewsRules.CARD_WIDTH_DP.dp)
            .testTag("today-news-card")
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button, onClickLabel = if (c.match != null) "Open the match" else "Open story", onClick = onTap)
            .padding(MekaSpace.xs)
            .clearAndSetSemantics { if (copy) hideFromAccessibility() else { contentDescription = c.spoken; role = Role.Button } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        val thumb = Modifier.size(TodayNewsRules.THUMB_DP.dp)
        if (story != null) {
            NewsThumb(core, story, thumb, corner = MekaRadius.s)
        } else {
            // The match: a tile in Barça's colour with a dot, as the ticker's match card had.
            Box(thumb.clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.barca.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Meka.colors.barca))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Crossfade(c.title, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "today-news-title") { t ->
                Text(t, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.barca) Box(Modifier.padding(end = MekaSpace.xxs).size(5.dp).clip(CircleShape).background(Meka.colors.barca))
                Text(c.meta, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
