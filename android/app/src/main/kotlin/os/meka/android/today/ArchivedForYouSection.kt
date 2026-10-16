package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import os.meka.core.domain.ArchivedEmailGroup
import os.meka.core.domain.ArchivedForYouCard
import os.meka.core.domain.ArchivedForYouLine
import os.meka.core.domain.ArchivedForYouRules
import os.meka.core.facade.MekaCore

/**
 * Inbox autopilot, slice 4: "Archived for you" in Needs you, from the 12:30 and 18:30 digests while Autopilot tidied
 * something Meka hasn't seen. The headline ("Tidied 14 · 9 newsletters · 3 receipts and orders · …"), then each tidy with
 * its emails by sender (2026-10-16: "Deliveroo · 4 promos", each subject with a muted reason chip and its time) and
 * Put back (the server returns those emails to the inbox at its next check); Show/Hide unfolds the tidies in place (expand
 * spring; reduced motion: cross-fade). Got it folds the card away on both apps (synced), with Undo.
 */
@Composable
internal fun ArchivedForYouSection(
    card: ArchivedForYouCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("archived-for-you"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = card.spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel("${card.title} · ${card.slot}")
                // The headline cross-fades as tidies are put back or new ones come.
                AnimatedContent(card.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "archived-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("archived-headline"))
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                card.lines.forEach { line ->
                    androidx.compose.runtime.key(line.id) {
                        ArchivedLineRow(line) {
                            haptics.light()
                            scope.launch { runCatching { core.undoMailTidy(line.id) }.getOrNull()?.let { undo(it.line, null) } }
                        }
                    }
                }
                card.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                Text(card.note, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            }
        }
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            ArchivedPill(ArchivedForYouRules.GOT_IT, spoken = "${ArchivedForYouRules.GOT_IT}, fold Archived for you away") {
                haptics.light()
                scope.launch {
                    val said = runCatching { core.seenArchivedForYou() }.getOrNull() ?: return@launch
                    undo(said) { runCatching { core.undoSeenArchivedForYou() } }
                }
            }
            val label = if (open) ArchivedForYouRules.HIDE else ArchivedForYouRules.SHOW
            ArchivedPill(label, quiet = true, spoken = "$label what was archived") { haptics.tick(); open = !open }
        }
    }
}

@Composable
private fun ArchivedLineRow(line: ArchivedForYouLine, onPutBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("archived-${line.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                Text("${line.time} · ${line.account}", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                Text(line.detail, style = MekaType.body, color = Meka.colors.textPrimary)
                // "Putting back at the next check…" / "Put back 6 emails in the inbox" fades in once asked.
                AnimatedVisibility(line.state != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(line.state.orEmpty(), style = MekaType.itemMeta, color = Meka.colors.accent)
                }
            }
            if (line.canPutBack) {
                ArchivedPill(ArchivedForYouRules.PUT_BACK, spoken = "${ArchivedForYouRules.PUT_BACK} ${line.detail} in the inbox", onClick = onPutBack)
            }
        }
        // "Archived for you" shows which emails: the tidy's emails by sender, each subject with its reason and time
        // (the sender's words, shown as plain text only).
        if (line.emails.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(start = MekaSpace.s),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
            ) {
                line.emails.forEach { g -> androidx.compose.runtime.key(g.id) { ArchivedEmailGroupRow(g) } }
                line.emailsMore?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
            }
        }
    }
}

/** One sender in a tidy: "Deliveroo · 4 promos", its domain and account, then each subject with a muted reason chip. */
@Composable
private fun ArchivedEmailGroupRow(g: ArchivedEmailGroup) {
    Column(
        Modifier.fillMaxWidth().testTag("archived-email-${g.id}").semantics(mergeDescendants = true) { contentDescription = g.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(g.title, style = MekaType.body, color = Meka.colors.textPrimary)
        Text(g.meta, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        g.rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(r.subject, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 2, modifier = Modifier.weight(1f))
                ReasonChip(r.chip)
                Text(r.time, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            }
        }
        g.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
    }
}

@Composable
private fun ReasonChip(label: String) {
    Text(
        label, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun ArchivedPill(label: String, spoken: String, quiet: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = if (quiet) Meka.colors.textSecondary else Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
