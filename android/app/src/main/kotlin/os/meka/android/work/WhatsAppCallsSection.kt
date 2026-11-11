package os.meka.android.work

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.WhatsAppCallRules
import os.meka.core.domain.WhatsAppCallSettings

/**
 * Work mode → Call assistant → **WhatsApp calls** (Meka approved 2026-11-11): the switch (off until he turns it on;
 * turning it on is his yes to the one fixed message) and his at-work message, shown and editable. The pill fills gold
 * on the appear spring, the line cross-fades, and the message unfolds with the expand spring (reduced motion: fades).
 */
@Composable
internal fun WhatsAppCallsRow(assistantCanPickUp: Boolean, modifier: Modifier = Modifier) {
    val store = (LocalContext.current.applicationContext as MekaApplication).whatsAppCalls
    val settings by store.settings.collectAsState()
    val haptics = rememberMekaHaptics()
    WhatsAppCallsRowView(settings, assistantCanPickUp, modifier, onToggle = { haptics.tick(); store.setOn(!settings.on) }, onMessage = store::setMessage)
}

@Composable
internal fun WhatsAppCallsRowView(
    settings: WhatsAppCallSettings,
    assistantCanPickUp: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val on = settings.on
    val fill by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(reduced), label = "wa-calls")
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(WhatsAppCallRules.TITLE, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onToggle() }
                .semantics { contentDescription = WhatsAppCallRules.SWITCH; selected = on }
                .testTag("wa-calls-switch").minTouch().padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(WhatsAppCallRules.SWITCH, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
            os.meka.android.designsystem.SwitchPill(on, fill)
        }
        Crossfade(WhatsAppCallRules.line(settings, assistantCanPickUp), animationSpec = MekaMotion.appear(reduced), label = "wa-calls-line") { line ->
            Text(LoneWordGlue.kept(line), style = MekaType.caption,
                color = if (line == WhatsAppCallRules.ASSISTANT_OFF_LINE) Meka.colors.critical else Meka.colors.textSecondary)
        }
        // The message itself, always shown so he sees what would go before turning it on.
        var text by rememberSaveable(settings.message) { mutableStateOf(settings.message) }
        LaunchedEffect(settings.message) { text = settings.message }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(WhatsAppCallRules.MAX_MESSAGE) },
                textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                cursorBrush = SolidColor(Meka.colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().testTag("wa-calls-message")
                    .semantics { contentDescription = "Your at-work message" }
                    // Saved when he leaves the field; blank puts the suggested message back.
                    .onFocusChanged { if (!it.isFocused && text != settings.message) onMessage(text) },
            )
        }
        AnimatedVisibility(
            text != settings.message,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Text("Save message", style = MekaType.captionStrong, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) { onMessage(text) }
                    .minTouch().padding(vertical = MekaSpace.xxs))
        }
        Text(LoneWordGlue.kept(WhatsAppCallRules.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** **Call back** under a WhatsApp call Work mode declined: opens the caller's WhatsApp chat (his tap; nothing is sent). */
@Composable
internal fun WhatsAppCallBackChip(item: CapturedItem) {
    if (!WhatsAppCallRules.isWhatsAppCall(item) || item.conversation != null) return
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    Text(
        WhatsAppCallRules.CALL_BACK, style = MekaType.captionStrong, color = Meka.colors.accent,
        modifier = Modifier.testTag("wa-call-back-${item.id}").clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button) { haptics.light(); WhatsAppCalls.callBack(context, item) }
            .minTouch().padding(vertical = MekaSpace.xxs),
    )
}
