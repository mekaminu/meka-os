package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.goals.Chips
import os.meka.android.ask.MekaSpeaker
import os.meka.core.domain.BriefHeadline
import os.meka.core.domain.BriefSpeech
import os.meka.core.domain.BriefLine
import os.meka.core.domain.DueState
import os.meka.core.domain.MorningBriefView
import os.meka.core.domain.WaitingItem
import os.meka.core.facade.MekaCore
import os.meka.android.shell.SharedMotion
import os.meka.android.designsystem.sharedTitleInPane

/** How long the "Got it" check stays before the pane drops away. */
private const val GOT_IT_HOLD_MS = 600L

/**
 * Morning brief (build plan M1): today at a glance (work hours, events and what's planned or due, in time order),
 * what you're waiting on, what needs you on your lists, habits and a running fast, and headlines from the news topics
 * chosen under "Topics" (synced; tapping one opens the article in the browser). "Got it" puts the card away here
 * and on the Mac until tomorrow morning. Nothing in the brief changes anything but the topic choice.
 *
 * Motion: sections stagger in 40 ms apart; chases due today are lit in the accent colour; the topic chips unfold in
 * place and a chosen chip's colour blends across; Got it pops a check (spring) with a light haptic and the pane drops
 * away. Reduced motion: cross-fades only.
 */
@Composable
fun BriefPane(core: MekaCore, onClose: () -> Unit, readAloud: Boolean = false) {
    val v by core.briefView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val context = LocalContext.current
    // Listen (Weather and a voice, slice 8): MEKA reads the brief aloud in its voice, else the phone's own.
    val speaker = remember { MekaSpeaker(context, core, scope) }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    fun listen() = speaker.say(BriefSpeech.script(core.briefView.value))
    // After the wake alarm the brief reads itself once it has something to say.
    var autoRead by rememberSaveable { mutableStateOf(readAloud) }
    LaunchedEffect(autoRead, v.dateLabel) {
        if (autoRead && v.dateLabel.isNotEmpty()) {
            autoRead = false
            listen()
        }
    }
    var closing by remember { mutableStateOf(false) }
    var topicsOpen by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        }
        item(key = "title") {
            Column(Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0))) {
                // The title arrives from the brief card in Today (Four tabs, slice 3).
                Text("${v.greeting}, Meka", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(SharedMotion.BRIEF)))
                Text(listOfNotNull(v.dateLabel, v.workLine).joinToString(" · "), style = MekaType.itemMeta,
                    color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
                // Today's weather (Weather slice 2): "9–15°, light rain from 15:00 — take a coat".
                v.weatherLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
                ListenPill(speaking = speaker.speaking, modifier = Modifier.padding(top = MekaSpace.s)) {
                    if (speaker.speaking) { haptics.tick(); speaker.stop() } else { haptics.light(); listen() }
                }
            }
        }

        item(key = "h-day") {
            Column(Modifier.animateItem().appear(rememberAppearance(1))) {
                SectionLabel("Today")
                Text(v.daySummary, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xs))
            }
        }
        items(v.day, key = { "d-" + it.id }) { r -> TomorrowLine(r, Modifier.animateItem().appear(rememberAppearance(1))) }

        val waitingLine = v.waitingLine
        if (waitingLine != null) {
            item(key = "h-waiting") {
                Column(Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(2))) {
                    SectionLabel("Waiting on")
                    Text(waitingLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xs))
                }
            }
            items(v.waiting, key = { "w-" + it.id }) { w -> WaitingLine(w, Modifier.animateItem().appear(rememberAppearance(2))) }
            if (v.waitingTotal > v.waiting.size) {
                item(key = "w-more") {
                    Text("+${v.waitingTotal - v.waiting.size} more in Lists", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().appear(rememberAppearance(2)))
                }
            }
        }

        if (v.attention.isNotEmpty()) {
            item(key = "h-lists") {
                SectionLabel("On your lists", Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(3)))
            }
            items(v.attention, key = { "a-" + it.id }) { a -> AttentionLine(a, Modifier.animateItem().appear(rememberAppearance(3))) }
        }

        val extras = listOfNotNull(v.habitsLine, v.fastingLine)
        if (extras.isNotEmpty()) {
            item(key = "h-you") {
                Column(Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(4))) {
                    SectionLabel("You")
                    extras.forEach { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(vertical = MekaSpace.xxs)) }
                }
            }
        }

        item(key = "h-news") {
            Column(Modifier.padding(top = MekaSpace.l).animateItem().appear(rememberAppearance(5))) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Headlines", Modifier.weight(1f))
                    Text(if (topicsOpen) "Done" else "Topics", style = MekaType.caption, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { topicsOpen = !topicsOpen }.padding(MekaSpace.xs))
                }
                AnimatedVisibility(
                    visible = topicsOpen,
                    enter = if (Meka.reducedMotion) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                    exit = if (Meka.reducedMotion) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                ) {
                    Box(Modifier.padding(bottom = MekaSpace.s)) {
                        Chips(null, v.newsTopics.map { it.label to it.chosen }) { i ->
                            val t = v.newsTopics[i]
                            haptics.tick()
                            scope.launch { runCatching { core.setNewsTopic(t.id, !t.chosen) } }
                        }
                    }
                }
                when {
                    v.newsTopics.none { it.chosen } -> NewsNote("No topics chosen. Tap Topics to pick some.")
                    v.headlines.isEmpty() -> NewsNote("No headlines yet. They're fetched every hour.")
                }
            }
        }
        items(v.headlines, key = { "n-" + it.id }) { h ->
            HeadlineLine(h, Modifier.animateItem().appear(rememberAppearance(5))) { url ->
                runCatching { uriHandler.openUri(url) }
            }
        }

        item(key = "got-it") {
            Column(Modifier.padding(top = MekaSpace.xl).animateItem().appear(rememberAppearance(6)), horizontalAlignment = Alignment.CenterHorizontally) {
                GotItCheck(visible = closing || v.seenToday)
                Spacer(Modifier.height(MekaSpace.m))
                if (!v.seenToday && !closing) {
                    BriefButton("Got it", filled = true) {
                        closing = true
                        haptics.light()
                        speaker.stop()
                        scope.launch {
                            runCatching { core.briefSeen("Fold") }
                            delay(GOT_IT_HOLD_MS)
                            onClose()
                        }
                    }
                } else {
                    BriefButton("Done", filled = false) { onClose() }
                }
            }
        }
    }
}

