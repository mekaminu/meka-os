package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.core.domain.DiscordCards
import os.meka.core.domain.DiscordChannelCard
import os.meka.core.domain.DiscordRules
import os.meka.core.facade.MekaCore

/**
 * Discord channel watch, slice 1: Needs you's DISCORD block — one card per channel he follows with the posts Navilife
 * kept from Discord's notifications since he last looked ("4 posts since you last looked · latest 14:02 · Admin"), the
 * newest few under it, each card with **Done** (gone on both apps; Undo on the bar brings it back). Information only:
 * nothing here posts to Discord or trades. The header folds the cards (expand spring; reduced motion: cross-fade); a
 * card leaves on the same fold.
 */
@Composable
internal fun DiscordSection(
    cards: DiscordCards, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    // Slice 3: a busy channel's one-line summary, asked for when the cards change (nothing due costs nothing).
    val app = context.applicationContext as? os.meka.android.MekaApplication
    androidx.compose.runtime.LaunchedEffect(cards.cards.map { it.key to it.ids.size }) {
        app?.let { os.meka.android.work.ChatSummarise.discord(it) }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("discord"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = "${DiscordRules.TITLE}. ${cards.headline}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(DiscordRules.TITLE)
                AnimatedContent(cards.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "discord-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary)
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                cards.cards.forEach { card ->
                    androidx.compose.runtime.key(card.key) {
                        DiscordChannelCardView(card, onKeep = { postId ->
                            haptics.tick()
                            scope.launch {
                                val said = runCatching { core.keepDiscordIdea(postId) }.getOrNull() ?: return@launch
                                if (said == DiscordRules.KEPT_LINE) undo(said) { runCatching { core.undoKeepDiscordIdea(postId) } }
                                else undo(said, null)
                            }
                        }) {
                            haptics.medium()
                            // Slice 2: the channel's alert notification has done its job.
                            os.meka.android.work.DiscordAlerts.cancel(context, card.key)
                            scope.launch {
                                val said = runCatching { core.discordDone(card.key) }.getOrNull() ?: return@launch
                                undo(said) { runCatching { core.undoDiscord() } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscordChannelCardView(card: DiscordChannelCard, onKeep: (String) -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("discord-${card.key}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${card.title}, ${card.server.orEmpty()}. ${card.line}" }) {
                Text(card.title, style = MekaType.body, color = Meka.colors.textPrimary)
                card.server?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
            }
            os.meka.android.designsystem.DonePill("Done with ${card.title}", tag = "discord-done-${card.key}", onDone = onDone)
        }
        Text(card.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        // Slice 3: Navilife's AI's one line on what was said; fades in when it arrives (reduced motion: no fade).
        AnimatedVisibility(card.summary != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut()) {
            Text(
                card.summary.orEmpty(), style = MekaType.body, color = Meka.colors.textPrimary,
                modifier = Modifier.padding(top = MekaSpace.xxs).semantics { contentDescription = "In short: ${card.summary.orEmpty()}" },
            )
        }
        // Slice 2: posts from a person or with a word he picked: "2 alerts · latest 14:02" in brass.
        card.alertLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent) }
        card.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
        Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            card.posts.forEach { post ->
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                    Text("${post.author} · ${post.timeLine}", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                    post.alert?.let { DiscordAlertChip(it) }
                    Text(post.text, style = MekaType.body, color = Meka.colors.textPrimary)
                    DiscordKeep(post.kept) { onKeep(post.id) }
                }
            }
        }
    }
}

/** Slice 2: why a post alerted ("From Admin · says “stop”"), a small brass chip above its words. */
@Composable
private fun DiscordAlertChip(why: String) {
    Text(
        why, style = MekaType.caption, color = Meka.colors.onAccent,
        modifier = Modifier.padding(vertical = MekaSpace.xxs).clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.accent).padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs)
            .semantics { contentDescription = "Alert: $why" },
    )
}

/**
 * Slice 4: "Keep as idea" under a post — a note in Lists → Someday → Trading ideas (never an order; nothing is traded).
 * Once kept it reads "In Trading ideas" (cross-fade; reduced motion: instant). Undo is on the bar.
 */
@Composable
private fun DiscordKeep(kept: Boolean, onKeep: () -> Unit) {
    val reduced = Meka.reducedMotion
    AnimatedContent(kept, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "discord-keep") { k ->
        if (k) {
            Text(
                "✓ " + DiscordRules.KEPT_CHIP, style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(vertical = MekaSpace.xs).semantics { contentDescription = "Kept in Trading ideas" },
            )
        } else {
            Text(
                DiscordRules.KEEP_ACTION, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button, onClick = onKeep)
                    .padding(vertical = MekaSpace.xs).wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = "Keep as a trade idea in Lists. A note only, nothing is traded" },
            )
        }
    }
}
