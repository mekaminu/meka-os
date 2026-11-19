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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import os.meka.android.designsystem.MekaChoreography
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.SwipeBackdrop
import os.meka.android.designsystem.SwipeGlyph
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.minTouch
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
import os.meka.core.domain.InstantMailRules
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
    card: ArchivedForYouCard, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, taps: MailTaps,
    modifier: Modifier = Modifier,
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
    // Tidied mail, slice 3: a swipe asks the same, with Undo on the bar.
    val swipe = rememberArchivedSwipe(core, undo)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("archived-for-you"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MekaSpace.touch).clickable(role = Role.Button) { haptics.tick(); open = !open }
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
                            onAsk = ask, onSwipe = swipe,
                        ) {
                            haptics.light()
                            scope.launch { runCatching { core.undoMailTidy(line.id) }.getOrNull()?.let { undo(it.line, null) } }
                        }
                    }
                }
                // Slice 3: "+ 4 earlier tidies · in All tidied mail" opens the whole list.
                card.moreLine?.let {
                    Text(it, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { haptics.light(); openAll() }.minTouch())
                }
                Text(card.note, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            }
        }
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            ArchivedPill(ArchivedForYouRules.GOT_IT, spoken = "${ArchivedForYouRules.GOT_IT}, fold Archived for you away") {
                haptics.light()
                // Taps feel instant, slice 20: the card folds away in this frame; Undo or a failure brings it back.
                taps.answer(InstantMailRules.archivedKey(card), ArchivedForYouRules.GOT_IT, { core.undoSeenArchivedForYou() }) {
                    core.seenArchivedForYou()
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
    onAsk: (ArchivedEmailPreview, MailTidiedAction) -> Unit, onSwipe: (ArchivedEmailRow, MailTidiedAction) -> Unit, onPutBack: () -> Unit,
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
                line.emails.forEach { g -> androidx.compose.runtime.key(g.id) { ArchivedEmailGroupRow(g, line, core, openEmail, onOpen, onAsk, onSwipe) } }
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
    onAsk: (ArchivedEmailPreview, MailTidiedAction) -> Unit, onSwipe: (ArchivedEmailRow, MailTidiedAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("archived-email-${g.id}"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${g.title}, ${g.meta}" }) {
            Text(g.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(g.meta, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        }
        g.rows.forEach { r ->
            val id = "${r.batchId}|${r.key}"
            androidx.compose.runtime.key(id) {
                ArchivedEmailRowView(r, onSwipe = { a -> onSwipe(r, a) }, onClick = { onOpen(id) })
                AnimatedVisibility(openEmail == id, enter = unfold(), exit = fold()) {
                    ArchivedEmailPreviewPanel(r, line.state, core, onAsk)
                }
            }
        }
        g.moreLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
    }
}

/** Tidied mail, slice 3: what a swipe on a row asks of the core, the bar's line and its Undo after. */
@Composable
internal fun rememberArchivedSwipe(core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit): (ArchivedEmailRow, MailTidiedAction) -> Unit {
    val scope = rememberCoroutineScope()
    val latestUndo by rememberUpdatedState(undo)
    return remember<(ArchivedEmailRow, MailTidiedAction) -> Unit>(core) {
        { r, action ->
            scope.launch {
                val said = runCatching { core.askArchivedEmail(r.batchId, r.key, action.name) }.getOrNull() ?: return@launch
                latestUndo(said) { runCatching { core.undoArchivedEmail(r.batchId, r.key, action.name) } }
            }
            Unit
        }
    }
}

/**
 * One tidied email's row. Tidied mail, slice 3 (Meka 2026-11-18): where [r]'s swipe allows it, a swipe right washes in
 * the gold Put back layer and a swipe left the critical Trash layer; a tick haptic arms each at the catalogue's arm
 * distance, letting go asks it (light haptic) with Undo on the bar, and the row springs back. TalkBack offers both as
 * actions. Reduced motion: the row springs back at once.
 */
@Composable
internal fun ArchivedEmailRowView(
    r: ArchivedEmailRow, spokenAs: String? = null, onSwipe: ((MailTidiedAction) -> Unit)? = null, onClick: () -> Unit,
) {
    val spoken = spokenAs ?: (listOfNotNull(r.subject, r.chip, r.time, r.state).joinToString(", ") + ". Opens its preview")
    val sw = r.swipe?.takeIf { onSwipe != null }
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { MekaChoreography.swipeArmDistanceDp.dp.toPx() }
    var dx by remember(r.key) { mutableFloatStateOf(0f) }
    var armed by remember(r.key) { mutableIntStateOf(0) }
    val act by rememberUpdatedState(onSwipe)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val max = constraints.maxWidth.toFloat().coerceAtLeast(threshold * 1.5f)
        val progress = MotionMath.swipeProgress(with(density) { abs(dx).toDp().value })
        if (sw != null && dx > 0f) SwipeBackdrop(
            side = 1, progress = progress, armed = armed > 0, glyph = SwipeGlyph.OPEN,
            label = sw.rightLabel.orEmpty(), fill = Meka.colors.accent, content = Meka.colors.onAccent,
        )
        if (sw != null && dx < 0f) SwipeBackdrop(
            side = -1, progress = progress, armed = armed < 0, glyph = SwipeGlyph.HIDE,
            label = sw.leftLabel.orEmpty(), fill = Meka.colors.critical, content = Meka.colors.background,
        )
        val drag = rememberDraggableState { delta ->
            val s = sw ?: return@rememberDraggableState
            val lo = if (s.left != null) -max else 0f
            val hi = if (s.right != null) max else 0f
            dx = (dx + delta).coerceIn(lo, hi)
            val now = MotionMath.swipeArmed(with(density) { dx.toDp().value })
            if (now != armed) { if (now != 0) haptics.tick(); armed = now }
        }
        ArchivedEmailRowBody(
            r, spoken,
            Modifier.offset { IntOffset(dx.roundToInt(), 0) }
                .clip(RoundedCornerShape(MekaRadius.s)).background(if (dx != 0f) Meka.colors.surfaceRaised else androidx.compose.ui.graphics.Color.Transparent)
                .then(
                    if (sw == null) Modifier else Modifier.draggable(
                        orientation = Orientation.Horizontal, state = drag,
                        onDragStopped = {
                            val side = armed
                            armed = 0
                            val action = if (side > 0) sw.right else if (side < 0) sw.left else null
                            if (action != null) { haptics.light(); act?.invoke(action) }
                            scope.launch { animate(dx, 0f, animationSpec = MekaMotion.complete(reduced)) { v, _ -> dx = v } }
                        },
                    ),
                )
                .semantics {
                    if (sw != null) customActions = listOfNotNull(
                        sw.right?.let { a -> CustomAccessibilityAction(sw.rightLabel.orEmpty()) { act?.invoke(a); true } },
                        sw.left?.let { a -> CustomAccessibilityAction(MailTidiedAskRules.TRASH) { act?.invoke(a); true } },
                    )
                },
            onClick,
        )
    }
}

@Composable
private fun ArchivedEmailRowBody(r: ArchivedEmailRow, spoken: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button, onClick = onClick)
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
            // Tidied mail, slice 3: Not junk first on a junk or scam email.
            pv.notJunk?.let { it to MailTidiedAction.NOT_JUNK },
            pv.putBack?.let { it to MailTidiedAction.PUT_BACK }, pv.keep?.let { it to MailTidiedAction.KEEP },
            pv.unsubscribe?.let { it to MailTidiedAction.UNSUBSCRIBE },
            // Tidied mail, slice 2 (Meka 2026-11-18): Move to Trash and Block sender, Undo on the bar until the next check.
            pv.trash?.let { it to MailTidiedAction.TRASH }, pv.block?.let { it to MailTidiedAction.BLOCK },
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
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            ArchivedPill(MailReaderRules.READ_WHOLE, spoken = "${MailReaderRules.READ_WHOLE}, ${pv.subject}") { reading = true }
            run {
                actions.forEach { (label, action) ->
                    ArchivedPill(label, spoken = "$label, ${pv.subject}", quiet = action == MailTidiedAction.UNSUBSCRIBE,
                        critical = action == MailTidiedAction.TRASH || action == MailTidiedAction.BLOCK) { onAsk(pv, action) }
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
internal fun ArchivedPill(label: String, spoken: String, quiet: Boolean = false, critical: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        // Slice 2: Move to Trash and Block wear the critical colour, as Junk it & block does.
        color = if (critical) Meka.colors.critical else if (quiet) Meka.colors.textSecondary else Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            // Works by hand (quality gate): the slim pill drawn inside a 48 dp touch, as the other mail cards' pills.
            .minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
