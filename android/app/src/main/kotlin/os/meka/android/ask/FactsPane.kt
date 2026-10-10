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
import os.meka.core.domain.FactAdded
import os.meka.core.domain.FactRow
import os.meka.core.domain.FactRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → What MEKA knows (V1, facts page, slice 1): the facts Meka keeps so MEKA can answer from them — the
 * car's reg, the GP, sizes, the utilities — one typed line at a time ("Car reg: AB12 CDE"; the same name again changes
 * it), shown in groups, each with "Keep from AI" (or "Let MEKA use it") and Remove. A line holding a password, PIN or
 * card, bank or ID number is turned away: the line under the field says why, lit in the accent, and the words stay to
 * fix.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary, the line under the field and
 * a row's "Kept from AI" cross-fade (the field's line blends to the accent when refused, with a tick haptic; Add gives
 * a light haptic, Keep from AI / Let MEKA use it and Remove a tick); groups and rows stagger in 40 ms apart and arrive,
 * move and leave with the list's item motion. Reduced motion: cross-fades.
 */
@Composable
fun FactsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.factsView.collectAsState()
    var text by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<FactAdded?>(null) }

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
            Text("What MEKA knows", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.FACTS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "facts-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            val line = said
            val color by animateColorAsState(
                if (line?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "facts-said",
            )
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(FactRules.HINT, value = text, onValue = { text = it }) { typed ->
                    scope.launch {
                        val r = runCatching { core.addFact(typed) }.getOrNull() ?: return@launch
                        said = r
                        if (r.refused) {
                            haptics.tick()
                            text = typed // keep the words to fix
                        } else {
                            haptics.light()
                        }
                    }
                }
                Crossfade(line?.line, animationSpec = MekaMotion.appear(reduced), label = "facts-line") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = color) }
                }
            }
        }
        var step = 3
        v.groups.forEach { group ->
            val base = step
            item(key = "label-${group.group}") {
                SectionLabel(group.label, Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(base)))
            }
            itemsIndexed(group.rows, key = { _, r -> "fact-${r.id}" }) { i, r ->
                FactRowView(
                    r, Modifier.animateItem().appear(rememberAppearance(base + 1 + i)),
                    onAi = {
                        haptics.tick()
                        scope.launch { runCatching { core.setFactAi(r.id, use = r.keptFromAi) } }
                    },
                    onRemove = {
                        haptics.tick()
                        scope.launch { runCatching { core.removeFact(r.id) } }
                    },
                )
            }
            step += 1 + group.rows.size.coerceAtMost(4)
        }
        item(key = "note") {
            Text(FactRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun FactRowView(row: FactRow, modifier: Modifier, onAi: () -> Unit, onRemove: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.label, style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(row.value, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(row.aiLine, animationSpec = MekaMotion.appear(reduced), label = "fact-ai-${row.id}") { l ->
                l?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }
        Text(
            row.aiButton, style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onAi)
                .semantics { contentDescription = "${row.aiButton}: ${row.label}" }
                .padding(MekaSpace.xs),
        )
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.label}" }
                .padding(MekaSpace.xs),
        )
    }
}
