package os.meka.android.ask

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.lists.Field
import os.meka.android.security.FoldBiometric
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.MoneyCategoryRow
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneySaved
import os.meka.core.domain.MoneyTxnRow
import os.meka.core.facade.MekaCore

/**
 * Ask → Life → Money (Money manager, slice 1): behind his fingerprint (or the phone's PIN when none is set up), the
 * month's spending by category from the statement files he imports — ‹ month ›, "Spent £1,234 · in £2,500", against
 * the 6 months before, each category with its share as a bar (brass when well over its average), and the month's
 * payments; tap one to put that shop in another category from now on (Undo). Import: name the account, pick the CSV file
 * from the bank. Remove an account (tap twice), Delete all money data (asks first). Read-only; nothing is ever paid.
 *
 * Locking: the core holds no figures for the screen until [MekaCore.openMoney] after the prompt's yes, and closing the
 * pane (or leaving it) calls [MekaCore.closeMoney].
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the month's lines cross-fade, its sections
 * stagger in 40 ms apart, each bar grows to its share (snaps with reduced motion), a refused line blends to the accent
 * with a tick haptic; a save gives a light haptic.
 */
@Composable
fun MoneyPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
    val v by core.moneyView.collectAsState()
    var account by rememberSaveable { mutableStateOf("") }
    var said by remember { mutableStateOf<MoneySaved?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val unlock: () -> Unit = {
        FoldBiometric.unlock(context, MoneyRules.UNLOCK_REASON, "Your spending, read-only") { ok ->
            if (ok) scope.launch { runCatching { core.openMoney() }; haptics.light() } else haptics.tick()
        }
    }
    LaunchedEffect(Unit) { if (core.moneyView.value.locked) unlock() }
    // The app going to the background locks it too: back on screen, Open Money asks again.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch { runCatching { core.closeMoney() } }
    }
    // Leaving the pane locks Money again (on a scope that outlives the pane's, which is cancelled as it goes).
    DisposableEffect(Unit) { onDispose { kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate).launch { runCatching { core.closeMoney() } } } }

    val say: (MoneySaved) -> Unit = { r -> if (r.refused) haptics.tick() else haptics.light(); said = r }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = account
        scope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 8_000_000 }?.decodeToString() }
                }.getOrNull()
            }
            val r = if (text == null) MoneySaved("Couldn't read that file.", refused = true)
            else runCatching { core.importMoneyStatement(name, text) }.getOrNull()
            busy = false
            r?.let(say)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text(MoneyRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.MONEY)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "money-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        if (v.locked) {
            item(key = "unlock") {
                Text(MoneyRules.UNLOCK, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { unlock() }.minTouch().appear(rememberAppearance(2)))
            }
            return@LazyColumn
        }
        v.month?.let { m ->
            item(key = "month") {
                Row(Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(2)), verticalAlignment = Alignment.CenterVertically) {
                    Arrow("‹", "Month before", enabled = v.earlier) { scope.launch { runCatching { core.moneyStep(-1) } } }
                    Crossfade(m.label, animationSpec = MekaMotion.appear(reduced), label = "money-month", modifier = Modifier.weight(1f)) { l ->
                        Text(l, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    }
                    Arrow("›", "Month after", enabled = v.later) { scope.launch { runCatching { core.moneyStep(1) } } }
                }
            }
            item(key = "spent") {
                Column(Modifier.animateItem().appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(m.spentLine, animationSpec = MekaMotion.appear(reduced), label = "money-spent") { l ->
                        Text(l, style = MekaType.body, color = Meka.colors.textPrimary)
                    }
                    m.vsLine?.let { vs ->
                        val c by animateColorAsState(if (m.vsLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-vs")
                        Crossfade(vs, animationSpec = MekaMotion.appear(reduced), label = "money-vs-line") { l ->
                            Text(l, style = MekaType.caption, color = c)
                        }
                    }
                }
            }
            if (m.categories.isNotEmpty()) {
                item(key = "cats-head") {
                    Text("BY CATEGORY", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                m.categories.forEachIndexed { i, c ->
                    item(key = "cat-${c.key}") { CategoryRow(c, Modifier.animateItem().appear(rememberAppearance(4 + i))) }
                }
            }
            if (m.txns.isNotEmpty()) {
                item(key = "txns-head") {
                    Text("PAYMENTS · TAP ONE TO CHANGE ITS CATEGORY", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().padding(top = MekaSpace.m))
                }
                m.txns.forEach { t ->
                    item(key = "txn-${t.id}") {
                        TxnRow(t, Modifier.animateItem()) { key ->
                            scope.launch { runCatching { core.setMoneyCategory(t.id, key) }.getOrNull()?.let(say) }
                        }
                    }
                }
                if (m.more > 0) {
                    item(key = "txns-more") {
                        Text("and ${m.more} more this month", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
                    }
                }
            }
        }
        item(key = "said") {
            val c by animateColorAsState(if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-said")
            Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "money-said-line") { s ->
                if (s != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(s.line, style = MekaType.caption, color = c, modifier = Modifier.weight(1f))
                    s.undo?.let { u ->
                        Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                scope.launch {
                                    runCatching { core.undoMoneyCategory(u) }
                                    haptics.light()
                                    said = null
                                }
                            }.minTouch().padding(MekaSpace.xs))
                    }
                }
            }
        }
        item(key = "import-head") {
            Text("IMPORT A STATEMENT", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.m))
        }
        v.empty?.let { e -> item(key = "empty") { Text(e, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.animateItem()) } }
        item(key = "import") {
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                if (v.accounts.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        v.accounts.take(4).forEach { a ->
                            val on = a.name == account
                            Text(a.name, style = MekaType.caption, color = if (on) Meka.colors.accent else Meka.colors.textSecondary,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                                    .clickable(role = Role.Button) { account = a.name }.minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
                        }
                    }
                }
                Field(MoneyRules.ACCOUNT_HINT, value = account, onValue = { account = it }) { account = it }
                Text(if (busy) "Reading the file…" else MoneyRules.IMPORT, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button, enabled = !busy) {
                        if (account.isBlank()) say(MoneySaved(MoneyRules.NO_ACCOUNT, refused = true))
                        else pick.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                    }.minTouch())
                Text(MoneyRules.CONNECT_LATER, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
        if (v.accounts.isNotEmpty()) {
            item(key = "accounts-head") {
                Text("ACCOUNTS", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
            v.accounts.forEach { a ->
                item(key = "acct-${a.id}") {
                    Row(Modifier.animateItem().fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MekaType.body, color = Meka.colors.textPrimary)
                            Text(a.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                        }
                        val asking = removing == a.id
                        Text(if (asking) "Remove it?" else "Remove", style = MekaType.caption, color = if (asking) Meka.colors.accent else Meka.colors.textTertiary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                if (!asking) { removing = a.id; haptics.tick() } else scope.launch {
                                    removing = null
                                    runCatching { core.removeMoneyAccount(a.id) }.getOrNull()?.let(say)
                                }
                            }.semantics { contentDescription = "Remove ${a.name} and its transactions" }.minTouch().padding(MekaSpace.xs))
                    }
                }
            }
        }
        item(key = "foot") {
            Column(Modifier.animateItem().padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(MoneyRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
                if (v.accounts.isNotEmpty()) {
                    Crossfade(deleting, animationSpec = MekaMotion.appear(reduced), label = "money-delete") { asking ->
                        if (!asking) {
                            Text(MoneyRules.DELETE_ALL, style = MekaType.caption, color = Meka.colors.textSecondary,
                                modifier = Modifier.clickable(role = Role.Button) { deleting = true; haptics.tick() }.minTouch())
                        } else Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            Text(MoneyRules.DELETE_ASK, style = MekaType.caption, color = Meka.colors.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                                Text("Delete", style = MekaType.itemMeta, color = Meka.colors.accent,
                                    modifier = Modifier.clickable(role = Role.Button) {
                                        deleting = false
                                        scope.launch { runCatching { core.deleteAllMoney() }.getOrNull()?.let(say) }
                                    }.minTouch())
                                Text("Cancel", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                                    modifier = Modifier.clickable(role = Role.Button) { deleting = false }.minTouch())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Arrow(glyph: String, spoken: String, enabled: Boolean, onClick: () -> Unit) {
    Text(glyph, style = MekaType.itemTitle, color = if (enabled) Meka.colors.accent else Meka.colors.textTertiary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = spoken }.minTouch().padding(horizontal = MekaSpace.m))
}

@Composable
private fun CategoryRow(c: MoneyCategoryRow, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    var shown by remember { mutableStateOf(if (reduced) c.share else 0f) }
    LaunchedEffect(c.share) { shown = c.share }
    val share by animateFloatAsState(shown, MekaMotion.appear(reduced), label = "money-bar-${c.key}")
    val bar by animateColorAsState(if (c.over) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "money-bar-c-${c.key}")
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = c.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(c.amount, style = MekaType.body, color = Meka.colors.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(bar))
        }
        Text(c.line, style = MekaType.caption, color = if (c.over) Meka.colors.accent else Meka.colors.textSecondary)
    }
}

@Composable
private fun TxnRow(t: MoneyTxnRow, modifier: Modifier, onCategory: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { menu = true }
                .semantics(mergeDescendants = true) { contentDescription = t.spoken + ". Change category" }
                .minTouch().padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(t.merchant, style = MekaType.body, color = Meka.colors.textPrimary)
                Text("${t.day} · ${t.categoryLabel}" + (if (t.taught) " · yours" else "") + " · ${t.account}", style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            Text(t.amount, style = MekaType.body, color = if (t.moneyIn) Meka.colors.accent else Meka.colors.textPrimary)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MoneyRules.CHOICES.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label, style = MekaType.body, color = if (c.key == t.category) Meka.colors.accent else Meka.colors.textPrimary) },
                    onClick = { menu = false; if (c.key != t.category) onCategory(c.key) },
                )
            }
        }
    }
}
