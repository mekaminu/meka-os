package os.meka.android.mail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.MailAccountRow
import os.meka.core.domain.MailBacklogRules
import os.meka.core.domain.MailAccountRules
import os.meka.core.domain.MailAccountsView
import os.meka.core.domain.MailAutopilotPanel
import os.meka.core.domain.MailAutopilotPanelRules
import os.meka.core.domain.MailTidyRow
import os.meka.core.domain.MailSenderPanelRules
import os.meka.core.domain.MailSenderRow
import os.meka.core.domain.MailLabel
import os.meka.core.domain.MailMomentRules
import os.meka.core.domain.MailMomentTime
import os.meka.core.facade.ConnectStart
import os.meka.core.facade.MekaCore

/**
 * Mail (Inbox autopilot, slice 1): the connected mail accounts with their labels, and Connect Gmail / Connect
 * Outlook. Connecting opens the provider's own sign-in page in the browser; MEKA never sees the password and the
 * server keeps the sign-in encrypted. The list refreshes whenever MEKA comes back to the foreground, so it updates as
 * soon as Meka returns from the browser. Reading and tidying come in the next slices.
 *
 * Slice 1b: an account whose Google sign-in ends within the day says so (accent) with Reconnect; **Disconnect** on each
 * account unfolds a question in place (expand spring, tick haptic) and Disconnect there (medium haptic) makes the server
 * forget the sign-in; the row leaves and the note says where to remove MEKA at the provider too. Reduced motion: fades.
 *
 * Slice 3b-2: **Autopilot** on top (its line and Turn off / Turn on; medium haptic, the line cross-fades), and under each
 * account its tidies (time, what moved, the label) with **Undo** (light haptic); an asked Undo cross-fades to "Putting
 * back at the next check…" and then the server's "Put back 6 emails in the inbox". The tidies unfold with the card's
 * size spring; reduced motion: no spring, fades only.
 */
