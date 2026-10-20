package os.meka.android.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.core.domain.DonePillSpec

/**
 * Done on a Needs you card (Meka, 2026-10-20 17:33; [DonePillSpec]): a gold-outlined pill, 32 dp high with 14 dp
 * each side and a 1 dp accent border on the raised surface, accent text. The 48 dp touch target sits *outside* the
 * drawn pill, so the tap height never stretches it. Put it at the card's trailing edge so it is in the same place on
 * every card. Press: the pill (not its touch box) scales to 0.96, and on the tap it flashes gold for a moment before
 * [onDone] runs and the card slides away. Reduced motion: no scale, no flash; Done runs at once and the card fades.
 */
@Composable
fun DonePill(
    spoken: String,
    modifier: Modifier = Modifier,
    label: String = "Done",
    tag: String = "done-pill",
    onDone: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(DonePillSpec.scale(pressed, reduced), MekaMotion.complete(reduced), label = "done-press")
    val flash = remember { Animatable(0f) }
    val pill = RoundedCornerShape(MekaRadius.pill)
    val accent = Meka.colors.accent
    val raised = Meka.colors.surfaceRaised
    Box(
        modifier
            .sizeIn(minWidth = DonePillSpec.TOUCH_DP.dp, minHeight = DonePillSpec.TOUCH_DP.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                val ms = DonePillSpec.flashMs(reduced)
                if (ms == 0L) onDone() else scope.launch {
                    launch { flash.animateTo(1f, MekaMotion.appear(false)) }
                    delay(ms)
                    onDone()
                    flash.snapTo(0f)
                }
            }
            .semantics { contentDescription = spoken }
            .testTag("$tag-touch"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .testTag(tag)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .height(DonePillSpec.HEIGHT_DP.dp)
                .clip(pill)
                .background(lerp(raised, accent.copy(alpha = 0.35f).compositeOver(raised), flash.value))
                .border(DonePillSpec.BORDER_DP.dp, accent, pill)
                .padding(horizontal = DonePillSpec.SIDE_PAD_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MekaType.captionStrong, color = accent, maxLines = 1)
        }
    }
}
