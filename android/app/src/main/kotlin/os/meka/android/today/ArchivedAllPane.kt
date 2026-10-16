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
import os.meka.core.domain.ArchivedAllRules
import os.meka.core.domain.ArchivedAllSender
import os.meka.core.domain.ArchivedEmailPreview
import os.meka.core.domain.MailTidiedAction
import os.meka.core.facade.MekaCore

/** Hosts All tidied mail over Needs you (or the command centre): it springs up as a pane and drops away on Close. */
@Composable
internal fun ArchivedAllHost(core: MekaCore, moves: DecisionMoves) {
    MekaPane(visible = moves.archivedAll.value) {
        ArchivedAllPane(core, moves.undoLine, onClose = { moves.archivedAll.value = false })
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
 */
@Composable
internal fun ArchivedAllPane(core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit, onClose: () -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    var sender by rememberSaveable { mutableStateOf<String?>(null) }
    var openEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<ArchivedAllList?>(null) }
    val tick by core.archivedTick.collectAsState()
    LaunchedEffect(sender, tick) { list = runCatching { core.archivedAll(sender) }.getOrNull() }
    val ask: (ArchivedEmailPreview, MailTidiedAction) -> Unit = { p, action ->
        haptics.light()
        scope.launch {
            val said = runCatching { core.askArchivedEmail(p.batchId, p.key, action.name) }.getOrNull() ?: return@launch
            undo(said) { runCatching { core.undoArchivedEmail(p.batchId, p.key, action.name) } }
        }
    }
    val l = list
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
        l?.days?.forEachIndexed { di, d ->
            item(key = "day-${d.id}") {
                SectionLabel(d.label.uppercase(), Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3 + di)))
            }
            items(d.rows, key = { r -> "row-${r.id}" }) { r ->
                val id = r.id
                Column(Modifier.animateItem().fillMaxWidth()) {
                    Text("${r.from} · ${r.meta}", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                    ArchivedEmailRowView(r.row, spokenAs = r.spoken) { haptics.tick(); openEmail = if (openEmail == id) null else id }
                    AnimatedVisibility(openEmail == id, enter = unfold(), exit = fold()) {
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
