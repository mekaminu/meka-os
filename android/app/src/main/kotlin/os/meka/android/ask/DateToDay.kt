package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.today.MailNeedsPill
import os.meka.core.domain.BoardFeedRules
import os.meka.core.domain.FeedDay

/**
 * Week board item (5) part 4: **To a day…** under a school date (School) or a birthday (Gifts). The tap (tick haptic)
 * unfolds "Which day?" and the chips from today up to the date (expand spring; reduced motion: cross-fade); a chip's
 * tap (medium haptic) folds them away and makes the task on that day of the Board. What it did cross-fades in under
 * the row with Undo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateToDay(
    key: String,
    title: String,
    days: () -> List<FeedDay>,
    said: String?,
    onToDay: (Long) -> Unit,
    onUndo: (() -> Unit)?,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var choosing by rememberSaveable(key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(
            BoardFeedRules.TO_DAY, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); choosing = !choosing }
                .minTouch().semantics { contentDescription = BoardFeedRules.dateSpoken(title) },
        )
        AnimatedVisibility(choosing, enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(BoardFeedRules.PICK, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    days().forEach { d ->
                        MailNeedsPill(d.label, spoken = "${d.spoken}: $title", touch = true, onClick = { haptics.medium(); choosing = false; onToDay(d.day) })
                    }
                }
            }
        }
        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "date-to-day-$key") { line ->
            if (line != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                    if (onUndo != null) {
                        Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); onUndo() }.minTouch().padding(horizontal = MekaSpace.xs))
                    }
                }
            }
        }
    }
}
