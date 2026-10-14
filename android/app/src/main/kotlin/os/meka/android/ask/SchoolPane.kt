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
import androidx.compose.ui.platform.LocalUriHandler
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
import os.meka.core.domain.SchoolTermCard
import os.meka.core.domain.SchoolTerms
import os.meka.core.domain.SchoolTermsAdded
import os.meka.core.facade.MekaCore

/**
 * Ask → More → School (school rhythm, slice 1): Rex's and Logan's school year, typed once, one line each — "INSET
 * 27 Oct", "Half term 26–30 Oct", "Rex PE Tue", "Logan's trip 20 Nov" — in three groups: days off, dates and every
 * week, each row with Remove. A week before a day off that falls on an office day, Needs you asks who's covering.
 *
 * School dates filled in (slice 1): a card under the summary offers the councils whose holidays Navilife knows; Fill in
 * writes them as days off (each row says "Council dates · checked … · check with the school"), Remove all takes them out,
 * and the line after either has Undo. Under it a second field takes the school's INSET days pasted as dates or a letter.
 *
 * Slice 2, the yearly look: the card's brass line says when a later Navilife carries new dates from the council ("New
 * dates from … · 2028–29 · 6 holidays" with Add them, Undo after) or, from June, that next school year's holidays aren't
 * in yet (with Council's page when the council's dates are in); it cross-fades in and out with the card's line.
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

    // The council card's last action, for its line and Undo: true after Fill in, false after Remove all.
    var terms by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // Slice 2: what Add them added, for Undo (exactly those go again).
    var added by remember { mutableStateOf<SchoolTermsAdded?>(null) }
    val uri = LocalUriHandler.current
    var termsSaid by remember { mutableStateOf<String?>(null) }
    var insetSaid by remember { mutableStateOf<String?>(null) }
    val insetColor by animateColorAsState(
        if (insetSaid == SchoolTerms.INSET_NONE) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "school-inset",
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
        v.terms?.let { card ->
            item(key = "terms") {
                TermsCard(
                    card, termsSaid, undo = terms != null || added != null,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.xs).appear(rememberAppearance(2)),
                    onFill = { id ->
                        haptics.light()
                        scope.launch {
                            termsSaid = runCatching { core.fillSchoolTerms(id) }.getOrNull()
                            terms = termsSaid?.let { id to true }; added = null
                        }
                    },
                    onAddNew = { id ->
                        haptics.light()
                        scope.launch {
                            added = runCatching { core.addNewSchoolTerms(id) }.getOrNull()
                            termsSaid = added?.line; terms = null
                        }
                    },
                    onOpen = { url -> runCatching { uri.openUri(url) } },
                    onRemove = { id ->
                        haptics.tick()
                        scope.launch {
                            termsSaid = runCatching { core.removeSchoolTerms(id) }.getOrNull()
                            terms = termsSaid?.let { id to false }; added = null
                        }
                    },
                    onUndo = {
                        added?.let { a ->
                            haptics.tick()
                            scope.launch { runCatching { core.undoNewSchoolTerms(a) }; added = null; termsSaid = null }
                            return@TermsCard
                        }
                        val (id, filled) = terms ?: return@TermsCard
                        haptics.tick()
                        scope.launch {
                            runCatching { if (filled) core.removeSchoolTerms(id) else core.fillSchoolTerms(id) }
                            terms = null; termsSaid = null
                        }
                    },
                )
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
        item(key = "inset") {
            Column(Modifier.animateItem().padding(top = MekaSpace.xs).appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(SchoolTerms.INSET_HINT) { text ->
                    haptics.light()
                    scope.launch { insetSaid = runCatching { core.addSchoolInset(text) }.getOrNull() ?: SchoolTerms.INSET_NONE }
                }
                Crossfade(insetSaid, animationSpec = MekaMotion.appear(reduced), label = "school-inset-said") { line ->
                    line?.let { Text(it, style = MekaType.caption, color = insetColor) }
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
            row.source?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
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

/**
 * The council card: the councils to pick from (each with Fill in) while none is in, else what's in with Remove all; the
 * line after either cross-fades in with Undo.
 */
@Composable
private fun TermsCard(
    card: SchoolTermCard,
    said: String?,
    undo: Boolean,
    modifier: Modifier,
    onFill: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddNew: (String) -> Unit,
    onOpen: (String) -> Unit,
    onUndo: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Crossfade(card.line, animationSpec = MekaMotion.appear(reduced), label = "school-terms-line") { line ->
            Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        // Slice 2: new dates from the council, or next school year's holidays not in yet.
        Crossfade(card.refresh, animationSpec = MekaMotion.appear(reduced), label = "school-terms-refresh") { r ->
            r?.let {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(it.line, style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.weight(1f))
                    val act = it.action
                    if (act != null) {
                        Text(
                            act, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) {
                                    it.councilId?.let(onAddNew) ?: it.url?.let(onOpen)
                                }
                                .semantics { contentDescription = "$act. ${it.line}" }
                                .minTouch().padding(MekaSpace.xs),
                        )
                    }
                }
            }
        }
        card.choices.forEach { c ->
            Row(Modifier.fillMaxWidth().minTouch(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${c.name}. ${c.line}" }) {
                    Text(c.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text(
                    "Fill in", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { onFill(c.id) }
                        .semantics { contentDescription = "Fill in ${c.name}'s holidays" }
                        .padding(MekaSpace.xs),
                )
            }
        }
        card.filled?.let { f ->
            Text(
                "Remove all", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onRemove(f.id) }
                    .semantics { contentDescription = "Remove all of ${f.name}'s holidays" }
                    .minTouch().padding(MekaSpace.xs),
            )
        }
        if (card.filled == null && card.choices.isNotEmpty()) {
            Text(SchoolTerms.NOT_LISTED, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "school-terms-said") { line ->
            line?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f, fill = false))
                    if (undo) {
                        Text(
                            "Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button, onClick = onUndo).minTouch().padding(MekaSpace.xs),
                        )
                    }
                }
            }
        }
    }
}
