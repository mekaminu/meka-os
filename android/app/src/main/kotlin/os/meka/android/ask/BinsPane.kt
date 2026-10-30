package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.testTag
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
import os.meka.android.work.SettingChip
import os.meka.core.domain.BinAdded
import os.meka.core.domain.BinRow
import os.meka.core.domain.BinRules
import os.meka.core.domain.LocalClock
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val BINS_LIST_TAG = "bins-list"

/**
 * Ask → More → Bins (bin days, slice 1): each bin typed once with its day — "Black bin Tue fortnightly from 13 Oct",
 * "Food caddy every Tuesday" — a row per bin with its pattern and next collection (a day later after a bank holiday),
 * each with Remove. Slice 3: one-offs typed in the same field ("Black bin Sat 2 Jan instead of 29 Dec", "No black bin
 * 29 Dec") list under ONE-OFFS, each with Remove (the collection goes on its usual day again). The evening before, Today says "Black bin and food caddy out tonight" and a heads-up comes at 19:00.
 * Completeness: REMINDER's chips (17:00 to 21:00) move that heads-up, synced so the Mac reminds at the same time.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the line under the field
 * cross-fade (a refused line lit in the accent); rows stagger in 40 ms apart and arrive, move and leave with the list's
 * item motion; Add gives a light haptic (a tick when refused), Remove a tick. Reduced motion: cross-fades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BinsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.binsView.collectAsState()
    var said by remember { mutableStateOf<BinAdded?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "bins-said",
    )

    LazyColumn(
        Modifier.fillMaxSize().testTag(BINS_LIST_TAG),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("Bins", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.BINS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "bins-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(BinRules.ADD_HINT, description = BinRules.ADD_DESCRIPTION) { text ->
                    scope.launch {
                        val r = runCatching { core.addBin(text) }.getOrNull()
                        if (r?.refused == false) haptics.light() else haptics.tick()
                        said = r
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "bins-added") { r ->
                    r?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        if (v.rows.isNotEmpty()) {
            item(key = "label") {
                SectionLabel("YOUR BINS", Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "bin-${r.id}" }) { i, r ->
            BinRowView(r, Modifier.animateItem().appear(rememberAppearance(4 + i))) {
                haptics.tick()
                scope.launch { runCatching { core.removeBin(r.id) } }
            }
        }
        // Slice 3: the one-offs still to come ("Sat 2 Jan instead of Wed 30 Dec"), each with Remove to take it back.
        if (v.oneOffs.isNotEmpty()) {
            item(key = "one-offs") {
                SectionLabel("ONE-OFFS", Modifier.animateItem().padding(top = MekaSpace.m))
            }
        }
        itemsIndexed(v.oneOffs, key = { _, r -> "move-${r.id}" }) { i, r ->
            BinRowView(r, Modifier.animateItem().appear(rememberAppearance(4 + v.rows.size + i)), "Remove the one-off for ${r.name}, ${r.line}") {
                haptics.tick()
                scope.launch { runCatching { core.removeBinMove(r.id) } }
            }
        }
        // Completeness: when the evening reminder comes (synced, both devices); Today's line from 16:00 is unchanged.
        item(key = "remind-label") {
            SectionLabel("REMINDER", Modifier.animateItem().padding(top = MekaSpace.m))
        }
        item(key = "remind") {
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    v.remindChoices.forEach { m ->
                        val label = LocalClock.formatMinute(m)
                        SettingChip(label, m == v.remindMinute, "Bins reminder at $label", touch = true) {
                            haptics.tick()
                            scope.launch {
                                runCatching { core.setBinReminder(m) }.getOrNull()?.let { said = BinAdded(it, refused = false) }
                            }
                        }
                    }
                }
                Crossfade(v.remindLine, animationSpec = MekaMotion.appear(reduced), label = "bins-remind") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        item(key = "council") {
            Text(BinRules.COUNCIL, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun BinRowView(row: BinRow, modifier: Modifier, removeLabel: String = "Remove ${row.name}", onRemove: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.name, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            row.note?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
        }
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = removeLabel }
                // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                .minTouch().padding(horizontal = MekaSpace.xs),
        )
    }
}
