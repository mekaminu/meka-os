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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import os.meka.core.domain.EmergencyAdded
import os.meka.core.domain.EmergencyRow
import os.meka.core.domain.EmergencyRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val EMERGENCY_LIST_TAG = "emergency-list"

/**
 * Ask → More → Emergency card (V1, slice 1): who to call, where things are and key accounts by name, one typed line at
 * a time under each part ("GP: Dr Patel, 01632 123456"), each with Remove. his wife reads the same card on her family
 * page. A line holding a password, PIN or card or account number is turned away: the line under its field says why,
 * lit in the accent, and the words stay in the field to fix.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and each part's line
 * cross-fade (the line's colour blends to the accent when refused, with a tick haptic; Add gives a light haptic); parts
 * and rows stagger in 40 ms apart; rows arrive and leave with the list's item motion. Reduced motion: cross-fades.
 */
@Composable
fun EmergencyPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.emergencyView.collectAsState()
    // Per part: what its field holds and what the last Add said.
    val texts = remember { mutableStateMapOf<String, String>() }
    val said = remember { mutableStateMapOf<String, EmergencyAdded>() }

    LazyColumn(
        Modifier.fillMaxSize().testTag(EMERGENCY_LIST_TAG),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("Emergency card", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.EMERGENCY)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "emergency-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        v.groups.forEachIndexed { g, group ->
            val base = 2 + g * 3
            item(key = "label-${group.section}") {
                SectionLabel(group.label, Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(base)))
            }
            itemsIndexed(group.rows, key = { _, r -> "em-${r.id}" }) { i, r ->
                EmergencyRowView(r, Modifier.animateItem().appear(rememberAppearance(base + 1 + i))) {
                    haptics.tick()
                    scope.launch { runCatching { core.removeEmergency(r.id) } }
                }
            }
            item(key = "field-${group.section}") {
                val line = said[group.section]
                val color by animateColorAsState(
                    if (line?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "em-said-${group.section}",
                )
                Column(Modifier.animateItem().appear(rememberAppearance(base + 1 + group.rows.size)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Field(group.hint, value = texts[group.section] ?: "", onValue = { texts[group.section] = it }, description = group.spokenHint) { typed ->
                        scope.launch {
                            val r = runCatching { core.addEmergency(group.section, typed) }.getOrNull() ?: return@launch
                            said[group.section] = r
                            if (r.refused) {
                                haptics.tick()
                                texts[group.section] = typed // keep the words to fix
                            } else {
                                haptics.light()
                            }
                        }
                    }
                    Crossfade(line?.line, animationSpec = MekaMotion.appear(reduced), label = "em-line-${group.section}") { l ->
                        l?.let { Text(it, style = MekaType.caption, color = color) }
                    }
                }
            }
        }
        item(key = "shared") {
            Text(EmergencyRules.SHARED, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun EmergencyRowView(row: EmergencyRow, modifier: Modifier, onRemove: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            row.detailShown?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        }
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                .minTouch().padding(horizontal = MekaSpace.xs),
        )
    }
}
