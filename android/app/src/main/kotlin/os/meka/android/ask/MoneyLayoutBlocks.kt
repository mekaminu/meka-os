package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.MoneyMonthReviewView
import os.meka.core.domain.MoneyTabs

/**
 * Money's layout rules (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped… make it easy on
 * the eye"), first on Spending: (a) a section is a **card** (rounded, the surface on black, 16 dp in) under its small caps
 * label; (b) the month in review is **numbers, not sentences**: Spent · Came in · Left as tiles and at most two short
 * lines; (c) help and where the numbers came from sit **behind a small ⓘ**, and "read-only · not financial advice" is said
 * **once, at the foot** of the tab. Every word is the core's ([MoneyMonthReviewView], [MoneyTabs]).
 *
 * Motion: a card appears with its section (the caller's appear stagger); the review's lines cross-fade when he pages to
 * another month (MekaMotion.appear honours reduced motion); ⓘ presses like every control and its sheet rises on the
 * expand spring ([MoneyDetailSheet]).
 */
@Composable
internal fun MoneyCard(modifier: Modifier = Modifier, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .let { m -> tag?.let { m.testTag(it) } ?: m }.padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        content = content,
    )
}

/**
 * A card's label as the approved mockup draws it (Money's mockup check, item 8): small, letter-spaced caps in the
 * secondary text colour at regular weight ([MekaType.cardLabel]), so the label reads quietly over its card's numbers
 * rather than as a dim grey caption. Every Money tab's card labels use it; [color] for the money check's brass one.
 */
@Composable
internal fun MoneyLabel(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Meka.colors.textSecondary) {
    Text(text, style = MekaType.cardLabel, color = color, modifier = modifier.semantics { heading() })
}

/** The small ⓘ that opens a card's help or where its numbers came from. */
@Composable
internal fun MoneyInfoButton(spoken: String, tag: String, onClick: () -> Unit) {
    Text(
        MoneyTabs.INFO, style = MekaType.body, color = Meka.colors.textTertiary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }.minTouch().testTag(tag),
    )
}

/** A card's small caps label with its ⓘ on the right. */
@Composable
internal fun MoneyHeadWithInfo(head: String, spoken: String, tag: String, modifier: Modifier = Modifier, onInfo: () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MoneyLabel(head, modifier = Modifier.weight(1f))
        MoneyInfoButton(spoken, tag, onInfo)
    }
}

/** The month in review as one card: its label and ⓘ, the three tiles, then at most two short lines. */
@Composable
internal fun MoneyReviewCard(rv: MoneyMonthReviewView, modifier: Modifier = Modifier, onInfo: () -> Unit) {
    val reduced = Meka.reducedMotion
    MoneyCard(modifier, tag = "money-review-card") {
        MoneyHeadWithInfo(rv.head, "About ${rv.infoTitle.lowercase()}", "money-review-info", onInfo = onInfo)
        if (rv.tiles.isNotEmpty()) MoneyTiles(rv.tiles)
        Crossfade(rv.lines, animationSpec = MekaMotion.appear(reduced), label = "money-review-lines") { lines ->
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                lines.forEach { l -> Text(l, style = MekaType.caption, color = Meka.colors.textSecondary) }
            }
        }
    }
}

/** The tab's one foot line, "Read-only · not financial advice", with its ⓘ. */
@Composable
internal fun MoneyTabFoot(modifier: Modifier = Modifier, text: String = MoneyTabs.FOOT_SHORT, onInfo: () -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(text, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
        MoneyInfoButton(MoneyTabs.INFO_SPOKEN, "money-foot-info", onInfo)
    }
}

/** What an ⓘ sheet says: [title] on top (the sheet draws it) and its lines, the first as the lead. */
internal data class MoneyInfo(val title: String, val lines: List<String>)

@Composable
internal fun MoneyInfoLines(info: MoneyInfo) {
    info.lines.forEachIndexed { i, l ->
        Text(l, style = if (i == 0) MekaType.body else MekaType.caption,
            color = if (i == 0) Meka.colors.textPrimary else Meka.colors.textSecondary)
    }
}

/**
 * Money's top as the mockup ([os.meka.core.domain.MoneyHeader]): the Updated pill on the title row, a small refresh
 * glyph and "Updated 06:10" on the raised surface; a tap asks the banks for a sync (the words cross-fade to "Updating…"
 * and back on the appear spring; reduced motion swaps them). A 48 dp target; a screen reader hears "Updated 06:10, refresh".
 */
@Composable
internal fun MoneyUpdatedPill(text: String, modifier: Modifier = Modifier, onRefresh: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onRefresh).minTouch()
            .semantics(mergeDescendants = true) { contentDescription = os.meka.core.domain.MoneyHeader.spoken(text) }
            .testTag("money-updated"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text("↻", style = MekaType.caption, color = Meka.colors.textSecondary)
            Crossfade(text, animationSpec = MekaMotion.appear(reduced), label = "money-updated") { t ->
                Text(t, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1)
            }
        }
    }
}
