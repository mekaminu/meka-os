package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneyBillRow
import os.meka.core.domain.MoneyBillsView
import os.meka.core.domain.MoneyCardBrief
import os.meka.core.domain.MoneyPaydayBrief

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

/**
 * Money review (Meka 2026-10-26 22:12, Bills: "remove the inline forms"): his cards as one card of one-line rows, "Amex
 * Platinum · Due Fri 30 Oct · in 3 days" with the statement on the right (or "Due ? · tap to add"), each opening the card's
 * sheet. A lit line (due soon, near the limit, 0% ending, a fee) wears the accent.
 */
@Composable
internal fun MoneyCardsBriefCard(rows: List<MoneyCardBrief>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("money-cards-card"),
    ) {
        for (r in rows) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onOpen(r.id) }
                    .semantics(mergeDescendants = true) { contentDescription = r.spoken }
                    .minTouch().padding(vertical = MekaSpace.xs).testTag("money-card-${r.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MekaType.taskTitle, color = Meka.colors.textPrimary, maxLines = 1)
                    Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "money-card-brief-${r.id}") { l ->
                        Text(l, style = MekaType.caption, color = if (r.lit) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1)
                    }
                }
                r.amount?.let { a -> Text(a, style = MekaType.metaStrong, color = Meka.colors.textPrimary) }
                Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
            }
        }
    }
}

/** Money review, Bills: Until payday as one row in a card (payday over what's safe to spend), opening its sheet. */
@Composable
internal fun MoneyPaydayRow(b: MoneyPaydayBrief, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = b.spoken }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.s).testTag("money-payday-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f)) {
            Crossfade(b.title, animationSpec = MekaMotion.appear(reduced), label = "money-payday-title") { t ->
                Text(t, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1)
            }
            b.line?.let { l ->
                Crossfade(l, animationSpec = MekaMotion.appear(reduced), label = "money-payday-brief") { t ->
                    Text(t, style = MekaType.caption, color = if (b.lit) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 2)
                }
            }
        }
        Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
}

/**
 * A Bills sheet (a card's details, Until payday): rises from the bottom over a 50 % dim (motion catalogue: Sheets and
 * panes; reduced motion cross-fades), its title on top and a Done pill at the foot; a tap on the dim, Back or Done closes
 * it. It keeps showing the last [item] while it leaves.
 */
@Composable
internal fun <T : Any> MoneyDetailSheet(
    item: T?, title: (T) -> String, tag: String, done: String, onClose: () -> Unit, content: @Composable ColumnScope.(T) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val shown = remember { arrayOfNulls<Any>(1) }
    if (item != null) shown[0] = item
    BackHandler(enabled = item != null, onBack = onClose)
    AnimatedVisibility(visible = item != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        @Suppress("UNCHECKED_CAST")
        val it = item ?: (shown[0] as T?) ?: return@AnimatedVisibility
        val name = title(it)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .semantics { contentDescription = "Close $name" }
                    .testTag("$tag-dim"),
            )
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .animateEnterExit(
                        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else slideInVertically(MekaMotion.expand(false)) { h -> h / 3 },
                        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else slideOutVertically(MekaMotion.expand(false)) { h -> h / 3 },
                    )
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(MekaSpace.m)
                    .testTag(tag),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Text(name, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                content(it)
                Box(
                    Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                        .clickable(role = Role.Button, onClick = onClose).minTouch().padding(MekaSpace.xs).testTag("$tag-done"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(done, style = MekaType.metaStrong, color = Meka.colors.onAccent)
                }
            }
        }
    }
}
