package os.meka.android.news

import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.foundation.lazy.items
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

/**
 * The Today rebuild, slice 2b-2b-2b-3 (Meka 2026-11-09 08:09 (d)): NEWS as the approved mockup's row of picture cards
 * (core [TodayNewsRules]), in place of the drifting ticker on Today. It starts at the first card, whole, glides on to
 * the next card every few seconds on its own (as the ticker drifted), and a swipe of his settles on a card's start
 * (snap), so a card is never left half off the left edge. Reduced motion: it stays still. A tap presses the card in with a light haptic and opens the story
 * (or the match). The picture cross-fades in over its lettered tile ([NewsThumb]).
 */
@Composable
fun TodayNewsRow(
    core: MekaCore, ticker: NewsTicker, modifier: Modifier = Modifier,
    openStory: (String) -> Unit, openMatch: (CalendarEvent) -> Unit,
    mode: os.meka.core.domain.TickerMode = os.meka.core.domain.TickerMode.CALM,
) {
    val cards = remember(ticker) { TodayNewsRules.cards(ticker) }
    if (cards.isEmpty()) return
    val haptics = rememberMekaHaptics()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = TodayNewsRules.START_INDEX)
    val snap = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val byId = remember(ticker) { ticker.items.associateBy { it.id } }
    // It drifts across on its own again (Meka 2026-11-09 19:20): a glide to the next card every few seconds, round
    // twice when calm, on and on when Always moving; his own swipe pauses it a few seconds; reduced motion stays still.
    val reduced = Meka.reducedMotion
    var touchedAt by remember { mutableStateOf<Long?>(null) }
    var gliding by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { moving -> if (moving && !gliding) touchedAt = System.currentTimeMillis() }
    }
    LaunchedEffect(cards.size, mode, reduced) {
        var left = TodayNewsRules.driftSteps(mode, cards.size, reduced)
        while (left > 0) {
            delay(TodayNewsRules.DRIFT_STEP_MS)
            if (!TodayNewsRules.mayDrift(touchedAt, System.currentTimeMillis()) || state.isScrollInProgress) continue
            val next = TodayNewsRules.nextIndex(state.firstVisibleItemIndex, cards.size)
            gliding = true
            try { state.animateScrollToItem(next) } finally { gliding = false }
            if (left != Int.MAX_VALUE) left--
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            items(cards, key = { it.key }) { c ->
                NewsCard(core, c, c.storyId?.let { byId[it] }) {
                    haptics.light()
                    val m = c.match
                    if (m != null) openMatch(m.event) else c.storyId?.let(openStory)
                }
            }
        }
    }
}

@Composable
private fun NewsCard(core: MekaCore, c: TodayNewsCard, story: NewsItem?, onTap: () -> Unit) {
    Row(
        Modifier.width(TodayNewsRules.CARD_WIDTH_DP.dp)
            .testTag("today-news-card")
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button, onClickLabel = if (c.match != null) "Open the match" else "Open story", onClick = onTap)
            .padding(MekaSpace.xs)
            .clearAndSetSemantics { contentDescription = c.spoken; role = Role.Button },
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
