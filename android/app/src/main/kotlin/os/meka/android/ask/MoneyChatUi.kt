package os.meka.android.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.lists.Field
import os.meka.core.domain.MoneyChat
import os.meka.core.domain.MoneyChatAction
import os.meka.core.domain.MoneyChatTurn

/**
 * Money redesign, **Ask Obi** (mockup `docs/design/money/`, v4, approved 2026-10-25), slice 1: the bar under the tabs on
 * every Money tab, and what its sheet holds: the turns (his question on the right, Obi's answer on the left with its
 * buttons), the "Try" questions from his own numbers, the field and the one foot line. Every word is the core's
 * ([MoneyChat]); answers are worked out on the device.
 *
 * Motion: the sheet rises on the expand spring ([MoneyDetailSheet]); each new turn appears on the appear spring (a fade
 * with reduced motion); the bar and every button press like every control.
 */
@Composable
internal fun MoneyAskBar(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l))
            .border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.l))
            .background(Meka.colors.background)
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics { contentDescription = MoneyChat.BAR_SPOKEN; role = Role.Button }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .testTag("money-ask-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        ObiDot()
        Text(MoneyChat.BAR, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        Text("›", style = MekaType.body, color = Meka.colors.accent)
    }
}

/** Obi's small gold orb (the mockup's dot beside "Ask Obi" and the sheet's title). */
@Composable
private fun ObiDot() {
    val a = Meka.colors.accent
    Box(Modifier.size(MekaSpace.l).clip(CircleShape).background(Brush.radialGradient(listOf(a, a.copy(alpha = 0.45f)))))
}

/** The sheet's insides: the turns (or Obi's hello), the Try chips, the field and the foot line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoneyChatContent(
    turns: List<MoneyChatTurn>, suggestions: List<String>, working: Boolean,
    onAsk: (String) -> Unit, onAction: (MoneyChatAction) -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag("money-chat"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        if (turns.isEmpty()) Bubble(MoneyChat.HELLO, mine = false, Modifier.appear(rememberAppearance(1)))
        turns.forEachIndexed { i, t ->
            key(i, t.question) {
                Column(Modifier.fillMaxWidth().appear(rememberAppearance(0)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Bubble(t.question, mine = true, Modifier.align(Alignment.End))
                    Bubble(t.text, mine = false, Modifier.testTag("money-chat-answer-$i"))
                    if (t.actions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            t.actions.forEach { a -> ActionPill(a.label, "money-chat-act-${a.kind}-$i") { onAction(a) } }
                        }
                    }
                }
            }
        }
        if (working) {
            Text(MoneyChat.WORKING, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (suggestions.isNotEmpty()) {
            Text(MoneyChat.TRY, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                suggestions.forEachIndexed { i, q ->
                    Text(q, style = MekaType.caption, color = Meka.colors.textPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button) { onAsk(q) }.minTouch()
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("money-chat-try-$i"))
                }
            }
        }
        Field(MoneyChat.FIELD, modifier = Modifier.testTag("money-chat-field"), value = typed, onValue = { typed = it }) { q ->
            typed = ""; onAsk(q)
        }
        Text(MoneyChat.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, modifier: Modifier = Modifier) {
    val shape = if (mine) RoundedCornerShape(MekaRadius.l, MekaRadius.l, MekaSpace.xxs, MekaRadius.l)
    else RoundedCornerShape(MekaRadius.l, MekaRadius.l, MekaRadius.l, MekaSpace.xxs)
    Text(
        text, style = MekaType.body, color = Meka.colors.textPrimary,
        modifier = modifier.widthIn(max = 480.dp).clip(shape)
            .background(if (mine) Meka.colors.accent.copy(alpha = 0.16f) else Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

@Composable
private fun ActionPill(label: String, tag: String, onClick: () -> Unit) {
    Text(label, style = MekaType.metaStrong, color = Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick).minTouch()
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag(tag))
}
