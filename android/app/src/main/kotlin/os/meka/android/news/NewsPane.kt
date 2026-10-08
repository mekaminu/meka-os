package os.meka.android.news

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.goals.Chips
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.calendar.EventDetailPane
import os.meka.android.today.SectionLabel
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.NewsDetail
import os.meka.core.domain.NewsItem
import os.meka.core.domain.NewsLane
import os.meka.core.domain.NewsMatchday
import os.meka.core.facade.MekaCore

/** A horizontal drag past this many dp moves to the next or previous story. */
private const val SWIPE_DP = 72f

/**
 * News (build plan M1, "News ticker + AI/tech sources", slice 1): Ask → More → News. The chosen topics as lanes,
 * Barça first, then AI, then the rest, each story once with its source and age; Topics unfolds the chips (synced with
 * the brief's). Tapping a story springs its detail sheet up: title, source and time, the feed's own summary as plain
 * text, "Read full story" (opens the browser, https only), and Previous/Next (buttons or a sideways swipe).
 * Headlines are untrusted (ADR-006): text only, nothing in MEKA acts on them.
 *
 * Slice 2: on matchday the place leads with the fixture ("Barça v Real Madrid · 21:00 · in 3 h"; tap for its detail),
 * and the Barça lane wears its own colour (the `barca` token). It also opens over Today from the command centre's
 * News, then [backLabel] says where back goes and [startStoryId] opens straight onto that story.
 *
 * Motion: lanes stagger in 40 ms apart; the topic chips unfold in place and a chosen chip's colour blends with a tick
 * haptic; the detail springs up from the bottom; Previous/Next slide the story across the way you moved with a tick
 * haptic. Reduced motion: cross-fades only.
 */
@Composable
fun NewsPane(core: MekaCore, onClose: () -> Unit, backLabel: String = "‹ Ask", startStoryId: String? = null) {
    val place by core.newsPlace.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    var topicsOpen by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf(startStoryId) }
    // The match's detail; the last one is kept while the pane leaves.
    var eventOpen by remember { mutableStateOf<CalendarEvent?>(null) }
    var eventShown by remember { mutableStateOf<CalendarEvent?>(null) }
    if (eventOpen != null) eventShown = eventOpen
    BackHandler(enabled = openId != null && eventOpen == null) { openId = null }
    BackHandler(enabled = eventOpen != null) { eventOpen = null }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text(backLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
            }
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(bottom = MekaSpace.m).appear(rememberAppearance(0)), verticalAlignment = Alignment.CenterVertically) {
                    // The title arrives from Ask's More row.
                    Text("News", style = MekaType.greeting, color = Meka.colors.textPrimary,
                        modifier = Modifier.weight(1f).sharedTitleInPane(SharedMotion.paneKey(MoreItem.NEWS)))
                    Text(if (topicsOpen) "Done" else "Topics", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { topicsOpen = !topicsOpen }.padding(MekaSpace.xs))
                }
            }
            item(key = "topics") {
                AnimatedVisibility(
                    visible = topicsOpen,
                    enter = if (Meka.reducedMotion) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                    exit = if (Meka.reducedMotion) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                ) {
                    Column(Modifier.padding(bottom = MekaSpace.m)) {
                        Chips(null, place.topics.map { it.label to it.chosen }) { i ->
                            val t = place.topics[i]
                            haptics.tick()
                            scope.launch { runCatching { core.setNewsTopic(t.id, !t.chosen) } }
                        }
                        Text(
                            "Shown here and in the morning brief. Barça: Mundo Deportivo, Sport and Google News · AI: The Verge, " +
                                "TechCrunch, MIT Technology Review and OpenAI · Tech news: Hacker News (200+ points) · the rest: BBC News. " +
                                "Refreshed every hour by your server; nothing about you is sent.",
                            style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs),
                        )
                    }
                }
            }
            place.matchday?.let { md ->
                item(key = "matchday") {
                    MatchdayRow(md, Modifier.padding(bottom = MekaSpace.m).animateItem().appear(rememberAppearance(1))) {
                        haptics.tick(); eventOpen = md.event
                    }
                }
            }
            place.emptyLine?.let { line ->
                item(key = "empty") {
                    Text(line, style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(1)))
                }
            }
            place.lanes.forEachIndexed { i, lane ->
                item(key = "lane-" + lane.topicId) {
                    LaneHeader(lane, Modifier.padding(top = if (i == 0) 0.dp else MekaSpace.l).animateItem().appear(rememberAppearance(1 + i)))
                }
                items(lane.items, key = { "n-" + it.id }) { n ->
                    StoryRow(n, Modifier.animateItem().appear(rememberAppearance(1 + i))) { haptics.tick(); openId = n.id }
                }
            }
        }

        MekaPane(visible = openId != null) {
            // Keep showing the last story while the sheet drops away.
            val detail = remember(openId, place) { openId?.let { place.detail(it) } }
            val last = remember { arrayOfNulls<NewsDetail>(1) }
            if (detail != null) last[0] = detail
            (detail ?: last[0])?.let { d ->
                NewsDetailSheet(d, onClose = { openId = null }) { id -> haptics.tick(); openId = id }
            }
        }
        MekaPane(visible = eventOpen != null) {
            eventShown?.let { e -> EventDetailPane(core, e, onClose = { eventOpen = null }) }
        }
    }
}

