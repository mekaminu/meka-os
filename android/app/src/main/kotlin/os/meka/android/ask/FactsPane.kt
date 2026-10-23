package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.platform.LocalContext
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
import os.meka.android.work.LiveReplies
import os.meka.core.domain.FactAdded
import os.meka.core.domain.FactRow
import os.meka.core.domain.FactRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → What MEKA knows (V1, facts page, slice 1): the facts Meka keeps so MEKA can answer from them — the
 * car's reg, the GP, sizes, the utilities — one typed line at a time ("Car reg: AB12 CDE"; the same name again changes
 * it), shown in groups, each with "Keep from AI" (or "Let MEKA use it") and Remove. A line holding a password, PIN or
 * card, bank or ID number is turned away: the line under the field says why, lit in the accent, and the words stay to
 * fix. Copy (slice 3, filling forms) puts the fact alone on the clipboard ("AB12 CDE") and the line under the field
 * says "Copied Car reg · AB12 CDE"; nothing is sent anywhere.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary, the line under the field and
 * a row's "Kept from AI" cross-fade (the field's line blends to the accent when refused, with a tick haptic; Add gives
 * and Copy a light haptic, Keep from AI / Let MEKA use it and Remove a tick); groups and rows stagger in 40 ms apart
 * and arrive, move and leave with the list's item motion. Reduced motion: cross-fades.
 */
@Composable
fun FactsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
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
            Text("What Navilife knows", style = MekaType.greeting, color = Meka.colors.textPrimary,
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
                Field(FactRules.HINT, value = text, onValue = { text = it }, description = FactRules.DESCRIPTION) { typed ->
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
                    onCopy = {
                        haptics.light()
                        LiveReplies.copyText(context, r.copyText, label = "Navilife fact")
                        said = FactAdded(FactRules.copiedLine(r), refused = false)
                    },
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

/** Under this width the row's three buttons go under the fact (quality gate slice 23: on the cover screen they left the fact ~25 dp). */
private val FACT_ACTIONS_BELOW = 420.dp

@Composable
private fun FactRowView(row: FactRow, modifier: Modifier, onCopy: () -> Unit, onAi: () -> Unit, onRemove: () -> Unit) {
    val reduced = Meka.reducedMotion
    val texts: @Composable (Modifier) -> Unit = { m ->
        Column(m.semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.label, style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(row.valueShown, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(row.aiLine, animationSpec = MekaMotion.appear(reduced), label = "fact-ai-${row.id}") { l ->
                l?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        FactAction(FactRules.COPY_BUTTON, Meka.colors.accent, row.copySpoken, onCopy)
        FactAction(row.aiButton, Meka.colors.textTertiary, "${row.aiButton}: ${row.label}", onAi)
        FactAction("Remove", Meka.colors.textTertiary, "Remove ${row.label}", onRemove)
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    ) {
        if (maxWidth < FACT_ACTIONS_BELOW) {
            // Narrow (the cover screen): the fact keeps the row's width and its buttons sit under it, each whole.
            Column(Modifier.fillMaxWidth()) {
                texts(Modifier.fillMaxWidth())
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs, Alignment.End)) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                texts(Modifier.weight(1f))
                actions()
            }
        }
    }
}

@Composable
private fun FactAction(label: String, color: androidx.compose.ui.graphics.Color, spoken: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = color, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .minTouch()
            .padding(MekaSpace.xs),
    )
}
