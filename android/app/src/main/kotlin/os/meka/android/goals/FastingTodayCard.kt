package os.meka.android.goals

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.FastingScreenRules
import os.meka.core.domain.FastingTodayCard
import os.meka.core.facade.MekaCore

/** The Today card's test tag. */
const val FASTING_TODAY_TAG = "fasting-today"

/** Today's live Fasting card while a fast runs (core [FastingScreenRules]); null when not fasting. Ticks with the clock. */
@Composable
fun rememberFastingTodayCard(core: MekaCore): FastingTodayCard? {
    val v by core.fastingView.collectAsState()
    val now = rememberFastClock(core, v.current != null)
    return remember(v, now) { if (v.current == null) null else core.fastingScreen(now).todayCard }
}

/**
 * Today's live Fasting card (Fasting rebuild slice 4, the approved design docs/design/fasting/today-card.dc.html): a
 * small gold ring with the time in, "FASTING" over the stage the fast is in, "Ends in 2:30 · 12:00" in gold, and an
 * End pill. A tap on the card opens the Fasting screen; End ends the fast with "Fast ended · Undo" on Today's bar.
 * Same card on the cover screen and unfolded.
 *
 * Motion: it rises in with Today's cards and leaves with the list's item motion; the ring sweeps with the clock (one-second
 * linear glide); End gives a light haptic. Reduced motion: the ring is redrawn each tick, no glide.
 */
@Composable
fun FastingTodayCardView(
    card: FastingTodayCard, open: () -> Unit, end: (FastingTodayCard) -> Unit, modifier: Modifier = Modifier,
) {
    val colors = Meka.colors
    val reduced = Meka.reducedMotion
    val sweep by animateFloatAsState(card.fraction, if (reduced) snap() else tween(1_000, easing = LinearEasing), label = "today-fast-sweep")
    val haptics = rememberMekaHaptics()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(colors.surface)
            .clickable(role = Role.Button) { haptics.light(); open() }
            .padding(MekaSpace.m)
            .testTag(FASTING_TODAY_TAG)
            .semantics { contentDescription = card.spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Box(Modifier.size(RING), contentAlignment = Alignment.Center) {
            val accent = colors.accent
            val track = colors.surfaceRaised
            Canvas(Modifier.size(RING)) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                drawArc(accent, -90f, 360f * sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(card.time, style = MekaType.metaStrong.copy(fontFeatureSettings = "tnum"), color = colors.textPrimary, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(card.label.uppercase(), style = MekaType.sectionLabel, color = colors.textSecondary, maxLines = 1)
            Text(card.stage, style = MekaType.itemTitle, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.line, style = MekaType.caption.copy(fontFeatureSettings = "tnum"), color = colors.accent)
        }
        Text(
            card.endLabel, style = MekaType.captionStrong, color = colors.accent,
            modifier = Modifier.testTag("$FASTING_TODAY_TAG-end")
                .clickable(role = Role.Button) { haptics.light(); end(card) }
                .minTouch()
                .border(1.dp, colors.accent, RoundedCornerShape(MekaRadius.pill))
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        )
    }
}

/** Ends the fast from Today's card, with Undo on the screen's bar (Undo brings the same fast back). */
@Composable
fun rememberFastingTodayEnd(core: MekaCore, undo: os.meka.android.calendar.EventUndo?): (FastingTodayCard) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(core, undo) {
        { card ->
            scope.launch {
                if (runCatching { core.endFast() }.isSuccess) undo?.show(FastingScreenRules.TODAY_ENDED) { core.resumeFast(card.fastId) }
            }
        }
    }
}

private val RING = 76.dp
