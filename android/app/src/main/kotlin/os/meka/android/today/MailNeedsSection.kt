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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
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
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.core.domain.AiFeature
import os.meka.core.domain.MailChaseCard
import os.meka.core.domain.MailChaseDraftRules
import os.meka.core.domain.MailChaseRow
import os.meka.core.domain.MailChaseRules
import os.meka.core.domain.MailDraft
import os.meka.core.domain.MailFindCard
import os.meka.core.domain.MailFindRow
import os.meka.core.domain.MailDraftRules
import os.meka.core.domain.MailAutoDraftRules
import os.meka.core.domain.MailNeedsCard
import os.meka.core.domain.MailNeedsRow
import os.meka.core.domain.MailNeedsRules
import os.meka.core.domain.MailSendAllRules
import os.meka.core.domain.MeetingRequestRules
import os.meka.core.domain.MailRowAction
import os.meka.core.domain.MailRowActionRules
import os.meka.core.domain.BoardFeedRules
import os.meka.core.domain.MailReaderRules
import os.meka.core.domain.MailReaderView
import os.meka.core.facade.MekaCore

/**
 * Inbox autopilot, slice 6a: "From your mail" in Needs you — the emails the server sorted as needing Meka, still in
 * the inbox, newest first: who, which account (the business one wears a Business chip), when, the subject and the
 * first words, each with **Reply** (slice 6b-2a: he writes it on the card; it goes from that account only when he taps
 * Send, at the server's next check, Undo on the bar stops it; slice 6b-2b: **Draft it** fills the editor with Navilife's
 * draft in his tone, never over his own words, still sent only on Send), **Done** (archived at the server's next check), **Task** and **Waiting for** (slice 6b-1: made
 * from the email, which is archived too) and **Snooze** (back tomorrow 08:00). Each has Undo on the bar. Slice 6c: emails
 * linked to it (same thread, order number, or person and topic) fold under it and go with it. Tapping the header folds the emails in and out (expand spring; reduced motion: cross-fade).
 * Email triage completeness (2026-10-15): with two or more emails, **Draft replies to N** drafts each one whose editor is
 * empty and opens it under its email to read and change; once two or more open editors hold a short reply, **Send all N**
 * fades in and sends each from the account its email came to (the same path and hold as Send), one Undo for all.
 */
