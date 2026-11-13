package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalUriHandler
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
import os.meka.core.domain.SchoolCoverDone
import os.meka.core.domain.SchoolPageRules
import os.meka.core.domain.SchoolRules
import os.meka.core.domain.SchoolTermCard
import os.meka.core.domain.SchoolTerms
import os.meka.core.domain.SchoolTermsAdded
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val SCHOOL_LIST_TAG = "school-list"
const val SCHOOL_ROW_SHEET_TAG = "school-row-sheet"
const val SCHOOL_ALL_SHEET_TAG = "school-all-sheet"
const val SCHOOL_COUNCIL_SHEET_TAG = "school-council-sheet"

/**
 * Ask → School in the page shape (every page under Ask in one shape, slice 14; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A day off · A school date · Every week,
 * each one typed line: "INSET 2 Nov", "Alex's trip 20 Nov", "Alex PE Tue"; The school's INSET days, pasted dates or the
 * school's letter, with School's website › from About you), three numbers (how soon the next day off is, the days off
 * to cover or the dates ahead, the weekly things), then Days off, Dates, Every week and Holidays from the council (five
 * rows each, See all for the rest). A day off waiting on "who's covering?" says so in brass. A row opens its sheet: its
 * day and who, the cover answer or reminder, where it came from, I'll work from home / Covered (with Undo) while the
 * question is open, To a day… for a date, and Remove; a council's row opens the council card (Fill in or Remove all,
 * Add them for new dates, each with Undo). Nothing is sent to the school.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the answer line cross-fades and blends to the accent when nothing could be read (tick haptic; an
 * add a light haptic); the council card's lines cross-fade as before. Reduced motion: cross-fades.
 */
