package os.meka.android.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import os.meka.core.domain.BoardOpenFoldRules

/**
 * The Week board's alignment list (8), a clean scrolled state: once a scroll area under a pinned header has scrolled,
 * whatever passes under the header fades out over [BoardOpenFoldRules.HEAD_FADE_DP] instead of being cut to a hard
 * sliver (a stray dashed edge of "+ add", an agenda row half under the week strip's divider). At rest nothing fades.
 * The fade crosses in and out with the appear curve; Reduced motion: the same short fade.
 *
 * Put it on the scroll area's viewport (before `verticalScroll`), or on a lazy list's own modifier.
 */
@Composable
fun Modifier.headFade(scrolled: Boolean): Modifier {
    val shown by animateFloatAsState(if (scrolled) 1f else 0f, MekaMotion.appear(Meka.reducedMotion), label = "head-fade")
    if (shown <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = BoardOpenFoldRules.HEAD_FADE_DP.dp.toPx().coerceAtMost(size.height)
            if (fade <= 0f) return@drawWithContent
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = BoardOpenFoldRules.headAlpha(0f, shown)),
                    1f to Color.Black,
                    startY = 0f, endY = fade,
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
}
