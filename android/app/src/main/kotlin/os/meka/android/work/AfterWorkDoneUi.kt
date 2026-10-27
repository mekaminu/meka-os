package os.meka.android.work

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaChoreography
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.SwipeBackdrop
import os.meka.android.designsystem.SwipeGlyph
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AfterWorkDone
import os.meka.core.domain.CallNoticeRules
import os.meka.core.domain.PersonSummary
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Swipe a card either way to clear it (Meka, 2026-10-15 20:28; catalogue "Swipe actions"): the brass Done layer
 * deepens behind it with the check, a tick haptic when it arms; let go armed and the card slides off the way it was
 * going (light haptic), then [onDone]. Not far enough: it springs back. Reduced motion: no slide-off, it just goes.
 * Screen readers get a "Done with …" action instead.
 */
@Composable
internal fun SwipeToDone(
    label: String, onDone: () -> Unit, modifier: Modifier = Modifier,
    /** The backdrop's word (Money's Worth a look says "That's fine"). */
    action: String = AfterWorkDone.DONE,
    content: @Composable () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { MekaChoreography.swipeArmDistanceDp.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier.fillMaxWidth().semantics { customActions = listOf(CustomAccessibilityAction(label) { onDone(); true }) },
    ) {
        val max = constraints.maxWidth.toFloat().coerceAtLeast(threshold * 1.5f)
        val progress = MotionMath.swipeProgress(with(LocalDensity.current) { dx.toDp().value })
        SwipeBackdrop(
            side = if (dx > 0) 1 else if (dx < 0) -1 else 0, progress = progress, armed = armed != 0, glyph = SwipeGlyph.CHECK,
            label = action, fill = Meka.colors.accent, content = Meka.colors.onAccent,
        )
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth()
                .offset { IntOffset(dx.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - 0.6f * (abs(dx) / max).coerceIn(0f, 1f) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        dx = (dx + delta).coerceIn(-max, max)
                        val now = if (abs(dx) >= threshold) (if (dx > 0) 1 else -1) else 0
                        if (now != armed) { if (now != 0) haptics.tick(); armed = now }
                    },
                    onDragStopped = {
                        val side = armed
                        armed = 0
                        if (side != 0) {
                            haptics.light()
                            scope.launch {
                                if (!reduced) animate(dx, side * max, animationSpec = MekaMotion.complete(false)) { v, _ -> dx = v }
                                onDone()
                            }
                        } else {
                            scope.launch { animate(dx, 0f, animationSpec = MekaMotion.complete(reduced)) { v, _ -> dx = v } }
                        }
                    },
                ),
        ) { content() }
    }
}

/**
 * Done on [people]'s cards, or Clear all (Meka, 2026-10-15 20:28): they leave both screens at once and the bar says
 * [line] with Undo for 5 s; then the core clears them on the Fold and the Mac, and this phone's sealed copy and any
 * call notification for them go too. Undo puts them back with nothing written.
 */
internal fun clearAfterWorkCards(
    app: os.meka.android.MekaApplication,
    people: List<PersonSummary>,
    line: String,
    undoShow: (String, (suspend () -> Unit)?) -> Unit,
) {
    val ids = people.flatMap { it.ids }
    if (ids.isEmpty()) return
    val pending = AfterWorkPending.start(ids)
    app.appScope.launch { runCatching { app.core.clearAfterWorkSoon(ids) } }
    undoShow(line) { AfterWorkPending.cancel(pending); app.core.undoClearAfterWork(ids) }
    app.appScope.launch {
        kotlinx.coroutines.delay(AfterWorkDone.UNDO_MS)
        if (AfterWorkPending.finish(pending)) {
            app.captures.clearHeld(ids.toSet())
            people.filter(AfterWorkDone::clearsCallNotice).forEach { CallNotification.cancelFor(app, it) }
            if (app.core.afterWork.value.isEmpty) app.nudger.dismiss()
        }
    }
}

/** The headline, cross-fading when a card's Done changes its counts. */
@Composable
internal fun AnimatedContentText(text: String, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    AnimatedContent(text, modifier, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut() }, label = "after-work-headline") {
        Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
    }
}

/**
 * The Dones waiting out their Undo on this phone, so the sealed copy and the call notification only go once the
 * five seconds pass without Undo (the synced clear waits in the core the same way).
 */
internal object AfterWorkPending {
    private val live = HashSet<Long>()
    private var n = 0L

    @Synchronized fun start(@Suppress("UNUSED_PARAMETER") ids: List<String>): Long = (++n).also { live += it }
    @Synchronized fun cancel(token: Long) { live -= token }
    /** True when [token] wasn't undone (and forgets it). */
    @Synchronized fun finish(token: Long): Boolean = live.remove(token)
}

/** Done on a missed-call or voice-message card also takes down that caller's notification (one per caller). */
internal fun CallNotification.cancelFor(context: Context, p: PersonSummary) {
    val manager = NotificationManagerCompat.from(context)
    for (item in p.items) {
        CallNoticeRules.tag(CallNoticeRules.numberOf(item))?.let { manager.cancel(id(it)) }
        manager.cancel(item.id.hashCode())
    }
}
