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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import os.meka.core.domain.ArchivedEmailPreview
import os.meka.core.domain.ArchivedEmailRow
import os.meka.core.domain.MailTidiedAction
import os.meka.core.domain.MailOpenRules
import os.meka.core.domain.MailReaderRules
import os.meka.core.domain.MailTidiedAskRules
import os.meka.core.domain.ArchivedForYouCard
import os.meka.core.domain.ArchivedForYouLine
import os.meka.core.domain.ArchivedForYouRules
import os.meka.core.domain.ArchivedAllRules
import os.meka.core.facade.MekaCore

/**
 * Inbox autopilot, slice 4: "Archived for you" in Needs you, from the 12:30 and 18:30 digests while Autopilot tidied
 * something Meka hasn't seen. The headline ("Tidied 14 · 9 newsletters · 3 receipts and orders · …"), then each tidy with
 * its emails by sender (2026-10-16: "Deliveroo · 4 promos", each subject with a muted reason chip and its time) and
 * Put back (the server returns those emails to the inbox at its next check); Show/Hide unfolds the tidies in place (expand
 * spring; reduced motion: cross-fade). Got it folds the card away on both apps (synced), with Undo.
 *
 * Slice 2: tap an email → its preview unfolds under it (one at a time; expand spring, reduced motion cross-fades): who
 * from, the subject, the provider's first lines, and Put back (just that email), Always keep from this sender and
 * Unsubscribe where the email offered one, each done at the server's next check with Undo on the bar until then.
 */
@Composable
internal fun ArchivedForYouSection(
    card: ArchivedForYouCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, modifier: Modifier = Modifier,
    openAll: () -> Unit = {},
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(true) }
    // Slice 2: the email whose preview is open ("batchId|key"), one at a time.
    var openEmail by rememberSaveable { mutableStateOf<String?>(null) }
    val reduced = Meka.reducedMotion
    val ask: (ArchivedEmailPreview, MailTidiedAction) -> Unit = { p, action ->
        haptics.light()
        scope.launch {
            val said = runCatching { core.askArchivedEmail(p.batchId, p.key, action.name) }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoArchivedEmail(p.batchId, p.key, action.name) } }
        }
    }
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
                // 2026-10-18: the same email again and again ("Came again · GitHub · “Run failed: ci” · 6 times since 09:10").
                card.repeats.forEach { r ->
                    Text(r, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.testTag("archived-repeat"))
                }
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                card.lines.forEach { line ->
                    androidx.compose.runtime.key(line.id) {
                        ArchivedLineRow(
                            line, core, openEmail,
                            onOpen = { id -> haptics.tick(); openEmail = if (openEmail == id) null else id },
                            onAsk = ask,
                        ) {
                            haptics.light()
                            scope.launch { runCatching { core.undoMailTidy(line.id) }.getOrNull()?.let { undo(it.line, null) } }
                        }
                    }
                }
                // Slice 3: "+ 4 earlier tidies · in All tidied mail" opens the whole list.
                card.moreLine?.let {
                    Text(it, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { haptics.light(); openAll() })
                }
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
            ArchivedPill(ArchivedAllRules.OPEN, quiet = true, spoken = "${ArchivedAllRules.OPEN}, every email tidied in the last 30 days") {
                haptics.light(); openAll()
            }
        }
    }
}

@Composable
private fun ArchivedLineRow(
    line: ArchivedForYouLine, core: MekaCore, openEmail: String?, onOpen: (String) -> Unit,
    onAsk: (ArchivedEmailPreview, MailTidiedAction) -> Unit, onPutBack: () -> Unit,
) {
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
                Modifier.fillMaxWidth().padding(start = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                line.emails.forEach { g -> androidx.compose.runtime.key(g.id) { ArchivedEmailGroupRow(g, line, core, openEmail, onOpen, onAsk) } }
                line.emailsMore?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
            }
        }
    }
}

/**
 * One sender in a tidy: "Deliveroo · 4 promos", its domain and account, then each subject with a muted reason chip.
 * Slice 2: each email is a button that unfolds its preview.
 */
