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
import os.meka.core.domain.AppointmentRules
import os.meka.core.domain.AppointmentsPageRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val APPOINTMENTS_LIST_TAG = "appointments-list"
const val APPOINTMENTS_ROW_SHEET_TAG = "appointments-row-sheet"
const val APPOINTMENTS_ALL_SHEET_TAG = "appointments-all-sheet"

/**
 * Ask → Appointments in the page shape (every page under Ask in one shape, slice 10; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (Something you keep up with · A visit
 * you've booked, each a field; A common one, the usual ones in one tap), three numbers (due to book, the next booked
 * visit, how many he keeps up with), then To book, Booked and Next due. A row opens its sheet: its status, the gold
 * Went, a Booked for… field, a When did you last go? field while no visit is noted, and Remove. When one comes due
 * Needs you asks once ([os.meka.android.today.AppointmentCardView]). Navilife books nothing and calls no one.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the summary, the answer line and a row's status cross-fade (a refusal or a due status lit in the
 * accent); Add, Went and a booking give a light haptic, a refusal and Remove a tick. Reduced motion: cross-fades.
 */
@Composable
fun AppointmentsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.appointmentsView.collectAsState()
    val page = remember(v) { core.appointmentsPage(v) }
    val today = remember(v) { core.todayEpochDay() }
    // The line after an add, Went or a booking (kept while the pane is open), and whether nothing was written.
    var said by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (refused) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "appt-said",
    )
    fun show(line: String, no: Boolean) {
        said = line
        refused = no
        if (no) haptics.tick() else haptics.light()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(APPOINTMENTS_LIST_TAG),
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
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.APPOINTMENTS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = null; said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "appt-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    // What the last add, Went or booking said, while no sheet is open.
                    val line = if (addOpen || rowKey != null) null else said
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "appt-line") { l ->
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
                        onRow = { r -> haptics.tick(); said = null; rowKey = r.key },
                    )
                }
            }
            item(key = "note") {
                Text(AppointmentRules.NOTE_SHOWN, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: something kept up with, a booked visit (each a field), or a common one in one tap.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            val choice = addChosen
            val hint = choice?.let { AppointmentsPageRules.hint(it) }
            if (hint != null) {
                Field(hint) { typed ->
                    scope.launch {
                        val r = runCatching { core.typeAppointment(typed) }.getOrNull() ?: return@launch
                        show(r.line, r.refused)
                        if (!r.refused) addOpen = false
                    }
                }
            } else if (choice == AppointmentsPageRules.ADD_COMMON) {
                v.suggestions.forEach { s ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) {
                                scope.launch {
                                    val r = runCatching { core.addAppointmentPreset(s.id) }.getOrNull() ?: return@launch
                                    show(r.line, r.refused)
                                    if (!r.refused) addOpen = false
                                }
                            }
                            .semantics(mergeDescendants = true) { contentDescription = "Add ${s.title}, ${s.line}" }
                            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    ) {
                        Text("+ ${s.title}", style = MekaType.body, color = Meka.colors.accent)
                        Text(s.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
            }
            if (refused) said?.let { Text(it, style = MekaType.caption, color = saidColor) }
        }
        // See all: every row of one card, a tap opens its sheet.
        val all = allCard
        LifeSheet(all != null, all ?: AppointmentsPageRules.TITLE, APPOINTMENTS_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) AppointmentsPageRules.allRows(v, all, today).forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allCard = null; rowKey = r.key }
                        .semantics(mergeDescendants = true) {}
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(listOf(r.line, r.whenText).filter { it.isNotEmpty() }.joinToString(" · "), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A row's sheet: its status, Went, Booked for…, When did you last go? (while not noted) and Remove.
        val openKey = rowKey
        val row = openKey?.let { AppointmentsPageRules.row(v, it) }
        LifeSheet(openKey != null, row?.title ?: AppointmentsPageRules.TITLE, APPOINTMENTS_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            if (row == null) {
                if (openKey != null) Text(AppointmentsPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            } else {
                val statusColor by animateColorAsState(
                    if (row.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "appt-status",
                )
                Text(row.line, style = MekaType.body, color = Meka.colors.textPrimary)
                Crossfade(row.status, animationSpec = MekaMotion.appear(reduced), label = "appt-status-line") { s ->
                    Text(s, style = MekaType.caption, color = statusColor)
                }
                Text(
                    AppointmentRules.WENT_LABEL, style = MekaType.itemMeta, color = Meka.colors.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Button) {
                            scope.launch {
                                val r = runCatching { core.appointmentWent(row.id) }.getOrNull() ?: return@launch
                                show(r.line, r.refused)
                            }
                        }
                        .semantics { contentDescription = "Went to ${row.title} today" }
                        .minTouch()
                        .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
                Text(AppointmentsPageRules.BOOKED_FOR, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(top = MekaSpace.xs))
                Field(AppointmentRules.WHEN_HINT, description = "${AppointmentsPageRules.BOOKED_FOR} ${row.title}") { typed ->
                    scope.launch {
                        val r = runCatching { core.bookAppointment(row.id, typed) }.getOrNull() ?: return@launch
                        show(r.line, r.refused)
                    }
                }
                if (AppointmentsPageRules.asksLastWent(row)) {
                    Text(AppointmentsPageRules.LAST_WENT, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                        modifier = Modifier.padding(top = MekaSpace.xs))
                    Field(AppointmentsPageRules.LAST_WENT_HINT, description = "${AppointmentsPageRules.LAST_WENT} ${row.title}") { typed ->
                        scope.launch {
                            val r = runCatching { core.typeAppointment(AppointmentsPageRules.lastWentLine(row.title, typed)) }.getOrNull() ?: return@launch
                            show(r.line, r.refused)
                        }
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "appt-row-said") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) {
                                haptics.tick()
                                rowKey = null
                                scope.launch { runCatching { core.removeAppointment(row.id) } }
                            }
                            .semantics { contentDescription = "Remove ${row.title}" }
                            .minTouch()
                            .padding(MekaSpace.xs),
                    )
                }
            }
        }
    }
}
