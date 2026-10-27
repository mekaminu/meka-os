package os.meka.android.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.lists.Field
import os.meka.core.domain.MoneyAccountItem
import os.meka.core.domain.MoneyAccountList
import os.meka.core.domain.MoneyAccountSheet
import os.meka.core.domain.MoneyAccountNames
import os.meka.core.domain.MoneyBusiness
import os.meka.core.domain.MoneyMore
import os.meka.core.domain.MoneyMoreKind
import os.meka.core.domain.MoneyMoreRow
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyWorth

/**
 * Money review (Meka 2026-10-26 22:12), Accounts: one card listing his accounts as the mockup draws them (a 40 dp tile
 * with two letters, the short name over what it is, the balance on the right; a card's owed amount in the accent; "Add
 * balance" when there's none), each row opening its sheet. A trading row goes to the Trading tab. Everything it says is
 * the core's `MoneyAccountList`; this only draws it.
 *
 * Motion: the card appears with the screen's stagger; a changed balance or line cross-fades; rows press like every row.
 */
@Composable
internal fun MoneyAccountsCard(items: List<MoneyAccountItem>, modifier: Modifier = Modifier, onOpen: (MoneyAccountItem) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("money-accounts-card"),
    ) {
        for (r in items) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onOpen(r) }
                    .semantics(mergeDescendants = true) { contentDescription = r.spoken }
                    .padding(vertical = MekaSpace.xs).testTag("money-account-${r.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(r.initials, style = MekaType.caption, color = Meka.colors.accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Crossfade(r.sub, animationSpec = MekaMotion.appear(reduced), label = "money-account-sub-${r.id}") { l ->
                        Text(l, style = MekaType.caption, color = if (r.lit) Meka.colors.accent else Meka.colors.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Crossfade(r.amount, animationSpec = MekaMotion.appear(reduced), label = "money-account-amount-${r.id}") { a ->
                    Text(a, style = if (r.addBalance) MekaType.caption else MekaType.metaStrong,
                        color = when {
                            r.addBalance -> Meka.colors.accent
                            r.owes -> Meka.colors.approval
                            else -> Meka.colors.textPrimary
                        }, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Accounts' rest (mockup, the card under the accounts): Side hustle and tax, Shared with his partner, and Import, export
 * and delete as one-line rows (title over its line, ›), each opening its sheet. Motion: the card appears with the
 * screen's stagger; a changed line cross-fades; rows press like every row.
 */
@Composable
internal fun MoneyMoreCard(rows: List<MoneyMoreRow>, modifier: Modifier = Modifier, onOpen: (MoneyMoreKind) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("money-more-card"),
    ) {
        for (r in rows) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onOpen(r.kind) }
                    .semantics(mergeDescendants = true) { contentDescription = r.spoken }
                    .padding(vertical = MekaSpace.xs).testTag("money-more-${r.kind.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-more-line-${r.kind}") { l ->
                        Text(l, style = MekaType.caption, color = if (r.lit) Meka.colors.accent else Meka.colors.textSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
            }
        }
    }
}

/**
 * Import, export and delete's sheet: IMPORT A STATEMENT (the account's name and Choose file), EXPORT (all payments as a
 * CSV to save) and Delete all money data, which asks first. The file picker and the save sheet are the caller's.
 */
@Composable
internal fun MoneyDataDetails(
    account: String, busy: Boolean, canDelete: Boolean, connectLater: Boolean,
    onAccount: (String) -> Unit, onPick: () -> Unit, onExport: () -> Unit, onDeleteAll: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(MoneyAccountList.IMPORT_HEAD.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text(MoneyAccountList.IMPORT_ROW_SUB, style = MekaType.caption, color = Meka.colors.textSecondary)
        Field(MoneyRules.ACCOUNT_HINT, value = account, onValue = onAccount) { onAccount(it) }
        Text(if (busy) "Reading the file…" else MoneyRules.IMPORT, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, enabled = !busy, onClick = onPick)
                .minTouch().padding(vertical = MekaSpace.xs).testTag("money-import-file"))
        if (connectLater) Text(MoneyRules.CONNECT_LATER, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text("EXPORT", style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.m))
        Text(MoneyMore.EXPORT_LINE, style = MekaType.caption, color = Meka.colors.textSecondary)
        Text(MoneyMore.EXPORT, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onExport)
                .minTouch().padding(vertical = MekaSpace.xs).testTag("money-export-all"))
        if (canDelete) AnimatedContent(
            asking, label = "money-delete-all", modifier = Modifier.padding(top = MekaSpace.m),
            transitionSpec = { fadeIn(MekaMotion.expand(reduced)) togetherWith fadeOut(MekaMotion.expand(reduced)) using SizeTransform(clip = false) },
        ) { ask ->
            if (!ask) Text(MoneyRules.DELETE_ALL, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = true }
                    .minTouch().padding(vertical = MekaSpace.xs).testTag("money-delete-all"))
            else Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(MoneyRules.DELETE_ASK, style = MekaType.body, color = Meka.colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                    Text("Delete", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = false; onDeleteAll() }
                            .minTouch().padding(MekaSpace.xs).testTag("money-delete-all-yes"))
                    Text(MoneyAccountList.CANCEL, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = false }
                            .minTouch().padding(MekaSpace.xs))
                }
            }
        }
    }
}

