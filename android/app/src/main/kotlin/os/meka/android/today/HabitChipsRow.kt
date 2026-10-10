package os.meka.android.today

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.core.domain.HabitChip

/** The habit chips' test tag: Today's UI tests look for the row on the closed Fold. */
const val HABIT_CHIPS_TAG = "habit-chips"

/**
 * Today's habits as compact chips under the news ticker (Fold review 2026-10-09 07:26, item 3), in place of the
 * full-width "0 of 1 habits today" tile: each chip is a ring to tick (it sweeps, fills and draws its check, as in
 * Goals) beside the habit's name, which dims once done. The chips rise in with Today's stagger and the row glides as
 * habits come and go; it scrolls sideways when they don't fit. Nothing shows when there are no habits today.
 * Reduced motion: the appearance cross-fades and the check is shown at once.
 */
@Composable
fun HabitChipsRow(chips: List<HabitChip>, play: Boolean, tick: (HabitChip) -> Unit, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    val colors = Meka.colors
    Row(
        modifier.testTag(HABIT_CHIPS_TAG).animateContentSize().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { i, chip ->
            key(chip.id) {
                Row(
                    Modifier.appear(rememberAppearance(i + 1, play))
                        .clip(RoundedCornerShape(MekaRadius.m))
                        .background(colors.surface)
                        .semantics(mergeDescendants = true) { contentDescription = chip.tickLabel }
                        .clickable(role = Role.Checkbox) { tick(chip) }
                        .padding(start = MekaSpace.xs, end = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    TickRing(chip.done, Modifier.size(20.dp).clip(CircleShape))
                    Text(
                        chip.title, style = MekaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            chip.done -> colors.textSecondary
                            chip.behind -> colors.accent
                            else -> colors.textPrimary
                        },
                    )
                    // An N-a-week habit's goes this week ("1/2"); a daily one has none.
                    chip.count?.let { Text(it, style = MekaType.caption, color = colors.textTertiary, maxLines = 1) }
                }
            }
        }
    }
}
