package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import os.meka.core.domain.VaultFileOpened
import os.meka.core.domain.VaultFileRules
import os.meka.core.domain.VaultLockRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val TRIPS_LIST_TAG = "trips-list"

/**
 * Ask → More → Trips (travel mode, slice 1): one field for trips and their bookings — "Barcelona 14–18 Nov", then
 * "Flight BA478 Sat 14 Nov 07:40 ref ABC123", "Hotel Arts 14–18 Nov" — a row per trip (on now, then to come, then
 * just ended) with its dates and status. Tap a row to open its day-by-day timeline: each booking with its time (or its
 * kind) and × to remove it, and Remove for the trip. Today's header shows the trip a week ahead and while it's on.
 * Under the timeline, TRAVEL DOCUMENTS from the Vault ([TripDocRules]): each passport and travel card with whether it
 * lasts the trip (lit when not) and Open copy for a kept copy, after his fingerprint, offline too (the Vault's lock).
 * Completeness slice 4: × and Remove trip say what went with **Undo** for a few seconds ("Removed Barcelona and its 3
 * bookings · Undo"), and a trip a week past its end moves under **PAST TRIPS · n ▾** (folded; tap to show, newest
 * first, each still opening its timeline).
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the line under the field
 * cross-fade (a refused line lit in the accent); rows stagger in 40 ms apart and arrive, move and leave with the list's
 * item motion; an opened row grows on the expand spring and its days fade in; the status blends to the accent while the
 * trip is on; Add gives a light haptic (a tick when refused), Remove and × a tick; the Undo line expands in and fades
 * out, Undo a light haptic; past trips arrive and leave with the list's item motion. Reduced motion: cross-fades.
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
    var said by remember { mutableStateOf<TripAdded?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    // Completeness slice 4: what the last × or Remove trip took, offered back for a few seconds; past trips folded.
    var removed by remember { mutableStateOf<TripRemoved?>(null) }
    var undoShown by remember { mutableStateOf(false) }
    var showPast by remember { mutableStateOf(false) }
    LaunchedEffect(removed, undoShown) { if (undoShown) { delay(TripRules.UNDO_MS); undoShown = false } }
    val removeBooking: (String) -> Unit = { id ->
        haptics.tick()
        scope.launch { runCatching { core.removeTripBookingForUndo(id) }.getOrNull()?.let { removed = it; undoShown = true } }
    }
    val removeTrip: (String) -> Unit = { id ->
        haptics.tick(); open = null
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
            Text("Trips", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.TRIPS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "trips-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(TripRules.addHint(hasTrips = !v.isEmpty), description = TripRules.ADD_DESCRIPTION) { text ->
                    scope.launch {
                        val r = runCatching { core.addTrip(text) }.getOrNull()
                        if (r?.refused == false) haptics.light() else haptics.tick()
                        said = r
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "trips-added") { r ->
                    r?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                }
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
        }
        if (v.rows.isNotEmpty()) {
            item(key = "label") {
                SectionLabel("YOUR TRIPS", Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "trip-${r.id}" }) { i, r ->
            TripRowView(
                row = r,
                open = open == r.id,
                modifier = Modifier.animateItem().appear(rememberAppearance(4 + i)),
                onToggle = { open = if (open == r.id) null else r.id },
                onRemoveBooking = removeBooking,
                docSaid = docSaid,
                onOpenCopy = openCopy,
                onRemove = { removeTrip(r.id) },
                lists = lists.firstOrNull { it.tripId == r.id }?.summary,
                onOpenLists = { haptics.tick(); onOpenLists(r.id) },
            )
        }
        if (v.past.isNotEmpty()) {
            item(key = "past-label") {
                SectionLabel(
                    v.pastLabel + (if (showPast) " ▴" else " ▾"),
                    Modifier.animateItem().padding(top = MekaSpace.m)
                        .clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); showPast = !showPast }
                        .semantics { contentDescription = if (showPast) "Hide past trips" else "Show ${v.pastLabel.lowercase()}" }
                        .minTouch(),
                )
            }
            if (showPast) {
                itemsIndexed(v.past, key = { _, r -> "past-${r.id}" }) { _, r ->
                    TripRowView(
                        row = r,
                        open = open == r.id,
                        modifier = Modifier.animateItem(),
                        onToggle = { open = if (open == r.id) null else r.id },
                        onRemoveBooking = removeBooking,
                        docSaid = docSaid,
                        onOpenCopy = openCopy,
                        onRemove = { removeTrip(r.id) },
                        lists = lists.firstOrNull { it.tripId == r.id }?.summary,
                        onOpenLists = { haptics.tick(); onOpenLists(r.id) },
                    )
                }
            }
        }
        item(key = "note") {
            Text(TripRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
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

@Composable
private fun TripRowView(
    row: TripRow,
    open: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onRemoveBooking: (String) -> Unit,
    docSaid: Pair<String, String>?,
    onOpenCopy: (String) -> Unit,
    onRemove: () -> Unit,
    lists: String?,
    onOpenLists: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (row.statusLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trip-status",
    )
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(reduced))
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).minTouch()
                .semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(row.name, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(row.status, style = MekaType.caption, color = statusColor)
        }
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
        AnimatedVisibility(
            visible = open,
            enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                if (row.timeline.all { it.lines.isEmpty() }) {
                    Text(TripRules.NO_BOOKINGS, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                for (day in row.timeline) {
                    if (day.lines.isEmpty()) continue
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
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
        }
    }
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
