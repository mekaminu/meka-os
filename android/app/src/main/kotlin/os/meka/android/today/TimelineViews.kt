package os.meka.android.today

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.TimelineRow
import os.meka.core.domain.UpNextEvent

/**
 * Today's timeline (calendar redesign, slice 1): events and planned tasks in one list with a now line and free gaps.
 * Events are context, not things to tick, so their titles use the regular body weight (build plan, type weight).
 * Motion: rows glide as the day moves on (animateItem); the now line's dot breathes; "3 earlier" unfolds in place.
 * Reduced motion: cross-fades only and a steady dot.
 */
internal val TIME_COLUMN = 92.dp

@Composable
internal fun TimeColumn(time: String, past: Boolean) {
    Text(
        time, style = MekaType.itemMeta,
        color = if (past) Meka.colors.textTertiary else Meka.colors.textSecondary,
        modifier = Modifier.width(TIME_COLUMN),
    )
}

/** An event: time on the left, title and where/which calendar under it. A running one is marked "Now". */
@Composable
internal fun TimelineEventRow(r: TimelineRow, past: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        TimeColumn(r.time, past)
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MekaType.body, color = if (past) Meka.colors.textTertiary else Meka.colors.textPrimary)
            val line = listOfNotNull(if (r.running) "Now" else null, r.detail).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MekaType.caption, color = if (r.running) Meka.colors.accent else Meka.colors.textTertiary)
            }
        }
    }
}

/** A free stretch: "1 h 30 free", quiet. */
@Composable
internal fun GapRow(r: TimelineRow, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        TimeColumn(r.time, past = true)
        Text(r.title, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** The now line: a breathing accent dot, the time, and a hairline across. */
@Composable
internal fun NowLine(r: TimelineRow, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val dotAlpha = if (reduced) 1f else {
        val t = rememberInfiniteTransition(label = "now-dot")
        val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "now-dot-a")
        a
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).semantics { contentDescription = "Now, ${r.time}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.time, style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.width(TIME_COLUMN))
        Box(Modifier.size(8.dp).alpha(dotAlpha).clip(CircleShape).background(Meka.colors.accent))
        Box(Modifier.weight(1f).height(1.dp).background(Meka.colors.accent.copy(alpha = 0.5f)))
    }
}

/** All-day events as chips above the timeline. */
@Composable
internal fun AllDayChips(events: List<CalendarEvent>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = MekaSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        events.forEach { e ->
            Text(
                e.title, style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xxs),
            )
        }
    }
}

/** "3 earlier ›": unfolds the events that have finished. */
@Composable
internal fun EarlierToggle(label: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.appear(Meka.reducedMotion), label = "earlier-turn")
    Row(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onToggle() }.padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TIME_COLUMN))
        Text(label, style = MekaType.caption, color = Meka.colors.textTertiary)
        Spacer(Modifier.width(MekaSpace.xxs))
        Text("›", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.rotate(turn))
    }
}

/** Up next's event line: "Call with Tunde in 25 min" with its time and place. */
@Composable
internal fun NextEventCard(e: UpNextEvent, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised).padding(MekaSpace.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Meka.colors.accent))
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            Text(e.line, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(e.detail, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}
