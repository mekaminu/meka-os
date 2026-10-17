package os.meka.android.today

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
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
import os.meka.core.domain.JunkReview
import os.meka.core.domain.JunkReviewRow
import os.meka.core.facade.MekaCore

/** Hosts a card's review (Empty Junk, the backlog clean-up, Mark N read) over Needs you (or the command centre): springs up on ↑ Review, drops away on Close. */
@Composable
internal fun JunkReviewHost(core: MekaCore, moves: DecisionMoves) {
    var shown by remember { mutableStateOf<String?>(null) }
    val id = moves.junkReview.value
    if (id != null) shown = id
    MekaPane(visible = id != null) {
        shown?.let { cardId -> JunkReviewPane(core, cardId, moves.undoLine, onClose = { moves.junkReview.value = null }) }
    }
}

/**
 * Review opens its own list (Meka, 2026-10-17 04:03), slice 1: **the Empty Junk review**; slice 4: the same pane shows the
 * backlog clean-up's list (who'd be archived or trashed, untick to keep in the inbox) and Mark N read's (untick to leave unread). What Approve would move:
 * the people going back to the inbox first (who and why), then every sender going to Trash, the most first (name,
 * domain, how many, two subjects, a "Looks like a scam" chip), each with a tick; taking it off keeps that sender's mail
 * in Junk (synced, Undo on the bar). Approve at the foot counts only what's still ticked.
 *
 * Motion: the pane springs up (MekaPane); the summary and Approve's label cross-fade as ticks change; rows stagger in
 * once; a tick's ring blends between brass and the hairline with a tick haptic. Reduced motion: cross-fades only.
 */
@Composable
internal fun JunkReviewPane(core: MekaCore, cardId: String, undo: (String, (suspend () -> Unit)?) -> Unit, onClose: () -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val reduced = Meka.reducedMotion
    var review by remember { mutableStateOf<JunkReview?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(cardId) { review = runCatching { core.junkReview(cardId) }.getOrNull(); loaded = true }
    fun keep(row: JunkReviewRow, keep: Boolean) {
        haptics.tick()
        scope.launch {
            val r = runCatching { core.keepJunkSender(cardId, row.key, keep) }.getOrNull() ?: return@launch
            review = r.review
            r.line?.let { line ->
                undo("$line · ${row.title}") {
                    runCatching { core.keepJunkSender(cardId, row.key, !keep) }.getOrNull()?.let { review = it.review }
                }
            }
        }
    }
    val r = review
    LazyColumn(
        Modifier.fillMaxSize().testTag("junk-review"),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("‹ Needs you", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); onClose() }.minTouch())
        }
        item(key = "title") {
            Text(r?.title ?: "Review", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.appear(rememberAppearance(0)))
        }
        if (r == null) {
            if (loaded) item(key = "gone") {
                Text("Nothing left to approve here · it has started, or there's nothing left to move.", style = MekaType.body,
                    color = Meka.colors.textSecondary)
            }
            return@LazyColumn
        }
        item(key = "summary") {
            Crossfade(r.summary, animationSpec = MekaMotion.appear(reduced), label = "junk-review-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "hint") {
            Text(r.hint, style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(2)))
        }
        if (r.rescues.isNotEmpty()) {
            item(key = "rescue-label") { SectionLabel("BACK TO YOUR INBOX · ${r.rescues.size}", Modifier.padding(top = MekaSpace.m)) }
            items(r.rescues, key = { "rescue-${it.title}-${it.meta}" }) { x ->
                Column(Modifier.animateItem().fillMaxWidth().padding(vertical = MekaSpace.xs)) {
                    Text(x.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    Text(x.meta, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                    Text(x.why, style = MekaType.itemMeta, color = Meka.colors.accent)
                    x.subjects.forEach { Text("“$it”", style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
                }
            }
        }
        if (r.senders.isNotEmpty()) {
            item(key = "trash-label") { SectionLabel(r.sendersLabel, Modifier.padding(top = MekaSpace.m)) }
            items(r.senders, key = { "sender-${it.key}" }) { s -> SenderRow(s, Modifier.animateItem()) { keep(s, !s.kept) } }
        }
        r.staysLine?.let { line ->
            item(key = "stays") { Text(line, style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.m)) }
        }
        r.partLine?.let { line -> item(key = "part") { Text(line, style = MekaType.itemMeta, color = Meka.colors.textTertiary) } }
        item(key = "approve") {
            Column(Modifier.padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Crossfade(r.approveLabel, animationSpec = MekaMotion.appear(reduced), label = "junk-review-approve") { label ->
                    Box(
                        Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                            .background(if (r.canApprove) Meka.colors.accent else Meka.colors.hairline)
                            .clickable(enabled = r.canApprove, role = Role.Button) {
                                haptics.medium()
                                scope.launch {
                                    val done = runCatching { core.approveMailBacklog(cardId) }.getOrNull() ?: return@launch
                                    undo(done.line) { core.unapproveMailBacklog(cardId); Unit }
                                    onClose()
                                }
                            }
                            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = MekaType.itemTitle, color = Meka.colors.onAccent) }
                }
                Text(r.note, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun SenderRow(s: JunkReviewRow, modifier: Modifier, onToggle: () -> Unit) {
    val ring by animateColorAsState(if (s.kept) Meka.colors.hairline else Meka.colors.accent, MekaMotion.appear(Meka.reducedMotion), label = "junk-tick")
    Row(
        modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onToggle).minTouch()
            .semantics(mergeDescendants = true) {
                contentDescription = "${s.title}, ${s.meta}"
                stateDescription = s.state
            }
            .padding(vertical = MekaSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = MekaSpace.xxs).size(22.dp).clip(CircleShape)
                .background(if (s.kept) Meka.colors.surface else ring).border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (!s.kept) Text("✓", style = MekaType.caption, color = Meka.colors.onAccent) }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, style = MekaType.itemTitle, color = if (s.kept) Meka.colors.textSecondary else Meka.colors.textPrimary)
                if (s.scam) {
                    Text("⚠ Looks like a scam", style = MekaType.caption, color = Meka.colors.critical,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).border(1.dp, Meka.colors.critical, RoundedCornerShape(MekaRadius.pill))
                            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs))
                }
            }
            Text(s.meta, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            s.scamLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.critical) }
            s.subjects.forEach { Text("“$it”", style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
            Text(s.state, style = MekaType.itemMeta, color = if (s.kept) Meka.colors.accent else Meka.colors.textTertiary)
        }
    }
}
