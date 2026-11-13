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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import os.meka.core.domain.GiftAdded
import os.meka.core.domain.GiftRow
import os.meka.core.domain.GiftRules
import os.meka.core.domain.GiftsPageRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val GIFTS_LIST_TAG = "gifts-list"
const val GIFTS_OCCASION_SHEET_TAG = "gifts-occasion-sheet"
const val GIFTS_ALL_SHEET_TAG = "gifts-all-sheet"

/**
 * Ask → Life → Gifts in the page shape (every page under Ask in one shape, slice 3; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A birthday · An anniversary, each a field
 * in the sheet: "Mum's birthday 3 Nov", "Wedding 20 Aug 2015", "… delivery 5 days"), three numbers (the next occasion,
 * what to sort soon, the ideas noted), then Coming up (the next two months, with the order-by day or ✓ sorted) and
 * Birthdays and anniversaries (See all past five). A row opens that occasion's sheet: its gift ideas (each with ×), a
 * field to note another, To a day…, Gift sorted / Not sorted yet, and Remove. Two weeks ahead and on the order-by day
 * a heads-up comes at 09:00; Navilife never orders anything.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, tiles and cards appear in turn; cards move
 * with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring (LifePageParts); the
 * added line cross-fades (a refused line lit in the accent); the order-by note's colour blends to the accent; an add
 * and Gift sorted give a light haptic (a tick when refused), Remove and × a tick. Reduced motion: cross-fades.
 */
@Composable
fun GiftsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.giftsView.collectAsState()
    val page = remember(v) { core.giftsPage(v) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allOpen by remember { mutableStateOf(false) }
    var occasionId by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<GiftAdded?>(null) }
    // Week board item (5) part 4: the occasion To a day… last answered, its line, and whether Undo applies.
    var toDay by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "gifts-said",
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(GIFTS_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                LifePageHeader(
                    page.title, "${page.info}\n\n${GiftRules.NOTE}", infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.GIFTS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = null; said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                val line = said?.takeIf { !it.refused }?.line ?: v.summary
                Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "gifts-summary") { s ->
                    Text(s, style = MekaType.caption, color = Meka.colors.textSecondary,
                        modifier = Modifier.appear(rememberAppearance(1)).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allOpen = true },
                        onRow = { r -> haptics.tick(); occasionId = GiftsPageRules.occasionId(r.key) },
                    )
                }
            }
        }
        // + Add: a birthday or an anniversary, typed once.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); addChosen = c.key; said = null }
            addChosen?.let { choice ->
                Field(GiftsPageRules.hint(choice), description = GiftRules.ADD_DESCRIPTION) { text ->
                    scope.launch {
                        val r = runCatching { core.addOccasion(GiftsPageRules.entry(choice, text)) }.getOrNull()
                        if (r?.refused == false) { haptics.light(); said = r; addOpen = false }
                        else { haptics.tick(); said = r ?: GiftAdded(GiftRules.NOT_READ, true) }
                    }
                }
                said?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every occasion, a tap opens its sheet.
        LifeSheet(allOpen, GiftsPageRules.ALL, GIFTS_ALL_SHEET_TAG, onClose = { allOpen = false }) {
            v.rows.forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allOpen = false; occasionId = r.id }
                        .semantics(mergeDescendants = true) { contentDescription = r.spoken }
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // An occasion's sheet: its day and order-by note, ideas, To a day…, Gift sorted and Remove.
        val occasion = v.rows.firstOrNull { it.id == occasionId }
        LifeSheet(occasion != null, occasion?.title ?: GiftsPageRules.TITLE, GIFTS_OCCASION_SHEET_TAG, onClose = { occasionId = null }) {
            val r = occasion ?: return@LifeSheet
            OccasionDetails(
                row = r,
                onIdea = { text ->
                    scope.launch {
                        if (runCatching { core.addGiftIdea(r.id, text) }.getOrDefault(false)) haptics.light() else haptics.tick()
                    }
                },
                onRemoveIdea = { id -> haptics.tick(); scope.launch { runCatching { core.removeGiftIdea(id) } } },
                onSorted = { sorted -> haptics.light(); scope.launch { runCatching { core.setGiftSorted(r.id, sorted) } } },
                onRemove = { haptics.tick(); occasionId = null; scope.launch { runCatching { core.removeOccasion(r.id) } } },
                toDay = {
                    // Week board item (5) part 4: the gift as a task on a day of the Board, up to the day itself.
                    val date = r.day
                    if (date != null && !r.sorted) DateToDay(
                        key = r.id, title = r.title, days = { core.dateFeedDays(date) },
                        said = toDay?.takeIf { it.first == r.id }?.second,
                        onToDay = { day ->
                            scope.launch {
                                val line = runCatching { core.giftToDay(r.id, day) }.getOrNull() ?: return@launch
                                toDay = Triple(r.id, line, line.startsWith("Task "))
                            }
                        },
                        onUndo = if (toDay?.let { it.first == r.id && it.third } == true) {
                            { scope.launch { runCatching { core.undoDateToDay() }; toDay = null }; Unit }
                        } else null,
                    )
                },
            )
        }
    }
}

/** What an occasion's sheet shows: its day and order-by note, its gift ideas (each ×), a field for another, To a day…, Gift sorted and Remove. */
@Composable
private fun OccasionDetails(
    row: GiftRow,
    onIdea: (String) -> Unit,
    onRemoveIdea: (String) -> Unit,
    onSorted: (Boolean) -> Unit,
    onRemove: () -> Unit,
    toDay: @Composable () -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    val noteColor by animateColorAsState(
        if (row.noteLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "gift-note",
    )
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
        Text(row.note, style = MekaType.caption, color = noteColor)
    }
    SectionLabel("GIFT IDEAS", Modifier.padding(top = MekaSpace.xs))
    if (row.ideas.isEmpty()) {
        Text("None yet", style = MekaType.caption, color = Meka.colors.textTertiary)
    }
    for (idea in row.ideas) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(idea.text, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text("×", style = MekaType.body, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onRemoveIdea(idea.id) }
                    .semantics { contentDescription = "Remove the idea ${idea.text}" }
                    .minTouch().padding(MekaSpace.xs))
        }
    }
    Field(GiftRules.IDEA_HINT) { text -> onIdea(text) }
    toDay()
    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (row.sorted) "Not sorted yet" else "Gift sorted", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button) { onSorted(!row.sorted) }.minTouch().padding(MekaSpace.xs),
        )
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                .minTouch().padding(MekaSpace.xs),
        )
    }
}
