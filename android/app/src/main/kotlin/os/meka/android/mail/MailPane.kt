package os.meka.android.mail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import os.meka.core.domain.MailAccountRules
import os.meka.core.domain.MailAccountsView
import os.meka.core.domain.MailLabel
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
                v.rows.forEachIndexed { i, row ->
                    MailAccountCard(
                        row, Modifier.appear(rememberAppearance(i)),
                        onReconnect = { v.connects.firstOrNull { it.provider == row.provider }?.let { connect(it.provider, it.note) } },
                        onLabel = { relabel(row, it) },
                        onDisconnect = { disconnect(row) },
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
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var asking by rememberSaveable(row.provider, row.email) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
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