@Composable
fun MailPane(core: MekaCore, onClose: () -> Unit) {
    // null: loading; Result.failure: couldn't reach the server.
    var view by remember { mutableStateOf<Result<MailAccountsView>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = rememberMekaHaptics()
    // Slice 7: when the ten-minute inbox comes (synced).
    var momentTime by remember { mutableStateOf<MailMomentTime?>(null) }
    LaunchedEffect(Unit) { momentTime = runCatching { core.mailMomentTime() }.getOrNull() }

    LaunchedEffect(reload) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            view = core.mailAccounts()?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
        }
    }

    fun connect(provider: String, connectNote: String) = scope.launch {
        haptics.light()
        message = null
        note = connectNote
        when (val r = core.startConnect(provider)) {
            is ConnectStart.OpenBrowser -> runCatching { uri.openUri(r.url) }.onFailure { message = "Couldn't open the browser." }
            ConnectStart.NotSetUp -> message = "Mail isn't set up on your server yet. Finish the registration steps, then try again."
            is ConnectStart.Failed -> message = r.reason
        }
    }

    fun disconnect(row: MailAccountRow) = scope.launch {
        haptics.medium()
        message = null
        val now = core.disconnectMail(row.provider, row.email)
        if (now == null) message = MailAccountRules.DISCONNECT_FAILED
        else { view = Result.success(now); note = MailAccountRules.disconnectedLine(row.provider, row.email) }
    }

    fun relabel(row: MailAccountRow, label: MailLabel) = scope.launch {
        haptics.tick()
        message = null
        val now = core.setMailLabel(row.provider, row.email, label)
        if (now == null) message = MailAccountRules.OFFLINE
        else { view = Result.success(now); note = MailAccountRules.labelledLine(label) }
    }

    fun switchAutopilot(on: Boolean) = scope.launch {
        haptics.medium()
        message = null
        val r = core.setMailAutopilot(on)
        note = r.line
        val now = r.view
        if (now != null) view = Result.success(now) else reload++
    }

    fun setMoment(time: MailMomentTime) = scope.launch {
        haptics.tick()
        message = null
        note = core.setMailMomentTime(time)
        momentTime = time
    }

    fun unsubscribe(id: String) = scope.launch {
        haptics.medium()
        message = null
        val r = core.unsubscribeMailSender(id)
        note = r.line
        val now = r.view
        if (now != null) view = Result.success(now) else reload++
    }

    // Completeness: Keep in inbox / Let Autopilot decide on one sender (synced; the server follows it from its next check).
    fun setRule(id: String, rule: String) = scope.launch {
        haptics.tick()
        message = null
        val r = core.setMailSenderRule(id, rule)
        note = r.line
        val now = r.view
        if (now != null) view = Result.success(now) else reload++
    }

    // Slice 8b: Approve / Cancel on an account's backlog clean-up (synced; the server applies it from its next check).
    fun approveBacklog(id: String, approve: Boolean) = scope.launch {
        if (approve) haptics.medium() else haptics.light()
        message = null
        val r = if (approve) core.approveMailBacklog(id) else core.unapproveMailBacklog(id)
        note = r.line
        val now = r.view
        if (now != null) view = Result.success(now) else reload++
    }

    fun undoTidy(id: String) = scope.launch {
        haptics.light()
        message = null
        val r = core.undoMailTidy(id)
        note = r.line
        val now = r.view
        if (now != null) view = Result.success(now) else reload++
    }

    Column(
        Modifier.fillMaxSize().padding(MekaSpace.gutter).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text(MailAccountRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.MAIL)))
        Text(MailAccountRules.HEADER, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        Spacer(Modifier.height(MekaSpace.m))

        val current = view
        when {
            current == null -> SkeletonRows(count = 2, rowHeight = 72.dp)
            current.isFailure -> Text(MailAccountRules.OFFLINE, style = MekaType.itemMeta, color = Meka.colors.critical)
            else -> {
                val v = current.getOrThrow()
                v.emptyLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                v.autopilot?.let { AutopilotCard(it, Modifier.appear(rememberAppearance(0))) { switchAutopilot(!it.on) } }
                if (v.autopilot != null) momentTime?.let { t -> MomentCard(t, Modifier.appear(rememberAppearance(0))) { setMoment(it) } }
                v.rows.forEachIndexed { i, row ->
                    MailAccountCard(
                        row, Modifier.appear(rememberAppearance(i + 1)),
                        onReconnect = { v.connects.firstOrNull { it.provider == row.provider }?.let { connect(it.provider, it.note) } },
                        onLabel = { relabel(row, it) },
                        onDisconnect = { disconnect(row) },
                        onUndo = { undoTidy(it) },
                        onUnsubscribe = { unsubscribe(it) },
                        onRule = { id, rule -> setRule(id, rule) },
                        onApprove = { id, yes -> approveBacklog(id, yes) },
                    )
                }
                v.toConnect?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                Spacer(Modifier.height(MekaSpace.l))
                v.connects.forEach { c -> ConnectButton(c.label) { connect(c.provider, c.note) } }
            }
        }
        Crossfade(note, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "mail-note") { n ->
            n?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        }
        message?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical) }
        Text("Refresh", style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.padding(top = MekaSpace.m).clickable(role = Role.Button) { reload++ }.minTouch())
    }
}

