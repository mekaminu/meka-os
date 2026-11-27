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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.goals.GoalAddRowView
import os.meka.android.goals.GoalAddSheetFrame
import os.meka.android.security.FoldBiometric
import os.meka.core.domain.DocumentAdded
import os.meka.core.domain.DocumentRow
import os.meka.core.domain.DocumentRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MailOpenRules
import os.meka.core.domain.VaultReceiptRules
import os.meka.core.domain.VaultFileOpened
import os.meka.core.domain.VaultFileRules
import os.meka.core.domain.VaultFileSaid
import os.meka.core.domain.VaultLockRules
import os.meka.core.facade.MekaCore

/**
 * The Vault's documents (V2, slice 1), above Your data: "+ Add a document" opening a sheet with one line — "Passport
 * expires 3 Mar 2029", "Theo passport 20 May 2027", "Railcard 05/2027" (walk-through week 5, A17: no open box on the
 * page; the sheet stays open while the line is read, a refusal said in it in the critical colour) — and a row per document with how long is left, the ones to renew first (their status
 * lit). Tap a row for Remove. The expiry dates reach the plan as heads-ups (six months before a passport…).
 * Slice 2b: an opened row offers Add a copy (a photo or PDF from the system picker, sealed with the Vault key here),
 * and once there is one Open copy (shown in the app only, [VaultCopyViewer]), Replace copy and Remove copy; the row
 * says what is kept ("Photo · 412 KB · encrypted · added on this Fold, Sun 18 Oct").
 * Receipts → Vault: a receipt kept from an email ([VaultReceiptRules]) also offers Open the email.
 * Slice 4: Open copy asks for his fingerprint first ([VaultLockRules]); leaving the Vault or the app going to the
 * background locks the copies again and closes a shown one.
 *
 * Week 5, A15: each document is said once: with one, the line under DOCUMENTS is the count alone ([DocumentsView.pageLine]),
 * the field's hint is a kind not on the list yet ([DocumentsView.addHint]) and a card's status says what's left.
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
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
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
    var adding by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun add(text: String) {
        if (!DocumentRules.canAdd(text, busy)) return
        busy = true
        scope.launch {
            try {
                val r = runCatching { core.addDocument(text) }.getOrNull()
                    ?: DocumentAdded(DocumentRules.NOT_READ, refused = true)
                if (!r.refused) { haptics.light(); adding = false } else haptics.tick()
                said = r
            } finally { busy = false }
        }
    }
    if (adding) DocumentAddSheetView(v.addHint, DocumentRules.sheetProblem(said), busy, onAdd = ::add, onClose = { adding = false; said = said?.takeIf { !it.refused } })
    Column(
        Modifier.fillMaxWidth().animateContentSize(MekaMotion.expand(reduced)),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("DOCUMENTS", style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(firstIndex)))
        Crossfade(v.pageLine, animationSpec = MekaMotion.appear(reduced), label = "docs-summary") { s ->
            Text(LoneWordGlue.kept(s), style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(firstIndex + 1)))
        }
        // Walk-through week 5, A17: "+ Add a document" is a card row opening the line's sheet, not an open box on the page.
        GoalAddRowView(DocumentRules.addRow(v.rows.size), Modifier.testTag(VAULT_ADD_ROW_TAG).appear(rememberAppearance(firstIndex + 2))) {
            said = null; adding = true
        }
        // What was kept, under the row; a refusal is said in the sheet, under the line it's about (A11).
        Crossfade(DocumentRules.pageSaid(said), animationSpec = MekaMotion.appear(reduced), label = "docs-added") { line ->
            line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
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
                        val show: () -> Unit = {
                            scope.launch {
                                val o = runCatching { core.openVaultCopy(r.id) }.getOrNull()
                                if (o?.base64 != null) { haptics.light(); viewing = o } else {
                                    haptics.tick()
                                    copySaid = r.id to VaultFileSaid(o?.problem ?: VaultFileRules.OFFLINE, refused = true)
                                }
                            }
                        }
                        // Slice 4: a copy opens after his fingerprint (the phone's PIN with none set up).
                        scope.launch {
                            if (runCatching { core.vaultCopiesLocked() }.getOrDefault(true)) {
                                FoldBiometric.unlock(context, VaultLockRules.UNLOCK_REASON, VaultLockRules.UNLOCK_SUB) { ok ->
                                    if (ok) scope.launch { runCatching { core.unlockVault() }; show() } else {
                                        haptics.tick()
                                        copySaid = r.id to VaultFileSaid(VaultLockRules.LOCKED, refused = true)
                                    }
                                }
                            } else show()
                        }
                    },
                    onOpenEmail = {
                        scope.launch {
                            val res = runCatching { core.vaultReceiptLink(r.id) }.getOrNull()
                            val link = res?.link
                            copySaid = r.id to VaultFileSaid(link?.hint ?: res?.problem ?: MailOpenRules.OFFLINE, refused = link == null)
                            if (link != null) runCatching { uri.openUri(link.url) }
                        }
                    },
                    onRemoveCopy = {
                        haptics.tick()
                        scope.launch { runCatching { core.removeVaultCopy(r.id) }.getOrNull()?.let { copySaid = r.id to it } }
                    },
                )
            }
        }
        Text(LoneWordGlue.kept(DocumentRules.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary)
        if (v.rows.any { it.hasCopy }) Text(VaultFileRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
        VaultLockRules.note(v.rows)?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        // Slice 2a: the household's Vault key, and the devices asking for it.
        VaultKeySection(core, firstIndex + 3 + v.rows.size)
    }
    viewing?.let { VaultCopyViewer(it, onClose = { viewing = null }) }
    // Slice 4: the app going to the background, or leaving the Vault, locks the copies again (and closes a shown one),
    // on a scope that outlives this one, which is cancelled as it goes.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        viewing = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch { runCatching { core.lockVault() } }
    }
    DisposableEffect(Unit) {
        onDispose { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch { runCatching { core.lockVault() } } }
    }
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
    onOpenEmail: () -> Unit,
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
            Text(LoneWordGlue.kept(row.line), style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(LoneWordGlue.kept(row.status), style = MekaType.caption, color = statusColor)
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
                // Receipts → Vault: the email it was kept from, in Gmail or Outlook (made at the tap).
                if (row.fromMail) CopyAction(VaultReceiptRules.OPEN, "Open the email ${row.title} came from", lit = !row.hasCopy, onClick = onOpenEmail)
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

/** The tag the screen tests find the Vault's "+ Add a document" row by. */
const val VAULT_ADD_ROW_TAG = "vault-add-row"

/**
 * The Vault's add sheet (walk-through week 5, A17): the line with an example of a kind not on the list yet, what
 * Navilife does with it, Close · Add. Add stays dark until something is typed and while it's read; the sheet stays open
 * until the document is kept, a line it couldn't read said under it in the critical colour (A22) with the line still there.
 */
@Composable
internal fun DocumentAddSheetView(hint: String, problem: String?, busy: Boolean, onAdd: (String) -> Unit, onClose: () -> Unit) {
    val reduced = Meka.reducedMotion
    var typed by rememberSaveable { mutableStateOf("") }
    GoalAddSheetFrame(
        DocumentRules.addSheet(hint), typed, { typed = it },
        add = { onAdd(typed) }, onClose = onClose,
        canAdd = DocumentRules.canAdd(typed, busy),
        maxLength = 80,
        closeOnAdd = false,
    ) {
        val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
        val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))
        AnimatedVisibility(problem != null, enter = enter, exit = exit) {
            Text(LoneWordGlue.kept(problem.orEmpty()), style = MekaType.caption, color = Meka.colors.critical)
        }
    }
}
