package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.core.domain.ArchivedAllList
import os.meka.core.domain.ArchivedAllKindChip
import os.meka.core.domain.ArchivedAllRules
import os.meka.core.domain.ArchivedAllSender
import os.meka.core.domain.ArchivedEmailPreview
import os.meka.core.domain.MailTidiedAction
import os.meka.core.domain.MailTidiedBulkRules
import os.meka.core.facade.MekaCore

internal const val ARCHIVED_ALL_KINDS_TAG = "archived-all-kinds"
internal const val ARCHIVED_ALL_SELECT_TAG = "archived-all-select"
internal const val ARCHIVED_ALL_CONFIRM_TAG = "archived-all-confirm"

/** Hosts All tidied mail over Needs you (or the command centre): it springs up as a pane and drops away on Close. */
@Composable
internal fun ArchivedAllHost(core: MekaCore, moves: DecisionMoves) {
    MekaPane(visible = moves.archivedAll.value) {
        ArchivedAllPane(core, moves.undoLine, moves.archivedAllKind.value,
            onClose = { moves.archivedAll.value = false; moves.archivedAllKind.value = null })
    }
}

/**
 * "Archived for you" shows which emails, slice 3: **All tidied mail** — every email Navilife tidied in the last 30 days,
 * newest first under its day, with the senders as filter chips ("Deliveroo · 12"; All first). Tap an email: its preview
 * unfolds under it as on the card (Put back, Always keep, Unsubscribe, Undo on the bar).
 *
 * Motion: the pane springs up (MekaPane); the summary cross-fades; a chip's colour blends to the accent with a tick
 * haptic when chosen; days and rows stagger in and move with the list's item motion as the filter changes; a preview
 * unfolds on the expand spring. Reduced motion: cross-fades.
 *
 * Tidied mail, slice 4 (bulk): **Select** turns a tap into picking (a ring that fills gold with a tick haptic; swipes
 * rest while picking), with a bar of "3 selected · Select all · Trash 3 · Put back 2"; on a chosen kind, **Trash all 38
 * promos** unfolds a confirm on the expand spring that says how many and that they go to Trash for 30 days. Each move
 * asks with one Undo on the bar for all of them.
 */
