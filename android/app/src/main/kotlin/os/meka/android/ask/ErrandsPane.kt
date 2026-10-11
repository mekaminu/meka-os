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
import os.meka.core.domain.ErrandAdded
import os.meka.core.domain.ErrandRow
import os.meka.core.domain.ErrandRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Errands (V1, errands by location slice 1): things that wait for a place, not a time. Type
 * "Tesco: milk, batteries" or "when I'm near the post office: parcel"; each place is a section with its errands, oldest
 * first. Done ticks one off (it moves to "Done lately", with Back); Remove takes it off for good. The reminder when the
 * Fold is near a place is slice 2.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the field's line
 * cross-fade (the line blends to the accent when refused, with a tick haptic; an add gives a light haptic); each
 * place's label and rows stagger in 40 ms apart and move with the list's item motion. Reduced motion: cross-fades.
 */
@Composable
fun ErrandsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.errandsView.collectAsState()
    var text by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<ErrandAdded?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "errand-said",
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
            Text("Errands", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ERRANDS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "errand-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(ErrandRules.HINT, value = text, onValue = { text = it }) { typed ->
                    scope.launch {
                        val r = runCatching { core.addErrand(typed) }.getOrNull() ?: return@launch
                        said = r
                        if (r.refused) { haptics.tick(); text = typed } else haptics.light()
                    }
                }
                Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "errand-line") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        var step = 3
        v.places.forEach { place ->
            val labelStep = step++
            item(key = "place-${place.key}") {
                SectionLabel(place.place, Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(labelStep)))
            }
            val base = step
            itemsIndexed(place.rows, key = { _, r -> "e-${r.id}" }) { i, r ->
                ErrandRowView(
                    r, Modifier.animateItem().appear(rememberAppearance(base + i)),
                    primary = "Done",
                    onPrimary = { haptics.light(); scope.launch { runCatching { core.errandDone(r.id, true) } } },
                    onRemove = { haptics.tick(); scope.launch { runCatching { core.removeErrand(r.id) } } },
                )
            }
            step += place.rows.size
        }
        if (v.done.isNotEmpty()) {
            val labelStep = step++
            item(key = "label-done") {
                SectionLabel("Done lately", Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(labelStep)))
            }
            val base = step
            itemsIndexed(v.done, key = { _, r -> "e-${r.id}" }) { i, r ->
                ErrandRowView(
                    r, Modifier.animateItem().appear(rememberAppearance(base + i)),
                    primary = "Back",
                    onPrimary = { haptics.tick(); scope.launch { runCatching { core.errandDone(r.id, false) } } },
                    onRemove = { haptics.tick(); scope.launch { runCatching { core.removeErrand(r.id) } } },
                )
            }
        }
        item(key = "note") {
            Text(ErrandRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun ErrandRowView(row: ErrandRow, modifier: Modifier, primary: String, onPrimary: () -> Unit, onRemove: () -> Unit) {
    val ink = if (row.done) Meka.colors.textSecondary else Meka.colors.textPrimary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.title, style = MekaType.itemTitle, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.meta?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            primary, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onPrimary)
                .semantics { contentDescription = "$primary: ${row.title}" }
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
