package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.designsystem.Meka
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.TriageCard
import os.meka.core.domain.TriageCardPrimary
import os.meka.core.domain.TriageReplyRules
import os.meka.core.domain.TriageSendAll

/** What Meka pressed on a triage card. [reply] is the words Send sends (the draft, or his edit of it). */
sealed interface TriageChoice {
    data class Send(val reply: String) : TriageChoice
    data object OpenChat : TriageChoice
    data object NotNow : TriageChoice
    data object Seen : TriageChoice
}

/**
 * A triaged message (V1, messages slice 3), in Needs you above the request cards: "Tunde · 14:02", the lane, the gist
 * (never the message), and for Needs a reply the drafted reply in a quiet box. Send answers through the notification's
 * own Reply while it is live (Meka's tap, Level 3); otherwise Open chat copies the draft and opens the chat. Edit unfolds
 * the reply as a field in place (expand spring); the edit ([reply]) is held by the list so "Send all" sends what each
 * card shows. Not now / Seen take it off both devices. Each press gives a light
 * haptic (Not now a tick); the card folds away with the list's item motion and the undo bar rises with what was done.
 */
@Composable
internal fun TriageCardView(
    card: TriageCard, live: Boolean, reply: String, onReply: (String) -> Unit, onChoice: (TriageChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val rules = TriageReplyRules
    val primary = rules.primary(card, live)
    var editing by rememberSaveable(card.id) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("triage-${card.id}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text("${card.from} · ${card.lane.label}", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
            modifier = Modifier.semantics { contentDescription = card.spoken })
        Text(card.gist, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        card.draft?.let { draft ->
            Column(
                Modifier.padding(top = MekaSpace.xs).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s))
                    .background(Meka.colors.surface).padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
            ) {
                if (!editing) {
                    Text(reply.ifBlank { draft }, style = MekaType.body, color = Meka.colors.textSecondary)
                }
                // Edit unfolds the reply as a field in place (the expand spring; reduced motion: cross-fade).
                AnimatedVisibility(editing, enter = unfold(), exit = fold()) {
                    Box {
                        if (reply.isEmpty()) Text(TriageReplyRules.REPLY_HINT, style = MekaType.body, color = Meka.colors.textTertiary)
                        BasicTextField(
                            value = reply,
                            onValueChange = { onReply(it.take(TriageReplyRules.MAX_REPLY)) },
                            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                            cursorBrush = SolidColor(Meka.colors.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().testTag("triage-reply-${card.id}")
                                .semantics { contentDescription = "Reply to ${TriageReplyRules.who(card)}" },
                        )
                    }
                }
            }
        }
        Row(Modifier.padding(top = MekaSpace.s), horizontalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
            val sendable = rules.cleanReply(reply)
            val label = rules.label(primary)
            TriagePill(label, filled = true, spoken = when (primary) {
                TriageCardPrimary.SEND -> "Send to ${rules.who(card)}: ${sendable ?: ""}"
                TriageCardPrimary.OPEN_CHAT -> "Open the chat with ${rules.who(card)}" + if (card.draft != null) ", the reply copied" else ""
                else -> label
            }) {
                haptics.light()
                when (primary) {
                    TriageCardPrimary.SEND -> sendable?.let { onChoice(TriageChoice.Send(it)) }
                    TriageCardPrimary.OPEN_CHAT -> onChoice(TriageChoice.OpenChat)
                    else -> onChoice(TriageChoice.Seen)
                }
            }
            if (rules.editable(card, live)) {
                val editLabel = if (editing) TriageReplyRules.DONE_EDITING else TriageReplyRules.EDIT
                TriagePill(editLabel, filled = false, spoken = "$editLabel the reply") { haptics.tick(); editing = !editing }
            }
            rules.secondary(card)?.let { quiet ->
                TriagePill(quiet, filled = false, quiet = true, spoken = "$quiet: ${rules.who(card)}") { haptics.tick(); onChoice(TriageChoice.NotNow) }
            }
        }
    }
}

/** "Send all 3" above the reply cards: the easy replies, each the draft shown on its card, on this one tap. */
@Composable
internal fun TriageSendAllRow(sendAll: TriageSendAll, onSendAll: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Row(modifier.fillMaxWidth().testTag("triage-send-all"), horizontalArrangement = Arrangement.End) {
        TriagePill(sendAll.label, filled = false, spoken = sendAll.spoken) { haptics.light(); onSendAll() }
    }
}

@Composable
private fun TriagePill(label: String, filled: Boolean, spoken: String, quiet: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = when { filled -> Meka.colors.onAccent; quiet -> Meka.colors.textSecondary; else -> Meka.colors.accent },
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

