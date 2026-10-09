package os.meka.android.fold

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.containerOrigin
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.NowAction
import os.meka.core.domain.NowKind
import os.meka.core.domain.NowView

/** What the "now" card's taps do; wired by Today. */
internal class NowHandlers(
    val complete: (String) -> Unit,
    val tomorrow: (String) -> Unit,
    val openTask: (String) -> Unit,
    val openEvent: (CalendarEvent) -> Unit,
    /** Null where Needs you is already listed alongside (the Fold's Today), so the line isn't shown. */
    val openNeedsYou: (() -> Unit)?,
    /** A booked session's "Went" and "Didn't go" (by habit id), as from Today's session card. */
    val went: (String) -> Unit = {},
    val didntGo: (String) -> Unit = {},
)

/**
 * The "now" card (Fold modes, slice 3): on the closed Fold's cover screen it heads Today in place of Up next; on the open
 * Fold its task form is Up next itself (Fold review 2026-10-09, item 3), so Up next is the same card on both. One thing
 * (an event or booked session starting or just started, a session asking "Did you go?", Up next, or clear) with its
 * one-tap actions: Join or Maps and Open for an event; Done, Tomorrow and Open for a task; Went and Didn't go for a
 * session on now or over. Under it, "Then: …" and "3 need you ›".
 *
 * Motion: when the thing changes, the content cross-slides like Up next (the old one out left, the new one in from the
 * right); chips press in (0.97) and give a light haptic; Done and Tomorrow let the card slide on to what's next.
 * Reduced motion: cross-fades, no press scale.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NowCard(now: NowView, handlers: NowHandlers, modifier: Modifier = Modifier, titleModifier: @Composable (String) -> Modifier = { Modifier }) {
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth()) {
      Box(Modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = now,
            contentKey = { "${it.kind}:${it.task?.id ?: it.event?.id ?: it.session?.habitId ?: ""}" },
            transitionSpec = {
                if (reduced) fadeIn(MekaMotion.replan(true)) togetherWith fadeOut(MekaMotion.replan(true))
                else (slideInHorizontally(MekaMotion.replan(false)) { it / 4 } + fadeIn(MekaMotion.appear(false))) togetherWith
                    (slideOutHorizontally(MekaMotion.replan(false)) { -it / 4 } + fadeOut(MekaMotion.appear(false)))
            },
            label = "now",
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised),
        ) { v ->
            Column(
                // A task's detail grows out of the card (catalogue "Task detail").
                Modifier.then(v.task?.let { Modifier.containerOrigin(it.id) } ?: Modifier).fillMaxWidth()
                    .clickable(enabled = v.task != null || v.event != null, role = Role.Button) {
                        v.task?.let { handlers.openTask(it.id) } ?: v.event?.let(handlers.openEvent)
                    }
                    .padding(MekaSpace.l),
            ) {
                val labelColor by animateColorAsState(
                    if (v.lit) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.themeBlend(reduced), label = "nowLabel",
                )
                Text(v.label.uppercase(), style = MekaType.sectionLabel, color = labelColor)
                Text(
                    // Semibold 22 sp (Fold review 2026-10-09, item 3): one bold line that doesn't shout.
                    v.title, style = MekaType.nowTitle,
                    color = if (v.kind == NowKind.CLEAR) Meka.colors.textSecondary else Meka.colors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MekaSpace.xxs).then(v.task?.let { titleModifier(it.id) } ?: Modifier),
                )
                v.line?.let {
                    Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = MekaSpace.xxs))
                }
                if (v.actions.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = MekaSpace.m),
                        horizontalArrangement = Arrangement.spacedBy(MekaSpace.s),
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
                    ) {
                        v.actions.forEach { a -> NowChip(a, v, handlers) }
                    }
                }
            }
        }
      }
        now.thenLine?.let { line ->
            Text(
                line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(enabled = now.thenTask != null || now.thenEvent != null, role = Role.Button) {
                        now.thenTask?.let { handlers.openTask(it.id) } ?: now.thenEvent?.let(handlers.openEvent)
                    }
                    .padding(vertical = MekaSpace.xxs),
            )
        }
        val openNeedsYou = handlers.openNeedsYou
        if (now.needsYouLine != null && openNeedsYou != null) {
            Text(
                "${now.needsYouLine} ›", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { openNeedsYou() }
                    .padding(vertical = MekaSpace.xxs),
            )
        }
    }
}

@Composable
private fun NowChip(a: NowAction, v: NowView, handlers: NowHandlers) {
    val uri = LocalUriHandler.current
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, MekaMotion.complete(reduced), label = "chipPress")
    val primary = a == NowAction.JOIN || a == NowAction.DONE || a == NowAction.WENT || (a == NowAction.MAPS && v.join == null)
    val label = when (a) {
        NowAction.JOIN -> v.join?.label ?: "Join"
        NowAction.MAPS -> "Directions"
        NowAction.OPEN_EVENT, NowAction.OPEN_TASK -> "Open"
        NowAction.DONE -> "Done"
        NowAction.TOMORROW -> "Tomorrow"
        NowAction.WENT -> "Went"
        NowAction.DIDNT_GO -> "Didn't go"
    }
    Text(
        label, style = MekaType.caption,
        color = if (primary) Meka.colors.onAccent else Meka.colors.textPrimary,
        modifier = Modifier.scale(scale).clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (primary) Meka.colors.accent else Meka.colors.background)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                if (a == NowAction.DIDNT_GO) haptics.tick() else haptics.light()
                when (a) {
                    NowAction.JOIN -> v.join?.let { runCatching { uri.openUri(it.url) } }
                    NowAction.MAPS -> v.mapsQuery?.let { runCatching { uri.openUri("geo:0,0?q=" + Uri.encode(it)) } }
                    NowAction.OPEN_EVENT -> v.event?.let(handlers.openEvent)
                    NowAction.DONE -> v.task?.let { handlers.complete(it.id) }
                    NowAction.TOMORROW -> v.task?.let { handlers.tomorrow(it.id) }
                    NowAction.OPEN_TASK -> v.task?.let { handlers.openTask(it.id) }
                    NowAction.WENT -> v.session?.let { handlers.went(it.habitId) }
                    NowAction.DIDNT_GO -> v.session?.let { handlers.didntGo(it.habitId) }
                }
            }
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
    )
}
