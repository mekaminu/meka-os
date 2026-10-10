package os.meka.android.today

import os.meka.android.designsystem.minTouch
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AlarmKind
import os.meka.core.domain.QuickAlarmItem
import os.meka.core.facade.MekaCore

/**
 * Quick alarms and timers typed into capture (Alarms, slice 2): one slim row each under Up next while they're still to
 * ring — "Pasta" · "20 min · ends 14:52 · 18 min left" — with a ✕ that cancels it on every device (tick haptic). The
 * detail moves on with Today's minute. Rows come and go with the expand spring (Motion Off: at once). Nothing shows
 * when none are set.
 */
@Composable
internal fun QuickAlarmRows(core: MekaCore, modifier: Modifier = Modifier) {
    val items by core.quickAlarms.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().animateContentSize(
            if (reduced) spring(stiffness = Spring.StiffnessHigh) else spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
        ),
    ) {
        items.forEach { item ->
            key(item.id) {
                QuickAlarmRow(item, onCancel = {
                    haptics.tick()
                    scope.launch { runCatching { core.cancelAlarm(item.id) } }
                })
            }
        }
        if (items.isNotEmpty()) Spacer(Modifier.height(MekaSpace.l))
    }
}

@Composable
private fun QuickAlarmRow(item: QuickAlarmItem, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A small brass ring for a timer, a filled dot for an alarm: quiet, like the calendar's dots.
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(if (item.kind == AlarmKind.TIMER) Meka.colors.accent.copy(alpha = 0.55f) else Meka.colors.accent),
        )
        Column(Modifier.weight(1f).padding(start = MekaSpace.m)) {
            Text(item.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1)
            Text(item.detail, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1)
        }
        Text(
            "✕",
            style = MekaType.body,
            color = Meka.colors.textSecondary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Button, onClick = onCancel)
                .semantics { contentDescription = item.cancelLabel }
                .minTouch().padding(horizontal = MekaSpace.m),
        )
    }
}
