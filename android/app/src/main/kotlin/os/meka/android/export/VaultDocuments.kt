package os.meka.android.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Field
import os.meka.core.domain.DocumentAdded
import os.meka.core.domain.DocumentRow
import os.meka.core.domain.DocumentRules
import os.meka.core.domain.VaultFileOpened
import os.meka.core.domain.VaultFileRules
import os.meka.core.domain.VaultFileSaid
import os.meka.core.facade.MekaCore

/**
 * The Vault's documents (V2, slice 1), above Your data: one field — "Passport expires 3 Mar 2029", "Theo passport 20
 * May 2027", "Railcard 05/2027" — and a row per document with how long is left, the ones to renew first (their status
 * lit). Tap a row for Remove. The expiry dates reach the plan as heads-ups (six months before a passport…).
 * Slice 2b: an opened row offers Add a copy (a photo or PDF from the system picker, sealed with the Vault key here),
 * and once there is one Open copy (shown in the app only, [VaultCopyViewer]), Replace copy and Remove copy; the row
 * says what is kept ("Photo · 412 KB · encrypted · added on this Fold, Sun 18 Oct").
 *
 * Motion: the section staggers in with the screen; the summary and the line under the field cross-fade (a refused line
 * lit in the accent); rows grow in and out on the expand spring; the status blends to the accent when a renewal window
 * opens; an opened row grows on the expand spring; Add gives a light haptic (a tick when refused), Remove a tick.
 * The copy line and what adding or removing a copy said cross-fade (a refusal lit in the accent); the viewer fades and
 * scales in. Reduced motion: cross-fades.
 */
@Composable
fun VaultDocuments(core: MekaCore, firstIndex: Int) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.documentsView.collectAsState()
    var said by remember { mutableStateOf<DocumentAdded?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // Slice 2b: the document a picked copy is for, what the last copy action said (by row), and the opened copy.
    var copyFor by remember { mutableStateOf<String?>(null) }
    var copySaid by remember { mutableStateOf<Pair<String, VaultFileSaid>?>(null) }
    var viewing by remember { mutableStateOf<VaultFileOpened?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val docId = copyFor ?: return@rememberLauncherForActivityResult
        copyFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = VaultCopies.read(context, uri)
            val said = if (picked.base64 == null) VaultFileSaid(picked.problem ?: VaultFileRules.COULDNT_READ, refused = true)
            else runCatching { core.addVaultCopy(docId, picked.type, picked.base64, picked.name) }.getOrNull()
                ?: VaultFileSaid(VaultFileRules.COULDNT_SEAL, refused = true)
            if (said.refused) haptics.tick() else haptics.light()
            copySaid = docId to said
        }
    }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "docs-said",
    )
    Column(
        Modifier.fillMaxWidth().animateContentSize(MekaMotion.expand(reduced)),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("DOCUMENTS", style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(firstIndex)))
        Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "docs-summary") { s ->
            Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(firstIndex + 1)))
        }
        Field(DocumentRules.ADD_HINT, Modifier.appear(rememberAppearance(firstIndex + 2))) { text ->
            scope.launch {
                val r = runCatching { core.addDocument(text) }.getOrNull()
                if (r?.refused == false) haptics.light() else haptics.tick()
                said = r
            }
        }
        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "docs-added") { r ->
            r?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
        }
        v.rows.forEachIndexed { i, r ->
            key(r.id) {
                DocumentRowView(
                    row = r,
                    open = open == r.id,
                    modifier = Modifier.appear(rememberAppearance(firstIndex + 3 + i)),
                    said = copySaid?.takeIf { it.first == r.id }?.second,
                    onToggle = { open = if (open == r.id) null else r.id },
                    onRemove = { haptics.tick(); open = null; scope.launch { runCatching { core.removeDocument(r.id) } } },
                    onAddCopy = {
                        copyFor = r.id
                        runCatching { picker.launch(arrayOf(VaultFileRules.JPEG, VaultFileRules.PNG, VaultFileRules.PDF)) }
                            .onFailure { copyFor = null }
                    },
                    onOpenCopy = {
                        scope.launch {
                            val o = runCatching { core.openVaultCopy(r.id) }.getOrNull()
                            if (o?.base64 != null) { haptics.light(); viewing = o } else {
                                haptics.tick()
                                copySaid = r.id to VaultFileSaid(o?.problem ?: VaultFileRules.OFFLINE, refused = true)
                            }
                        }
                    },
                    onRemoveCopy = {
                        haptics.tick()
                        scope.launch { runCatching { core.removeVaultCopy(r.id) }.getOrNull()?.let { copySaid = r.id to it } }
                    },
                )
            }
        }
        Text(DocumentRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
        if (v.rows.any { it.hasCopy }) Text(VaultFileRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
        // Slice 2a: the household's Vault key, and the devices asking for it.
        VaultKeySection(core, firstIndex + 3 + v.rows.size)
    }
    viewing?.let { VaultCopyViewer(it, onClose = { viewing = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentRowView(
    row: DocumentRow,
    open: Boolean,
    modifier: Modifier,
    said: VaultFileSaid?,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onAddCopy: () -> Unit,
    onOpenCopy: () -> Unit,
    onRemoveCopy: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (row.statusLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "doc-status",
    )
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(reduced))
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).minTouch()
                .semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(row.status, style = MekaType.caption, color = statusColor)
            Crossfade(row.copyLine, animationSpec = MekaMotion.appear(reduced), label = "doc-copy") { c ->
                c?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
            }
        }
        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "doc-copy-said") { r ->
            r?.let { Text(it.line, style = MekaType.caption, color = if (it.refused) Meka.colors.accent else Meka.colors.textSecondary) }
        }
        AnimatedVisibility(
            visible = open,
            enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                if (row.hasCopy) {
                    CopyAction(VaultFileRules.OPEN, "Open the copy of ${row.title}", lit = true, onClick = onOpenCopy)
                    CopyAction(VaultFileRules.REPLACE, "Replace the copy of ${row.title}", lit = false, onClick = onAddCopy)
                    CopyAction(VaultFileRules.REMOVE, "Remove the copy of ${row.title}", lit = false, onClick = onRemoveCopy)
                } else {
                    CopyAction(VaultFileRules.ADD, "Add a photo or PDF of ${row.title}", lit = true, onClick = onAddCopy)
                }
                Text(
                    "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button, onClick = onRemove)
                        .semantics { contentDescription = "Remove ${row.title}" }
                        .minTouch().padding(MekaSpace.xs),
                )
            }
        }
    }
}

/** One of a row's copy actions: Open copy and Add a copy in the accent, the others secondary. */
@Composable
private fun CopyAction(label: String, spoken: String, lit: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.accent else Meka.colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .minTouch().padding(MekaSpace.xs),
    )
}