@Composable
fun SchoolPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.schoolView.collectAsState()
    val page = remember(v) { core.schoolPage(v) }
    val uri = LocalUriHandler.current
    // The last add's answer: what was added, or that nothing could be read (lit, the words kept in the field).
    var said by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (SchoolPageRules.isNotRead(said)) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "school-said",
    )
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var addText by remember { mutableStateOf("") }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    var councilOpen by remember { mutableStateOf(false) }
    // A cover answered from a row's sheet, for its Undo.
    var coverDone by remember { mutableStateOf<SchoolCoverDone?>(null) }
    // The council card's last action, for its line and Undo: true after Fill in, false after Remove all.
    var terms by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // What Add them added, for Undo (exactly those go again).
    var added by remember { mutableStateOf<SchoolTermsAdded?>(null) }
    var termsSaid by remember { mutableStateOf<String?>(null) }
    // Week board item (5) part 4: the date row To a day… last answered, its line, and whether Undo applies.
    var toDay by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    fun open(key: String) {
        haptics.tick(); said = null; coverDone = null
        if (SchoolPageRules.isCouncil(key)) councilOpen = true else rowKey = key
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(SCHOOL_LIST_TAG),
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
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.SCHOOL)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "school-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    // What the last add said, while no sheet is open.
                    val line = if (addOpen) null else said
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "school-said-line") { l ->
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
                        onRow = { r -> open(r.key) },
                    )
                }
            }
            item(key = "shared") {
                Text(SchoolRules.SHARED, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: which kind, then one typed line (or the school's INSET days pasted).
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                if (SchoolPageRules.isInset(choice)) v.inset?.let { l ->
                    // Where the INSET days are, and the school's website from About you.
                    Text(l.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    val url = l.url
                    val action = l.action
                    if (url != null && action != null) {
                        Text("$action ›", style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) { haptics.light(); runCatching { uri.openUri(url) } }.minTouch())
                    }
                }
                Field(
                    SchoolPageRules.hint(choice), value = addText, onValue = { addText = it },
                    description = SchoolPageRules.spokenHint(choice), keep = true,
                ) { typed ->
                    scope.launch {
                        val line = runCatching { if (SchoolPageRules.isInset(choice)) core.addSchoolInset(typed) else core.addSchool(typed) }.getOrNull()
                        said = line ?: if (SchoolPageRules.isInset(choice)) SchoolTerms.INSET_NONE else SchoolRules.NOT_READ
                        if (line == null || SchoolPageRules.isNotRead(line)) haptics.tick() else { haptics.light(); addText = ""; addOpen = false }
                    }
                }
                said?.takeIf { SchoolPageRules.isNotRead(it) }?.let { Text(it, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every row of one card; a tap opens its sheet.
        val all = allCard
        LifeSheet(all != null, all?.let(SchoolPageRules::cardTitle) ?: SchoolPageRules.TITLE, SCHOOL_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) SchoolPageRules.allRows(v, all, core.todayEpochDay()).forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { allCard = null; open(r.key) }
                        .semantics(mergeDescendants = true) {}
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(listOf(r.line, r.whenText).filter { it.isNotEmpty() }.joinToString(" · "), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A row's sheet: its part, day and who, notes, the cover question's taps, To a day… and Remove.
        val openKey = rowKey
        val row = openKey?.let { SchoolPageRules.row(v, it) }
        LifeSheet(openKey != null, row?.title ?: SchoolPageRules.TITLE, SCHOOL_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            if (row == null) {
                if (openKey != null) Text(SchoolPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            } else {
                Text(SchoolPageRules.partLabel(v, row), style = MekaType.caption, color = Meka.colors.textTertiary)
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = row.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(row.line, style = MekaType.body, color = Meka.colors.textPrimary)
                    Crossfade(row.note, animationSpec = MekaMotion.appear(reduced), label = "school-row-note") { n ->
                        n?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                    }
                    row.source?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                }
                val cover = openKey?.let { SchoolPageRules.cover(v, it) }
                if (cover != null) {
                    Text(cover.question, style = MekaType.body, color = Meka.colors.accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        listOf(true to cover.homeLabel, false to cover.coveredLabel).forEach { (home, label) ->
                            Text(
                                label, style = MekaType.metaStrong, color = if (home) Meka.colors.onAccent else Meka.colors.textPrimary,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                    .background(if (home) Meka.colors.accent else Meka.colors.surfaceRaised)
                                    .clickable(role = Role.Button) {
                                        haptics.medium()
                                        scope.launch { coverDone = runCatching { core.coverSchool(cover.id, home) }.getOrNull() }
                                    }
                                    .semantics { contentDescription = "$label: ${cover.title}" }
                                    .minTouch().padding(horizontal = MekaSpace.m),
                            )
                        }
                    }
                    Text(cover.detail, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                coverDone?.takeIf { it.id == row.id }?.let { done ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(done.line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f, fill = false))
                        Text(
                            "Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) {
                                    haptics.tick()
                                    scope.launch { runCatching { core.undoSchoolCover(done) }; coverDone = null }
                                }
                                .minTouch().padding(MekaSpace.xs),
                        )
                    }
                }
                // Week board item (5) part 4: getting ready for a school date as a task on a day of the Board.
                val date = row.day
                if (date != null) DateToDay(
                    key = row.id, title = row.title, days = { core.dateFeedDays(date) },
                    said = toDay?.takeIf { it.first == row.id }?.second,
                    onToDay = { day ->
                        scope.launch {
                            val line = runCatching { core.schoolDateToDay(row.id, day) }.getOrNull() ?: return@launch
                            toDay = Triple(row.id, line, line.startsWith("Task "))
                        }
                    },
                    onUndo = if (toDay?.let { it.first == row.id && it.third } == true) {
                        { scope.launch { runCatching { core.undoDateToDay() }; toDay = null }; Unit }
                    } else null,
                )
                Text(
                    "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) {
                            haptics.tick()
                            rowKey = null
                            scope.launch { runCatching { core.removeSchool(row.id) } }
                        }
                        .semantics { contentDescription = "Remove ${row.title}" }
                        // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                        .minTouch().padding(horizontal = MekaSpace.xs),
                )
            }
        }
        // A council's row: the council card as before (Fill in or Remove all, Add them, each with Undo).
        LifeSheet(councilOpen, SchoolTerms.CARD_TITLE, SCHOOL_COUNCIL_SHEET_TAG, onClose = { councilOpen = false }) {
            v.terms?.let { card ->
                TermsCard(
                    card, termsSaid, undo = terms != null || added != null,
                    modifier = Modifier.testTag("school-terms"),
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
    ) {
        // Quality gate slice 16's findings: no spacedBy here, so a part with nothing to show (no new dates, nothing said)
        // leaves no gap; each part that shows brings its own gap above it, and the card ends just under its words.
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Crossfade(card.line, animationSpec = MekaMotion.appear(reduced), label = "school-terms-line") { line ->
            Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xs))
        }
        // Slice 2: new dates from the council, or next school year's holidays not in yet.
        Crossfade(card.refresh, animationSpec = MekaMotion.appear(reduced), label = "school-terms-refresh") { r ->
            r?.let {
                Row(
                    Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
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
            Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs).minTouch(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${c.name}. ${c.line}" }) {
                    Text(c.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text(
                    "Fill in", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { onFill(c.id) }
                        .semantics { contentDescription = "Fill in ${c.name}'s holidays" }
                        // Works by hand (quality gate (3)): a 48 dp touch, the words drawn as before.
                        .minTouch().padding(horizontal = MekaSpace.xs),
                )
            }
        }
        card.filled?.let { f ->
            Text(
                "Remove all", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onRemove(f.id) }
                    .semantics { contentDescription = "Remove all of ${f.name}'s holidays" }
                    .minTouch().padding(MekaSpace.xs),
            )
        }
        if (card.filled == null && card.choices.isNotEmpty()) {
            Text(
                SchoolTerms.NOT_LISTED, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs),
            )
        }
        Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "school-terms-said") { line ->
            line?.let {
                Row(
                    Modifier.padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
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
