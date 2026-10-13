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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.core.domain.MailChaseCard
import os.meka.core.domain.MailChaseRow
import os.meka.core.domain.MailChaseRules
import os.meka.core.domain.MailDraft
import os.meka.core.domain.MailDraftRules
import os.meka.core.domain.MailNeedsCard
import os.meka.core.domain.MailNeedsRow
import os.meka.core.domain.MailNeedsRules
import os.meka.core.facade.MekaCore

/**
 * Inbox autopilot, slice 6a: "From your mail" in Needs you — the emails the server sorted as needing Meka, still in
 * the inbox, newest first: who, which account (the business one wears a Business chip), when, the subject and the
 * first words, each with **Reply** (slice 6b-2a: he writes it on the card; it goes from that account only when he taps
 * Send, at the server's next check, Undo on the bar stops it; slice 6b-2b: **Draft it** fills the editor with Navilife's
 * draft in his tone, never over his own words, still sent only on Send), **Done** (archived at the server's next check), **Task** and **Waiting for** (slice 6b-1: made
 * from the email, which is archived too) and **Snooze** (back tomorrow 08:00). Each has Undo on the bar. Tapping the header folds the emails in and out (expand spring; reduced motion: cross-fade).
 */
@Composable
internal fun MailNeedsSection(
    card: MailNeedsCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    fun answer(action: suspend () -> String) {
        scope.launch {
            val said = runCatching { action() }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoMailItem() } }
        }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("mail-needs"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = "${card.title}. ${card.headline}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(card.title)
                // The headline cross-fades as emails are done or snoozed.
                AnimatedContent(card.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-needs-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("mail-needs-headline"))
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            // A done or snoozed email leaves and the list closes up on the expand spring.
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                card.rows.forEach { row ->
                    androidx.compose.runtime.key(row.id) {
                        MailNeedsRowView(
                            row,
                            onDone = { haptics.medium(); answer { core.doneMailItem(row.id) } },
                            onTask = { haptics.medium(); answer { core.taskFromMailItem(row.id) } },
                            onWaiting = { haptics.medium(); answer { core.waitingFromMailItem(row.id) } },
                            onSnooze = { haptics.light(); answer { core.snoozeMailItem(row.id) } },
                            onDraft = { core.draftMailReply(row.id) },
                            onSend = { text, sent ->
                                haptics.medium()
                                scope.launch {
                                    val said = runCatching { core.replyToMailItem(row.id, text) }.getOrNull() ?: return@launch
                                    // A problem with the words keeps the editor open with them; otherwise Undo on the bar.
                                    if (MailNeedsRules.replyProblem(text) != null) return@launch
                                    sent()
                                    undo(said) { runCatching { core.undoMailItem() } }
                                }
                            },
                        )
                    }
                }
                card.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                card.snoozedLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailNeedsRowView(
    row: MailNeedsRow, onDone: () -> Unit, onTask: () -> Unit, onWaiting: () -> Unit, onSnooze: () -> Unit,
    onDraft: suspend () -> MailDraft,
    onSend: (String, () -> Unit) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    // Slice 6b-2a: a reply that didn't send opens again with his words.
    var replying by rememberSaveable(row.id, row.replyDraft) { mutableStateOf(row.replyDraft != null) }
    var text by rememberSaveable(row.id, row.replyDraft) { mutableStateOf(row.replyDraft ?: "") }
    // Slice 6b-2b: Navilife's last draft (Draft it never writes over his own words) and the line under the editor.
    var lastDraft by rememberSaveable(row.id) { mutableStateOf<String?>(null) }
    var draftLine by rememberSaveable(row.id) { mutableStateOf<String?>(null) }
    var drafting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("mail-item-${row.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(row.from, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (row.business) {
                    Text(
                        MailNeedsRules.BUSINESS_CHIP, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                    )
                }
            }
            Text(row.subject, style = MekaType.body, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.preview?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(row.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            row.notice?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical, modifier = Modifier.testTag("mail-reply-notice")) }
        }
        // Reply unfolds an editor under the email (expand spring; reduced motion: cross-fade). Nothing goes until Send.
        AnimatedVisibility(replying, enter = unfold(), exit = fold()) {
            val problem = MailNeedsRules.replyProblem(text)
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
                        .padding(MekaSpace.xs),
                ) {
                    if (text.isEmpty()) Text(MailNeedsRules.REPLY_HINT, style = MekaType.body, color = Meka.colors.textTertiary)
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                        cursorBrush = SolidColor(Meka.colors.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().testTag("mail-reply-${row.id}").semantics { contentDescription = "Reply to ${row.from}" },
                    )
                }
                Text(row.replyLine, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                // The draft's line cross-fades in under the editor (reduced motion: the plain fade).
                AnimatedContent(draftLine, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-draft-line") { line ->
                    if (line != null) Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.testTag("mail-draft-line"))
                }
                if (problem == MailNeedsRules.REPLY_TOO_LONG) Text(problem, style = MekaType.itemMeta, color = Meka.colors.critical)
                // Send · Draft it · Cancel wrap on the narrow cover screen rather than squeezing.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    MailNeedsPill(
                        MailNeedsRules.SEND, enabled = problem == null,
                        spoken = "Send this reply to ${row.from} now", onClick = { onSend(text) { replying = false; text = ""; lastDraft = null; draftLine = null } },
                    )
                    if (MailDraftRules.canDraft(text, lastDraft)) {
                        MailNeedsPill(
                            if (drafting) MailDraftRules.DRAFTING else MailDraftRules.label(text), quiet = true, enabled = !drafting,
                            spoken = "Let Navilife draft a reply to ${row.from} for you to check; nothing is sent",
                            onClick = {
                                haptics.light()
                                drafting = true
                                scope.launch {
                                    val d = runCatching { onDraft() }.getOrNull()
                                    drafting = false
                                    d?.text?.let { text = it; lastDraft = it }
                                    draftLine = d?.line ?: MailDraftRules.UNUSABLE_LINE
                                }
                            },
                        )
                    }
                    MailNeedsPill(MailNeedsRules.CANCEL, quiet = true, spoken = "Close the reply without sending", onClick = { replying = false })
                }
            }
        }
        // Five answers wrap onto a second line on the narrow cover screen rather than squeezing.
        FlowRow(
            Modifier.padding(top = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            if (!replying) MailNeedsPill(MailNeedsRules.REPLY, spoken = "Reply to ${row.from}", onClick = { replying = true })
            MailNeedsPill(MailNeedsRules.DONE, spoken = "${MailNeedsRules.DONE}: archive ${row.subject}", onClick = onDone)
            MailNeedsPill(MailNeedsRules.TASK, spoken = "Make a task from ${row.subject} and archive the email", onClick = onTask)
            MailNeedsPill(MailNeedsRules.WAITING, quiet = true, spoken = "Waiting for ${row.from} on ${row.subject}, chase in 3 days", onClick = onWaiting)
            MailNeedsPill(MailNeedsRules.SNOOZE, quiet = true, spoken = "${MailNeedsRules.SNOOZE} ${row.subject} till tomorrow 8 o'clock", onClick = onSnooze)
        }
    }
}

