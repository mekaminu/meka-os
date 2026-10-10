package os.meka.android.today

import os.meka.android.designsystem.containerOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.SwipeActionIcon
import os.meka.android.designsystem.SwipeGlyph
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.DecisionCard
import os.meka.core.domain.DecisionEffect
import os.meka.core.domain.DecisionMove
import os.meka.core.domain.NeedsYouStackRules
import kotlin.math.abs

/**
 * Needs you as a swipeable stack (four tabs, slice 2). The top card follows the finger: right = yes/do, left = later,
 * up = open. Past the threshold its label pops with a tick haptic; letting go does it with a light haptic. Done,
 * Tomorrow and Later fly the card off the way it went and the next one rises; Open and Choose spring it back and open
 * the task (or Lists). Two cards peek out behind. Buttons under the card and screen-reader actions do the same.
 * Reduced motion: no tilt or flight; the card fades out and the next one is there.
 */
@Composable
fun DecisionStackView(
    cards: List<DecisionCard>,
    onMove: (DecisionCard, DecisionMove) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Cards already done or snoozed stay hidden until the core drops them (a frame or two), so they don't flash back.
    var gone by remember { mutableStateOf(emptySet<String>()) }
    val ids = cards.map { it.id }.toSet()
    LaunchedEffect(ids) { gone = gone.intersect(ids) }
    // If the core never drops one (the change failed), it comes back rather than staying hidden.
    LaunchedEffect(gone) { if (gone.isNotEmpty()) { delay(2_000); gone = emptySet() } }
    val shown = cards.filter { it.id !in gone }
    val top = shown.firstOrNull() ?: return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = MekaSpace.l), contentAlignment = Alignment.TopCenter) {
            val widthPx = constraints.maxWidth.toFloat()
            // The cards behind: smaller, lower and dimmer, so the stack reads as a pile.
            shown.drop(1).take(2).reversed().forEachIndexed { i, card ->
                val depth = shown.drop(1).take(2).size - i
                key(card.id) {
                    val scale by animateFloatAsState(1f - 0.05f * depth, MekaMotion.approve(Meka.reducedMotion), label = "stack-scale")
                    val lift by animateFloatAsState(12f * depth, MekaMotion.approve(Meka.reducedMotion), label = "stack-lift")
                    DecisionCardFace(
                        card, armed = null, progress = 0f,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationY = lift.dp.toPx()
                            alpha = 1f - 0.3f * depth
                        },
                    )
                }
            }
            key(top.id) {
                TopCard(top, widthPx, onMove = { move, flies ->
                    if (flies && top.effect(move) != DecisionEffect.SET_ASIDE) gone = gone + top.id
                    onMove(top, move)
                })
            }
        }
        NeedsYouStackRules.moreLine(shown.size)?.let {
            Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(horizontal = MekaSpace.xs))
        }
    }
}

/** The top card with its drag, fly-off and the three buttons under it. */
@Composable
private fun TopCard(card: DecisionCard, widthPx: Float, onMove: (DecisionMove, Boolean) -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thresholdX = with(density) { 110.dp.toPx() }
    val thresholdY = with(density) { 100.dp.toPx() }
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var armed by remember { mutableStateOf<DecisionMove?>(null) }
    var busy by remember { mutableIntStateOf(0) }
    // Where the finger has taken the card; the Animatables follow it (their snaps run a frame later).
    val target = remember { FloatArray(2) }

    fun armedFor(x: Float, y: Float): DecisionMove? = when {
        -y >= thresholdY && -y > abs(x) -> DecisionMove.OPEN
        x >= thresholdX -> DecisionMove.YES
        x <= -thresholdX -> DecisionMove.LATER
        else -> null
    }

    /** Does [move]: flies the card off (or fades it) when it leaves the stack, else springs it back first. */
    fun perform(move: DecisionMove) {
        if (busy > 0) return
        busy++
        haptics.light()
        val effect = card.effect(move)
        val leaves = effect == DecisionEffect.COMPLETE_TASK || effect == DecisionEffect.SNOOZE_TASK || effect == DecisionEffect.SET_ASIDE
        scope.launch {
            if (leaves) {
                if (reduced) {
                    fade.animateTo(0f, tween(120))
                } else {
                    val toX = when (move) { DecisionMove.YES -> widthPx * 1.4f; DecisionMove.LATER -> -widthPx * 1.4f; else -> dx.value }
                    launch { dy.animateTo(dy.value + 40f, tween(220)) }
                    dx.animateTo(toX, tween(220))
                }
                onMove(move, true)
                // A card set aside comes round again at the back; it starts from the middle next time.
                target[0] = 0f; target[1] = 0f
                dx.snapTo(0f); dy.snapTo(0f); fade.snapTo(1f)
            } else {
                target[0] = 0f; target[1] = 0f
                launch { dy.animateTo(0f, MekaMotion.complete(reduced)) }
                dx.animateTo(0f, MekaMotion.complete(reduced))
                onMove(move, false)
            }
            armed = null
            busy--
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        val x = dx.value
        val y = dy.value
        val progress = (maxOf(abs(x) / thresholdX, -y / thresholdY)).coerceIn(0f, 1f)
        DecisionCardFace(
            card, armed = armed ?: preview(x, y), progress = progress,
            modifier = Modifier
                // Open grows the task's detail out of the card (closed Fold).
                .then(card.taskId?.let { Modifier.containerOrigin(it) } ?: Modifier)
                .graphicsLayer {
                    translationX = x
                    translationY = y
                    rotationZ = if (reduced) 0f else (x / widthPx.coerceAtLeast(1f)) * 12f
                    alpha = fade.value
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(card.yesLabel) { perform(DecisionMove.YES); true },
                        CustomAccessibilityAction(card.laterLabel) { perform(DecisionMove.LATER); true },
                        CustomAccessibilityAction(card.openLabel) { perform(DecisionMove.OPEN); true },
                    )
                }
                .pointerInput(card.id) {
                    detectDragGestures(
                        onDragStart = { target[0] = dx.value; target[1] = dy.value },
                        onDragEnd = {
                            val move = armed
                            if (move != null) perform(move) else scope.launch {
                                target[0] = 0f; target[1] = 0f
                                launch { dy.animateTo(0f, MekaMotion.complete(reduced)) }
                                dx.animateTo(0f, MekaMotion.complete(reduced))
                            }
                        },
                        onDragCancel = {
                            armed = null
                            target[0] = 0f; target[1] = 0f
                            scope.launch { launch { dy.animateTo(0f, MekaMotion.complete(reduced)) }; dx.animateTo(0f, MekaMotion.complete(reduced)) }
                        },
                    ) { change, drag ->
                        if (busy > 0) return@detectDragGestures
                        change.consume()
                        val nx = target[0] + drag.x
                        val ny = (target[1] + drag.y).coerceAtMost(thresholdY * 0.3f)
                        target[0] = nx; target[1] = ny
                        scope.launch { dx.snapTo(nx); dy.snapTo(ny) }
                        val now = armedFor(nx, ny)
                        if (now != armed) {
                            if (now != null) haptics.tick()
                            armed = now
                        }
                    }
                },
        )
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            StackButton("← ${card.laterLabel}", primary = false, Modifier.weight(1f)) { perform(DecisionMove.LATER) }
            StackButton("↑ ${card.openLabel}", primary = false, Modifier.weight(1f)) { perform(DecisionMove.OPEN) }
            StackButton("${card.yesLabel} →", primary = true, Modifier.weight(1f)) { perform(DecisionMove.YES) }
        }
    }
}

