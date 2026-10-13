package os.meka.android.today

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaChoreography
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.HabitDot
import os.meka.core.domain.HeaderCollapseRules
import os.meka.core.domain.WatchFace
import os.meka.core.domain.WatchFaceRules
import os.meka.core.domain.WeekGoals

/** The compact bar's test tag (shown once Today has scrolled far enough). */
const val COMPACT_HEADER_TAG = "today-compact-header"

/** The pinned face's own top padding in the compact bar, and the bar's height around the [HeaderCollapseRules.COMPACT_DP] clock. */
internal val COMPACT_BAR_PAD = MekaSpace.xs
internal val COMPACT_BAR_HEIGHT = MekaSpace.xs * 2 + HeaderCollapseRules.COMPACT_DP.dp

/**
 * Today's collapsing header on the closed Fold's cover screen (watch face item, part 2; [HeaderCollapseRules]). The
 * face sits pinned over the header's right-hand slot (the header row keeps an empty slot its size, so the lines wrap as
 * before) and shrinks with the scroll, about its top-right corner, into a [HeaderCollapseRules.COMPACT_DP] clock, rising
 * from the header's top to the slim bar's. Over the last part of the glide the bar fades in behind it: the greeting and
 * one line ("Tue 13 Oct · 14°"); tapping the bar scrolls Today back to the top, tapping the clock opens the whole day.
 * It is one face throughout, so the clock keeps ticking and TalkBack reads the time once (the face's own line, minute
 * by minute); the habit dots stop taking taps once it's compact. Reduced motion: [shown] is 0 or 1, no glide.
 */
@Composable
internal fun CollapsingHeader(
    shown: Float,
    face: WatchFace,
    fullDp: Int,
    greeting: String,
    line: String,
    play: DayRingPlay,
    played: () -> Unit,
    onOpenFace: () -> Unit,
    night: Boolean,
    week: WeekGoals,
    dots: List<HabitDot>,
    onTick: ((HabitDot) -> Unit)?,
    onTop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = Meka.reducedMotion
    val faceAlpha by animateFloatAsState(
        WatchFaceRules.nightAlpha(night),
        if (reduced) MekaMotion.appear<Float>(true) else tween<Float>(MekaChoreography.nightFallMs),
        label = "collapsing-face-night",
    )
    val bar = HeaderCollapseRules.barAlpha(shown)
    val scale = HeaderCollapseRules.faceScale(shown, fullDp)
    val top = HeaderCollapseRules.faceTopDp(shown, MekaSpace.xl.value, COMPACT_BAR_PAD.value)
    // Read first by TalkBack: the header is above the list on screen.
    Box(modifier.fillMaxWidth().semantics { isTraversalGroup = true; traversalIndex = -1f }) {
        if (HeaderCollapseRules.barShown(shown)) {
            Column(
                Modifier.fillMaxWidth()
                    .graphicsLayer { alpha = bar }
                    .background(Meka.colors.background)
                    .clickable(role = Role.Button, onClickLabel = "Back to the top of Today") { onTop() }
                    .heightIn(min = COMPACT_BAR_HEIGHT)
                    .padding(start = MekaSpace.gutter, end = MekaSpace.gutter + HeaderCollapseRules.COMPACT_DP.dp + MekaSpace.m,
                        top = COMPACT_BAR_PAD, bottom = COMPACT_BAR_PAD)
                    .testTag(COMPACT_HEADER_TAG),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                // One line each, however large the system font: the bar never grows past its slim height by much.
                Text(greeting, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line.isNotEmpty()) {
                    Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        WatchFaceDial(
            face, play, played,
            Modifier.align(Alignment.TopEnd)
                .offset(y = top.dp)
                .padding(end = MekaSpace.gutter)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(1f, 0f)
                    scaleX = scale
                    scaleY = scale
                    alpha = faceAlpha
                },
            size = fullDp.dp, onOpen = onOpenFace, dots = dots,
            onTick = onTick.takeIf { HeaderCollapseRules.dotsTappable(shown) },
            night = night, week = week,
        )
    }
}
