package os.meka.android.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Motion foundation (build plan M1). Every screen builds its motion from these helpers so the catalogue in
 * docs/build-plan.md is applied the same way everywhere and reduced motion is honoured in one place.
 */

private val EaseOutCubic = Easing { MotionMath.easeOutCubic(it) }

/** Progress of one appearing item: 0 = hidden and lowered, 1 = in place. */
@Stable
class Appearance internal constructor(internal val progress: Animatable<Float, AnimationVector1D>, internal val reduced: Boolean, internal val risePx: Float)

/**
 * Item [index] of a staggered group: fades up after `index × 40 ms`. When [play] is false (the intro already
 * played, or the item arrived later) it is simply there. Reduced motion: a short cross-fade, no rise, no stagger.
 */
@Composable
fun rememberAppearance(index: Int, play: Boolean = true): Appearance {
    val reduced = Meka.reducedMotion
    val risePx = with(LocalDensity.current) { MekaChoreography.riseDistanceDp.dp.toPx() }
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(MotionMath.staggerDelayMs(index, reduced).toLong())
            progress.animateTo(1f, MekaMotion.appear(reduced))
        }
    }
    return remember(progress, reduced, risePx) { Appearance(progress, reduced, risePx) }
}

/** Applies an [Appearance]: alpha and rise, drawn in the graphics layer so layout never jumps. */
fun Modifier.appear(a: Appearance): Modifier = graphicsLayer {
    val p = a.progress.value
    alpha = p.coerceIn(0f, 1f)
    translationY = MotionMath.riseOffset(p, a.risePx, a.reduced)
}

/**
 * A number that counts up to [value] when it first appears and rolls to new values after that.
 * Reduced motion: shows the value straight away. Screen readers always get the final value.
 */
@Composable
fun CountUpText(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, format: (Int) -> String = { it.toString() }) {
    val reduced = Meka.reducedMotion
    val shown = remember { Animatable(if (reduced) value.toFloat() else 0f) }
    LaunchedEffect(value, reduced) {
        if (reduced) shown.snapTo(value.toFloat())
        else shown.animateTo(value.toFloat(), tween(MekaChoreography.countUpMs, easing = EaseOutCubic))
    }
    val n = if (shown.value == value.toFloat()) value else shown.value.toInt()
    Text(format(n), style = style, color = color, modifier = modifier.semantics { contentDescription = format(value) })
}

/** Skeleton rows with a slow shimmer: the catalogue's loading state (never a spinner on its own). */
@Composable
fun SkeletonRows(count: Int = 3, modifier: Modifier = Modifier, rowHeight: androidx.compose.ui.unit.Dp = 44.dp) {
    val reduced = Meka.reducedMotion
    val base = Meka.colors.surfaceRaised
    val glint = Meka.colors.hairline
    val t = if (reduced) 0f else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val v by transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(MekaChoreography.shimmerPeriodMs, easing = LinearEasing), RepeatMode.Restart), label = "shimmer-x",
        )
        v
    }
    Column(modifier.semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        repeat(count) { i ->
            Box(
                Modifier.fillMaxWidth(if (i == count - 1) 0.6f else 1f).height(rowHeight)
                    .clip(RoundedCornerShape(MekaRadius.m))
                    .background(
                        if (reduced) Brush.linearGradient(listOf(base, base))
                        else {
                            val x = (t * 3f - 1f) * 1000f
                            Brush.linearGradient(listOf(base, glint, base), start = Offset(x - 400f, 0f), end = Offset(x + 400f, 0f))
                        },
                    ),
            )
        }
    }
}

/**
 * A full-screen pane over the current screen (phone): springs up from the bottom and fades; leaves the same way.
 * Reduced motion: a cross-fade only. Content can share elements with the screen below via [sharedTitleInPane].
 */
@Composable
fun MekaPane(visible: Boolean, content: @Composable () -> Unit) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else
            slideInVertically(MekaMotion.expand(false)) { it / 3 } + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else
            slideOutVertically(MekaMotion.expand(false)) { it / 3 } + fadeOut(MekaMotion.appear(false)),
    ) {
        // Shared titles inside ride this pane's enter/exit (see MekaShared.kt).
        CompositionLocalProvider(LocalPaneScope provides this) {
            Box(Modifier.fillMaxSize().background(Meka.colors.background)) { content() }
        }
    }
}

/** Haptics named for what happened, so every screen feels the same (catalogue: light on complete, medium on approve). */
class MekaHaptics internal constructor(private val h: HapticFeedback) {
    /** A task completed, a habit ticked. */
    fun light() = h.performHapticFeedback(HapticFeedbackType.ToggleOn)
    /** An approval confirmed. */
    fun medium() = h.performHapticFeedback(HapticFeedbackType.Confirm)
    /** Something was refused or failed. */
    fun reject() = h.performHapticFeedback(HapticFeedbackType.Reject)
    /** A small step: a picker notch, a segment change. */
    fun tick() = h.performHapticFeedback(HapticFeedbackType.SegmentTick)
}

@Composable
fun rememberMekaHaptics(): MekaHaptics {
    val h = LocalHapticFeedback.current
    return remember(h) { MekaHaptics(h) }
}
