package os.meka.android.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.VoiceChoice
import os.meka.core.domain.VoicePickerRules
import os.meka.core.domain.VoicePickerView
import os.meka.core.facade.MekaCore

/**
 * Ask → More → MEKA's voice (Weather and a voice, item 2): MEKA's voices (Amazon Polly's British voices through MEKA's
 * own server) first, the default marked, then the phone's own voice; each with ▶ Sample ("Good morning, Meka…", MEKA's
 * own words). Choosing one sets the synced "MEKA's voice", so Talk, the spoken brief and the call assistant use it on
 * every device. Under the list: why MEKA's voices are missing (if they are), the month's characters, and how to get a
 * better free phone voice.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; a shimmer while the server answers; rows
 * stagger in 40 ms apart; the chosen row's ring and border blend to the accent with a tick haptic; Sample presses in
 * with a light haptic and its label cross-fades to "■ Stop" while it plays (Stop: tick haptic); the status and month
 * lines cross-fade. Reduced motion: cross-fades.
 */
@Composable
fun VoicePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val context = LocalContext.current
    val speaker = remember { MekaSpeaker(context, core, scope) }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    var view by remember { mutableStateOf<VoicePickerView?>(null) }
    // The row whose sample is playing (null: none).
    var playing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        speaker.prepare()
        view = runCatching { core.voicePicker(mac = false) }.getOrNull()
            ?: VoicePickerRules.view(null, emptyList(), null, null, null, mac = false, connected = false)
    }
    val reduced = Meka.reducedMotion

    fun choose(c: VoiceChoice) {
        if (c.selected) return
        haptics.tick()
        // Lit at once; the saved choice follows to every device.
        view = view?.let { v -> v.copy(choices = v.choices.map { it.copy(selected = it.id == c.id) }) }
        scope.launch { if (runCatching { core.chooseMekaVoice(c.id) }.getOrDefault(false)) view = runCatching { core.voicePicker(mac = false) }.getOrNull() ?: view }
    }

    fun sample(c: VoiceChoice) {
        if (playing == c.id && speaker.speaking) {
            haptics.tick()
            speaker.stop()
            playing = null
            return
        }
        haptics.light()
        playing = c.id
        speaker.sample(c.id) { playing = null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { speaker.stop(); onClose() }.padding(vertical = MekaSpace.s))
        Text(VoicePickerRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.VOICE)).appear(rememberAppearance(0)))
        Text(VoicePickerRules.INTRO, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))
        val v = view
        if (v == null) {
            SkeletonRows(count = 4, rowHeight = 56.dp)
        } else {
            v.choices.forEachIndexed { i, c ->
                VoiceRow(c, playing = playing == c.id && speaker.speaking, modifier = Modifier.appear(rememberAppearance(i + 1)),
                    choose = { choose(c) }, sample = { sample(c) })
            }
            val after = v.choices.size + 1
            AnimatedContent(
                targetState = listOfNotNull(v.statusLine, v.usageLine),
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "voice-lines",
                modifier = Modifier.appear(rememberAppearance(after)),
            ) { lines ->
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    lines.forEachIndexed { i, line ->
                        Text(line, style = MekaType.caption,
                            color = if (i == 0 && v.statusLine != null) Meka.colors.accent else Meka.colors.textTertiary)
                    }
                }
            }
            Spacer(Modifier.height(MekaSpace.s))
            Text(v.help, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(after + 1)))
        }
    }
}

@Composable
private fun VoiceRow(c: VoiceChoice, playing: Boolean, modifier: Modifier, choose: () -> Unit, sample: () -> Unit) {
    val reduced = Meka.reducedMotion
    val ring by animateColorAsState(if (c.selected) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "voice-ring")
    val fill by animateColorAsState(if (c.selected) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "voice-fill")
    val edge by animateColorAsState(if (c.selected) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "voice-edge")
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .border(1.dp, edge, RoundedCornerShape(MekaRadius.m))
            .semantics { selected = c.selected }
            .clickable(role = Role.RadioButton, onClickLabel = "Use ${c.label}") { choose() }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).border(1.5.dp, ring, CircleShape).padding(4.dp).clip(CircleShape).background(fill))
        Spacer(Modifier.width(MekaSpace.s))
        Column(Modifier.weight(1f)) {
            // A setting you choose, not something to act on: the regular weight (type weight, 2026-10-06).
            Text(c.label, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(c.detail, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2)
        }
        if (c.sample) {
            Box(
                Modifier.padding(start = MekaSpace.s).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, onClickLabel = if (playing) "Stop the sample" else "Hear ${c.label}") { sample() }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) {
                Crossfade(targetState = playing, animationSpec = MekaMotion.appear(reduced), label = "voice-sample") { on ->
                    Text(if (on) "■  Stop" else "▶  Sample", style = MekaType.itemMeta, color = Meka.colors.accent)
                }
            }
        }
    }
}
