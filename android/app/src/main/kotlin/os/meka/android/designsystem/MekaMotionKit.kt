package os.meka.android.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
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
class Appearance internal constructor(
    internal val progress: Animatable<Float, AnimationVector1D>,
    internal val reduced: Boolean,
    internal val risePx: Float,
    internal val expressive: Boolean,
)

/**
 * Item [index] of a staggered group: fades up after `index × 40 ms` (Expressive: 60 ms apart, rising further, growing
 * from 0.96 and settling on the slower appear spring, about 1.4× longer). When [play] is false (the intro already played, or the item arrived later) it is simply there.
 * Reduced motion (Motion → Off): a short cross-fade, no rise, no stagger.
 */
@Composable
fun rememberAppearance(index: Int, play: Boolean = true): Appearance {
    val reduced = Meka.reducedMotion
    val expressive = Meka.expressiveMotion
    val risePx = with(LocalDensity.current) { MotionMath.riseDistanceDp(expressive).dp.toPx() }
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(MotionMath.staggerDelayMs(index, reduced, expressive).toLong())
            progress.animateTo(1f, MekaMotion.appear(reduced, expressive))
        }
    }
    return remember(progress, reduced, risePx, expressive) { Appearance(progress, reduced, risePx, expressive) }
}

/** Applies an [Appearance]: alpha, rise and (Expressive) scale, drawn in the graphics layer so layout never jumps. */
fun Modifier.appear(a: Appearance): Modifier = graphicsLayer {
    val p = a.progress.value
    alpha = p.coerceIn(0f, 1f)
    translationY = MotionMath.riseOffset(p, a.risePx, a.reduced)
    val s = MotionMath.entryScale(p, a.expressive, a.reduced)
    scaleX = s
    scaleY = s
}

/**
 * A number that counts up to [value] when it first appears and rolls to new values after that.
 * Reduced motion: shows the value straight away. Screen readers always get the final value.
 */
@Composable
fun CountUpText(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, format: (Int) -> String = { it.toString() }) {
    val reduced = Meka.reducedMotion
    val countUpMs = MotionMath.countUpMs(Meka.expressiveMotion)
    val shown = remember { Animatable(if (reduced) value.toFloat() else 0f) }
    LaunchedEffect(value, reduced) {
        if (reduced) shown.snapTo(value.toFloat())
        else shown.animateTo(value.toFloat(), tween(countUpMs, easing = EaseOutCubic))
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
 * A pane over the current screen (phone): springs up from the bottom as a sheet with rounded top corners while the
 * screen behind dims (motion pass 2, slice 3); leaves the same way. The dim takes taps so nothing behind is pressed by
 * mistake. Reduced motion: cross-fades only (the dim still fades). Content can share elements with the screen below
 * via [sharedTitleInPane].
 */
@Composable
fun MekaPane(visible: Boolean, content: @Composable () -> Unit) = MekaPane(visible, origin = null, content = content)

/**
 * [MekaPane] that can grow out of the row it was opened from (motion pass 2, list → detail container transform):
 * [origin] gives that row's bounds in root pixels (asked once as the pane opens, again as it closes, so it shrinks back
 * into wherever the row now is, or where it was if it has gone). The container's edges and corners travel from the
 * row's to the sheet's on the expand spring while the screen behind dims, and the content fades in once the container
 * has grown past row size ([MotionMath.containerContentAlpha]). No row (null), or reduced motion: the sheet as before.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MekaPane(visible: Boolean, origin: (() -> Bounds?)?, content: @Composable () -> Unit) {
    val reduced = Meka.reducedMotion
    // The pane's own enter/exit lives on its two layers below; this only keeps them composed while they animate.
    AnimatedVisibility(visible = visible, enter = EnterTransition.None, exit = ExitTransition.None) {
        // Shared titles inside ride this pane's enter/exit (see MekaShared.kt).
        CompositionLocalProvider(LocalPaneScope provides this) {
            // Where the row was when the pane opened; refreshed as it closes (the row may have moved or gone).
            val opened = remember { if (reduced) null else origin?.invoke() }
            val closing = transition.targetState == EnterExitState.PostExit
            val row = remember(closing) { if (closing) origin?.invoke() ?: opened else opened }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .animateEnterExit(enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced)))
                        .background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .clearAndSetSemantics { },
                )
                if (opened != null) {
                    ContainerSheet(this@AnimatedVisibility, row ?: opened, content)
                } else {
                    val dark = Meka.theme.isDark
                    val topRadius = if (MotionMath.sheetTopRounded(dark)) MekaRadius.l else 0.dp
                    Box(
                        Modifier.fillMaxSize().padding(top = MotionMath.sheetTopGapDp(dark).dp)
                            .animateEnterExit(
                                enter = if (reduced) fadeIn(MekaMotion.expand(true)) else
                                    slideInVertically(MekaMotion.expand(false)) { it / 3 } + fadeIn(MekaMotion.appear(false)),
                                exit = if (reduced) fadeOut(MekaMotion.expand(true)) else
                                    slideOutVertically(MekaMotion.expand(false)) { it / 3 } + fadeOut(MekaMotion.appear(false)),
                            )
                            .clip(RoundedCornerShape(topStart = topRadius, topEnd = topRadius))
                            .background(Meka.colors.background),
                    ) { content() }
                }
            }
        }
    }
}

/** The sheet of a [MekaPane] growing out of [row] (root pixels): its clip travels from the row's bounds to its own. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun ContainerSheet(scope: AnimatedVisibilityScope, row: Bounds, content: @Composable () -> Unit) {
    val progress by scope.transition.animateFloat(
        transitionSpec = { MekaMotion.expand(false) }, label = "container",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    // Where the sheet sits in the root, so the row can be put in its coordinates; written on placement, read on draw.
    val at = remember { floatArrayOf(0f, 0f) }
    val density = LocalDensity.current
    val dark = Meka.theme.isDark
    val rowRadius = with(density) { MekaRadius.m.toPx() }
    val sheetRadius = if (MotionMath.sheetTopRounded(dark)) with(density) { MekaRadius.l.toPx() } else 0f
    Box(
        Modifier.fillMaxSize().padding(top = MotionMath.sheetTopGapDp(dark).dp)
            .onPlaced { c -> val p = c.positionInRoot(); at[0] = p.x; at[1] = p.y }
            .graphicsLayer {
                val p = progress
                val full = Bounds(0f, 0f, size.width, size.height)
                val from = MotionMath.containerOrigin(row, Bounds(at[0], at[1], at[0] + size.width, at[1] + size.height)) ?: full
                val b = MotionMath.containerBounds(from, full, p)
                val top = MotionMath.containerCorner(rowRadius, sheetRadius, p)
                val bottom = MotionMath.containerCorner(rowRadius, 0f, p)
                shape = BoundsShape(b, top, bottom)
                clip = true
            }
            .background(Meka.colors.background),
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = MotionMath.containerContentAlpha(progress) }) { content() }
    }
}

/** A rounded rectangle at [b] inside the layer, [top] and [bottom] corner radii in pixels. */
private class BoundsShape(private val b: Bounds, private val top: Float, private val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                b.left, b.top, b.right, b.bottom,
                topLeftCornerRadius = CornerRadius(top), topRightCornerRadius = CornerRadius(top),
                bottomRightCornerRadius = CornerRadius(bottom), bottomLeftCornerRadius = CornerRadius(bottom),
            ),
        )
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
