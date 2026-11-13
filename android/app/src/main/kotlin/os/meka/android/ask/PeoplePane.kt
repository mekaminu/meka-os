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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.Box
import os.meka.android.lists.Field
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
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.ListRules
import os.meka.core.domain.PeoplePageRules
import os.meka.core.domain.PersonOwed
import os.meka.core.domain.PersonRow
import os.meka.core.facade.MekaCore

/**
 * Ask → Life → People in the page shape (every page under Ask in one shape, slice 2; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does and where its lists come from), one
 * gold + Add (A birthday · Something I promised · Something I'm waiting for, each a field in the sheet), three numbers
 * (the next birthday, what's due or to chase, how many people), then Coming up and Everyone. A row opens that person's
 * sheet: their lines, what he promised (each with Kept) and what he's waiting for; Everyone's See all opens every person.
 * Promises are kept in Lists → Promises and birthdays in Gifts; this page gathers them by person and adds to them.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, tiles and cards appear in turn; cards move
 * with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring (LifePageParts); the
 * added line cross-fades; Kept and an add give a light haptic. Reduced motion: cross-fades.
 */
/** People's list, for the by-hand tests' bounded scroll. */
const val PEOPLE_LIST_TAG = "people-list"
const val PEOPLE_PERSON_SHEET_TAG = "people-person-sheet"
const val PEOPLE_ALL_SHEET_TAG = "people-all-sheet"

@Composable
fun PeoplePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.peopleView.collectAsState()
    val page = remember(v) { core.peoplePage(v) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allOpen by remember { mutableStateOf(false) }
    var personKey by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    val saidColor by animateColorAsState(
        if (refused) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "people-said",
    )
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(PEOPLE_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                LifePageHeader(
                    page.title, "${page.info}\n\n${PeoplePageRules.PRIVACY}", infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.PEOPLE)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = null; refused = false; addOpen = true },
                )
            }
            item(key = "summary") {
                Crossfade(said ?: v.summary, animationSpec = MekaMotion.appear(reduced), label = "people-summary") { s ->
                    Text(s, style = MekaType.caption, color = if (said != null) saidColor else Meka.colors.textSecondary,
                        modifier = Modifier.appear(rememberAppearance(1)).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allOpen = true },
                        onRow = { r -> haptics.tick(); personKey = PeoplePageRules.personKey(r.key) },
                    )
                }
            }
        }
        // + Add: a birthday (into Gifts), something he promised or something he's waiting for (into Lists → Promises).
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); addChosen = c.key; refused = false }
            addChosen?.let { choice ->
                Field(PeoplePageRules.hint(choice)) { text ->
                    scope.launch {
                        val line: String? = when (choice) {
                            PeoplePageRules.ADD_BIRTHDAY -> runCatching { core.addOccasion(text) }.getOrNull()?.let { r ->
                                if (r.refused) { refused = true; said = r.line; null } else r.line
                            }
                            else -> PeoplePageRules.readOwed(text)?.let { o ->
                                runCatching {
                                    if (choice == PeoplePageRules.ADD_PROMISE) core.addPromise(o.what, o.who, null)
                                    else core.addWaiting(o.what, o.who, ListRules.DEFAULT_CHASE_DAYS)
                                }.getOrNull()?.let { PeoplePageRules.addedLine(choice, o) }
                            }
                        }
                        if (line != null) { haptics.light(); refused = false; said = line; addOpen = false }
                        else { haptics.tick(); if (!refused) { refused = true; said = PeoplePageRules.NOT_READ } }
                    }
                }
                if (refused) said?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }
        // See all: every person, a tap opens their sheet.
        LifeSheet(allOpen, PeoplePageRules.EVERYONE, PEOPLE_ALL_SHEET_TAG, onClose = { allOpen = false }) {
            v.rows.forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allOpen = false; personKey = r.key }
                        .semantics(mergeDescendants = true) { contentDescription = r.spoken }
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    (r.owedLine ?: r.lastContact ?: r.birthday)?.let {
                        Text(it, style = MekaType.caption, color = if (r.lit) Meka.colors.accent else Meka.colors.textSecondary)
                    }
                }
            }
        }
        // A person's sheet: their lines, what he promised (Kept) and what he's waiting for.
        val person = v.rows.firstOrNull { it.key == personKey }
        LifeSheet(person != null, person?.name ?: PeoplePageRules.TITLE, PEOPLE_PERSON_SHEET_TAG, onClose = { personKey = null }) {
            val r = person ?: return@LifeSheet
            PersonDetails(r, onKept = { id -> haptics.light(); scope.launch { runCatching { core.keptPromise(id) } } })
        }
    }
}

/** What a person's sheet shows: which list has them, what's owed, the birthday, the last contact, then each thing owed. */
@Composable
private fun PersonDetails(row: PersonRow, onKept: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        row.listed?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        row.owedLine?.let { Text(it, style = MekaType.caption, color = if (row.lit) Meka.colors.accent else Meka.colors.textSecondary) }
        row.birthday?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        row.lastContact?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
    if (row.promised.isNotEmpty()) {
        SectionLabel("YOU PROMISED", Modifier.padding(top = MekaSpace.xs))
        row.promised.forEach { p -> OwedLine(p, keptLabel = "Kept", onKept = { onKept(p.id) }) }
    }
    if (row.waiting.isNotEmpty()) {
        SectionLabel("WAITING FOR", Modifier.padding(top = MekaSpace.xs))
        row.waiting.forEach { w -> OwedLine(w, keptLabel = null, onKept = {}) }
    }
    if (row.promised.isEmpty() && row.waiting.isEmpty()) {
        Text(PeoplePageRules.NOTHING_OWED, style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

@Composable
private fun OwedLine(item: PersonOwed, keptLabel: String?, onKept: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(item.meta, style = MekaType.caption, color = if (item.due) Meka.colors.accent else Meka.colors.textSecondary)
        }
        keptLabel?.let { label ->
            Text(
                label, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClick = onKept)
                    .semantics { contentDescription = "Kept: ${item.title}" }
                    .minTouch().padding(MekaSpace.xs),
            )
        }
    }
}
