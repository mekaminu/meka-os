package os.meka.android.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import os.meka.core.domain.HabitItem
import os.meka.core.domain.HabitPace
import os.meka.core.domain.MeanwhileLine
import os.meka.core.domain.NeedsYouMeanwhile
import os.meka.core.domain.NeedsYouMeanwhileRules

/**
 * Under "Nothing needs you": today's habits (the tick ring draws its check as in Goals, light haptic), Waiting on and
 * the next renewals (a tap opens Lists). Each section staggers in after the empty line (the list's item motion), rows
 * glide with `animateItem`. Reduced motion: the appearance cross-fades.
 */
internal fun LazyListScope.meanwhileItems(
    m: NeedsYouMeanwhile, play: Boolean, firstIndex: Int, tick: (HabitItem) -> Unit, open: () -> Unit,
) {
    var index = firstIndex
    if (m.habits.isNotEmpty()) {
        val i = index++
        item(key = "mw-habits") {
            MeanwhileLabel(NeedsYouMeanwhileRules.HABITS_LABEL, m.habitsLine, Modifier.animateItem().appear(rememberAppearance(i, play)))
        }
        items(m.habits, key = { "mw-h-${it.id}" }) { h ->
            MeanwhileHabit(h, { tick(h) }, Modifier.animateItem().appear(rememberAppearance(i, play)))
        }
    }
    if (m.waiting.isNotEmpty()) {
        val i = index++
        item(key = "mw-waiting") {
            MeanwhileLabel(NeedsYouMeanwhileRules.WAITING_LABEL, null, Modifier.animateItem().appear(rememberAppearance(i, play)))
        }
        items(m.waiting, key = { "mw-w-${it.id}" }) { l -> MeanwhileRow(l, open, Modifier.animateItem().appear(rememberAppearance(i, play))) }
        m.waitingMore?.let { more -> item(key = "mw-w-more") { MeanwhileMore(more, open, Modifier.animateItem()) } }
    }
    if (m.renewals.isNotEmpty()) {
        val i = index
        item(key = "mw-renewals") {
            MeanwhileLabel(NeedsYouMeanwhileRules.RENEWALS_LABEL, null, Modifier.animateItem().appear(rememberAppearance(i, play)))
        }
        items(m.renewals, key = { "mw-r-${it.id}" }) { l -> MeanwhileRow(l, open, Modifier.animateItem().appear(rememberAppearance(i, play))) }
        m.renewalsMore?.let { more -> item(key = "mw-r-more") { MeanwhileMore(more, open, Modifier.animateItem()) } }
    }
}

@Composable
private fun MeanwhileLabel(text: String, line: String?, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(top = MekaSpace.l), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(text, Modifier.weight(1f))
        line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(bottom = MekaSpace.xxs)) }
    }
}

/** A habit to tick: the ring (drawn check, pop) and its title in the action weight. */
@Composable
private fun MeanwhileHabit(h: HabitItem, tick: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .semantics(mergeDescendants = true) { contentDescription = NeedsYouMeanwhileRules.tickLabel(h) }
            .clickable(role = Role.Checkbox) { tick() }
            .padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickRing(h.doneToday, Modifier.size(24.dp).clip(CircleShape))
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MekaType.itemTitle, color = if (h.doneToday) Meka.colors.textSecondary else Meka.colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.meta.isNotEmpty()) {
                Text(h.meta, style = MekaType.caption, color = if (h.pace == HabitPace.BEHIND) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1)
            }
        }
    }
}

/** Waiting on / a renewal: context, so body weight; a tap opens Lists. */
@Composable
private fun MeanwhileRow(l: MeanwhileLine, open: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClickLabel = "Open Lists") { open() }
            .padding(vertical = MekaSpace.xs),
    ) {
        Text(l.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(l.meta, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MeanwhileMore(text: String, open: () -> Unit, modifier: Modifier) {
    Text(text, style = MekaType.caption, color = Meka.colors.accent,
        modifier = modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClickLabel = "Open Lists") { open() }
            .padding(vertical = MekaSpace.xxs))
}