@Composable
internal fun ArchivedAllPane(core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, startKind: String? = null, onClose: () -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    var sender by rememberSaveable { mutableStateOf<String?>(null) }
    // Meka 2026-11-18 23:34: a Needs you tile opens the list on today's emails of its kind; the chips change it.
    var kind by rememberSaveable(startKind) { mutableStateOf(startKind) }
    var openEmail by rememberSaveable { mutableStateOf<String?>(null) }
    // Slice 4: picking several, and the Trash all confirm.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sender, kind) { selected = emptySet(); confirming = false }
    var list by remember { mutableStateOf<ArchivedAllList?>(null) }
    val tick by core.archivedTick.collectAsState()
    LaunchedEffect(sender, kind, tick) { list = runCatching { core.archivedAll(sender, kind) }.getOrNull() }
    val ask: (ArchivedEmailPreview, MailTidiedAction) -> Unit = { p, action ->
        haptics.light()
        scope.launch {
            val said = runCatching { core.askArchivedEmail(p.batchId, p.key, action.name) }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoArchivedEmail(p.batchId, p.key, action.name) } }
        }
    }
    // Tidied mail, slice 3: swipe right = put back, left = Trash, with Undo on the bar.
    val swipe = rememberArchivedSwipe(core, undo)
    val l = list
    val shown = l?.days?.flatMap { it.rows }.orEmpty()
    val bulk: (List<String>, MailTidiedAction) -> Unit = { ids, action ->
        haptics.light()
        scope.launch {
            val said = runCatching { core.askArchivedEmails(ids, action.name, kind) }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoArchivedEmails(ids, action.name) } }
        }
        selected = emptySet(); selecting = false; confirming = false
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("archived-all"),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text(ArchivedAllRules.CLOSE, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); onClose() }.minTouch())
        }
        item(key = "title") {
            Text(ArchivedAllRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(l?.summary.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "archived-all-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary,
                    modifier = Modifier.appear(rememberAppearance(1)).semantics { contentDescription = l?.spoken ?: s })
            }
        }
        if (l != null && l.kinds.isNotEmpty()) {
            item(key = "kinds") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = MekaSpace.xs)
                        .appear(rememberAppearance(2)).testTag(ARCHIVED_ALL_KINDS_TAG),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    l.kinds.forEach { k: ArchivedAllKindChip ->
                        SenderChip(k.label, k.spoken, k.selected) {
                            haptics.tick(); kind = k.kind; sender = null; openEmail = null
                        }
                    }
                }
            }
        }
        if (l != null && l.senders.isNotEmpty()) {
            item(key = "senders") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = MekaSpace.xs)
                        .appear(rememberAppearance(2)),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    SenderChip(ArchivedAllRules.ALL, "Show every sender", l.allSelected) { haptics.tick(); sender = null; openEmail = null }
                    l.senders.forEach { s: ArchivedAllSender ->
                        SenderChip(s.label, s.spoken, s.selected) {
                            haptics.tick(); sender = if (s.selected) null else s.id; openEmail = null
                        }
                    }
                }
            }
        }
        if (l != null && (MailTidiedBulkRules.selectable(shown).isNotEmpty() || l.trashAll != null)) {
            item(key = "bulk") {
                Column(Modifier.animateItem().fillMaxWidth().testTag(ARCHIVED_ALL_SELECT_TAG), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        if (MailTidiedBulkRules.selectable(shown).isNotEmpty()) {
                            val label = if (selecting) MailTidiedBulkRules.DONE else MailTidiedBulkRules.SELECT
                            ArchivedPill(label, spoken = if (selecting) "Stop selecting" else "Select several emails", quiet = !selecting) {
                                haptics.tick(); selecting = !selecting; selected = emptySet(); confirming = false; openEmail = null
                            }
                        }
                        val all = l.trashAll
                        if (all != null && !selecting) {
                            ArchivedPill(all.label, spoken = all.spoken, critical = true) { haptics.tick(); confirming = !confirming }
                        }
                    }
                    AnimatedVisibility(selecting, enter = unfold(), exit = fold()) {
                        val bar = MailTidiedBulkRules.bar(shown, selected)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface).padding(MekaSpace.s)) {
                            Text(bar.line, style = MekaType.body, color = Meka.colors.textPrimary,
                                modifier = Modifier.semantics { contentDescription = bar.spoken })
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                                ArchivedPill(bar.toggle, spoken = bar.toggle, quiet = true) {
                                    haptics.tick(); selected = MailTidiedBulkRules.toggled(shown, selected)
                                }
                                bar.trash?.let { t ->
                                    ArchivedPill(t, spoken = "$t, to Trash", critical = true) {
                                        bulk(shown.filter { it.id in selected && it.row.swipe?.left == MailTidiedAction.TRASH }.map { it.id }, MailTidiedAction.TRASH)
                                    }
                                }
                                bar.putBack?.let { b ->
                                    ArchivedPill(b, spoken = b) {
                                        bulk(shown.filter { it.id in selected && it.row.swipe?.right == MailTidiedAction.PUT_BACK }.map { it.id }, MailTidiedAction.PUT_BACK)
                                    }
                                }
                            }
                        }
                    }
                    val all = l.trashAll
                    AnimatedVisibility(confirming && all != null && !selecting, enter = unfold(), exit = fold()) {
                        if (all != null) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                                .padding(MekaSpace.s).testTag(ARCHIVED_ALL_CONFIRM_TAG)) {
                                Text(all.confirmTitle, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                                Text(all.confirmBody, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                                    ArchivedPill(all.confirmButton, spoken = all.confirmButton, critical = true) { bulk(all.ids, MailTidiedAction.TRASH) }
                                    ArchivedPill(MailTidiedBulkRules.CANCEL, spoken = MailTidiedBulkRules.CANCEL, quiet = true) { haptics.tick(); confirming = false }
                                }
                            }
                        }
                    }
                }
            }
        }
        l?.days?.forEachIndexed { di, d ->
            item(key = "day-${d.id}") {
                SectionLabel(d.label.uppercase(), Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3 + di)))
            }
            items(d.rows, key = { r -> "row-${r.id}" }) { r ->
                val id = r.id
                Column(Modifier.animateItem().fillMaxWidth()) {
                    Text("${r.from} · ${r.meta}", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                    val pickable = selecting && r.row.swipe?.any == true
                    if (pickable) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.s), verticalAlignment = Alignment.CenterVertically) {
                            SelectRing(id in selected)
                            Column(Modifier.weight(1f)) {
                                ArchivedEmailRowView(r.row, spokenAs = (if (id in selected) "Selected. " else "Not selected. ") + r.spoken.removeSuffix(". Opens its preview")) {
                                    haptics.tick(); selected = if (id in selected) selected - id else selected + id
                                }
                            }
                        }
                    } else {
                        ArchivedEmailRowView(r.row, spokenAs = r.spoken, onSwipe = if (selecting) null else ({ a: MailTidiedAction -> swipe(r.row, a) })) {
                            if (!selecting) { haptics.tick(); openEmail = if (openEmail == id) null else id }
                        }
                    }
                    AnimatedVisibility(openEmail == id && !selecting, enter = unfold(), exit = fold()) {
                        ArchivedEmailPreviewPanel(r.row, tick, core, ask)
                    }
                }
            }
        }
        val tail = if (l == null) emptyList() else listOfNotNull(l.more, l.countsOnly, l.empty?.takeIf { l.days.isEmpty() && l.countsOnly == null })
        tail.forEach { line ->
            item(key = "line-$line") {
                Text(line, style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
        }
        item(key = "note") {
            Text(ArchivedAllRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun SenderChip(label: String, spoken: String, selected: Boolean, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val color by animateColorAsState(
        if (selected) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "sender-chip",
    )
    Text(
        label, style = MekaType.itemMeta, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (selected) Meka.colors.surfaceRaised else Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken; this.selected = selected }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** Slice 4: the pick ring beside a row while selecting; it fills gold when picked. */
@Composable
private fun SelectRing(on: Boolean) {
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "select-ring")
    Box(
        Modifier.size(22.dp).clip(CircleShape).background(fill)
            .border(2.dp, if (on) Meka.colors.accent else Meka.colors.textTertiary, CircleShape),
    )
}
