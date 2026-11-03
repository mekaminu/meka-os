package os.meka.android.designsystem

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.core.domain.DonePillSpec

/**
 * A settings pill (quality gate slice 55): drawn [DonePillSpec.HEIGHT_DP] (32 dp) high with 16 dp sides on [fill], its
 * label in [ink], while the 48 dp touch sits *outside* the drawn pill, so the tap height never stretches it into a tall
 * slab (Speaking up's and What Navilife may do's Turn off, Ask first and Pause were drawn the full 48 dp). The label
 * cross-fades when it changes (Pause ↔ Resume); reduced motion: the same fade, quicker. The drawn pill carries the
 * `touch-pill` tag so the shots can measure it; [spoken], when given, is what TalkBack reads.
 */
@Composable
fun TouchPill(
    label: String,
    fill: Color,
    ink: Color,
    modifier: Modifier = Modifier,
    spoken: String? = null,
    onClick: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Box(
        modifier
            .sizeIn(minWidth = MekaSpace.touch, minHeight = MekaSpace.touch)
            .clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.testTag("touch-pill").height(DonePillSpec.HEIGHT_DP.dp).clip(RoundedCornerShape(MekaRadius.pill))
                .background(fill).padding(horizontal = MekaSpace.m),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(label, animationSpec = MekaMotion.appear(reduced), label = "touch-pill-label") { t ->
                Text(t, style = MekaType.itemMeta, color = ink, maxLines = 1)
            }
        }
    }
}

/**
 * The On/Off pill at the end of a switch row (walk-through 2026-11-02 A9: About you's Watch for requests, Calendars'
 * ON TODAY, Notifications and the call assistant's rows drew it about 20 dp tall). The row is the 48 dp switch; the
 * pill is only its picture, drawn [DonePillSpec.HEIGHT_DP] (32 dp) high like every settings pill, its word centred,
 * on [fill] (the accent when on, which blends in as it changes). Tagged `switch-pill` so the shots can measure it.
 */
@Composable
fun SwitchPill(on: Boolean, fill: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.testTag("switch-pill").height(DonePillSpec.HEIGHT_DP.dp).clip(RoundedCornerShape(MekaRadius.pill))
            .background(fill).padding(horizontal = MekaSpace.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (on) "On" else "Off", style = MekaType.caption, color = Meka.colors.onAccent, maxLines = 1)
    }
}
