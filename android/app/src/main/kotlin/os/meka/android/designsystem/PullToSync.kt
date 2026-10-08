package os.meka.android.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Pull to sync (motion pass 2, slice 3; catalogue: Sync). Pulling Today down past its top draws a brass ring that
 * fills with the pull; letting go once it is full syncs: the ring turns while the round runs (at least one turn, so a
 * quick sync is still seen) and the content settles back. Motion → Off: the ring fills and holds still, no turn.
 */

private const val RING_DP = 28
private const val RING_STROKE_DP = 2.5f

/** The pull's state: how far the content has followed the finger, and whether a sync is running. */
@Stable
class PullToSyncState internal constructor(
    private val scope: CoroutineScope,
    private val density: () -> Float,
    private val sync: suspend () -> Unit,
    private val armed: () -> Unit,
    private val synced: () -> Unit,
) {
    /** The finger's travel past the top (dp) while dragging; 0 when not dragging. */
    private var dragDp by mutableFloatStateOf(0f)
    /** Where the content sits (dp) once the finger has let go: settling back, or holding while it syncs. */
    private val settle = Animatable(0f)
    var syncing by mutableStateOf(false)
        private set

    /** How far (dp) Today's content sits below its place right now. */
    val offsetDp: Float get() = if (dragDp > 0f) MotionMath.pullOffsetDp(dragDp) else settle.value

    private fun dragTo(newDragDp: Float) {
        val wasArmed = MotionMath.pullArmed(MotionMath.pullOffsetDp(dragDp))
        dragDp = newDragDp.coerceAtLeast(0f)
        if (!wasArmed && MotionMath.pullArmed(MotionMath.pullOffsetDp(dragDp))) armed()
    }

    /** The finger let go: sync if the ring is full, then settle back. The gesture never waits for the round. */
    private suspend fun release() {
        val from = MotionMath.pullOffsetDp(dragDp)
        settle.snapTo(from)
        dragDp = 0f
        if (MotionMath.pullArmed(from)) { syncing = true; scope.launch { start(fromPull = true) } }
        else scope.launch { settle.animateTo(0f, MekaMotion.expand(false)) }
    }

    /** Runs one sync with the ring showing (a pull, or the screen reader's "Sync now"). */
    fun syncNow() {
        if (syncing) return
        syncing = true
        scope.launch { start(fromPull = false) }
    }

    /** Called with [syncing] already set, so a second pull or tap can't start another round meanwhile. */
    private suspend fun start(fromPull: Boolean) {
        // Hold the ring where it filled (or bring it in for a screen reader's sync).
        val rest = MekaChoreography.pullThresholdDistanceDp.toFloat()
        if (fromPull) settle.animateTo(rest, MekaMotion.expand(false)) else settle.snapTo(rest)
        val began = System.currentTimeMillis()
        try {
            runCatching { sync() }
            delay(MotionMath.ringHoldMs(System.currentTimeMillis() - began))
        } finally {
            syncing = false
        }
        synced()
        settle.animateTo(0f, MekaMotion.expand(false))
    }

    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Pushing back up while pulled: the pull gives way before the list scrolls.
            if (available.y < 0f && dragDp > 0f && !syncing) {
                val before = dragDp
                dragTo(before + available.y / density())
                return Offset(0f, (dragDp - before) * density())
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // The list is at its top and the finger keeps pulling down: Today follows it.
            if (source == NestedScrollSource.UserInput && available.y > 0f && !syncing) {
                dragTo(dragDp + available.y / density())
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (dragDp > 0f) { release(); return available }
            return Velocity.Zero
        }
    }
}

/**
 * Remembers a pull-to-sync for one screen. [sync] runs one round (e.g. `core.syncNow()`); a tick haptic marks the
 * moment letting go would sync and a light one the end of the round.
 */
@Composable
fun rememberPullToSync(sync: suspend () -> Unit): PullToSyncState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = rememberMekaHaptics()
    val currentSync by rememberUpdatedState(sync)
    return remember(scope, haptics) {
        PullToSyncState(scope, { density.density }, { currentSync() }, armed = { haptics.tick() }, synced = { haptics.light() })
    }
}

/**
 * The pullable area: [content] (a scrolling list) follows the pull and the brass ring shows in the gap it opens.
 * Screen readers get a "Sync now" action instead of the gesture.
 */
@Composable
fun PullToSyncBox(state: PullToSyncState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier
            .nestedScroll(state.connection)
            .semantics { customActions = listOf(CustomAccessibilityAction("Sync now") { state.syncNow(); true }) },
    ) {
        Box(Modifier.graphicsLayer { translationY = state.offsetDp * density.density }) { content() }
        SyncRing(state, Modifier.align(Alignment.TopCenter))
    }
}

/** The brass ring: fills with the pull, turns while syncing, fades with the pull as the content settles back. */
@Composable
private fun SyncRing(state: PullToSyncState, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    var spin by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.syncing, reduced) {
        if (!state.syncing || reduced) { spin = 0f; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            spin = withFrameMillis { now -> MotionMath.ringSpinDegrees(now - start, reduced) }
        }
    }
    val offset = state.offsetDp
    if (offset <= 0.5f) return
    val progress = MotionMath.pullProgress(offset)
    // Centred in the gap the pull opens, never above the top edge.
    val y = ((offset - RING_DP) / 2f).coerceAtLeast(0f)
    Canvas(modifier.offset(y = y.dp).size(RING_DP.dp).graphicsLayer { alpha = progress }) {
        val stroke = RING_STROKE_DP.dp.toPx()
        val inset = stroke / 2f
        val arc = Size(size.width - stroke, size.height - stroke)
        rotate(spin) {
            drawArc(
                color = accent, startAngle = -90f, sweepAngle = MotionMath.ringSweepDegrees(progress), useCenter = false,
                topLeft = Offset(inset, inset), size = arc, style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