@Composable
private fun ArchivedEmailGroupRow(
    g: ArchivedEmailGroup, line: ArchivedForYouLine, core: MekaCore, openEmail: String?, onOpen: (String) -> Unit,
    onAsk: (ArchivedEmailPreview, MailTidiedAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("archived-email-${g.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${g.title}, ${g.meta}" }) {
            Text(g.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(g.meta, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        }
        g.rows.forEach { r ->
            val id = "${r.batchId}|${r.key}"
            androidx.compose.runtime.key(id) {
                ArchivedEmailRowView(r, onClick = { onOpen(id) })
                AnimatedVisibility(openEmail == id, enter = unfold(), exit = fold()) {
                    ArchivedEmailPreviewPanel(r, line.state, core, onAsk)
                }
            }
        }
        g.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
    }
}

@Composable
internal fun ArchivedEmailRowView(r: ArchivedEmailRow, spokenAs: String? = null, onClick: () -> Unit) {
    val spoken = spokenAs ?: (listOfNotNull(r.subject, r.chip, r.time, r.state).joinToString(", ") + ". Opens its preview")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = MekaSpace.xxs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(r.subject, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            ReasonChip(r.chip)
            Text(r.time, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        }
        // "Putting back at Navilife's next check…" → the server's "Back in the inbox" cross-fades in.
        AnimatedContent(r.state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "archived-email-state") { st ->
            if (st != null) Text(st, style = MekaType.itemMeta, color = Meka.colors.accent)
        }
    }
}

/** Slice 2: the email's preview, read from the synced tidy (it follows the server's answers as they come). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ArchivedEmailPreviewPanel(
    r: ArchivedEmailRow, refresh: Any?, core: MekaCore, onAsk: (ArchivedEmailPreview, MailTidiedAction) -> Unit,
) {
    var p by remember { mutableStateOf<ArchivedEmailPreview?>(null) }
    // Read again whenever the card changes (an ask, the server's answer).
    LaunchedEffect(r, refresh) { p = runCatching { core.archivedEmail(r.batchId, r.key) }.getOrNull() }
    val pv = p ?: return
    Column(
        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
            .padding(MekaSpace.m).testTag("archived-preview-${r.key}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        // Read the full email (2026-10-20): a tap on the preview, or Read the whole email, opens the reader.
        var reading by remember(r.key) { mutableStateOf(false) }
        Column(
            Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { reading = true }
                .semantics(mergeDescendants = true) { contentDescription = "${pv.spoken}. ${MailReaderRules.READ_WHOLE}" },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text("${pv.from} · ${pv.meta}", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            Text(pv.subject, style = MekaType.body, color = Meka.colors.textPrimary)
            Text("${pv.chip} · ${pv.time}", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            Text(pv.lines ?: MailTidiedAskRules.NO_LINES, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            pv.state?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.accent) }
        }
        val actions = listOfNotNull(
            pv.putBack?.let { it to MailTidiedAction.PUT_BACK }, pv.keep?.let { it to MailTidiedAction.KEEP },
            pv.unsubscribe?.let { it to MailTidiedAction.UNSUBSCRIBE },
        )
        // Slice 4: Open in Gmail / Outlook — the link is made on the tap and handed to the browser, never kept.
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var openLine by remember(r.key) { mutableStateOf<String?>(null) }
        fun openInMail() {
            scope.launch {
                val res = runCatching { core.archivedEmailLink(r.batchId, r.key) }.getOrNull()
                val link = res?.link
                openLine = link?.hint ?: res?.problem ?: MailOpenRules.OFFLINE
                if (link != null) runCatching { uri.openUri(link.url) }
            }
        }
        if (reading) {
            MailReaderSheet(
                load = { images -> core.readArchivedMail(r.batchId, r.key, images) },
                actions = actions.map { (label, action) ->
                    MailReaderAction(label, "$label, ${pv.subject}") { onAsk(pv, action) }
                },
                openLabel = pv.openLabel, onOpenInMail = { openInMail() },
                openAttachment = { v, i -> core.openMailAttachment(v, i) },
                onClose = { reading = false },
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            ArchivedPill(MailReaderRules.READ_WHOLE, spoken = "${MailReaderRules.READ_WHOLE}, ${pv.subject}") { reading = true }
            run {
                actions.forEach { (label, action) ->
                    ArchivedPill(label, spoken = "$label, ${pv.subject}", quiet = action == MailTidiedAction.UNSUBSCRIBE) { onAsk(pv, action) }
                }
                pv.openLabel?.let { label ->
                    ArchivedPill(label, spoken = "$label, ${pv.subject}", quiet = true) { openInMail() }
                }
            }
        }
        // The line under the button cross-fades from the account's hint to what the tap found.
        AnimatedContent(openLine ?: pv.openHint, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "archived-open-line") { l ->
            if (l != null) Text(l, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        }
        // Receipts → Vault: Keep in the Vault / Take it out of the Vault on a receipt; the line cross-fades to what it did.
        pv.vault?.let { offer ->
            var vaultLine by remember(r.key) { mutableStateOf<String?>(null) }
            ArchivedPill(offer.action, spoken = "${offer.action}, ${pv.subject}", quiet = offer.kept) {
                scope.launch {
                    vaultLine = if (offer.kept) runCatching { core.takeReceiptOutOfVault(r.key) }.getOrNull()
                    else runCatching { core.keepReceiptInVault(r.batchId, r.key) }.getOrNull()?.line
                    p = runCatching { core.archivedEmail(r.batchId, r.key) }.getOrNull() ?: p
                }
            }
            AnimatedContent(vaultLine ?: offer.line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "archived-vault-line") { l ->
                Text(l, style = MekaType.itemMeta, color = if (offer.kept || vaultLine != null) Meka.colors.accent else Meka.colors.textTertiary)
            }
        }
        Text(pv.note, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun ReasonChip(label: String) {
    Text(
        label, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
    )
}

@Composable
internal fun ArchivedPill(label: String, spoken: String, quiet: Boolean = false, onClick: () -> Unit) {
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