@Composable
internal fun MailNeedsSection(
    card: MailNeedsCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, taps: MailTaps,
    modifier: Modifier = Modifier,
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
    // Taps feel instant, slice 20: an answer that takes the row away does so in this frame.
    fun gone(id: String, what: String, work: suspend () -> String) = taps.answer(id, what, { core.undoMailItem() }, work)
    // Draft replies and Send all: each open editor's words by email, the drafts Draft replies put in, and its line.
    val texts = remember { mutableStateMapOf<String, String>() }
    val incoming = remember { mutableStateMapOf<String, Pair<Int, String>>() }
    val drafted = remember { mutableStateMapOf<String, String>() }
    var seq by remember { mutableStateOf(0) }
    var draftingAll by remember { mutableStateOf(false) }
    var allLine by rememberSaveable { mutableStateOf<String?>(null) }
    // AI layer completeness slice 3: while Navilife's AI can't draft, the bar says so and what still works.
    var aiLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { aiLine = runCatching { core.aiFeatureLine(AiFeature.DRAFTS) }.getOrNull() }
    val draftAll = MailSendAllRules.draftLabel(card.rows.size)
    val sendAll = MailSendAllRules.sendAll(card.rows, texts.toMap())
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("mail-needs"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MekaSpace.touch).clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = "${card.title}. ${card.headline}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(card.title)
                // The headline cross-fades as emails are done or snoozed.
                AnimatedContent(card.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-needs-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("mail-needs-headline"))
                }
                // Drafted replies: "3 emails need a reply · 2 drafted" (cross-fades as drafts are sent or put away).
                AnimatedContent(card.replyLine, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-needs-replies") { line ->
                    if (line != null) Text(line, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.testTag("mail-needs-replies"))
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            // A done or snoozed email leaves and the list closes up on the expand spring.
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                if (draftAll != null) {
                    MailSendAllBar(
                        draftLabel = if (draftingAll) MailSendAllRules.DRAFTING else draftAll, drafting = draftingAll,
                        draftSpoken = MailSendAllRules.draftSpoken(card.rows.size),
                        sendLabel = sendAll?.label, sendSpoken = sendAll?.spoken, line = allLine ?: aiLine,
                        onDraft = {
                            haptics.light()
                            draftingAll = true
                            scope.launch {
                                // Only into an editor that is empty or still holds Navilife's draft: never over his words.
                                val targets = card.rows.filter { MailDraftRules.canDraft(texts[it.id] ?: "", drafted[it.id]) }
                                var n = 0
                                var problem: String? = null
                                for (r in targets) {
                                    val d = runCatching { core.draftMailReply(r.id) }.getOrNull()
                                    val t = d?.text
                                    if (t != null) { n++; drafted[r.id] = t; incoming[r.id] = (seq++) to t } else problem = d?.line ?: problem
                                }
                                draftingAll = false
                                allLine = MailSendAllRules.draftedLine(n, targets.size, problem)
                            }
                        },
                        onSendAll = onSendAll@{
                            val all = sendAll ?: return@onSendAll
                            haptics.medium()
                            scope.launch {
                                val said = runCatching { core.replyToMailItems(all.ids, all.texts) }.getOrNull() ?: return@launch
                                all.ids.forEach { texts.remove(it); incoming.remove(it); drafted.remove(it) }
                                allLine = null
                                undo(said) { runCatching { core.undoMailItem() } }
                            }
                        },
                    )
                }
                card.rows.forEach { row ->
                    androidx.compose.runtime.key(row.id) {
                        MailNeedsRowView(
                            row,
                            incoming = incoming[row.id],
                            onEditor = { t -> if (t == null) texts.remove(row.id) else texts[row.id] = t },
                            onDone = { haptics.medium(); gone(row.id, MailNeedsRules.DONE) { core.doneMailItem(row.id) } },
                            onTask = { haptics.medium(); gone(row.id, MailNeedsRules.TASK) { core.taskFromMailItem(row.id) } },
                            onToDay = { day -> haptics.medium(); gone(row.id, "To a day") { core.mailToDay(row.id, day) } },
                            toDays = { core.boardFeedDays() },
                            onWaiting = { haptics.medium(); gone(row.id, MailNeedsRules.WAITING) { core.waitingFromMailItem(row.id) } },
                            onSnooze = { haptics.light(); gone(row.id, MailNeedsRules.SNOOZE) { core.snoozeMailItem(row.id) } },
                            // Slice 10b: Done, and Autopilot tidies this sender's later emails as they arrive.
                            onAlwaysTidy = { haptics.medium(); gone(row.id, "Always tidy") { core.alwaysTidyMailItem(row.id) } },
                            // Junk fix slice 2: Block sender (Bin & block on a possible scam) and Unsubscribe, Undo on the bar.
                            onBlock = { haptics.medium(); gone(row.id, "Block") { core.blockMailItem(row.id) } },
                            onUnsubscribe = { haptics.medium(); gone(row.id, "Unsubscribe") { core.unsubscribeMailItem(row.id) } },
                            onDraft = { core.draftMailReply(row.id) },
                            onSuggestTimes = { core.suggestMeetingTimes(row.id) },
                            // Drafted replies: Not needed puts Navilife's draft away (Undo on the bar); the email stays.
                            onNotNeeded = { haptics.light(); answer { core.dismissMailDraft(row.id) } },
                            onBook = { haptics.medium(); answer { core.bookMeetingPick(row.id) } },
                            onRead = { images -> core.readMailItem(row.id, images) },
                            onAttachment = { v, i -> core.openMailAttachment(v, i) },
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
                // Protected time, slice 3: the business mail that waits till morning.
                card.heldLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MailNeedsRowView(
    row: MailNeedsRow, onDone: () -> Unit, onTask: () -> Unit, onWaiting: () -> Unit, onSnooze: () -> Unit,
    onDraft: suspend () -> MailDraft,
    onSend: (String, () -> Unit) -> Unit,
    onAlwaysTidy: (() -> Unit)? = null,
    /** Junk fix slice 2: Block sender / Bin & block and Unsubscribe (null: not offered here). */
    onBlock: (() -> Unit)? = null,
    onUnsubscribe: (() -> Unit)? = null,
    incoming: Pair<Int, String>? = null,
    onEditor: ((String?) -> Unit)? = null,
    /** Scheduling assistant, slice 1: his free times as a reply, for an email that asks to meet (null: not offered here). */
    onSuggestTimes: (suspend () -> MailDraft)? = null,
    /** Scheduling assistant, slice 2: Book the time their reply picked from his offer (null: not offered here). */
    onBook: (() -> Unit)? = null,
    /** Drafted replies: Not needed on Navilife's draft (null: not offered here). */
    onNotNeeded: (() -> Unit)? = null,
    /** Read the full email (2026-10-20): the whole email for the reader (null: not offered here). */
    onRead: (suspend (Boolean) -> MailReaderView)? = null,
    /** Read the full email, slice 2: open one of the reader's attachments (null: listed only). */
    onAttachment: (suspend (MailReaderView, Int) -> os.meka.core.domain.MailAttachmentOpened)? = null,
    /** Week board item (5): To a day… puts the email's task on one of [toDays] (null: not offered here). */
    onToDay: ((Long) -> Unit)? = null,
    toDays: () -> List<os.meka.core.domain.FeedDay> = { emptyList() },
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
    var suggesting by remember { mutableStateOf(false) }
    var showLinked by rememberSaveable(row.id) { mutableStateOf(false) }
    // Read the full email: a tap on the email (its sender, subject and first lines) opens the reader.
    var reading by rememberSaveable(row.id) { mutableStateOf(false) }
    // Draft replies on the card puts its draft in this editor and opens it (the editor unfolds on the expand spring).
    LaunchedEffect(incoming) {
        incoming?.let { (_, t) -> text = t; lastDraft = t; draftLine = MailDraftRules.DRAFTED_LINE; replying = true }
    }
    // The card reads each open editor's words for Send all.
    LaunchedEffect(replying, text) { onEditor?.invoke(if (replying) text else null) }
    Column(Modifier.fillMaxWidth().testTag("mail-item-${row.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s))
                .then(if (onRead != null) Modifier.clickable(role = Role.Button) { haptics.light(); reading = true } else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = if (onRead != null) "${row.spoken}. ${MailReaderRules.READ_WHOLE}" else row.spoken },
        ) {
            // Junk fix slice 3: a link he emailed himself, above the sender.
            row.note?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.testTag("mail-note-${row.id}")) }
            // Account-security mail: who it's really from and what to do, above the sender.
            row.security?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical, modifier = Modifier.testTag("mail-security-${row.id}")) }
            // A possible scam (critical) or a real company's "log in yourself" (brass), above the sender.
            row.warning?.let {
                Text(it, style = MekaType.itemMeta, color = if (row.scam) Meka.colors.critical else Meka.colors.accent,
                    modifier = Modifier.testTag("mail-warning-${row.id}"))
            }
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
            // Slice 3: an answer turning his times down says so in its own line instead.
            if (row.asksToMeet && row.pickedLine == null) Text(MeetingRequestRules.ASKS, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.testTag("mail-asks-to-meet"))
            // Scheduling assistant, slice 2: the time they picked, then "Booked · …" (cross-fades in place).
            AnimatedContent(
                row.pickedLine, label = "mail-picked",
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() },
            ) { picked ->
                if (picked != null) Text(picked, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.testTag("mail-picked"))
            }
            row.notice?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical, modifier = Modifier.testTag("mail-reply-notice")) }
            // Drafted replies: what they want, in their words.
            row.wants?.let { w ->
                Text(
                    if (w == MailAutoDraftRules.NEEDS_REPLY) w else "${MailAutoDraftRules.NEEDS_REPLY} · $w",
                    style = MekaType.itemMeta, color = Meka.colors.accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("mail-wants-${row.id}"),
                )
            }
        }
        // Drafted replies: Navilife's draft, ready to Send (his tap), Edit or put away; it unfolds on the expand spring and
        // folds away once he edits, sends or says Not needed (reduced motion: cross-fade).
        AnimatedVisibility(row.autoDraft != null && !replying, enter = unfold(), exit = fold()) {
            val draft = row.autoDraft ?: ""
            var full by rememberSaveable(row.id) { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
                    .padding(MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)).testTag("mail-auto-draft-${row.id}"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(
                    draft, style = MekaType.body, color = Meka.colors.textPrimary,
                    maxLines = if (full) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); full = !full }
                        .semantics { contentDescription = "Navilife's draft reply to ${row.from}: $draft" },
                )
                Text(MailAutoDraftRules.DRAFT_LABEL, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    MailCardPill(
                        MailAutoDraftRules.SEND, spoken = "Send Navilife's draft to ${row.from} now",
                        onClick = { onSend(draft) { } },
                    )
                    MailCardPill(
                        MailAutoDraftRules.EDIT, spoken = "Edit the draft before sending",
                        onClick = { haptics.light(); text = draft; lastDraft = draft; draftLine = MailDraftRules.DRAFTED_LINE; replying = true },
                    )
                    if (onNotNeeded != null) {
                        MailCardPill(MailAutoDraftRules.NOT_NEEDED, spoken = "No reply needed to ${row.from}; put the draft away", onClick = onNotNeeded)
                    }
                    MailCardPill(MailAutoDraftRules.LATER, spoken = "Remind me about ${row.subject} tomorrow at 8", onClick = onSnooze)
                }
            }
        }
        // Slice 6c: the emails linked to this one, folded under it; the line unfolds them (expand spring; reduced
        // motion: cross-fade). Done, Snooze, Task and Waiting for take them all; one Undo brings them back.
        row.linkedLine?.let { line ->
            Text(
                line, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); showLinked = !showLinked }
                    .padding(vertical = MekaSpace.xxs).testTag("mail-linked-${row.id}")
                    .semantics { contentDescription = if (showLinked) "$line. Hide them" else "$line. Show them" },
            )
        }
        AnimatedVisibility(showLinked && row.linked.isNotEmpty(), enter = unfold(), exit = fold()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface).padding(MekaSpace.xs),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                row.linked.forEach { l ->
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${l.from}, ${l.subject}. ${l.line}" }) {
                        Text(l.from, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l.subject, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                    }
                }
            }
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    MailCardPill(
                        MailNeedsRules.SEND, enabled = problem == null,
                        spoken = "Send this reply to ${row.from} now", onClick = { onSend(text) { replying = false; text = ""; lastDraft = null; draftLine = null } },
                    )
                    if (MailDraftRules.canDraft(text, lastDraft)) {
                        MailCardPill(
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
                    MailCardPill(MailNeedsRules.CANCEL, quiet = true, spoken = "Close the reply without sending", onClick = { replying = false })
                }
            }
        }
        // Junk fix slice 3: every answer wears the accent (Bin & block the critical colour); Reply · Done · Task show and the
        // rest unfold under ⋯ More (expand spring; reduced motion: cross-fade). They wrap on the narrow cover screen.
        val actions = MailRowActionRules.actions(row, tidy = onAlwaysTidy != null, block = onBlock != null, unsubscribe = onUnsubscribe != null, toDay = onToDay != null)
        var more by rememberSaveable(row.id) { mutableStateOf(false) }
        // Week board item (5): To a day… unfolds the days under the answers; a tap on one sends it there.
        var choosingDay by rememberSaveable(row.id) { mutableStateOf(false) }
        fun perform(a: MailRowAction) {
            when (a) {
                MailRowAction.BOOK -> onBook?.invoke()
                MailRowAction.SUGGEST -> {
                    haptics.light()
                    suggesting = true
                    scope.launch {
                        val d = onSuggestTimes?.let { f -> runCatching { f() }.getOrNull() }
                        suggesting = false
                        d?.text?.let { if (MailDraftRules.canDraft(text, lastDraft)) { text = it; lastDraft = it } }
                        draftLine = d?.line ?: MeetingRequestRules.NONE_FREE_LINE
                        replying = true
                    }
                }
                MailRowAction.REPLY -> replying = true
                MailRowAction.DONE -> onDone()
                MailRowAction.TASK -> onTask()
                MailRowAction.WAITING -> onWaiting()
                MailRowAction.SNOOZE -> onSnooze()
                MailRowAction.UNSUBSCRIBE -> onUnsubscribe?.invoke()
                MailRowAction.ALWAYS_TIDY -> onAlwaysTidy?.invoke()
                MailRowAction.BIN, MailRowAction.BLOCK -> onBlock?.invoke()
                MailRowAction.TO_DAY -> { haptics.tick(); choosingDay = !choosingDay }
            }
        }
        fun pillLabel(a: MailRowAction): String =
            if (a == MailRowAction.SUGGEST && suggesting) MeetingRequestRules.FINDING else MailRowActionRules.label(a, row)
        fun offered(a: MailRowAction): Boolean = when (a) {
            MailRowAction.BOOK -> onBook != null
            // Scheduling assistant, slice 1: the editor opens with his free times; never over his own words.
            MailRowAction.SUGGEST -> onSuggestTimes != null && MailDraftRules.canDraft(text, lastDraft)
            MailRowAction.REPLY -> !replying
            else -> true
        }
        FlowRow(
            Modifier.padding(top = MekaSpace.xxs).animateContentSize(MekaMotion.expand(reduced)),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            actions.shown.filter(::offered).forEach { a ->
                MailCardPill(
                    pillLabel(a), spoken = MailRowActionRules.spoken(a, row), critical = MailRowActionRules.critical(a),
                    enabled = !(a == MailRowAction.SUGGEST && suggesting), onClick = { perform(a) },
                )
            }
            if (actions.more.isNotEmpty()) {
                MailCardPill(
                    if (more) MailRowActionRules.FEWER else MailRowActionRules.MORE, spoken = MailRowActionRules.moreSpoken(more, row),
                    onClick = { haptics.tick(); more = !more },
                )
            }
            if (more) actions.more.forEach { a ->
                MailCardPill(pillLabel(a), spoken = MailRowActionRules.spoken(a, row), critical = MailRowActionRules.critical(a), onClick = { perform(a) })
            }
        }
        // The days unfold on the expand spring (reduced motion: cross-fade); the email leaves once one is tapped.
        AnimatedVisibility(choosingDay && onToDay != null, enter = unfold(), exit = fold()) {
            Column(Modifier.fillMaxWidth().testTag("mail-to-day-${row.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(BoardFeedRules.PICK, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    toDays().forEach { d ->
                        MailCardPill(d.label, spoken = "${d.spoken}: ${row.subject}", onClick = { choosingDay = false; onToDay?.invoke(d.day) })
                    }
                }
            }
        }
        // The reader, with this card's own answers at its foot (each closes the reader first; Reply opens the editor here).
        if (reading && onRead != null) {
            MailReaderSheet(
                load = onRead,
                openAttachment = onAttachment,
                actions = (actions.shown + actions.more).filter(::offered).map { a ->
                    MailReaderAction(MailRowActionRules.label(a, row), MailRowActionRules.spoken(a, row), MailRowActionRules.critical(a)) { perform(a) }
                },
                onClose = { reading = false },
            )
        }
    }
}

