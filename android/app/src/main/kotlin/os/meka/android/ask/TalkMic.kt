package os.meka.android.ask

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.TalkPhase
import os.meka.core.domain.TalkStart
import os.meka.core.domain.TalkStartRules

/**
 * Talk without tapping the mic (slice 3): the mic on the Fold's own screens (the bedside clock and the closed Fold's
 * now card): the orb's resting look (a still brass ring with a microphone), pressing in to 0.97 on the complete spring
 * with a light haptic. [onTalk] starts listening at once. Reduced motion: no press scale. [dim]: quiet hours at the
 * bedside, the mic quietens with the clock.
 */
@Composable
fun TalkMic(onTalk: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp, dim: Boolean = false) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, MekaMotion.complete(reduced), label = "talkMicPress")
    val alpha by animateFloatAsState(if (dim) 0.45f else 1f, MekaMotion.themeBlend(reduced), label = "talkMicDim")
    VoiceOrb(
        TalkPhase.ENDED, 0f, size = size,
        modifier = modifier.scale(scale).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(interactionSource = source, indication = null, role = Role.Button) { haptics.light(); onTalk() }
            .semantics { contentDescription = TalkStartRules.MIC_LABEL },
    )
}

/** The cover screen's mic: Ask, already listening (as the side button), with the shell's slide. */
fun talkFromCover(app: MekaApplication?) {
    app ?: return
    app.talkNow.value = TalkStartRules.fromMic(bedside = false)
    app.openDestination.value = os.meka.android.shell.ShellDestination.ASK
}

/** The bedside mic: the pane over the clock starts listening once it is composed ([AskMekaSection] reads talkNow). */
fun talkAtBedside(app: MekaApplication?) {
    app?.talkNow?.value = TalkStart.BEDSIDE
}
