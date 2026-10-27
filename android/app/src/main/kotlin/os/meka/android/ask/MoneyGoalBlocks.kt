package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.moneyTone
import os.meka.core.domain.MoneyGoalRow
import os.meka.core.domain.MoneyGoals
import os.meka.core.domain.MoneyGoalsView

/**
 * Money redesign, Plan's goals card (mockup `docs/design/money/`, Plan): **GOALS** as one card. Each £ goal is a row: its
 * name and "£240 of £600", a bar in its own colour, and one note on its pace ("£180 a month to 15 Dec · on track"; amber
 * when it's behind). A tap opens the goal's sheet ([MoneyGoalDetails]), where it can follow an account's balance. Every
 * word and number is the core's [MoneyGoals.view].
 *
 * Motion: the bar glides to its share and the note cross-fades (MekaMotion.appear honours reduced motion); a row presses
 * like every row and its sheet rises on the expand spring.
 */
@Composable
internal fun MoneyGoalsCard(gv: MoneyGoalsView, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .testTag("money-goals-card").padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        if (gv.rows.isEmpty()) {
            Text(gv.empty ?: MoneyGoals.EMPTY, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        gv.rows.forEach { r -> GoalCardRow(r) { onOpen(r.id) } }
    }
}

@Composable
private fun GoalCardRow(r: MoneyGoalRow, onOpen: () -> Unit) {
    val reduced = Meka.reducedMotion
    val share by animateFloatAsState(r.share, MekaMotion.appear(reduced), label = "money-goal-bar-${r.id}")
    val noteColour by animateColorAsState(if (r.behind) Meka.colors.money3 else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "money-goal-note-${r.id}")
    Column(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = r.spoken }
            .padding(vertical = MekaSpace.xxs).testTag("money-goal-${r.id}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Crossfade(r.amount, animationSpec = MekaMotion.appear(reduced), label = "money-goal-amount-${r.id}") { a ->
                Text(a, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1)
            }
            Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.moneyTone(r.tone)))
        }
        Crossfade(r.note, animationSpec = MekaMotion.appear(reduced), label = "money-goal-note-line-${r.id}") { n ->
            Text(n, style = MekaType.caption, color = noteColour, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A goal's sheet: where it stands and its pace, then which account it follows (a chip per account, the chosen one in
 * the accent; tap it again to stop) and the foot. The follow chips were open on the page before the goals card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoneyGoalDetails(r: MoneyGoalRow, gv: MoneyGoalsView, onFollow: (String?) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-goal-sheet-line") { l ->
            Text(l, style = MekaType.body, color = Meka.colors.textPrimary)
        }
        Text(r.note, style = MekaType.caption, color = if (r.behind) Meka.colors.money3 else Meka.colors.textSecondary)
        Text(MoneyGoals.FOLLOW, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
        Crossfade(r.follows ?: MoneyGoals.NOT_FOLLOWING, animationSpec = MekaMotion.appear(reduced), label = "money-goal-sheet-follows") { l ->
            Text(l, style = MekaType.caption, color = if (r.follows != null) Meka.colors.accent else Meka.colors.textSecondary)
        }
        if (r.choices.isEmpty()) Text(MoneyGoals.NO_ACCOUNTS, style = MekaType.caption, color = Meka.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            r.choices.forEach { c ->
                val col by animateColorAsState(if (c.picked) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "money-goal-chip-${c.accountId}")
                Text(c.name + if (c.picked) " ✓" else "", style = MekaType.caption, color = col,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .clickable(role = Role.Button) { onFollow(if (c.picked) null else c.accountId) }
                        .semantics { contentDescription = if (c.picked) "Stop following ${c.name}" else "Follow ${c.name}" }
                        .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
                        .testTag("money-goal-follow-${c.accountId}"))
            }
        }
        Text(gv.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}
