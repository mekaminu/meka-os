package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.security.FoldApprovalKey
import os.meka.android.security.FoldBiometric
import os.meka.core.domain.ApprovalCard
import os.meka.core.domain.ApprovalRules
import os.meka.core.facade.MekaCore

/**
 * The approvals inbox (V1, slice 1), first in Needs you: a request from outside MEKA to trade, pay or share a document
 * ("TRADE · KESTREL", what would happen, its detail lines, "Asked 21:40 · expires in 14 min", lit in the critical
 * colour in its last 15 minutes), then Approve and Decline. **Approve asks for the fingerprint every time** (strong
 * biometrics only); the finger unlocks the phone's approval key ([FoldApprovalKey], slice 2) for one signature over the
 * core's text, and only that signed yes reaches the core. Decline is a tap. Nothing
 * happens until Meka answers; answering here clears the Mac. Buttons press in (Approve a light haptic, Decline a
 * tick); once taken the card folds away with the list's item motion (a medium haptic on a yes) and the bar says
 * "Approved · …" (no Undo: the one who asked acts on it). Cards stagger in after the title. Reduced motion: the list's
 * cross-fade.
 */
@Composable
internal fun ApprovalCardView(card: ApprovalCard, core: MekaCore, say: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val approve = {
        haptics.light()
        if (!FoldBiometric.available(context)) {
            say(ApprovalRules.NO_BIOMETRIC_FOLD)
        } else scope.launch {
            // Slice 2: what to sign first (an expired or answered one is refused before any prompt), then the
            // fingerprint unlocks the approval key for that one signature, then the signed yes goes to the core.
            val prep = runCatching { core.prepareApproval(card.id) }.getOrNull() ?: return@launch
            prep.refusal?.let { say(it); return@launch }
            val message = prep.message ?: return@launch
            when (val ready = withContext(Dispatchers.Default) { FoldApprovalKey.ready() }) {
                FoldApprovalKey.Ready.Unavailable -> say(ApprovalRules.NO_BIOMETRIC_FOLD)
                FoldApprovalKey.Ready.Renewed -> {
                    withContext(Dispatchers.Default) { FoldApprovalKey.publicKey() }?.let { runCatching { core.registerApprovalKey(it, FoldApprovalKey.LABEL) } }
                    say(ApprovalRules.KEY_RENEWED_FOLD)
                }
                is FoldApprovalKey.Ready.Sign -> FoldBiometric.sign(context, card.title, ApprovalRules.promptReason(card), ready.signature, message) { sig ->
                    if (sig != null) scope.launch {
                        val answer = runCatching {
                            core.approve(card.id, prep.decisionKey, prep.decidedAtMs, ready.publicKey, sig, FoldApprovalKey.LABEL)
                        }.getOrNull()
                        if (answer != null) {
                            if (answer.done) haptics.medium()
                            say(answer.line)
                        }
                    }
                }
            }
        }
        Unit
    }
    val decline = {
        haptics.tick()
        scope.launch { runCatching { core.declineApproval(card.id) }.getOrNull()?.let { say(it.line) } }
        Unit
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("approval-${card.id}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(card.label, style = MekaType.caption, color = Meka.colors.textSecondary,
            modifier = Modifier.semantics { contentDescription = card.spoken })
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        card.lines.forEach { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
        Text(card.why, style = MekaType.itemMeta, color = if (card.urgent) Meka.colors.critical else Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.xxs))
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            ApprovalPill(card.approveLabel, filled = true, spoken = "${card.approveLabel} ${card.title}, with your fingerprint", onClick = approve)
            ApprovalPill(card.declineLabel, filled = false, spoken = "${card.declineLabel} ${card.title}", onClick = decline)
        }
        Text(ApprovalRules.LOCK_FOLD, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun ApprovalPill(label: String, filled: Boolean, spoken: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = if (filled) Meka.colors.onAccent else Meka.colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
