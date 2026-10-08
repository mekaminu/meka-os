package os.meka.android.designsystem

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

/**
 * The list's foot above a bar: rows fade out over the last [MotionMath.FOOT_FADE_DP] rather than being cut mid-row
 * where the list meets the capture bar or the tabs. Pair it with a bottom content padding of at least that much
 * ([MotionMath.footClear]) so the last row scrolls fully clear once you reach the end. No motion of its own, so
 * reduced motion changes nothing.
 */
fun Modifier.footFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = MotionMath.FOOT_FADE_DP.dp.toPx().coerceAtMost(size.height)
        val top = size.height - fade
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = top, endY = size.height),
            topLeft = Offset(0f, top),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }
