package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CarriedTask
import os.meka.core.domain.CarryChoice
import os.meka.core.domain.RollOverRules

/**
 * One "From yesterday" row (Week board and roll-over, slice 1; [RollOverRules]): the task as Today draws it, its line
 * "Carried 2 days" in the accent, and from three carries the one-time prompt under it — Break it down · Someday · Drop ·
 * Keep — which folds away once answered (reduced motion: fades).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CarriedRow(
    c: CarriedTask, actions: TodayActions, motion: RowMotion, modifier: Modifier = Modifier,
    onChoice: (String, CarryChoice) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(modifier) {
        TaskRow(c.task, actions, motion = motion, carriedLine = c.line)
        AnimatedVisibility(
            c.asks,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else fadeIn(MekaMotion.appear(false)) + expandVertically(MekaMotion.expand(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else fadeOut(MekaMotion.appear(false)) + shrinkVertically(MekaMotion.expand(false)),
        ) {
            Column(Modifier.padding(start = CARRIED_INDENT, bottom = MekaSpace.xs)) {
                Text(RollOverRules.ASK_LINE, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                FlowRow(
                    Modifier.padding(top = MekaSpace.xs),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    CarryPill(RollOverRules.BREAK_DOWN, primary = true) { onChoice(c.task.id, CarryChoice.BREAK_DOWN) }
                    CarryPill(RollOverRules.SOMEDAY) { onChoice(c.task.id, CarryChoice.SOMEDAY) }
                    CarryPill(RollOverRules.DROP) { onChoice(c.task.id, CarryChoice.DROP) }
                    CarryPill(RollOverRules.KEEP, quiet = true) { onChoice(c.task.id, CarryChoice.KEEP) }
                }
            }
        }
    }
}

/** The ring's width plus the gap after it, so the prompt lines up with the task's title. */
private val CARRIED_INDENT = MekaSpace.m + 28.dp

@Composable
private fun CarryPill(label: String, primary: Boolean = false, quiet: Boolean = false, onClick: () -> Unit) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, MekaMotion.complete(reduced), label = "carryPress")
    Text(
        label, style = MekaType.caption, textAlign = TextAlign.Center,
        color = when { primary -> Meka.colors.onAccent; quiet -> Meka.colors.textSecondary; else -> Meka.colors.textPrimary },
        modifier = Modifier.scale(scale).clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (primary) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(interactionSource = source, indication = null, role = Role.Button) { haptics.light(); onClick() }
            .minTouch().padding(horizontal = MekaSpace.l),
    )
}
