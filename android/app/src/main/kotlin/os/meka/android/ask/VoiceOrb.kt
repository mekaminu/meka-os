package os.meka.android.ask

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MotionMath
import os.meka.core.domain.TalkOrb
import os.meka.core.domain.TalkPhase

/**
 * The voice orb (catalogue "Assistant · Talk"): a brass ring that breathes while idle or thinking (the empty states'
 * breath), swells with Meka's voice while listening ([TalkOrb.scale]) and sends ripples out while MEKA speaks
 * ([TalkOrb.ripple]). Reduced motion: a still ring with a level bar under it. Drawing only; the caller gives it its
 * click and screen-reader label.
 */
@Composable
fun VoiceOrb(phase: TalkPhase, level: Float, modifier: Modifier = Modifier, size: Dp = 76.dp) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    val onAccent = Meka.colors.onAccent
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduced, phase) {
        if (reduced || phase == TalkPhase.ENDED) return@LaunchedEffect
        val start = withFrameMillis { it } - elapsed
        while (true) withFrameMillis { elapsed = it - start }
    }
    val shownLevel by animateFloatAsState(if (phase == TalkPhase.LISTENING) level else 0f, label = "orb-level")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size).clearAndSetSemantics { }) {
            val breath = MotionMath.breath(elapsed, reduced)
            val scale = if (reduced) 1f else TalkOrb.scale(phase, shownLevel, breath)
            val base = this.size.minDimension / 2 / (1f + TalkOrb.SWELL) // room to swell and ripple inside the box
            val r = base * scale
            val stroke = 2.dp.toPx()
            val lit = phase == TalkPhase.LISTENING || phase == TalkPhase.SPEAKING
            if (phase == TalkPhase.SPEAKING && !reduced) {
                repeat(TalkOrb.RIPPLES) { i ->
                    val p = TalkOrb.ripple(i, elapsed)
                    drawCircle(accent.copy(alpha = accent.alpha * 0.45f * (1f - p)), radius = base * (1f + TalkOrb.SWELL * p),
                        style = Stroke(stroke * (1f - 0.5f * p)))
                }
            }
            // The glow, swelling with the breath (or the voice).
            val glow = if (phase == TalkPhase.LISTENING) 0.35f + 0.65f * shownLevel else MotionMath.breathGlow(breath)
            drawCircle(accent.copy(alpha = accent.alpha * 0.18f * glow), radius = r)
            if (lit) drawCircle(accent.copy(alpha = accent.alpha * 0.9f), radius = r - stroke)
            drawCircle(accent.copy(alpha = accent.alpha * (0.6f + 0.4f * glow)), radius = r - stroke / 2, style = Stroke(stroke))
            drawMic(if (lit) onAccent else accent, r * 0.9f)
        }
        if (reduced && phase == TalkPhase.LISTENING) {
            // Reduced motion: the voice level as a still bar instead of the swell.
            Box(Modifier.padding(top = MekaSpace.xs).width(size).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Meka.colors.surfaceRaised)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f)).background(accent))
            }
        }
    }
}

/** A small microphone: a capsule, its cradle and stem, [extent] across, centred. */
private fun DrawScope.drawMic(color: Color, extent: Float) {
    val w = extent * 0.26f
    val h = extent * 0.46f
    val top = center.y - extent * 0.36f
    drawRoundRect(color, topLeft = Offset(center.x - w / 2, top), size = Size(w, h), cornerRadius = CornerRadius(w / 2, w / 2))
    val stroke = Stroke(extent * 0.06f, cap = StrokeCap.Round)
    val cw = extent * 0.46f
    drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(center.x - cw / 2, top + h * 0.35f), size = Size(cw, h * 0.95f), style = stroke)
    val stemTop = top + h * 0.35f + h * 0.95f
    drawLine(color, Offset(center.x, stemTop), Offset(center.x, stemTop + extent * 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
}
