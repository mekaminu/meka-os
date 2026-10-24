package os.meka.android.calendar

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.calendarTone
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BoardPlannerRules
import os.meka.core.domain.BoardTimeline
import os.meka.core.domain.BoardTimelineRules
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.DayBlock
import os.meka.core.domain.DayBlockKind
import kotlin.math.abs

/** The day timeline (Option B slice 5, UI tests). */
internal const val TIMELINE_TAG = "board-timeline"
internal const val TIMELINE_TAB_TAG = "board-timeline-tab"

/**
 * The Week board redesign, Option B slice 5: a day as an hour-by-hour timeline, the approved mockup's "Today" panel.
 * The heading ("Today" over "Mon 27 Oct"), all-day events as one quiet line, then the hours down the side with their
 * hairlines and the day's blocks laid over them: events in their calendar's tone, tasks planned at a time in gold (a
 * done one faded like its card), the commute in orange; overlapping blocks share the width. On today a gold line marks
 * now, and the timeline opens scrolled to an hour before it. A tap on an event opens it.
 *
 * [onSwipe] (the cover screen's tab): a swipe across steps a day (-1 earlier, +1 later). Motion: the blocks arrive with
 * the opening stagger and a new day cross-fades in (appear spec); Reduced motion: plain fades.
 */
@Composable
internal fun DayTimelineView(
    timeline: BoardTimeline,
    onEvent: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    onSwipe: ((Int) -> Unit)? = null,
) {
    val reduced = Meka.reducedMotion
    val density = LocalDensity.current
    val haptics = rememberMekaHaptics()
    val swipePx = with(density) { MekaSpace.xxl.toPx() }
    Column(
        modifier.testTag(TIMELINE_TAG)
            .then(
                if (onSwipe != null) Modifier.pointerInput(onSwipe) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (abs(total) >= swipePx) { haptics.tick(); onSwipe(if (total < 0) 1 else -1) } },
                    ) { change, amount -> total += amount; change.consume() }
                } else Modifier,
            ),
    ) {
        Crossfade(timeline, animationSpec = MekaMotion.appear(reduced), label = "timeline-day", contentKey = { it.epochDay }) { timeline ->
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = MekaSpace.xs).semantics(mergeDescendants = true) {
                        heading(); contentDescription = timeline.spoken
                    },
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(timeline.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (timeline.dateLabel != timeline.title) {
                        Text(timeline.dateLabel, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
                    }
                }
                timeline.allDayLine?.let {
                    Text(
                        it, style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = MekaSpace.xs).clearAndSetSemantics { },
                    )
                }
                timeline.emptyLine?.let {
                    Text(it, style = MekaType.micro, color = Meka.colors.textTertiary, modifier = Modifier.padding(bottom = MekaSpace.xs))
                }
                Hours(timeline, onEvent, Modifier.fillMaxWidth().weight(1f), reduced)
            }
        }
    }
}

private val LABEL_W = 36.dp

@Composable
private fun Hours(timeline: BoardTimeline, onEvent: (CalendarEvent) -> Unit, modifier: Modifier, reduced: Boolean) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val hourPx = with(density) { BoardTimelineRules.HOUR_DP.dp.toPx() }
    // Open an hour before now on today, else at the first block (or the top).
    LaunchedEffect(timeline.epochDay) {
        val focus = timeline.nowMinute ?: timeline.blocks.firstOrNull()?.startMinute ?: (timeline.firstHour * 60)
        val px = ((focus / 60f - 1f - timeline.firstHour) * hourPx).coerceAtLeast(0f)
        scroll.scrollTo(px.toInt())
    }
    val hairline = Meka.colors.hairline
    Box(modifier.verticalScroll(scroll)) {
        Box(Modifier.fillMaxWidth().height(timeline.heightDp.dp)) {
            timeline.hours.forEachIndexed { i, label ->
                Row(Modifier.fillMaxWidth().offset(y = (i * BoardTimelineRules.HOUR_DP).dp).height(BoardTimelineRules.HOUR_DP.dp)) {
                    Text(
                        label, style = MekaType.micro, color = Meka.colors.textTertiary,
                        modifier = Modifier.width(LABEL_W).padding(top = MekaSpace.xxs).clearAndSetSemantics { },
                    )
                    Box(Modifier.weight(1f).height(1.dp).background(hairline))
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = LABEL_W)) {
                val area = maxWidth
                timeline.blocks.forEachIndexed { i, b ->
                    val colW = area / b.columns
                    Block(
                        b, Modifier.offset(x = colW * b.column, y = b.topDp(timeline.firstHour).dp)
                            .width(colW - 2.dp).height(b.heightDp.dp)
                            .appear(rememberAppearance(minOf(i, 8))),
                        onEvent,
                    )
                }
                timeline.nowTopDp?.let { top ->
                    Box(Modifier.fillMaxWidth().offset(y = top.dp - 1.dp).height(2.dp).background(Meka.colors.accent).clearAndSetSemantics { })
                    Box(
                        Modifier.offset(x = (-4).dp, y = top.dp - 4.dp).size(8.dp).clip(CircleShape).background(Meka.colors.accent)
                            .clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

@Composable
private fun Block(b: DayBlock, modifier: Modifier, onEvent: (CalendarEvent) -> Unit) {
    val tone = when (b.kind) {
        DayBlockKind.EVENT -> Meka.colors.calendarTone(b.tone)
        DayBlockKind.TASK -> Meka.colors.accent
        DayBlockKind.COMMUTE -> Meka.colors.calendar3
    }
    val event = b.event
    Row(
        modifier.alpha(if (b.done) BoardPlannerRules.DONE_ALPHA else 1f)
            .clip(RoundedCornerShape(MekaRadius.s / 1.25f))
            .background(tone.copy(alpha = 0.16f))
            .then(if (event != null) Modifier.clickable(role = Role.Button) { onEvent(event) } else Modifier)
            .clearAndSetSemantics {
                contentDescription = b.spoken
                if (event != null) role = Role.Button
            },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(tone))
        Column(Modifier.padding(horizontal = MekaSpace.xs, vertical = if (b.oneLine) 1.dp else MekaSpace.xxs)) {
            Text(b.title, style = MekaType.micro, color = Meka.colors.textPrimary, maxLines = if (b.oneLine) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (!b.oneLine) Text(b.label, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
        }
    }
}

/**
 * The cover screen's Cards · Timeline switch (refinement 2): two pills under the day strip, the chosen one lit gold
 * (the colours blend on the appear spec, tick haptic). Each is a 48 dp target.
 */
@Composable
internal fun BoardFoldTabs(timeline: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val haptics = rememberMekaHaptics()
    Row(modifier.testTag(TIMELINE_TAB_TAG), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        listOf(false to BoardTimelineRules.TAB_CARDS, true to BoardTimelineRules.TAB_TIMELINE).forEach { (isTimeline, label) ->
            val on = isTimeline == timeline
            val bg by androidx.compose.animation.animateColorAsState(
                if (on) Meka.colors.accent.copy(alpha = 0.18f) else Meka.colors.surface, MekaMotion.appear(Meka.reducedMotion), label = "tab-bg",
            )
            Box(
                Modifier.heightIn(min = MekaSpace.touch).clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Tab) { if (!on) { haptics.tick(); onChange(isTimeline) } }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MekaType.caption, color = if (on) Meka.colors.accent else Meka.colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
            }
        }
    }
}