/** Which move the card is leaning towards (shown faintly before it's armed). */
private fun preview(x: Float, y: Float): DecisionMove? = when {
    -y > abs(x) && y < 0f -> DecisionMove.OPEN
    x > 0f -> DecisionMove.YES
    x < 0f -> DecisionMove.LATER
    else -> null
}

/**
 * One card: why (lit when urgent), the title, the hint. As it leans to a move the card takes on that move's colour
 * (the accent for Done, grey for Tomorrow/Later and Open; [MotionMath.swipeWash]) and the move's chip fades in with
 * its icon growing; at the threshold the chip pops and the icon pops further (catalogue "Swipe actions").
 */
@Composable
private fun DecisionCardFace(card: DecisionCard, armed: DecisionMove?, progress: Float, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val leanColor = when (armed) {
        DecisionMove.YES -> Meka.colors.accent
        DecisionMove.LATER -> Meka.colors.textSecondary
        DecisionMove.OPEN -> Meka.colors.textPrimary
        null -> Meka.colors.surfaceRaised
    }
    val face = lerp(Meka.colors.surfaceRaised, leanColor, if (armed == null) 0f else MotionMath.swipeWash(progress))
    Box(
        modifier.fillMaxWidth().heightIn(min = 196.dp).clip(RoundedCornerShape(MekaRadius.l))
            .background(face).padding(MekaSpace.l),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(card.why, style = MekaType.itemMeta, color = if (card.urgent) Meka.colors.critical else Meka.colors.accent)
            Text(card.title, style = MekaType.upNextTitle, color = Meka.colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(NeedsYouStackRules.hint(card), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        if (armed != null && progress > 0f) {
            val pop by animateFloatAsState(if (progress >= 1f && !reduced) 1.15f else 1f, MekaMotion.approve(reduced), label = "stack-pop")
            val yes = armed == DecisionMove.YES
            val ink = if (yes) Meka.colors.onAccent else Meka.colors.textPrimary
            Row(
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(
                        when (armed) {
                            DecisionMove.YES -> Alignment.TopEnd
                            DecisionMove.LATER -> Alignment.TopStart
                            DecisionMove.OPEN -> Alignment.BottomCenter
                        },
                    )
                    .graphicsLayer { alpha = progress; scaleX = pop; scaleY = pop }
                    .clip(RoundedCornerShape(MekaRadius.m))
                    .background(if (yes) Meka.colors.accent else Meka.colors.background)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) {
                SwipeActionIcon(
                    when (armed) {
                        DecisionMove.YES -> SwipeGlyph.CHECK
                        DecisionMove.LATER -> SwipeGlyph.LATER
                        DecisionMove.OPEN -> SwipeGlyph.OPEN
                    },
                    ink, progress, armed = progress >= 1f,
                )
                Text(card.label(armed), style = MekaType.itemTitle, color = ink)
            }
        }
    }
}

@Composable
private fun StackButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(
        label,
        style = MekaType.itemMeta,
        color = if (primary) Meka.colors.onAccent else Meka.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(MekaRadius.m))
            .background(if (primary) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onClick)
            .minTouch().padding(horizontal = MekaSpace.xs),
    )
}
