package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.MoneyBillRow
import os.meka.core.domain.MoneyBillsView

/**
 * Money redesign (B3), the Bills tab in the approved mockup's look (docs/design/money/Main.dc.html, `tab` Bills): one
 * raised card with what's still to go out before payday as the big number, how many bills, a thin bar of the month's
 * bills already paid and the line under it; then each bill of the month by date (a month/day block, the name and what
 * it is, the amount), the ones still to come first and the paid ones greyed. Everything it says is the core's
 * `MoneyBillsView`; this only draws it.
 *
 * Motion: the card and rows appear with the screen's stagger; the bar grows to its length (snaps with reduced motion);
 * the big number cross-fades when it changes.
 */
@Composable
internal fun MoneyBillsCard(b: MoneyBillsView, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    var shown by remember { mutableStateOf(if (reduced) b.paidShare else 0f) }
    LaunchedEffect(b.paidShare) { shown = b.paidShare }
    val bar by animateFloatAsState(shown, MekaMotion.appear(reduced), label = "money-bills-bar")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .semantics(mergeDescendants = true) { contentDescription = b.spoken }
            .testTag("money-bills-card")
            .padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(b.head, style = MekaType.caption, color = Meka.colors.textSecondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Crossfade(b.amount, animationSpec = MekaMotion.appear(reduced), label = "money-bills-amount") { a ->
                Text(a, style = MekaType.greeting, color = Meka.colors.textPrimary)
            }
            Text(b.count, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xxs))
        }
        if (b.paidLine != null) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)) {
                Box(Modifier.fillMaxWidth(bar.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
            }
            Text(b.paidLine!!, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}

/** One bill: the month and day stacked in a 44 dp block, the name and what it is, the amount; a paid one greyed. */
@Composable
internal fun MoneyBillRowView(r: MoneyBillRow, modifier: Modifier = Modifier) {
    val sub = when {
        r.paid -> Meka.colors.success
        r.subLit -> Meka.colors.accent
        else -> Meka.colors.textSecondary
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = r.spoken }
            .padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(r.month, style = MekaType.caption, color = Meka.colors.textTertiary, textAlign = TextAlign.Center)
            Text(r.day, style = MekaType.metaStrong, color = if (r.paid) Meka.colors.textTertiary else Meka.colors.textPrimary, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.taskTitle, color = if (r.paid) Meka.colors.textSecondary else Meka.colors.textPrimary, maxLines = 1)
            Text(r.sub, style = MekaType.caption, color = sub, maxLines = 1)
        }
        Text(r.amount, style = MekaType.metaStrong, color = if (r.paid) Meka.colors.textTertiary else Meka.colors.textPrimary)
    }
}
