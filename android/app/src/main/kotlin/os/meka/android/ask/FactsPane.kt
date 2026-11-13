package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import os.meka.android.work.LiveReplies
import os.meka.core.domain.FactAdded
import os.meka.core.domain.FactRules
import os.meka.core.domain.FactsPageRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val FACTS_LIST_TAG = "facts-list"
const val FACTS_ROW_SHEET_TAG = "facts-row-sheet"
const val FACTS_ALL_SHEET_TAG = "facts-all-sheet"

/**
 * Ask → What Navilife knows in the page shape (every page under Ask in one shape, slice 13; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A fact · A fact kept from the AI, each a
 * field: "Car reg: AB12 CDE"), three numbers (facts kept, how many Obi can use, how many kept from the AI), then one
 * card a group (Sizes, Health and care, Car, Home and utilities, Family and school, Other; five rows each, See all for
 * the rest). A fact's row opens its sheet with the fact whole, a field to change it, Copy (the fact alone on the
 * clipboard), Keep from AI / Let Navilife use it and Remove. A line holding a password, PIN or card, bank or ID number
 * is turned away: the line under the field says why, lit in the accent, and the words stay to fix.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the answer line cross-fades and blends to the accent when refused (tick haptic; an add or a Copy a
 * light haptic); a fact and its "Kept from AI" cross-fade as they change. Reduced motion: cross-fades.
 */
@Composable
fun FactsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
    val v by core.factsView.collectAsState()
    val page = remember(v) { core.factsPage(v) }
    var said by remember { mutableStateOf<FactAdded?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    // What + Add's field and the row sheet's field hold (kept after a refusal, to fix).
    var addText by remember { mutableStateOf("") }
    var changeText by remember { mutableStateOf("") }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "facts-said",
    )
    fun add(text: String, keptFromAi: Boolean, onDone: () -> Unit) {
        scope.launch {
            val r = runCatching { if (keptFromAi) core.addFactKept(text) else core.addFact(text) }.getOrNull() ?: return@launch
            said = r
            if (r.refused) haptics.tick() else { haptics.light(); onDone() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(FACTS_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                LifePageHeader(
                    page.title, page.info, infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.FACTS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "facts-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    // What the last add, change or Copy said, while no sheet is open.
                    val line = if (addOpen || rowKey != null) null else said?.line
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "facts-line") { l ->
                        l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                    }
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allCard = c.key },
                        onRow = { r -> haptics.tick(); said = null; changeText = ""; rowKey = r.key },
                    )
                }
            }
            item(key = "note") {
                Text(FactRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: a fact, or one kept from the AI, then one typed line.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(
                    FactsPageRules.hint(choice), value = addText, onValue = { addText = it },
                    description = FactsPageRules.spokenHint(choice), keep = true,
                ) { typed -> add(typed, FactsPageRules.keepsFromAi(choice)) { addText = ""; addOpen = false } }
                said?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every fact of one group; a tap opens its sheet.
        val all = allCard
        val allTitle = page.cards.firstOrNull { it.key == all }?.title ?: FactsPageRules.TITLE
        LifeSheet(all != null, allTitle, FACTS_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) FactsPageRules.allRows(v, all).forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allCard = null; said = null; changeText = ""; rowKey = r.key }
                        .semantics(mergeDescendants = true) {}
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A fact's sheet: its group and the fact whole, a field to change it, Copy, Keep from AI, Remove.
        val openKey = rowKey
        val row = openKey?.let { FactsPageRules.fact(v, it) }
        LifeSheet(openKey != null, row?.label ?: FactsPageRules.TITLE, FACTS_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            if (row == null) {
                if (openKey != null) Text(FactsPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            } else {
                Text(FactsPageRules.groupLabel(row), style = MekaType.caption, color = Meka.colors.textTertiary)
                Crossfade(row.valueShown, animationSpec = MekaMotion.appear(reduced), label = "fact-value") { d ->
                    Text(d, style = MekaType.body, color = Meka.colors.textPrimary,
                        modifier = Modifier.semantics { contentDescription = row.spoken })
                }
                Crossfade(row.aiLine, animationSpec = MekaMotion.appear(reduced), label = "fact-ai") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                }
                Field(FactsPageRules.CHANGE_HINT, value = changeText, onValue = { changeText = it }, keep = true) { typed ->
                    FactsPageRules.changeText(row, typed)?.let { text -> add(text, keptFromAi = false) { changeText = "" } }
                }
                Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "fact-row-said") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SheetWord(FactRules.COPY_BUTTON, Meka.colors.accent, row.copySpoken) {
                        haptics.light()
                        LiveReplies.copyText(context, row.copyText, label = "Navilife fact")
                        said = FactAdded(FactRules.copiedLine(row), refused = false)
                    }
                    SheetWord(row.aiButton, Meka.colors.textTertiary, "${row.aiButton}: ${row.label}") {
                        haptics.tick()
                        scope.launch { runCatching { core.setFactAi(row.id, use = row.keptFromAi) } }
                    }
                    SheetWord("Remove", Meka.colors.textTertiary, "Remove ${row.label}") {
                        haptics.tick()
                        rowKey = null
                        scope.launch { runCatching { core.removeFact(row.id) } }
                    }
                }
            }
        }
    }
}

/** One word in a fact's sheet (Copy · Keep from AI · Remove): a 48 dp touch, the word drawn as before. */
@Composable
private fun SheetWord(label: String, color: androidx.compose.ui.graphics.Color, spoken: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = color, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .minTouch().padding(horizontal = MekaSpace.xs),
    )
}