@Composable
private fun MailNeedsPill(label: String, spoken: String, quiet: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = when { !enabled -> Meka.colors.textTertiary; quiet -> Meka.colors.textSecondary; else -> Meka.colors.accent },
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/**
 * Inbox autopilot, slice 6b-3: "Time to chase" in Needs you — each Waiting for item made from an email whose chase day
 * has come: who, what, which account and since when, with **Chase** (an editor that opens holding Navilife's short
 * chase, his to change; it goes from that account, in the email's thread, only when he taps Send, at the server's next
 * check, Undo on the bar stops it), **Got it** (off Waiting for) and **Not yet** (chase again in 3 days, nothing sent).
 * A chase that didn't send comes back with his words and why. Tapping the header folds the rows in and out (expand
 * spring; reduced motion: cross-fade).
 */
@Composable
internal fun MailChaseSection(
    card: MailChaseCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    fun answer(action: suspend () -> String) {
        scope.launch {
            val said = runCatching { action() }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoMailChase() } }
        }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("mail-chases"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = "${card.title}. ${card.headline}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(card.title)
                AnimatedContent(card.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-chases-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary)
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                card.rows.forEach { row ->
                    androidx.compose.runtime.key(row.id) {
                        MailChaseRowView(
                            row,
                            onGot = { haptics.medium(); answer { core.gotMailChase(row.id) } },
                            onNotYet = { haptics.light(); answer { core.notYetMailChase(row.id) } },
                            onSend = { text, sent ->
                                haptics.medium()
                                scope.launch {
                                    val said = runCatching { core.chaseMailItem(row.id, text) }.getOrNull() ?: return@launch
                                    if (MailNeedsRules.replyProblem(text) != null) return@launch
                                    sent()
                                    undo(said) { runCatching { core.undoMailChase() } }
                                }
                            },
                        )
                    }
                }
                card.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailChaseRowView(row: MailChaseRow, onGot: () -> Unit, onNotYet: () -> Unit, onSend: (String, () -> Unit) -> Unit) {
    val reduced = Meka.reducedMotion
    // A chase that didn't send opens again with his words.
    var chasing by rememberSaveable(row.id, row.notice) { mutableStateOf(row.notice != null) }
    var text by rememberSaveable(row.id, row.draft) { mutableStateOf(row.draft) }
    Column(Modifier.fillMaxWidth().testTag("mail-chase-${row.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(row.from, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (row.business) {
                    Text(
                        MailNeedsRules.BUSINESS_CHIP, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                    )
                }
            }
            Text(row.title, style = MekaType.body, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            row.notice?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical) }
        }
        // Chase unfolds the editor, already holding the chase (expand spring; reduced motion: cross-fade).
        AnimatedVisibility(chasing, enter = unfold(), exit = fold()) {
            val problem = MailNeedsRules.replyProblem(text)
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
                        .padding(MekaSpace.xs),
                ) {
                    if (text.isEmpty()) Text(MailNeedsRules.REPLY_HINT, style = MekaType.body, color = Meka.colors.textTertiary)
                    BasicTextField(
                        value = text, onValueChange = { text = it },
                        textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                        cursorBrush = SolidColor(Meka.colors.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().testTag("mail-chase-text-${row.id}").semantics { contentDescription = "Chase ${row.from}" },
                    )
                }
                Text(row.replyLine, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                if (problem == MailNeedsRules.REPLY_TOO_LONG) Text(problem, style = MekaType.itemMeta, color = Meka.colors.critical)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    MailNeedsPill(
                        MailNeedsRules.SEND, enabled = problem == null,
                        spoken = "Send this chase to ${row.from} now", onClick = { onSend(text) { chasing = false } },
                    )
                    MailNeedsPill(MailNeedsRules.CANCEL, quiet = true, spoken = "Close the chase without sending", onClick = { chasing = false })
                }
            }
        }
        FlowRow(
            Modifier.padding(top = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            if (!chasing) MailNeedsPill(MailChaseRules.CHASE, spoken = "Chase ${row.from} by email; Navilife has drafted it, nothing is sent until you tap Send", onClick = { chasing = true })
            MailNeedsPill(MailChaseRules.GOT_IT, spoken = "Got it: ${row.title} arrived, off Waiting for", onClick = onGot)
            MailNeedsPill(MailChaseRules.NOT_YET, quiet = true, spoken = "Not yet: chase ${row.from} again in 3 days", onClick = onNotYet)
        }
    }
}
