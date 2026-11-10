package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.wordsTap
import os.meka.core.domain.NeedsYouChip
import os.meka.core.domain.NeedsYouFilter
import os.meka.core.domain.NeedsYouFyiRow
import os.meka.core.domain.NeedsYouLayoutRules
import os.meka.core.domain.NeedsYouMessageRow

/*
 * The approved Needs you look (Meka 2026-11-09 22:33, docs/design/needs-you/needs-you.dc.html), slice 1 on the Fold:
 * the header with its counts and Clear FYI, the filter chips, MESSAGES as one list of rows that open in place to the
 * chat's own card, and FYI as thin rows with a tick. The cards inside keep every tap they had.
 */

internal const val NEEDS_YOU_CHIPS_TAG = "needs-you-chips"
internal const val NEEDS_YOU_ROW_TAG = "needs-you-row"
internal const val NEEDS_YOU_FYI_TAG = "needs-you-fyi"

/** "Needs you" over "2 to decide · 4 to reply · 4 FYI", with Clear FYI on the right while there is FYI. */
@Composable
internal fun NeedsYouHeader(subtitle: String, showClear: Boolean, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Needs you", style = MekaType.greeting, color = Meka.colors.textPrimary)
            Text(subtitle, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
        }
        if (showClear) {
            Text(
                NeedsYouLayoutRules.CLEAR_FYI, style = MekaType.captionStrong, color = Meka.colors.textSecondary,
                modifier = Modifier.minTouch().wordsTap(onClick = onClear),
            )
        }
    }
}

/** All · Decide 2 · Reply 4 · FYI 4 · Mail: one sliding line of chips, the chosen one in gold. */
@Composable
internal fun NeedsYouChips(chips: List<NeedsYouChip>, chosen: NeedsYouFilter, onPick: (NeedsYouFilter) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(NEEDS_YOU_CHIPS_TAG),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        chips.forEach { c ->
            val on = c.filter == chosen
            val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surface, MekaMotion.themeBlend(Meka.reducedMotion), label = "ny-chip-bg")
            val fg by animateColorAsState(if (on) Meka.colors.onAccent else Meka.colors.textPrimary, MekaMotion.themeBlend(Meka.reducedMotion), label = "ny-chip-fg")
            Box(
                Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                    .clickable(role = Role.RadioButton) { if (!on) { haptics.light(); onPick(c.filter) } }
                    .semantics { selected = on }
                    .padding(horizontal = MekaSpace.m),
                contentAlignment = Alignment.Center,
            ) { Text(c.label, style = if (on) MekaType.captionStrong else MekaType.caption, color = fg, maxLines = 1) }
        }
    }
}

/** A section's quiet label: "MESSAGES · 4". */
@Composable
internal fun NeedsYouHead(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = modifier.padding(top = MekaSpace.xs, bottom = MekaSpace.xxs))
}

/**
 * One person or group: the avatar's letters, the name and time, the one-line gist and the badge; a tap opens the
 * chat's own card under it (expand spring + fade; fades only with reduced motion), a second tap closes it.
 */
@Composable
internal fun NeedsYouMessageRowView(
    row: NeedsYouMessageRow, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Close" else "Open") { haptics.light(); onToggle() }
                .semantics(mergeDescendants = true) { contentDescription = row.spoken; stateDescription = if (open) "Open" else "Closed" }
                .testTag(NEEDS_YOU_ROW_TAG)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Meka.colors.surfaceRaised), contentAlignment = Alignment.Center) {
                Text(row.initials, style = MekaType.captionStrong, color = Meka.colors.accent, maxLines = 1)
            }
            Spacer(Modifier.width(MekaSpace.xs))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, style = MekaType.taskTitle, color = Meka.colors.textPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text(row.time, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1,
                        modifier = Modifier.padding(start = MekaSpace.xs))
                }
                Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(MekaSpace.xs))
            Text(
                row.badge, style = MekaType.micro, maxLines = 1,
                color = if (row.hot) Meka.colors.approval else Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
            )
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = MekaSpace.xs, end = MekaSpace.xs, bottom = MekaSpace.xs)) { content() }
        }
    }
}

/** A chat needing nothing: who, what, and a ✓ that clears it (Done, with Undo on the bar). */
@Composable
internal fun NeedsYouFyiRowView(row: NeedsYouFyiRow, onSeen: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken }.testTag(NEEDS_YOU_FYI_TAG)
            .padding(start = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.who, style = MekaType.captionStrong, color = Meka.colors.textPrimary, maxLines = 1,
            modifier = Modifier.padding(end = MekaSpace.xs))
        Text(row.what, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Box(
            Modifier.minTouch().clip(CircleShape).clickable(onClickLabel = NeedsYouLayoutRules.SEEN) { haptics.light(); onSeen() },
            contentAlignment = Alignment.Center,
        ) { Text("✓", style = MekaType.captionStrong, color = Meka.colors.textTertiary) }
    }
}
