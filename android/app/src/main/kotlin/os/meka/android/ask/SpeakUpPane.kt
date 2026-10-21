package os.meka.android.ask

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.SpeakUpView
import os.meka.core.domain.TalkSetupSection
import os.meka.core.facade.MekaCore

/**
 * Ask → Settings → Speaking up (the JARVIS step, slice 1): a section per moment Ada says out loud on her own, each with
 * Turn on / Turn off; How often (up to 6 a day or 3); Mute for 2 hours / Speak again now. The words are the core's
 * [os.meka.core.domain.SpeakUpRules.view], synced, the same as the Mac's sheet.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; sections stagger in 40 ms apart; a
 * button presses in with a tick haptic and the status line blends to the accent while on. Reduced motion: cross-fades.
 */
@Composable
fun SpeakUpPane(core: MekaCore, onClose: () -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<SpeakUpView?>(null) }
    LaunchedEffect(Unit) { view = runCatching { core.speakUpView() }.getOrNull() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text(MoreItem.SPEAK_UP.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.SPEAK_UP)).appear(rememberAppearance(0)))
        val v = view ?: return@Column
        Text(v.intro, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))
        v.sections.forEachIndexed { i, s ->
            SpeakSection(s, Modifier.appear(rememberAppearance(i + 1))) {
                haptics.tick()
                scope.launch { view = runCatching { core.speakUpAction(s.label) }.getOrNull() ?: view }
            }
        }
        Text(v.footer, style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(v.sections.size + 1)))
    }
}

@Composable
private fun SpeakSection(s: TalkSetupSection, modifier: Modifier, onAction: () -> Unit) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (s.lit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "speak-status",
    )
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(s.label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.semantics { heading() })
        Text(s.status, style = MekaType.body, color = statusColor)
        s.steps.forEach { step -> Text(step, style = MekaType.caption, color = Meka.colors.textSecondary) }
        s.action?.let { label ->
            Spacer(Modifier.height(MekaSpace.xs))
            Box(
                Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, onClick = onAction)
                    .semantics { contentDescription = "$label: ${s.label}" }
                    .minTouch()
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) {
                Text(label, style = MekaType.itemMeta, color = Meka.colors.accent)
            }
        }
    }
}
