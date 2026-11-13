package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import os.meka.core.domain.EmergencyAdded
import os.meka.core.domain.EmergencyPageRules
import os.meka.core.domain.EmergencyRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val EMERGENCY_LIST_TAG = "emergency-list"
const val EMERGENCY_ROW_SHEET_TAG = "emergency-row-sheet"
const val EMERGENCY_ALL_SHEET_TAG = "emergency-all-sheet"

/**
 * Ask → Emergency card in the page shape (every page under Ask in one shape, slice 12; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (Someone to call · Where something is · A
 * key account, each a field in the sheet: "GP: Dr Patel, 01632 123456"), three numbers (to call, things placed, key
 * accounts), then Who to call, Where things are and Key accounts (five rows each, See all for the rest). A line's row
 * opens its sheet with its details, a field to change them and Remove. his wife reads the same card on her family
 * page. A line holding a password, PIN or card or account number is turned away: the line under the field says why,
 * lit in the accent, and the words stay in the field to fix.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the answer line cross-fades and blends to the accent when refused (tick haptic; an add a light
 * haptic); a line's details cross-fade as they change. Reduced motion: cross-fades.
 */
@Composable
fun EmergencyPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.emergencyView.collectAsState()
    val page = remember(v) { core.emergencyPage(v) }
    var said by remember { mutableStateOf<EmergencyAdded?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    // What + Add's field and the row sheet's field hold (kept after a refusal, to fix).
    var addText by remember { mutableStateOf("") }
    var changeText by remember { mutableStateOf("") }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "em-said",
    )
    fun add(section: String, text: String, onDone: () -> Unit) {
        scope.launch {
            val r = runCatching { core.addEmergency(section, text) }.getOrNull() ?: return@launch
            said = r
            if (r.refused) haptics.tick() else { haptics.light(); onDone() }
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                LifePageHeader(
                    page.title, page.info, infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.EMERGENCY)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "emergency-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    // What the last add or change said, while no sheet is open.
                    val line = if (addOpen || rowKey != null) null else said?.line
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "emergency-said") { l ->
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
            item(key = "shared") {
                Text(EmergencyRules.SHARED, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: which part, then one typed line.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(
                    EmergencyPageRules.hint(choice), value = addText, onValue = { addText = it },
                    description = EmergencyPageRules.spokenHint(choice), keep = true,
                ) { typed -> add(choice, typed) { addText = ""; addOpen = false } }
                said?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every line of one part; a tap opens its sheet.
        val all = allCard
        val allTitle = page.cards.firstOrNull { it.key == all }?.title ?: EmergencyPageRules.TITLE
        LifeSheet(all != null, allTitle, EMERGENCY_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) EmergencyPageRules.allRows(v, all).forEach { r ->
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
        // A line's sheet: its part and details, a field to change them, Remove.
        val openKey = rowKey
        val row = openKey?.let { EmergencyPageRules.line(v, it) }
        LifeSheet(openKey != null, row?.title ?: EmergencyPageRules.TITLE, EMERGENCY_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            if (row == null) {
                if (openKey != null) Text(EmergencyPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            } else {
                Text(EmergencyPageRules.partLabel(row), style = MekaType.caption, color = Meka.colors.textTertiary)
                Crossfade(row.detailShown ?: EmergencyPageRules.NAME_ONLY, animationSpec = MekaMotion.appear(reduced), label = "em-detail") { d ->
                    Text(d, style = MekaType.body, color = if (row.detail == null) Meka.colors.textSecondary else Meka.colors.textPrimary,
                        modifier = Modifier.semantics { contentDescription = row.spoken })
                }
                Field(EmergencyPageRules.CHANGE_HINT, value = changeText, onValue = { changeText = it }, keep = true) { typed ->
                    EmergencyPageRules.changeText(row, typed)?.let { text -> add(row.section, text) { changeText = "" } }
                }
                Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "em-row-said") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
                Text(
                    "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) {
                            haptics.tick()
                            rowKey = null
                            scope.launch { runCatching { core.removeEmergency(row.id) } }
                        }
                        .semantics { contentDescription = "Remove ${row.title}" }
                        // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                        .minTouch().padding(horizontal = MekaSpace.xs),
                )
            }
        }
    }
}
