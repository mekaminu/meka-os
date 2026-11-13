package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
import os.meka.android.export.VaultCopyViewer
import os.meka.android.lists.Field
import os.meka.android.security.FoldBiometric
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.TripAdded
import os.meka.core.domain.TripDocRow
import os.meka.core.domain.TripDocRules
import os.meka.core.domain.TripListRules
import os.meka.core.domain.TripRemoved
import os.meka.core.domain.TripRow
import os.meka.core.domain.TripRules
import os.meka.core.domain.TripsPageRules
import os.meka.core.domain.VaultFileOpened
import os.meka.core.domain.VaultFileRules
import os.meka.core.domain.VaultLockRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val TRIPS_LIST_TAG = "trips-list"
const val TRIPS_ROW_SHEET_TAG = "trips-row-sheet"
const val TRIPS_ALL_SHEET_TAG = "trips-all-sheet"

/**
 * Ask → Trips in the page shape (every page under Ask in one shape, slice 16; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A trip · A booking, one typed line the
 * Trips field reads: "Barcelona 14–18 Nov", "Flight BA478 14 Nov 07:40 ref ABC123"), three numbers (how soon the next
 * trip is or which day of it, what's booked for it, the travel documents to check), then Your trips, Booked for the
 * next trip, Travel documents and Past trips (five rows each, See all for the rest). A trip's row opens its sheet: its
 * day-by-day timeline with × on each booking, Lists › (To buy, To pack, Before we leave), its TRAVEL DOCUMENTS from
 * the Vault with Open copy (after his fingerprint, offline too) and Remove trip; a booking's row its day, Remove and
 * the whole trip; a document's row its line and Open copy. × and Remove trip say what went with **Undo** for a few
 * seconds under the summary. Today's header shows the trip a week ahead and while it's on.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); a trip's Lists cross-fade in place of Trips; the line under the summary cross-fades (a refusal lit
 * in the accent, tick haptic; an add a light haptic); the Undo line expands in and fades out, Undo a light haptic.
 * Reduced motion: cross-fades.
 */
@Composable
fun TripsPane(core: MekaCore, onClose: () -> Unit) {
    // Trip lists, slice 1: a trip's Lists open in place of Trips (‹ Trips or Back returns), cross-fading.
    var listsFor by rememberSaveable { mutableStateOf<String?>(null) }
    Crossfade(listsFor, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "trips-lists") { id ->
        if (id != null) TripListsPane(core, id, onBack = { listsFor = null })
        else TripsHome(core, onClose, onOpenLists = { listsFor = it })
    }
}