/**
 * "▶ Listen" under the date, "■ Stop" while MEKA reads; the label cross-fades as it changes, the pill presses in.
 * Reduced motion: the same short cross-fade.
 */
@Composable
private fun ListenPill(speaking: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .semantics { stateDescription = if (speaking) "Reading the brief aloud" else "" }
            .clickable(role = Role.Button, onClickLabel = if (speaking) "Stop reading" else "Read the brief aloud") { onClick() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    ) {
        Crossfade(targetState = speaking, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "listen") { on ->
            Text(if (on) "■  Stop" else "▶  Listen", style = MekaType.itemMeta, color = Meka.colors.accent)
        }
    }
}

@Composable
private fun WaitingLine(w: WaitingItem, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs)) {
        Text(w.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(w.meta, style = MekaType.caption, color = if (w.state == DueState.DUE) Meka.colors.accent else Meka.colors.textTertiary)
    }
}

/** A headline: the title, then "BBC News · World · 2 h ago". Tapping opens the article in the browser (https only). */
@Composable
private fun HeadlineLine(h: BriefHeadline, modifier: Modifier, open: (String) -> Unit) {
    val url = h.url
    Column(
        modifier.fillMaxWidth()
            .then(if (url != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open in browser") { open(url) } else Modifier)
            .padding(vertical = MekaSpace.xs),
    ) {
        Text(h.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(h.meta, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun NewsNote(text: String) {
    Text(text, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(vertical = MekaSpace.xs))
}

@Composable
private fun AttentionLine(a: BriefLine, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs)) {
        Text(a.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        a.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
    }
}

/** The check that pops when the brief is read (reduced motion: fades in). */
@Composable
private fun GotItCheck(visible: Boolean) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(MekaMotion.appear(true))
        else scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.6f) +
            fadeIn(MekaMotion.appear(false)),
        exit = fadeOut(MekaMotion.appear(reduced)),
    ) {
        Box(Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent).padding(horizontal = MekaSpace.l, vertical = MekaSpace.s)) {
            Text("✓", style = MekaType.upNextTitle, color = Meka.colors.onAccent)
        }
    }
}

@Composable
private fun BriefButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = if (filled) Meka.colors.onAccent else Meka.colors.accent,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}

/**
 * The morning card in Today: "Morning brief" with the day in one line. It rises in when the morning starts (the end
 * of quiet hours) and goes at noon or once read.
 */
/** "Brief read on your Mac · Open": the card's quiet stand-in once the brief was read on the other device. */
@Composable
internal fun BriefReadLine(line: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClickLabel = "Open the morning brief") { onOpen() }
            .padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text(" · ", style = MekaType.caption, color = Meka.colors.textTertiary)
        Text("Open", style = MekaType.caption, color = Meka.colors.accent)
    }
}

@Composable
internal fun BriefCard(v: MorningBriefView, onOpen: () -> Unit, modifier: Modifier = Modifier, titleModifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onOpen() }.padding(MekaSpace.l),
    ) {
        Text("Morning brief", style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = titleModifier)
        Text(v.cardLine, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
    }
}
