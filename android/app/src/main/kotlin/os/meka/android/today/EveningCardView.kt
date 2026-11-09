package os.meka.android.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.EveningCard
import os.meka.core.domain.EveningCardRules
import os.meka.core.domain.EveningStep
import os.meka.core.domain.EveningStepKind

/** Test tag of the evening card (the Today rebuild, slice 2a). */
internal const val EVENING_CARD_TAG = "evening-card"

/**
 * The Today rebuild, slice 2a (`docs/design/day-list/TodayNew.dc.html`): "THIS EVENING · 1 OF 3", one card right under
 * Now from 17:00 with the evening's steps (Review your week, Shut down the day, Plan tomorrow). Each step is a 48 dp row
 * that opens its own place; a done step's number turns into a gold tick (a colour fade, the catalogue's state change;
 * with reduced motion it is the same fade, never movement). Later puts the card away until 21:00.
 */
@Composable
internal fun EveningCardView(
    card: EveningCard,
    onStep: (EveningStepKind) -> Unit,
    onLater: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(card.label, Modifier.weight(1f))
            if (onLater != null) {
                Text(
                    EveningCardRules.LATER, style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Button, onClickLabel = "Put the evening card away until 21:00") { onLater() }
                        .minTouch().padding(horizontal = MekaSpace.xs),
                )
            }
        }
        card.steps.forEachIndexed { i, s -> EveningStepRow(i, s) { onStep(s.kind) } }
    }
}

@Composable
private fun EveningStepRow(index: Int, step: EveningStep, onOpen: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (step.done) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "evening-dot")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen() }
            .padding(vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = step.title + (if (step.done) ", done. " else ". ") + step.line },
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(fill)
                .then(if (step.done) Modifier else Modifier.border(1.5.dp, Meka.colors.textTertiary, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (step.done) "✓" else "${index + 1}", style = MekaType.caption,
                color = if (step.done) Meka.colors.onAccent else Meka.colors.textSecondary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MekaType.body, color = if (step.done) Meka.colors.textSecondary else Meka.colors.textPrimary, maxLines = 1)
            Text(step.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 1)
        }
        Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
}

/** The thin gold done bar under Today's head (3 dp, the Day list's own). */
@Composable
internal fun TodayDoneBar(progress: Float, modifier: Modifier = Modifier) {
    val p = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Meka.colors.hairline)) {
        if (p > 0f) Box(Modifier.fillMaxWidth(p).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Meka.colors.accent))
    }
}
