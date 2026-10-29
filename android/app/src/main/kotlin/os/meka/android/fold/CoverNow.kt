package os.meka.android.fold

import androidx.compose.ui.platform.testTag
import os.meka.android.designsystem.minTouch
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.PillSize
import os.meka.android.designsystem.containerOrigin
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.CoverNowRules
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
    /** "Move to later" on a late planned task (by task id): re-planned to the first free stretch, with Undo. */
    val later: (String) -> Unit = {},
    /**
     * "Break it down" on a task carried over 2+ days (Fold review 2026-10-12 17:56, item 5): opens it with the
     * checklist's add field focused ([StepFocus]).
     */
    val breakDown: (String) -> Unit = {},
)

/**
 * The "now" card (Fold modes, slice 3): on the closed Fold's cover screen it heads Today in place of Up next; on the open
 * Fold its task form is Up next itself (Fold review 2026-10-09, item 3), so Up next is the same card on both. One thing
 * (an event or booked session starting or just started, a session asking "Did you go?", Up next, or clear) with its
 * one-tap actions: Join or Maps and Open for an event; Done, Tomorrow and Open for a task (Done, Move to later and
 * Tomorrow once a planned task's time has gone by, its label lit); Went and Didn't go for a session on now or over. Under it, "Then: …" and "3 need you ›".
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
                Modifier.testTag(NOW_CARD_TAG).then(v.task?.let { Modifier.containerOrigin(it.id) } ?: Modifier).fillMaxWidth()
                    .clickable(enabled = v.task != null || v.event != null, role = Role.Button) {
                        v.task?.let { handlers.openTask(it.id) } ?: v.event?.let(handlers.openEvent)
                    }
                    // 16 dp inside (was 24), less the pills' own target inset at the bottom (Calmer Today, slice 2).
                    .padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.m,
                        bottom = if (v.actions.isNotEmpty()) MekaSpace.m - PillSize.INSET else MekaSpace.m),
            ) {
                val labelColor by animateColorAsState(
                    if (v.lit) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.themeBlend(reduced), label = "nowLabel",
                )
                // Calmer Today, slice 2 (the approved mockup): "UP NEXT · Carried over 5 days" on one row when the line
                // is short (core CoverNowRules.lineOnLabelRow), so the card is about half its old height.
                val lineOnLabel = CoverNowRules.lineOnLabelRow(v.line)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(v.label.uppercase(), style = MekaType.sectionLabel, color = labelColor, maxLines = 1)
                    // The label keeps its width; the line takes the rest, at the row's end.
                    Text(if (lineOnLabel) v.line.orEmpty() else "", style = MekaType.caption, color = Meka.colors.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f).padding(start = MekaSpace.xs))
                }
                Text(
                    // Semibold 17 sp (Calmer Today, slice 2; was 22 sp): the one bold line on Today, not a headline.
                    v.title, style = MekaType.upNextCardTitle,
                    color = if (v.kind == NowKind.CLEAR) Meka.colors.textSecondary else Meka.colors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MekaSpace.xxs).then(v.task?.let { titleModifier(it.id) } ?: Modifier),
                )
                if (!lineOnLabel) v.line?.let {
                    Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = MekaSpace.xxs))
                }
                if (v.actions.isNotEmpty()) {
                    // 36 dp pills, each in its 48 dp target (PillSize): the target's own inset is most of the gap above.
                    FlowRow(
                        Modifier.padding(top = MekaSpace.xxs),
                        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
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
        NowAction.LATER -> os.meka.core.domain.LateTaskRules.MOVE_LATER
        NowAction.BREAK_DOWN -> os.meka.core.domain.CoverNowRules.BREAK_DOWN_LABEL
    }
    // The 48 dp target (testTag: the quality gate's one-row check) holds a 36 dp pill (Calmer Today, slice 2).
    Box(
        Modifier.testTag(NOW_CHIP_TAG).scale(scale)
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
                    NowAction.LATER -> v.task?.let { handlers.later(it.id) }
                    NowAction.BREAK_DOWN -> v.task?.let { handlers.breakDown(it.id) }
                }
            }
            .minTouch(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = if (primary) MekaType.captionStrong else MekaType.caption,
            color = if (primary) Meka.colors.onAccent else Meka.colors.textPrimary,
            maxLines = 1,
            // 16 dp sides: Done · Break it down · Tomorrow fit one row on the cover screen's card; quiet ones a step
            // lighter than the card, as the mockup draws them (beside it 2026-10-29 they were black on the grey card).
            modifier = Modifier.height(PillSize.DRAWN).clip(RoundedCornerShape(MekaRadius.pill))
                .background(if (primary) Meka.colors.accent else Meka.colors.hairline)
                .padding(horizontal = MekaSpace.m).wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/** The now card's action chips, for the quality gate's one-row check. */
internal const val NOW_CHIP_TAG = "now-chip"

/** The now / Up next card itself, for the quality gate's height check (Calmer Today, slice 2). */
internal const val NOW_CARD_TAG = "now-card"