/** One account: its label and provider, the address, its state, Reconnect when the sign-in ended, and the label chips. */
@Composable
private fun MailAccountCard(
    row: MailAccountRow, modifier: Modifier, onReconnect: () -> Unit, onLabel: (MailLabel) -> Unit, onDisconnect: () -> Unit,
    onUndo: (String) -> Unit, onUnsubscribe: (String) -> Unit = {}, onApprove: (String, Boolean) -> Unit = { _, _ -> },
    onRule: (String, String) -> Unit = { _, _ -> },
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var asking by rememberSaveable(row.provider, row.email) { mutableStateOf(false) }
    var allSenders by rememberSaveable(row.provider, row.email, "senders") { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .animateContentSize(MekaMotion.expand(reduced)).padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Crossfade(row.title, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "mail-title") {
                    Text(it, style = MekaType.body, color = Meka.colors.textPrimary)
                }
                Text(row.email, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            if (row.needsReconnect) {
                Text("Reconnect", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onReconnect() }.minTouch().padding(start = MekaSpace.m))
            }
        }
        Crossfade(row.line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "mail-line") {
            Text(it, style = MekaType.caption, color = when {
                row.ending -> Meka.colors.accent
                row.needsReconnect -> Meka.colors.critical
                else -> Meka.colors.textTertiary
            })
        }
        // Slice 2a: what the rules made of the inbox; it fades in once the server has sorted the account.
        AnimatedVisibility(
            row.sortedLine != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Crossfade(row.sortedLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "mail-sorted") {
                Text(it, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        // Slice 3a: Autopilot's dry run, what it would tidy (nothing moves yet); unfolds the same way.
        AnimatedVisibility(
            row.tidyLine != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Crossfade(row.tidyLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "mail-tidy") {
                Text(it, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
        // Slice 3b-2: what Autopilot tidied here, newest first, each with Undo.
        row.tidies.forEach { t -> TidyLine(t) { onUndo(t.id) } }
        // Slice 5a: who sends the newsletters and promos, each with Unsubscribe (Navilife does it at its next check).
        row.sendersLine?.let { line ->
            Text(MailSenderPanelRules.TITLE, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
            Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
            val shown = if (allSenders) row.senders else row.senders.take(SENDERS_FOLDED)
            shown.forEach { s -> SenderLine(s, onRule = { onRule(s.id, s.ruleSets) }) { onUnsubscribe(s.id) } }
            if (row.senders.size > SENDERS_FOLDED) {
                Text(if (allSenders) "Show fewer" else "Show ${row.senders.size - SENDERS_FOLDED} more", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); allSenders = !allSenders }.minTouch())
            }
        }
        // Slice 8a: the backlog clean-up's plan (the older mail already in the inbox); unfolds once the server starts it,
        // and its line cross-fades as the scan counts up. Nothing moves yet.
        AnimatedVisibility(
            row.backlog != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            row.backlog?.let { b ->
                Column(Modifier.padding(top = MekaSpace.xs).semantics(mergeDescendants = true) {}) {
                    Text(b.title, style = MekaType.caption, color = Meka.colors.textSecondary)
                    Crossfade(b.line, animationSpec = MekaMotion.appear(reduced), label = "mail-backlog") {
                        Text(it, style = MekaType.caption, color = if (b.ready) Meka.colors.textPrimary else Meka.colors.textTertiary)
                    }
                    b.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                    Text(b.note, style = MekaType.caption, color = Meka.colors.textTertiary)
                    // Slice 8b: Approve (the plan is final), Cancel (approved, nothing moved yet), Undo (the clean-up's tidy).
                    val action = b.approveId?.let { MailBacklogRules.APPROVE to { onApprove(it, true) } }
                        ?: b.cancelId?.let { MailBacklogRules.CANCEL to { onApprove(it, false) } }
                        ?: b.undoId?.let { MailBacklogRules.UNDO to { onUndo(it) } }
                    Crossfade(action?.first, animationSpec = MekaMotion.appear(reduced), label = "mail-backlog-action") { label ->
                        if (label != null) {
                            Text(label, style = MekaType.itemMeta,
                                color = if (label == MailBacklogRules.CANCEL) Meka.colors.textSecondary else Meka.colors.accent,
                                modifier = Modifier.clickable(role = Role.Button) { action?.second?.invoke() }.minTouch())
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = MekaSpace.xs),
        ) {
            MailAccountRules.LABELS.forEach { l -> LabelChip(l, selected = l == row.label) { if (l != row.label) onLabel(l) } }
            Spacer(Modifier.weight(1f))
            Text(MailAccountRules.DISCONNECT, style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); asking = !asking }.minTouch())
        }
        AnimatedVisibility(
            asking,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs), modifier = Modifier.padding(top = MekaSpace.xs)) {
                Text(MailAccountRules.disconnectAsk(row), style = MekaType.caption, color = Meka.colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
                    Text(MailAccountRules.DISCONNECT, style = MekaType.itemMeta, color = Meka.colors.critical,
                        modifier = Modifier.clickable(role = Role.Button) { asking = false; onDisconnect() }.minTouch())
                    Text(MailAccountRules.KEEP, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); asking = false }.minTouch())
                }
            }
        }
    }
}

/**
 * Slice 7: the ten-minute inbox's time — Morning · Evening · Off chips (the lit one blends to the accent with a tick
 * haptic; the line cross-fades). Synced with the Mac.
 */
@Composable
private fun MomentCard(time: MailMomentTime, modifier: Modifier, onPick: (MailMomentTime) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = MailMomentRules.TITLE + ". " + MailMomentRules.settingLine(time) }) {
            Text(MailMomentRules.TITLE, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(MailMomentRules.settingLine(time), animationSpec = MekaMotion.appear(reduced), label = "moment-line") {
                Text(it, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            MailMomentTime.entries.forEach { t ->
                val lit = t == time
                val fill by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(reduced), label = "moment-chip")
                val ink by animateColorAsState(if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "moment-ink")
                Text(
                    t.label, style = MekaType.itemMeta, color = ink,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                        .clickable(role = Role.Button) { if (!lit) onPick(t) }
                        .semantics { selected = lit; contentDescription = "Ten-minute inbox: ${t.label}" }
                        .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
            }
        }
    }
}

/** Autopilot's switch: what it does (or that it's off or paused) and Turn off / Turn on. */
@Composable
private fun AutopilotCard(panel: MailAutopilotPanel, modifier: Modifier, onSwitch: () -> Unit) {
    val reduced = Meka.reducedMotion
    val dot by animateColorAsState(
        if (panel.on && !panel.paused) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(reduced), label = "autopilot-dot",
    )
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m)
            .semantics(mergeDescendants = true) { contentDescription = panel.title + ". " + panel.line },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(panel.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(panel.line, animationSpec = MekaMotion.appear(reduced), label = "autopilot-line") {
                Text(it, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        Crossfade(panel.action, animationSpec = MekaMotion.appear(reduced), label = "autopilot-action") { a ->
            Text(
                a, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)
                    .clickable(role = Role.Button) { onSwitch() }.minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}

/** One tidy under its account: "09:05 · Tidied 3 emails · Personal", what moved, and Undo (or how the Undo went). */
@Composable
private fun TidyLine(t: MailTidyRow, onUndo: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = MekaSpace.xxs)) {
        Column(Modifier.weight(1f)) {
            Text("${t.time} · ${t.summary}", style = MekaType.caption, color = Meka.colors.textSecondary)
            t.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            Crossfade(t.state, animationSpec = MekaMotion.appear(reduced), label = "tidy-state") { st ->
                st?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }
        if (t.canUndo) {
            Text(MailAutopilotPanelRules.UNDO, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onUndo() }.minTouch().padding(start = MekaSpace.m))
        }
    }
}

private const val SENDERS_FOLDED = 3

/**
 * One sender under its account: its name, "example.com · 8 emails · last Mon 12 Oct", and Unsubscribe; once asked the
 * state cross-fades in ("Unsubscribing at the next check…", then the server's outcome, accent when done); a sender that
 * only offers a page shows Open page.
 */
@Composable
private fun SenderLine(s: MailSenderRow, onRule: () -> Unit = {}, onUnsubscribe: () -> Unit) {
    val reduced = Meka.reducedMotion
    val uri = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = MekaSpace.xxs)) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MekaType.body, color = if (s.done) Meka.colors.textTertiary else Meka.colors.textPrimary)
            if (s.line.isNotEmpty()) Text(s.line, style = MekaType.caption, color = Meka.colors.textTertiary)
            Crossfade(s.state, animationSpec = MekaMotion.appear(reduced), label = "sender-state") { st ->
                st?.let { Text(it, style = MekaType.caption, color = if (s.done) Meka.colors.accent else Meka.colors.textSecondary) }
            }
            // Completeness: his rule for the sender ("Always kept in the inbox · …") cross-fades in, with the action
            // that sets or takes it off.
            Crossfade(s.ruleLine, animationSpec = MekaMotion.appear(reduced), label = "sender-rule") { r ->
                r?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
            }
            Crossfade(s.ruleAction, animationSpec = MekaMotion.appear(reduced), label = "sender-rule-action") { a ->
                Text(a, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onRule() }.minTouch())
            }
        }
        val link = s.link
        when {
            link != null -> Text(MailSenderPanelRules.OPEN, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(link) } }.minTouch().padding(start = MekaSpace.m))
            s.canUnsubscribe -> Text(MailSenderPanelRules.UNSUBSCRIBE, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onUnsubscribe() }.minTouch().padding(start = MekaSpace.m))
        }
    }
}

/** A label chip; its fill blends as it changes (tick haptic from the caller). */
@Composable
private fun LabelChip(label: MailLabel, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(Meka.reducedMotion), label = "mail-chip")
    Text(
        label.label, style = MekaType.caption, color = if (selected) Meka.colors.onAccent else Meka.colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
            .clickable(role = Role.RadioButton) { onClick() }
            .semantics { contentDescription = "Label ${label.label}"; this.selected = selected }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun ConnectButton(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = Meka.colors.onAccent,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}