/**
 * Matchday: a card in the Barça colour leading the place ("MATCHDAY" or "ON NOW", then "Barça v Real Madrid · 21:00 ·
 * in 3 h"); the line cross-fades as the time moves on; tap opens the fixture's detail.
 */
@Composable
private fun MatchdayRow(md: NewsMatchday, modifier: Modifier, open: () -> Unit) {
    val reducedLine = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClickLabel = "Open the match") { open() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = MekaSpace.s).size(8.dp).clip(CircleShape).background(Meka.colors.barca))
        Column(Modifier.weight(1f)) {
            Text(if (md.live) "ON NOW" else "MATCHDAY", style = MekaType.sectionLabel, color = Meka.colors.barca)
            AnimatedContent(
                targetState = md.line,
                transitionSpec = { fadeIn(MekaMotion.appear(reducedLine)) togetherWith fadeOut(MekaMotion.appear(reducedLine)) },
                label = "matchday-line",
            ) { line -> Text(line, style = MekaType.body, color = Meka.colors.textPrimary) }
        }
    }
}

@Composable
private fun LaneHeader(lane: NewsLane, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (lane.isBarca) {
            Text(lane.label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.barca, modifier = Modifier.padding(bottom = MekaSpace.xxs))
        } else SectionLabel(lane.label)
        if (lane.sources.isNotEmpty()) Text(lane.sources, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** A story: a small dot (Barça's colour on Barça stories), the title (regular weight: news is context, not something to act on), "Sport · 2 h ago". */
@Composable
private fun StoryRow(n: NewsItem, modifier: Modifier, open: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClickLabel = "Open story") { open() }
            .padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 7.dp, end = MekaSpace.s).size(6.dp).clip(CircleShape)
            .background(if (n.topic == "barca") Meka.colors.barca else Meka.colors.textTertiary))
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(n.meta, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}

/**
 * The detail sheet: the story with its summary, "Read full story", Previous/Next. A sideways swipe moves too; the
 * story slides across the way you moved (reduced motion: cross-fade).
 */
@Composable
private fun NewsDetailSheet(d: NewsDetail, onClose: () -> Unit, go: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val reduced = Meka.reducedMotion
    var dragged by remember { mutableStateOf(0f) }
    val swipePx = SWIPE_DP

    Column(
        Modifier.fillMaxSize().padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.l)
            .pointerInput(d.item.id) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        val threshold = swipePx * density
                        when {
                            dragged < -threshold -> d.nextId?.let(go)
                            dragged > threshold -> d.previousId?.let(go)
                        }
                        dragged = 0f
                    },
                ) { _, amount -> dragged += amount }
            },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
            Spacer(Modifier.weight(1f))
            Text(d.position, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        AnimatedContent(
            targetState = d,
            transitionSpec = {
                if (reduced) fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
                else {
                    val dir = if (initialState.index() <= targetState.index()) 1 else -1
                    (slideInHorizontally(MekaMotion.expand(false)) { w -> dir * w / 8 } + fadeIn(MekaMotion.appear(false))) togetherWith
                        (slideOutHorizontally(MekaMotion.expand(false)) { w -> -dir * w / 8 } + fadeOut(MekaMotion.appear(false)))
                }
            },
            contentKey = { it.item.id },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "news-detail",
        ) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = MekaSpace.m)) {
                Text(s.item.title, style = MekaType.greeting, color = Meka.colors.textPrimary)
                Text(s.item.meta, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
                Spacer(Modifier.height(MekaSpace.l))
                Text(
                    s.item.summary ?: "No summary from ${s.item.source}. Read the full story for the details.",
                    style = MekaType.body, color = if (s.item.summary != null) Meka.colors.textPrimary else Meka.colors.textTertiary,
                )
                val url = s.item.url
                if (url != null) {
                    Text(
                        "Read full story", style = MekaType.itemTitle, color = Meka.colors.onAccent,
                        modifier = Modifier.padding(top = MekaSpace.xl).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill))
                            .background(Meka.colors.accent)
                            .clickable(role = Role.Button) { runCatching { uriHandler.openUri(url) } }
                            .semantics { contentDescription = "Read full story on ${s.item.source}, opens the browser" }
                            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.m), horizontalArrangement = Arrangement.SpaceBetween) {
            NavButton("‹ Previous", d.previousId) { go(it) }
            NavButton("Next ›", d.nextId) { go(it) }
        }
    }
}

/** "3 of 18" → 3. */
private fun NewsDetail.index(): Int = position.substringBefore(' ').toIntOrNull() ?: 0

@Composable
private fun NavButton(label: String, id: String?, go: (String) -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = if (id != null) Meka.colors.accent else Meka.colors.textTertiary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .then(if (id != null) Modifier.clickable(role = Role.Button) { go(id) } else Modifier)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
    )
}
