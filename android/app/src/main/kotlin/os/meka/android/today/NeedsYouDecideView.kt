package os.meka.android.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.NeedsYouDecideCard
import os.meka.core.domain.NeedsYouDecideIcon

internal const val NEEDS_YOU_DECIDE_TAG = "needs-you-decide"

/**
 * A TO DECIDE card in the approved look (`docs/design/needs-you/needs-you.dc.html`): a 34 dp icon tile, what it is
 * over who it's from (one line), then Add · Change · No. The taps are the request card's own (light haptic on Add and
 * Change, a tick on No); the card folds away with the list's item motion and the undo bar rises. Nothing is sent.
 */
@Composable
internal fun NeedsYouDecideCardView(card: NeedsYouDecideCard, onChoice: (RequestChoice) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .testTag(NEEDS_YOU_DECIDE_TAG).padding(horizontal = MekaSpace.m, vertical = MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = card.spoken }, verticalAlignment = Alignment.CenterVertically) {
            DecideIcon(card.icon)
            Column(Modifier.weight(1f).padding(start = MekaSpace.xs)) {
                Text(card.what, style = MekaType.taskTitle, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(card.from, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            DecidePill(card.yesLabel, DecideTone.FILLED, "${card.yesLabel}: ${card.what}") { haptics.light(); onChoice(RequestChoice.ADD) }
            card.changeLabel?.let { label ->
                DecidePill(label, DecideTone.PLAIN, "$label ${card.what}") { haptics.light(); onChoice(RequestChoice.CHANGE) }
            }
            DecidePill(card.noLabel, DecideTone.QUIET, "${card.noLabel}: ${card.what}") { haptics.tick(); onChoice(RequestChoice.DECLINE) }
        }
    }
}

private enum class DecideTone { FILLED, PLAIN, QUIET }

@Composable
private fun DecidePill(label: String, tone: DecideTone, spoken: String, onClick: () -> Unit) {
    val bg = when (tone) { DecideTone.FILLED -> Meka.colors.accent; DecideTone.PLAIN -> Meka.colors.surfaceRaised; DecideTone.QUIET -> Color.Transparent }
    val fg = when (tone) { DecideTone.FILLED -> Meka.colors.onAccent; DecideTone.PLAIN -> Meka.colors.textPrimary; DecideTone.QUIET -> Meka.colors.textSecondary }
    Box(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = if (tone == DecideTone.QUIET) MekaSpace.xs else MekaSpace.m),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = if (tone == DecideTone.FILLED) MekaType.captionStrong else MekaType.caption, color = fg, maxLines = 1) }
}

@Composable
private fun DecideIcon(icon: NeedsYouDecideIcon) {
    val ink = when (icon) {
        NeedsYouDecideIcon.HOME -> Meka.colors.success
        NeedsYouDecideIcon.TASK, NeedsYouDecideIcon.IDEA -> Meka.colors.accent
        NeedsYouDecideIcon.EVENT, NeedsYouDecideIcon.TRIP -> Meka.colors.calendar1
        NeedsYouDecideIcon.REMINDER, NeedsYouDecideIcon.RENEWAL -> Meka.colors.approval
        NeedsYouDecideIcon.REPLY -> Meka.colors.textSecondary
    }
    val path = remember(icon) { PathParser().parsePathString(icon.path).toPath() }
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                drawPath(path, ink, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