/**
 * An account's sheet (inside `MoneyDetailSheet`): the bank's own name and what's in it, BALANCE (its figure and line, a
 * field to type it where that makes sense), It's a card and Side hustle account as pills, Import a statement into it, and
 * Remove account, which asks first ("Remove HSBC current and its 120 transactions?").
 */
@Composable
internal fun MoneyAccountDetails(
    a: MoneyAccountSheet, busy: Boolean,
    onBalance: (String) -> Unit, onCard: (Boolean) -> Unit, onSideHustle: (Boolean) -> Unit,
    onImport: () -> Unit, onRemove: () -> Unit, onRename: (String) -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    var asking by rememberSaveable(a.id) { mutableStateOf(false) }
    var balance by rememberSaveable(a.id) { mutableStateOf("") }
    var name by rememberSaveable(a.id) { mutableStateOf(a.nick ?: a.title) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        a.fullName?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(a.line, style = MekaType.caption, color = Meka.colors.textSecondary)
        // Accounts' rest: Name — type what to call it and press Done on the keyboard; clear it for its own name.
        Text(MoneyAccountNames.RENAME.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
        Field(MoneyAccountNames.RENAME_HINT, modifier = Modifier.testTag("money-account-name"), value = name,
            onValue = { name = it.take(MoneyAccountNames.NICK_MAX) }, description = "${MoneyAccountNames.RENAME} of ${a.title}") { onRename(it) }
        Text(MoneyAccountList.BALANCE, style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
        Crossfade(a.amount ?: MoneyAccountList.ADD_BALANCE, animationSpec = MekaMotion.appear(reduced), label = "money-account-sheet-amount") { t ->
            Text(t, style = if (a.amount != null) MekaType.nowTitle else MekaType.body,
                color = if (a.amount != null) Meka.colors.textPrimary else Meka.colors.textSecondary, modifier = Modifier.testTag("money-account-amount"))
        }
        a.balanceLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        if (a.canType) Field(MoneyWorth.BALANCE_HINT, modifier = Modifier.testTag("money-account-balance"), value = balance,
            onValue = { balance = it }, description = "${MoneyWorth.BALANCE_HINT} for ${a.title}") { balance = ""; onBalance(it) }
        Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            SwitchRow(MoneyAccountList.CARD_SWITCH, MoneyAccountList.CARD_SWITCH_LINE, a.card, "money-account-card") { onCard(!a.card) }
            a.sideHustle?.let { on -> SwitchRow(MoneyBusiness.SWITCH, MoneyAccountList.SIDE_HUSTLE_LINE, on, "money-account-side") { onSideHustle(!on) } }
        }
        Text(if (busy) "Reading the file…" else MoneyAccountList.IMPORT, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, enabled = !busy, onClick = onImport).minTouch().padding(vertical = MekaSpace.xs)
                .testTag("money-account-import"))
        AnimatedContent(
            asking, label = "money-account-remove",
            transitionSpec = { fadeIn(MekaMotion.expand(reduced)) togetherWith fadeOut(MekaMotion.expand(reduced)) using SizeTransform(clip = false) },
        ) { ask ->
            if (!ask) Text(MoneyAccountList.REMOVE, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = true }
                    .minTouch().padding(vertical = MekaSpace.xs).testTag("money-account-remove"))
            else Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(a.removeAsk, style = MekaType.body, color = Meka.colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                    Text(MoneyAccountList.REMOVE_YES, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = false; onRemove() }
                            .minTouch().padding(MekaSpace.xs).testTag("money-account-remove-yes"))
                    Text(MoneyAccountList.CANCEL, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { asking = false }
                            .minTouch().padding(MekaSpace.xs))
                }
            }
        }
    }
}

/** A switch as a row: the label over its line, and an On pill lit in the accent (a tick haptic is the caller's). */
@Composable
private fun SwitchRow(label: String, line: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $line"; stateDescription = if (on) "On" else "Off" }
            .minTouch().padding(vertical = MekaSpace.xxs).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        TripPill(if (on) "On" else "Off", on = on, description = "$label, ${if (on) "on" else "off"}", onClick = onClick)
    }
}
