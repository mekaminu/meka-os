package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import os.meka.core.domain.SchoolRow
import os.meka.core.domain.SchoolRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → School (school rhythm, slice 1): Rex's and Logan's school year, typed once, one line each — "INSET
 * 27 Oct", "Half term 26–30 Oct", "Rex PE Tue", "Logan's trip 20 Nov" — in three groups: days off, dates and every
 * week, each row with Remove. A week before a day off that falls on an office day, Needs you asks who's covering.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the line under the field
 * cross-fade (the "couldn't read" line lit in the accent); rows stagger in 40 ms apart and arrive, move and leave with
 * the list's item motion; Add gives a light haptic, Remove a tick. Reduced motion: cross-fades.
 */
@Composable
fun SchoolPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.schoolView.collectAsState()
    // The line under the field after Add: what was added, or that nothing could be read (kept while the pane is open).
    var said by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (said == SchoolRules.NOT_READ) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "school-said",
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
            Text("School", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.SCHOOL)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "school-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(SchoolRules.ADD_HINT) { text ->
                    haptics.light()
                    scope.launch { said = runCatching { core.addSchool(text) }.getOrNull() ?: SchoolRules.NOT_READ }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "school-added") { line ->
                    line?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        val remove: (SchoolRow) -> Unit = { row -> haptics.tick(); scope.launch { runCatching { core.removeSchool(row.id) } } }
        group("Days off", "off", v.off, 3, remove)
        group("Dates", "dates", v.dates, 3 + v.off.size, remove)
        group("Every week", "weekly", v.weekly, 3 + v.off.size + v.dates.size, remove)
        item(key = "shared") {
            Text(SchoolRules.SHARED, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

private fun LazyListScope.group(label: String, key: String, rows: List<SchoolRow>, firstStep: Int, remove: (SchoolRow) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "label-$key") {
        SectionLabel(label.uppercase(), Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(firstStep)))
    }
    itemsIndexed(rows, key = { _, r -> "school-${r.id}" }) { i, r ->
        SchoolRowView(r, Modifier.animateItem().appear(rememberAppearance(firstStep + 1 + i))) { remove(r) }
    }
}

@Composable
private fun SchoolRowView(row: SchoolRow, modifier: Modifier, onRemove: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            row.note?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
        }
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                .padding(MekaSpace.xs),
        )
    }
}
