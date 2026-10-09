package os.meka.android.work

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.WorkDayRow
import os.meka.core.domain.WorkSchedule

/**
 * Work mode → Hours → each day (Places item 1): one row per work day ("Thu · 09:00–15:30 · own hours", lit when a
 * day has its own hours); tapping a row unfolds its Start/End steppers in place (expand spring, the chevron turns,
 * tick haptic); "Same as usual" puts the day back on the usual hours. Reduced motion: cross-fades.
 */
@Composable
internal fun WorkDayHoursSection(
    schedule: WorkSchedule,
    modifier: Modifier,
    onSet: (isoDay: Int, startMinute: Int, endMinute: Int) -> Unit,
    onClear: (isoDay: Int) -> Unit,
) {
    if (!schedule.enabled || schedule.days.isEmpty()) return
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val haptics = rememberMekaHaptics()
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text("Each day", style = MekaType.caption, color = Meka.colors.textTertiary)
        schedule.weekRows.forEach { row ->
            DayHoursRow(row, open == row.isoDay,
                onToggle = { haptics.tick(); open = if (open == row.isoDay) null else row.isoDay },
                onSet = { a, b -> haptics.tick(); onSet(row.isoDay, a, b) },
                onClear = { haptics.tick(); onClear(row.isoDay) })
        }
    }
}

@Composable
private fun DayHoursRow(row: WorkDayRow, open: Boolean, onToggle: () -> Unit, onSet: (Int, Int) -> Unit, onClear: () -> Unit) {
    val reduced = Meka.reducedMotion
    val lineColor by animateColorAsState(if (row.own) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "day-hours-line")
    val chevron by animateFloatAsState(if (open) 90f else 0f, if (reduced) MekaMotion.appear<Float>(true) else MekaMotion.expand<Float>(false), label = "day-hours-chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { onToggle() }
                .semantics { contentDescription = "${row.name}: ${row.line}" }.padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.dayShort, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.width(48.dp))
            Crossfade(row.line, Modifier.weight(1f), MekaMotion.appear(reduced), label = "day-hours-text") { line ->
                Text(line, style = MekaType.itemMeta, color = lineColor)
            }
            Text("›", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.rotate(chevron))
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = MekaSpace.m)) {
                TimeStepper("${row.dayShort} start", row.startMinute, Modifier) { onSet(it, row.endMinute) }
                TimeStepper("${row.dayShort} end", row.endMinute, Modifier) { onSet(row.startMinute, it) }
                if (row.own) {
                    Text("Same as usual", style = MekaType.caption, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { onClear() }.padding(vertical = MekaSpace.xxs))
                }
            }
        }
    }
}