/**
 * Draft replies to N and, once two or more open editors hold a short reply, Send all N (it fades in; reduced motion: the
 * plain fade), with the line saying what Draft replies did or how Send all sends. Wraps on the narrow cover screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailSendAllBar(
    draftLabel: String, drafting: Boolean, draftSpoken: String, sendLabel: String?, sendSpoken: String?, line: String?,
    onDraft: () -> Unit, onSendAll: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().testTag("mail-send-all"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            AnimatedVisibility(sendLabel != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut()) {
                MailCardPill(sendLabel ?: "", spoken = sendSpoken ?: "", onClick = onSendAll)
            }
            MailCardPill(draftLabel, quiet = true, enabled = !drafting, spoken = draftSpoken, onClick = onDraft)
        }
        val shown = if (sendLabel != null) MailSendAllRules.LINE else line
        AnimatedContent(shown, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-send-all-line") { l ->
            if (l != null) Text(l, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.testTag("mail-send-all-line"))
        }
    }
}

@Composable
internal fun MailNeedsPill(
    label: String, spoken: String, quiet: Boolean = false, enabled: Boolean = true, critical: Boolean = false,
    /**
     * Works by hand (quality gate): the slim pill drawn inside a 48 dp touch target, as Lists' chips (Needs you's
     * To a day… chips and every mail card's pills, through [MailCardPill]).
     */
    touch: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when { !enabled -> Meka.colors.textTertiary; critical -> Meka.colors.critical; quiet -> Meka.colors.textSecondary; else -> Meka.colors.accent }
    val shape = RoundedCornerShape(MekaRadius.pill)
    Text(
        label, style = MekaType.itemMeta, color = color,
        modifier = if (touch) {
            Modifier.clip(shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = spoken }
                .minTouch()
                .clip(shape).background(Meka.colors.surface)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
        } else {
            Modifier.clip(shape)
                .background(Meka.colors.surface)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = spoken }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
        },
    )
}

