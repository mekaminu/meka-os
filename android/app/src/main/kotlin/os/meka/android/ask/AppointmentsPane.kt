package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextOverflow
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
import os.meka.core.domain.AppointmentRow
import os.meka.core.domain.AppointmentRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Appointments (V1, appointment nudges slice 1): the dentist, the optician, a haircut… each with its
 * usual interval, due from the last visit. One field does it all: "Dentist every 6 months, last 12 Apr", "haircut
 * every 6 weeks", "dentist booked Tue 20 Oct 9:30". Each row says how often and when Meka last went, and where it
 * stands ("Due Mon 2 Nov", lit in the accent when due or not noted); Went notes a visit today, Remove takes it off.
 * "Common ones" adds the dentist, the optician, a haircut… in one tap. When one comes due Needs you asks once
 * ([os.meka.android.today.AppointmentCardView]). MEKA books nothing and calls no one.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the field's line
 * cross-fade (the line blends to the accent when refused, with a tick haptic; an add gives a light haptic); rows and
 * chips stagger in 40 ms apart and move with the list's item motion (a row moves up the list as it comes due); each
 * row's status cross-fades and blends to the accent. Reduced motion: cross-fades.
 *
 * Quality gate slice 16: a row's name and its "Every 6 months · last went Sat 12 Apr" take up to two lines each, so on
 * the cover screen (beside Went and Remove) they read whole instead of ending in "…".
 */
@Composable
fun AppointmentsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.appointmentsView.collectAsState()
    var text by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val saidColor by animateColorAsState(
        if (said?.second == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "appt-said",
    )
    fun show(line: String, refused: Boolean) {
        said = line to refused
        if (refused) haptics.tick() else haptics.light()
    }

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
            Text("Appointments", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.APPOINTMENTS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "appt-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(AppointmentRules.HINT, value = text, onValue = { text = it }) { typed ->
                    scope.launch {
                        val r = runCatching { core.typeAppointment(typed) }.getOrNull() ?: return@launch
                        show(r.line, r.refused)
                        if (r.refused) text = typed
                    }
                }
                Crossfade(said?.first, animationSpec = MekaMotion.appear(reduced), label = "appt-line") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        if (v.rows.isNotEmpty()) {
            item(key = "label-rows") {
                SectionLabel("Kept up with", Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(3)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "a-${r.id}" }) { i, r ->
            AppointmentRowView(
                r, Modifier.animateItem().appear(rememberAppearance(4 + i)),
                onWent = {
                    scope.launch { runCatching { core.appointmentWent(r.id) }.getOrNull()?.let { show(it.line, false) } }
                },
                onRemove = { haptics.tick(); scope.launch { runCatching { core.removeAppointment(r.id) } } },
            )
        }
        if (v.suggestions.isNotEmpty()) {
            item(key = "suggestions") {
                Column(Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(4 + v.rows.size)),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SectionLabel("Common ones")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        v.suggestions.forEach { s ->
                            Column(
                                Modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                                    .clickable(role = Role.Button) {
                                        scope.launch { runCatching { core.addAppointmentPreset(s.id) }.getOrNull()?.let { show(it.line, it.refused) } }
                                    }
                                    .semantics { contentDescription = "Add ${s.title}, ${s.line}" }
                                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                            ) {
                                Text("+ ${s.title}", style = MekaType.itemMeta, color = Meka.colors.accent)
                                Text(s.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                            }
                        }
                    }
                }
            }
        }
        item(key = "note") {
            Text(AppointmentRules.NOTE_SHOWN, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun AppointmentRowView(row: AppointmentRow, modifier: Modifier, onWent: () -> Unit, onRemove: () -> Unit) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (row.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "appt-status",
    )
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Crossfade(row.status, animationSpec = MekaMotion.appear(reduced), label = "appt-status-line") { s ->
                Text(s, style = MekaType.caption, color = statusColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            AppointmentRules.WENT_LABEL, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onWent)
                .semantics { contentDescription = "Went to ${row.title} today" }
                .padding(MekaSpace.xs),
        )
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                .padding(MekaSpace.xs),
        )
    }
}
