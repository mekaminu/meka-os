package os.meka.android.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.goals.fold
import os.meka.android.goals.unfold
import os.meka.android.goals.GoalAddRowView
import os.meka.android.goals.LineAddSheetView
import androidx.compose.ui.platform.testTag
import os.meka.android.today.SectionLabel
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.WinAdded
import os.meka.core.domain.WinRow
import os.meka.core.domain.WinRules
import os.meka.core.domain.WinsView

/**
 * Work wins in the weekly review (V1, slice 1): the reviewed week's wins, the "+ Add a win" row opening a sheet to keep
 * one ("What went well this week?"; walk-through week 5, A17: no open box on the page), and this quarter's wins week by week. Friday at 16:00 MEKA asks; until a win is kept the section's line is
 * lit in the accent.
 *
 * Motion: the section staggers in with the review's others; the line under the row and the week's line cross-fade
 * (the week's line blends to the accent while MEKA is asking); the sheet rises over a fading scrim; its Add (or Done on
 * the keyboard) gives a light haptic and the win arrives with
 * the list's item motion; Remove gives a tick and the row leaves; "This quarter" unfolds its weeks in place (expand
 * spring; the chevron turns; tick haptic). Reduced motion: cross-fades.
 */
internal fun LazyListScope.winsItems(
    wins: WinsView,
    said: WinAdded?,
    index: Int,
    onAdd: (String) -> Unit,
    onRemove: (WinRow) -> Unit,
    onToggleQuarter: () -> Unit,
) {
    item(key = "h-wins") {
        val reduced = Meka.reducedMotion
        val color by animateColorAsState(
            if (wins.asking) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "wins-line",
        )
        Column(Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(index))) {
            SectionLabel("Wins")
            Crossfade(wins.weekLine, animationSpec = MekaMotion.appear(reduced), label = "wins-week-line") { l ->
                Text(LoneWordGlue.kept(l), style = MekaType.itemMeta, color = color, modifier = Modifier.padding(bottom = MekaSpace.xs))
            }
        }
    }
    itemsIndexed(wins.week, key = { _, w -> "win-" + w.id }) { _, w ->
        WinLine(w, Modifier.animateItem().appear(rememberAppearance(index))) { onRemove(w) }
    }
    item(key = "win-field") {
        val reduced = Meka.reducedMotion
        val color by animateColorAsState(
            if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "win-said",
        )
        var adding by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.animateItem().padding(top = MekaSpace.xs).appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            // Walk-through week 5, A17: "+ Add a win" is a card row opening a sheet, not an open box on the page.
            GoalAddRowView(wins.addRow, Modifier.testTag(REVIEW_ADD_WIN_TAG)) { adding = true }
            Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "win-said-line") { l ->
                l?.let { Text(it, style = MekaType.caption, color = color) }
            }
        }
        if (adding) LineAddSheetView(wins.sheet, WinRules::canAdd, WinRules.MAX_TEXT * 2, onAdd = onAdd, onClose = { adding = false })
    }
    if (wins.quarterCount > 0) item(key = "win-quarter") {
        WinsQuarter(wins, Modifier.animateItem().appear(rememberAppearance(index)), onToggleQuarter)
    }
}

/** The tag the screen tests find Review's "+ Add a win" row by. */
const val REVIEW_ADD_WIN_TAG = "review-add-win"

@Composable
private fun WinLine(w: WinRow, modifier: Modifier, onRemove: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = w.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(w.text, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(w.dayLabel, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${w.text}" }
                .minTouch().padding(horizontal = MekaSpace.xs),
        )
    }
}

/** "This quarter · Oct – Dec · 7 wins", unfolding the quarter's weeks (newest first) in place. */
@Composable
private fun WinsQuarter(wins: WinsView, modifier: Modifier, onToggle: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val reduced = Meka.reducedMotion
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) MekaMotion.appear<Float>(true) else MekaMotion.expand<Float>(false), label = "wins-chevron")
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide this quarter's wins" else "Show this quarter's wins") {
                    onToggle(); open = !open
                }
                .minTouch().padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(wins.quarterTitle, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.weight(1f))
            Text("›", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                wins.quarter.forEachIndexed { i, week ->
                    Column(Modifier.appear(rememberAppearance(i)).padding(top = MekaSpace.xs)) {
                        Text(week.label, style = MekaType.caption, color = Meka.colors.textTertiary)
                        week.rows.forEach { w ->
                            Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).semantics(mergeDescendants = true) { contentDescription = w.spoken }) {
                                Text(w.text, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                                Text(w.dayLabel, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(start = MekaSpace.xs))
                            }
                        }
                    }
                }
            }
        }
    }
}