/**
 * Works by hand (quality gate, Needs you's mail cards): every pill on a mail card is [MailNeedsPill] drawn as before
 * inside a 48 dp touch, so the rows of pills sit with no extra gap (the touch leaves 7 dp above and below each).
 */
@Composable
internal fun MailCardPill(
    label: String, spoken: String, quiet: Boolean = false, enabled: Boolean = true, critical: Boolean = false,
    onClick: () -> Unit,
) = MailNeedsPill(label, spoken = spoken, quiet = quiet, enabled = enabled, critical = critical, touch = true, onClick = onClick)

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
    card: MailChaseCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, taps: MailTaps,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    // Taps feel instant, slice 20: an answer that takes the row away does so in this frame.
    fun gone(id: String, what: String, work: suspend () -> String) = taps.answer(id, what, { core.undoMailChase() }, work)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("mail-chases"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MekaSpace.touch).clickable(role = Role.Button) { haptics.tick(); open = !open }
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
                            onGot = { haptics.medium(); gone(row.id, MailChaseRules.GOT_IT) { core.gotMailChase(row.id) } },
                            onNotYet = { haptics.light(); gone(row.id, MailChaseRules.NOT_YET) { core.notYetMailChase(row.id) } },
                            onDraft = { core.draftMailChase(row.id) },
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
private fun MailChaseRowView(
    row: MailChaseRow, onGot: () -> Unit, onNotYet: () -> Unit, onDraft: suspend () -> MailDraft, onSend: (String, () -> Unit) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    // A chase that didn't send opens again with his words.
    var chasing by rememberSaveable(row.id, row.notice) { mutableStateOf(row.notice != null) }
    var text by rememberSaveable(row.id, row.draft) { mutableStateOf(row.draft) }
    // Follow-up radar, slice 5: the AI's last chase (never written over his own words) and the line under the editor.
    var lastDraft by rememberSaveable(row.id) { mutableStateOf<String?>(null) }
    var draftLine by rememberSaveable(row.id) { mutableStateOf<String?>(null) }
    var drafting by remember { mutableStateOf(false) }
    fun draft() {
        if (drafting || !MailChaseDraftRules.canDraft(text, row.draft, lastDraft)) return
        drafting = true
        draftLine = MailChaseDraftRules.DRAFTING
        scope.launch {
            val d = runCatching { onDraft() }.getOrNull()
            drafting = false
            // Only if the editor still holds what was there: a word he typed meanwhile wins.
            if (d?.text != null && MailChaseDraftRules.canDraft(text, row.draft, lastDraft)) { text = d.text!!; lastDraft = d.text }
            draftLine = d?.line ?: MailChaseDraftRules.UNUSABLE_LINE
        }
    }
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
                // The AI's line cross-fades in under the editor (reduced motion: the plain fade).
                AnimatedContent(draftLine, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-chase-draft-line") { line ->
                    if (line != null) Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.testTag("mail-chase-draft-line"))
                }
                if (problem == MailNeedsRules.REPLY_TOO_LONG) Text(problem, style = MekaType.itemMeta, color = Meka.colors.critical)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    MailCardPill(
                        MailNeedsRules.SEND, enabled = problem == null && !drafting,
                        spoken = "Send this chase to ${row.from} now", onClick = { onSend(text) { chasing = false; lastDraft = null; draftLine = null } },
                    )
                    if (MailChaseDraftRules.canDraft(text, row.draft, lastDraft)) {
                        MailCardPill(
                            if (drafting) MailChaseDraftRules.DRAFTING else MailChaseDraftRules.label(text, lastDraft), quiet = true, enabled = !drafting,
                            spoken = "Let Navilife write the chase to ${row.from} from your email; nothing is sent",
                            onClick = { haptics.light(); draft() },
                        )
                    }
                    MailCardPill(MailNeedsRules.CANCEL, quiet = true, spoken = "Close the chase without sending", onClick = { chasing = false })
                }
            }
        }
        FlowRow(
            Modifier.padding(top = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            if (!chasing) MailCardPill(
                MailChaseRules.CHASE, spoken = "Chase ${row.from} by email; Navilife drafts it, nothing is sent until you tap Send",
                // Opening a fresh chase asks the AI once for one written from the email; the plain one shows meanwhile.
                onClick = { chasing = true; if (MailChaseDraftRules.autoDraft(row) && lastDraft == null) draft() },
            )
            MailCardPill(MailChaseRules.GOT_IT, spoken = "Got it: ${row.title} arrived, off Waiting for", onClick = onGot)
            MailCardPill(MailChaseRules.NOT_YET, quiet = true, spoken = "Not yet: chase ${row.from} again in 3 days", onClick = onNotYet)
        }
    }
}

