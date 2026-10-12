package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.lists.Field
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.TripAdded
import os.meka.core.domain.TripRow
import os.meka.core.domain.TripRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Trips (travel mode, slice 1): one field for trips and their bookings — "Barcelona 14–18 Nov", then
 * "Flight BA478 Sat 14 Nov 07:40 ref ABC123", "Hotel Arts 14–18 Nov" — a row per trip (on now, then to come, then
 * just ended) with its dates and status. Tap a row to open its day-by-day timeline: each booking with its time (or its
 * kind) and × to remove it, and Remove for the trip. Today's header shows the trip a week ahead and while it's on.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the line under the field
 * cross-fade (a refused line lit in the accent); rows stagger in 40 ms apart and arrive, move and leave with the list's
 * item motion; an opened row grows on the expand spring and its days fade in; the status blends to the accent while the
 * trip is on; Add gives a light haptic (a tick when refused), Remove and × a tick. Reduced motion: cross-fades.
 */
@Composable
fun TripsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.tripsView.collectAsState()
    var said by remember { mutableStateOf<TripAdded?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trips-said",
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("Trips", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.TRIPS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "trips-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(TripRules.ADD_HINT) { text ->
                    scope.launch {
                        val r = runCatching { core.addTrip(text) }.getOrNull()
                        if (r?.refused == false) haptics.light() else haptics.tick()
                        said = r
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "trips-added") { r ->
                    r?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        if (v.rows.isNotEmpty()) {
            item(key = "label") {
                SectionLabel("YOUR TRIPS", Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "trip-${r.id}" }) { i, r ->
            TripRowView(
                row = r,
                open = open == r.id,
                modifier = Modifier.animateItem().appear(rememberAppearance(4 + i)),
                onToggle = { open = if (open == r.id) null else r.id },
                onRemoveBooking = { id -> haptics.tick(); scope.launch { runCatching { core.removeTripBooking(id) } } },
                onRemove = { haptics.tick(); open = null; scope.launch { runCatching { core.removeTrip(r.id) } } },
            )
        }
        item(key = "note") {
            Text(TripRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun TripRowView(
    row: TripRow,
    open: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onRemoveBooking: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (row.statusLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trip-status",
    )
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(reduced))
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).minTouch()
                .semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(row.name, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(row.status, style = MekaType.caption, color = statusColor)
        }
        AnimatedVisibility(
            visible = open,
            enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                if (row.timeline.all { it.lines.isEmpty() }) {
                    Text(TripRules.NO_BOOKINGS, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                for (day in row.timeline) {
                    if (day.lines.isEmpty()) continue
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        SectionLabel(day.label.uppercase())
                        for (line in day.lines) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                            ) {
                                Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = line.spoken }) {
                                    Text(line.lead, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.width(56.dp))
                                    Text(line.text, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                                }
                                Text("×", style = MekaType.body, color = Meka.colors.textTertiary,
                                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                        .clickable(role = Role.Button) { onRemoveBooking(line.bookingId) }
                                        .semantics { contentDescription = "Remove ${line.text}" }
                                        .minTouch().padding(MekaSpace.xs))
                            }
                        }
                    }
                }
                Text(
                    "Remove trip", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button, onClick = onRemove)
                        .semantics { contentDescription = "Remove ${row.name} and its bookings" }
                        .minTouch().padding(MekaSpace.xs),
                )
            }
        }
    }
}
