package os.meka.android.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.security.FoldApprovalKey
import os.meka.android.security.FoldBiometric
import os.meka.core.domain.ApprovalCard
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.NeedsYouDecideRules
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
    // The approved Needs you look, slice 5: the compact TO DECIDE card (icon, title over "Trade · Kestrel · Asked 21:40 ·
    // expires in 14 min", lit when urgent; what would happen kept whole; Approve · Decline; the fingerprint note).
    NeedsYouDecideShell(
        remember(card) { NeedsYouDecideRules.approval(card, ApprovalRules.LOCK_FOLD) },
        onYes = approve, onNo = decline,
        modifier = modifier.testTag("approval-${card.id}"),
    )
}