/**
 * Promises, receipts and renewals from email, slice 1: "Renewals from your mail" in Needs you — each renewal the server
 * read from a renewal email (who, when it renews, the price and how often, the kind, the account and the email's
 * subject), with **Add** (onto Lists → Renewals; Undo on the bar takes it off) and **Not needed** (gone on both apps;
 * Undo brings it back). Nothing reaches the radar without Add. Slice 2: promises made to him share the card ("Waiting
 * for: Signed contract · by Fri 16 Oct"); their Add puts a Waiting for item on, chased the day after. Tapping the header folds the rows in and out (expand
 * spring; reduced motion: cross-fade); an answered row leaves on the same fold.
 */
@Composable
internal fun MailFindSection(
    card: MailFindCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, taps: MailTaps,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    val reduced = Meka.reducedMotion
    fun answer(action: suspend () -> String) {
        scope.launch {
            val said = runCatching { action() }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoMailFind() } }
        }
    }
    // Taps feel instant, slice 20: an answer that takes the row away does so in this frame.
    fun gone(id: String, what: String, work: suspend () -> String) = taps.answer(id, what, { core.undoMailFind() }, work)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("mail-finds"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MekaSpace.touch).clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = "${card.title}. ${card.headline}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(card.title)
                AnimatedContent(card.headline, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "mail-finds-headline") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary)
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(Modifier.padding(top = MekaSpace.xs).animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                card.rows.forEach { row ->
                    androidx.compose.runtime.key(row.id) {
                        MailFindRowView(
                            row,
                            onAdd = { haptics.medium(); gone(row.id, row.addLabel) { core.addMailFind(row.id) } },
                            onNotNeeded = { haptics.light(); gone(row.id, row.notNeededLabel) { core.notNeededMailFind(row.id) } },
                            onWait = { days -> haptics.tick(); answer { core.setFollowUpWait(row.id, days) } },
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
private fun MailFindRowView(row: MailFindRow, onAdd: () -> Unit, onNotNeeded: () -> Unit, onWait: (Int) -> Unit) {
    // Follow-up radar, slice 3: "Wait 3 days ▾" unfolds the chips for how long he waits for this person's replies.
    var waitOpen by rememberSaveable(row.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("mail-find-${row.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (row.business) {
                    Text(
                        MailNeedsRules.BUSINESS_CHIP, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                    )
                }
            }
            Text(row.line, style = MekaType.body, color = Meka.colors.textSecondary)
            Text(row.from, style = MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(
            Modifier.padding(top = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            MailCardPill(row.addLabel, spoken = row.addSpoken, onClick = onAdd)
            MailCardPill(row.notNeededLabel, spoken = row.notNeededSpoken, quiet = true, onClick = onNotNeeded)
            row.waitPill?.let { pill ->
                MailCardPill(pill, spoken = row.waitSpoken ?: pill, quiet = true, onClick = { waitOpen = !waitOpen })
            }
        }
        AnimatedVisibility(waitOpen && row.waits.isNotEmpty(), enter = unfold(), exit = fold()) {
            Column(Modifier.padding(top = MekaSpace.xs).testTag("mail-find-wait-${row.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                row.waitHeading?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    row.waits.forEach { c ->
                        MailCardPill(c.label, spoken = c.spoken, quiet = !c.chosen, onClick = { waitOpen = false; if (!c.chosen) onWait(c.days) })
                    }
                }
            }
        }
    }
}