@Composable
private fun TripsHome(core: MekaCore, onClose: () -> Unit, onOpenLists: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.tripsView.collectAsState()
    val lists by core.tripListsView.collectAsState()
    val page = remember(v) { core.tripsPage(v) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var addText by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<TripAdded?>(null) }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    // Completeness slice 4: what the last × or Remove trip took, offered back for a few seconds.
    var removed by remember { mutableStateOf<TripRemoved?>(null) }
    var undoShown by remember { mutableStateOf(false) }
    LaunchedEffect(removed, undoShown) { if (undoShown) { delay(TripRules.UNDO_MS); undoShown = false } }
    fun open(key: String) { haptics.tick(); said = null; rowKey = key }
    val removeBooking: (String) -> Unit = { id ->
        haptics.tick()
        scope.launch { runCatching { core.removeTripBookingForUndo(id) }.getOrNull()?.let { removed = it; undoShown = true } }
    }
    val removeTrip: (String) -> Unit = { id ->
        haptics.tick(); rowKey = null
        scope.launch { runCatching { core.removeTripForUndo(id) }.getOrNull()?.let { removed = it; undoShown = true } }
    }
    val context = LocalContext.current
    // Travel documents: the copy shown, and what the last Open copy said (by document).
    var viewing by remember { mutableStateOf<VaultFileOpened?>(null) }
    var docSaid by remember { mutableStateOf<Pair<String, String>?>(null) }
    val openCopy: (String) -> Unit = { docId ->
        val show: () -> Unit = {
            scope.launch {
                val o = runCatching { core.openVaultCopy(docId) }.getOrNull()
                if (o?.base64 != null) { haptics.light(); docSaid = null; viewing = o } else {
                    haptics.tick()
                    docSaid = docId to (o?.problem ?: VaultFileRules.OFFLINE)
                }
            }
        }
        // The Vault's lock (slice 4): a copy opens after his fingerprint (the phone's PIN with none set up).
        scope.launch {
            if (runCatching { core.vaultCopiesLocked() }.getOrDefault(true)) {
                FoldBiometric.unlock(context, VaultLockRules.UNLOCK_REASON, VaultLockRules.UNLOCK_SUB) { ok ->
                    if (ok) scope.launch { runCatching { core.unlockVault() }; show() } else {
                        haptics.tick()
                        docSaid = docId to VaultLockRules.LOCKED
                    }
                }
            } else show()
        }
    }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trips-said",
    )

    // The Undo after × or Remove trip: under the summary, or in the trip's sheet while it's open.
    val undoLine: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = undoShown && removed != null,
            enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            removed?.let { r ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(
                        "Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) {
                                haptics.light()
                                undoShown = false
                                scope.launch { runCatching { core.undoTripRemoval(r.token) } }
                            }
                            .semantics { contentDescription = "Undo: ${r.line}" }
                            .minTouch().padding(MekaSpace.xs),
                    )
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(TRIPS_LIST_TAG),
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
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.TRIPS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "trips-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    val line = if (addOpen) null else said
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "trips-added") { r ->
                        r?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                    }
                    if (rowKey == null) undoLine()
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
            item(key = "note") {
                Text(TripRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: a trip or a booking, one typed line; the Trips field tells one from the other.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(
                    TripsPageRules.hint(choice), value = addText, onValue = { addText = it },
                    description = TripsPageRules.spokenHint(choice), keep = true,
                ) { typed ->
                    scope.launch {
                        val r = runCatching { core.addTrip(typed) }.getOrNull() ?: return@launch
                        said = r
                        if (r.refused) haptics.tick() else { haptics.light(); addText = ""; addOpen = false }
                    }
                }
                said?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every row of one card; a tap opens its sheet.
        val all = allCard
        val today = core.todayEpochDay()
        LifeSheet(all != null, all?.let { TripsPageRules.cardTitle(v, it, today) } ?: TripsPageRules.TITLE, TRIPS_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) TripsPageRules.allRows(v, all, today).forEach { r ->
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
        // A row's sheet: a trip (its timeline, lists, documents, Remove trip), a booking, or a travel document.
        val openKey = rowKey
        LifeSheet(openKey != null, openKey?.let { TripsPageRules.sheetTitle(v, it, today) } ?: TripsPageRules.TITLE, TRIPS_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            if (openKey == null) return@LifeSheet
            val trip = TripsPageRules.trip(v, openKey)
            val booking = TripsPageRules.booking(v, openKey)
            val doc = TripsPageRules.doc(v, openKey, today)
            when {
                trip != null -> TripSheetContent(
                    row = trip,
                    undoLine = undoLine,
                    lists = lists.firstOrNull { it.tripId == trip.id }?.summary,
                    onOpenLists = { haptics.tick(); rowKey = null; onOpenLists(trip.id) },
                    onRemoveBooking = removeBooking,
                    docSaid = docSaid,
                    onOpenCopy = openCopy,
                    onRemove = { removeTrip(trip.id) },
                )
                booking != null -> {
                    val inTrip = TripsPageRules.bookingTrip(v, openKey)
                    val day = TripsPageRules.bookingDay(v, openKey)
                    Text(listOfNotNull(day?.label, inTrip?.name).joinToString(" · "), style = MekaType.caption, color = Meka.colors.textTertiary)
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = booking.spoken }) {
                        Text(booking.lead, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.width(56.dp))
                        Text(booking.text, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                        Text(
                            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { rowKey = null; removeBooking(booking.bookingId) }
                                .semantics { contentDescription = "Remove ${booking.text}" }
                                .minTouch().padding(MekaSpace.xs),
                        )
                        inTrip?.let { t ->
                            Text(
                                "All of ${t.name} ›", style = MekaType.caption, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                    .clickable(role = Role.Button) { open(TripsPageRules.tripKey(t.id)) }
                                    .minTouch().padding(MekaSpace.xs),
                            )
                        }
                    }
                }
                doc != null -> {
                    TripDocView(doc, docSaid?.takeIf { it.first == doc.docId }?.second) { openCopy(doc.docId) }
                    if (doc.hasCopy) Text(TripDocRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                else -> Text(TripsPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
    }
    viewing?.let { VaultCopyViewer(it, onClose = { viewing = null }) }
    // As in the Vault: the app going to the background, or leaving Trips, locks the copies again and closes a shown one.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        viewing = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch { runCatching { core.lockVault() } }
    }
    DisposableEffect(Unit) {
        onDispose { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch { runCatching { core.lockVault() } } }
    }
}

/** A trip's sheet: its status and dates, Lists ›, its day-by-day timeline with ×, its travel documents and Remove trip. */
@Composable
private fun TripSheetContent(
    row: TripRow,
    undoLine: @Composable () -> Unit,
    lists: String?,
    onOpenLists: () -> Unit,
    onRemoveBooking: (String) -> Unit,
    docSaid: Pair<String, String>?,
    onOpenCopy: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (row.statusLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trip-status",
    )
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
        Text(row.status, style = MekaType.caption, color = statusColor)
    }
    undoLine()
    // Trip lists, slice 1: To buy, To pack and Before we leave, one tap away on every trip.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOpenLists)
            .semantics(mergeDescendants = true) { contentDescription = "Open the lists for ${row.name}: ${lists ?: TripListRules.LISTS_EMPTY}" }
            .minTouch().testTag("trip-lists-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(TripListRules.OPEN_LISTS, style = MekaType.captionStrong, color = Meka.colors.accent)
        Text(lists ?: TripListRules.LISTS_EMPTY, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Text("›", style = MekaType.body, color = Meka.colors.accent)
    }
    if (row.timeline.all { it.lines.isEmpty() }) {
        Text(TripRules.NO_BOOKINGS, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
    for (day in row.timeline) {
        if (day.lines.isEmpty()) continue
        Column(Modifier.animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            SectionLabel(day.label.uppercase())
            for (line in day.lines) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = line.spoken }) {
                        Text(line.lead, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.width(56.dp))
                        Text(line.text, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                    Text("×", style = MekaType.body, color = Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) { onRemoveBooking(line.bookingId) }
                            .semantics { contentDescription = "Remove ${line.text}" }
                            .minTouch().padding(MekaSpace.xs))
                }
            }
        }
    }
    row.documents?.let { docs ->
        Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            SectionLabel(TripDocRules.LABEL)
            val summaryColor by animateColorAsState(
                if (docs.summaryLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trip-docs",
            )
            Text(docs.summary, style = MekaType.caption, color = summaryColor)
            for (d in docs.rows) TripDocView(d, docSaid?.takeIf { it.first == d.docId }?.second) { onOpenCopy(d.docId) }
            if (!docs.isEmpty && docs.rows.any { it.hasCopy }) {
                Text(TripDocRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
    Text(
        "Remove trip", style = MekaType.caption, color = Meka.colors.textTertiary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove ${row.name} and its bookings" }
            .minTouch().padding(MekaSpace.xs),
    )
}

/** One travel document: its name, whether it lasts the trip (lit when not) and Open copy for a kept copy. */
@Composable
private fun TripDocView(d: TripDocRow, said: String?, onOpenCopy: () -> Unit) {
    val reduced = Meka.reducedMotion
    val lineColor by animateColorAsState(
        if (d.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trip-doc-line",
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = d.spoken }) {
            Text(d.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(d.line, style = MekaType.caption, color = lineColor)
            Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "trip-doc-said") { s ->
                s?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
            }
        }
        if (d.hasCopy) {
            Text(
                "Open copy", style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClick = onOpenCopy)
                    .semantics { contentDescription = "Open the copy of ${d.title}" }
                    .minTouch().padding(MekaSpace.xs),
            )
        }
    }
}
