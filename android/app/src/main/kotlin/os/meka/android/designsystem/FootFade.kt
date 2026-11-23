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

/**
 * Walk-through week 4, A6: a list under a fixed header (Today's two columns on the open Fold) fades its top edge as well
 * as its foot once it has scrolled ([scrolled]), so a row passing under the header's habit chips softens over
 * [MotionMath.FOOT_FADE_DP] instead of being sliced through its title. At rest the top is crisp. No motion of its own.
 * Walk-through week 5, A12: [more] says whether anything is still below (a sheet's body over its fixed Cancel · Save
 * foot), so the foot fades only while there is more to scroll to and the last line sits crisp at the end.
 * [topDp] is how deep the top fades once scrolled (Today's split columns fade over a whole row, core
 * TodayColumnsRules.TOP_FADE_DP, so a row under the habit chips dissolves rather than reading as sliced).
 * [topStops], when given, are the top fade's alphas at even steps down it (core TodayColumnsRules.topFadeStops, an
 * eased fade so a row's stub at rest is all but gone); null keeps the straight ramp.
 */
fun Modifier.edgeFades(
    more: () -> Boolean = { true },
    topDp: Int = MotionMath.FOOT_FADE_DP,
    topStops: List<Float>? = null,
    scrolled: () -> Boolean,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = MotionMath.FOOT_FADE_DP.dp.toPx().coerceAtMost(size.height / 2)
        if (scrolled()) {
            val topFade = topDp.dp.toPx().coerceAtMost(size.height / 2)
            val brush = if (topStops != null && topStops.size >= 2) {
                val last = (topStops.size - 1).toFloat()
                Brush.verticalGradient(
                    *Array(topStops.size) { i -> (i / last) to Color.Black.copy(alpha = topStops[i].coerceIn(0f, 1f)) },
                    startY = 0f, endY = topFade,
                )
            } else {
                Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = topFade)
            }
            drawRect(
                brush = brush,
                topLeft = Offset.Zero,
                size = Size(size.width, topFade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (!more()) return@drawWithContent
        val top = size.height - fade
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = top, endY = size.height),
            topLeft = Offset(0f, top),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }
