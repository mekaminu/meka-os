package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Field
import os.meka.core.domain.TripList
import os.meka.core.domain.TripListAdded
import os.meka.core.domain.TripLeaveSaved
import os.meka.core.domain.TripListCopied
import os.meka.core.domain.TripListGroup
import os.meka.core.domain.TripListRemoved
import os.meka.core.domain.TripListRow
import os.meka.core.domain.TripListRules
import os.meka.core.domain.TripProgress
import os.meka.core.domain.TripRules
import os.meka.core.domain.TripSuggestAdded
import os.meka.core.domain.TripSuggestRules
import os.meka.core.domain.TripSuggestion
import os.meka.core.domain.TripTemplateSaved
import os.meka.core.domain.TripWho
import os.meka.core.facade.MekaCore

/**
 * Trips → a trip → Lists (trip lists, slice 1; mockup `docs/design/trip/trip-lists.dc.html`): the trip's dates and days
 * to go, two thin bars (Bought n of N, Packed n of N), the three lists as tabs (To buy · To pack · Before we leave), one
 * add field ("buy sun cream" → To buy, "pack 2 chargers" → To pack × 2, else the open tab; the tab follows where it
 * went), Everyone / Me / Kids on To pack, and each list's groups with a count ("1 of 3", "All done"), folding on a tap.
 * A tick shows in the same frame (the core's write runs after; the rule for every tap); ticked items sink to the foot of
 * their group and fade. A long press removes an item, with Undo for a few seconds. Synced with the Mac.
 *
 * Motion: the header and bars stagger in; the bars fill on the appear spring; the tab content cross-fades; rows move
 * with the list's item motion (a tick sinks the row), the ring draws its check (TickRing) and the title fades and
 * strikes; groups fold on the expand spring with the chevron turning; the line under the field cross-fades (refused lit
 * in the accent). Haptics: light on a tick and an add, a tick on a tab, a filter, a fold or a remove. Reduced motion:
 * cross-fades only, the bars set at once.
 *
 * Slice 2d: a tap on the header (or "Add who's going and when you leave" on a new trip) opens who's going (Me, his
 * partner, the kids: chips lit at once, written after) and Leave home at ("06:30"); the dates line then counts the
 * people and the countdown says when he leaves. The editor opens and closes on the expand spring (a fade when reduced).
 *
 * Slice 2e: "Save as a template for next time" at the foot (a dashed outline, as the mockup; "Update the Lisbon template" once
 * saved); a new trip's empty lists offer saved templates first, then the last trip; a long press forgets a template.
 * The offers fold away on the expand spring once one is used; the saved line fades in and out.
 *
 * Slice 3b: **Obi's suggestions** above the add field, one card per tab (the core's [TripSuggestion]): Obi's line, the
 * chips (a tap adds that one), Add all and Not now. Nothing is added without a tap. A tapped chip leaves in the same
 * frame (the card's height follows on the expand spring), the card folds away once empty or put away, and "Added 3
 * things to To pack" fades in with Undo. Light haptic on an add, a tick on Not now. Reduced motion: fades only.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TripListsPane(core: MekaCore, tripId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val all by core.tripListsView.collectAsState()
    val v = all.firstOrNull { it.tripId == tripId }
    var tab by rememberSaveable { mutableStateOf(TripList.PACK) }
    var who by rememberSaveable { mutableStateOf(TripWho.EVERYONE) }
    var said by remember { mutableStateOf<TripListAdded?>(null) }
    var removed by remember { mutableStateOf<TripListRemoved?>(null) }
    // Slice 2b: what "Start from last trip" copied, with Undo for a few seconds; [copying] hides the card at once.
    var copied by remember { mutableStateOf<TripListCopied?>(null) }
    var copying by remember { mutableStateOf(false) }
    // Slice 2e: what "Save as a template for next time" said, for a few seconds.
    var templateSaid by remember { mutableStateOf<TripTemplateSaved?>(null) }
    // Slice 3b: chips this screen has added or put away, hidden at once until the view agrees; what adding said.
    val suggestGone = remember { mutableStateMapOf<String, Boolean>() }
    var suggestSaid by remember { mutableStateOf<TripSuggestAdded?>(null) }
    LaunchedEffect(suggestSaid) { if (suggestSaid != null) { delay(TripRules.UNDO_MS); suggestSaid = null } }
    // The tap's result in the next frame: what this screen ticked, until the view says the same.
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    // Slice 2d: the who's-going editor, its chips lit at once (until the view agrees), and what the leave time said.
    var editing by rememberSaveable { mutableStateOf(false) }
    val goingPending = remember { mutableStateMapOf<String, Boolean>() }
    var leaveSaid by remember { mutableStateOf<TripLeaveSaved?>(null) }
    LaunchedEffect(v?.going) {
        val shown = v?.going.orEmpty().associate { it.wire to it.on }
        goingPending.keys.toList().forEach { w -> if (shown[w] == goingPending[w]) goingPending.remove(w) }
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(v?.suggestions) {
        val offered = v?.suggestions.orEmpty().flatMap { c -> c.chips.map { it.key } }.toSet()
        suggestGone.keys.toList().forEach { k -> if (k !in offered) suggestGone.remove(k) }
    }
    LaunchedEffect(v) {
        val shown = v?.tabs.orEmpty().flatMap { t -> t.groups.flatMap { it.rows } }.associate { it.id to it.done }
        pending.keys.toList().forEach { id -> if (shown[id] == null || shown[id] == pending[id]) pending.remove(id) }
    }
    LaunchedEffect(removed) { if (removed != null) { delay(TripRules.UNDO_MS); removed = null } }
    LaunchedEffect(copied) { if (copied != null) { delay(TripRules.UNDO_MS); copied = null } }
    LaunchedEffect(templateSaid) { if (templateSaid != null) { delay(TripRules.UNDO_MS); templateSaid = null } }
    // Once the view has the copy (no offer any more), the card's own hiding is no longer needed.
    LaunchedEffect(v?.startFrom) { if (v?.startFrom == null) copying = false }
    val tick: (TripListRow, Boolean) -> Unit = { row, done ->
        haptics.light()
        pending[row.id] = done
        scope.launch { if (runCatching { core.tickTripItem(row.id, done) }.isFailure) pending.remove(row.id) }
    }
    val remove: (TripListRow) -> Unit = { row ->
        haptics.tick()
        scope.launch { runCatching { core.removeTripItem(row.id) }.getOrNull()?.let { removed = it } }
    }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "trip-list-said",
    )

    LazyColumn(
        Modifier.fillMaxSize().testTag("trip-lists"),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "back") {
            Text("‹ Trips", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onBack() }.semantics { contentDescription = "Back to Trips" }.minTouch())
        }
        if (v == null) {
            item(key = "gone") { Text(TripListRules.GONE, style = MekaType.body, color = Meka.colors.textSecondary) }
            return@LazyColumn
        }
        item(key = "head") {
            Column(
                Modifier.fillMaxWidth().appear(rememberAppearance(0)).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClickLabel = TripListRules.EDIT) { haptics.tick(); editing = !editing }
                    .semantics(mergeDescendants = true) { if (editing) stateDescription = "Editing who's going" }
                    .testTag("trip-head"),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                Text(v.title, style = MekaType.nowTitle, color = Meka.colors.textPrimary)
                Crossfade(v.dates, animationSpec = MekaMotion.appear(reduced), label = "trip-dates") { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                Crossfade(v.countdown, animationSpec = MekaMotion.appear(reduced), label = "trip-countdown") {
                    Text(it, style = MekaType.captionStrong, color = if (v.countdownLit) Meka.colors.accent else Meka.colors.textSecondary)
                }
                if (v.needsSetUp && !editing) {
                    Text(TripListRules.SET_UP, style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.minTouch().testTag("trip-set-up"))
                }
            }
        }
        item(key = "going") {
            AnimatedVisibility(
                visible = editing,
                enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
                exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                        .padding(MekaSpace.m).testTag("trip-going"),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    Text(TripListRules.GOING_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        for (c in v.going) {
                            val on = goingPending[c.wire] ?: c.on
                            val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "trip-going-chip")
                            Text(
                                c.label, style = MekaType.caption, color = if (on) Meka.colors.onAccent else Meka.colors.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                                    .clickable(role = Role.Checkbox) {
                                        haptics.light()
                                        goingPending[c.wire] = !on
                                        scope.launch { if (runCatching { core.setTripGoing(tripId, c.wire, !on) }.isFailure) goingPending.remove(c.wire) }
                                    }
                                    .semantics { contentDescription = c.label; stateDescription = if (on) "Going" else "Not going" }
                                    .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("trip-going-${c.wire}"),
                            )
                        }
                    }
                    Text(TripListRules.LEAVE_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
                    Field(v.leave.ifEmpty { TripListRules.LEAVE_HINT }, description = TripListRules.LEAVE_DESCRIPTION) { text ->
                        scope.launch {
                            val r = runCatching { core.setTripLeave(tripId, text) }.getOrNull()
                            if (r?.refused == false) haptics.light() else haptics.reject()
                            leaveSaid = r
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Crossfade(leaveSaid, animationSpec = MekaMotion.appear(reduced), label = "trip-leave-said", modifier = Modifier.weight(1f)) { r ->
                            Text(r?.line ?: TripListRules.LEAVE_DESCRIPTION, style = MekaType.caption,
                                color = if (r?.refused == true) Meka.colors.accent else if (r == null) Meka.colors.textTertiary else Meka.colors.textSecondary)
                        }
                        if (v.leave.isNotEmpty()) {
                            Text("Clear", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                    .clickable(role = Role.Button) {
                                        haptics.tick()
                                        scope.launch { leaveSaid = runCatching { core.setTripLeave(tripId, "") }.getOrNull() }
                                    }
                                    .semantics { contentDescription = "Clear the leave time" }
                                    .minTouch().padding(MekaSpace.xs))
                        }
                        Text(TripListRules.DONE, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { haptics.tick(); editing = false; leaveSaid = null }
                                .minTouch().padding(MekaSpace.xs).testTag("trip-going-done"))
                    }
                }
            }
        }
        item(key = "bars") {
            Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs).appear(rememberAppearance(1)), horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                ProgressBar(v.bought, Modifier.weight(1f))
                ProgressBar(v.packed, Modifier.weight(1f))
            }
        }
        item(key = "tabs") {
            Row(
                Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                    .padding(MekaSpace.xxs).appear(rememberAppearance(2)),
            ) {
                for (t in v.tabs) {
                    val on = t.list == tab
                    val bg by animateColorAsState(if (on) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(reduced), label = "trip-tab")
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                            .clickable(role = Role.Tab) { if (!on) { haptics.tick(); tab = t.list; said = null } }
                            .semantics { contentDescription = t.label + if (t.left > 0) ", ${t.left} to go" else ""; if (on) stateDescription = "Selected" }
                            .minTouch().testTag("trip-tab-${t.list.wire}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.label, style = MekaType.captionStrong, color = if (on) Meka.colors.accent else Meka.colors.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item(key = "suggest") {
            val card = v.suggestion(tab)
            val chips = card?.chips.orEmpty().filter { suggestGone[it.key] != true }
            Column(Modifier.animateContentSize(MekaMotion.expand(reduced))) {
                AnimatedVisibility(
                    visible = chips.isNotEmpty(),
                    enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
                    exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
                ) {
                    if (card != null) SuggestCard(card, chips.map { it.key }.toSet(),
                        onAdd = { keys ->
                            haptics.light()
                            keys.forEach { suggestGone[it] = true }
                            scope.launch {
                                val r = runCatching { core.addTripSuggestions(tripId, card.list, keys.toList()) }.getOrNull()
                                if (r == null || r.refused) { haptics.reject(); keys.forEach { suggestGone.remove(it) } }
                                suggestSaid = r
                            }
                        },
                        onNotNow = { keys ->
                            haptics.tick()
                            keys.forEach { suggestGone[it] = true }
                            scope.launch { if (runCatching { core.dismissTripSuggestions(tripId, keys.toList()) }.isFailure) keys.forEach { suggestGone.remove(it) } }
                        })
                }
                AnimatedVisibility(visible = suggestSaid != null, enter = fadeIn(), exit = fadeOut()) {
                    suggestSaid?.let { r ->
                        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            Text(r.line, style = MekaType.caption, color = if (r.refused) Meka.colors.accent else Meka.colors.textSecondary,
                                modifier = Modifier.weight(1f).testTag("trip-suggest-said"))
                            if (!r.refused) {
                                Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                        .clickable(role = Role.Button) {
                                            haptics.light(); suggestSaid = null
                                            scope.launch { runCatching { core.undoTripListsCopy(r.token) }; suggestGone.clear() }
                                        }
                                        .semantics { contentDescription = "Undo: ${r.line}" }
                                        .minTouch().padding(MekaSpace.xs).testTag("trip-suggest-undo"))
                            }
                        }
                    }
                }
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(v.tab(tab).hint, description = TripListRules.ADD_DESCRIPTION) { text ->
                    scope.launch {
                        val r = runCatching { core.addTripItem(tripId, text, tab) }.getOrNull()
                        if (r?.refused == false) { haptics.light(); tab = r.list } else haptics.reject()
                        said = r
                    }
                }
                Crossfade(said?.line ?: TripListRules.ADD_NOTE, animationSpec = MekaMotion.appear(reduced), label = "trip-list-added") { line ->
                    Text(line, style = MekaType.caption, color = if (said != null) saidColor else Meka.colors.textTertiary)
                }
                // Slice 2e: every way to start empty lists: saved templates (newest first), then the last trip.
                for (from in v.startOptions) {
                    AnimatedVisibility(
                        visible = !copying,
                        enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
                        exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
                    ) {
                        val tpl = from.templateId
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                                .combinedClickable(
                                    role = Role.Button,
                                    onClick = {
                                        haptics.light(); copying = true
                                        scope.launch {
                                            val r = runCatching {
                                                if (tpl != null) core.startTripFromTemplate(tpl, tripId) else core.copyTripLists(from.sourceTripId, tripId)
                                            }.getOrNull()
                                            copied = r
                                            if (r == null || r.refused) { haptics.reject(); copying = false }
                                        }
                                    },
                                    onLongClick = if (tpl == null) null else ({
                                        haptics.tick()
                                        scope.launch { runCatching { core.forgetTripTemplate(tpl) } }
                                    }),
                                    onLongClickLabel = if (tpl == null) null else TripListRules.FORGET_TEMPLATE,
                                )
                                .semantics(mergeDescendants = true) { contentDescription = from.spoken }
                                .minTouch().padding(MekaSpace.m).testTag(if (tpl == null) "trip-start-from" else "trip-start-template"),
                            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                        ) {
                            Text(from.line, style = MekaType.itemMeta, color = Meka.colors.accent)
                            Text(from.sub, style = MekaType.caption, color = Meka.colors.textSecondary)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = copied != null,
                    enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
                    exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
                ) {
                    copied?.let { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            Text(c.line, style = MekaType.caption, color = if (c.refused) Meka.colors.accent else Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                            if (!c.refused) {
                                Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                        .clickable(role = Role.Button) {
                                            haptics.light(); copied = null; copying = false
                                            scope.launch { runCatching { core.undoTripListsCopy(c.token) } }
                                        }
                                        .semantics { contentDescription = "Undo: ${c.line}" }
                                        .minTouch().padding(MekaSpace.xs).testTag("trip-copy-undo"))
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = removed != null,
                    enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
                    exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
                ) {
                    removed?.let { r ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                            Text("Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                    .clickable(role = Role.Button) {
                                        haptics.light(); removed = null
                                        scope.launch { runCatching { core.undoTripItemRemoval(r.token) } }
                                    }
                                    .semantics { contentDescription = "Undo: ${r.line}" }
                                    .minTouch().padding(MekaSpace.xs))
                        }
                    }
                }
            }
        }
        // Slice 2d: Kids goes from the filters when the kids aren't going (and nothing is theirs).
        val whoShown = if (who in v.whoFilters) who else TripWho.EVERYONE
        if (tab == TripList.PACK) {
            item(key = "who") {
                Row(Modifier.animateItem(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    for (w in v.whoFilters) {
                        val on = w == whoShown
                        Text(
                            w.label, style = MekaType.caption, color = if (on) Meka.colors.onAccent else Meka.colors.textPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                .background(if (on) Meka.colors.accent else Meka.colors.surface)
                                .clickable(role = Role.Tab) { haptics.tick(); who = w }
                                .semantics { if (on) stateDescription = "Selected" }
                                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                        )
                    }
                }
            }
        }
        val groups = v.groups(tab, whoShown)
        if (groups.isEmpty()) {
            item(key = "empty-${tab.wire}") {
                Text(v.tab(tab).empty, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.m))
            }
        }
        for (g in groups) {
            val open = folded[g.key] != true
            item(key = "group-${g.key}") {
                GroupHead(g, open, pending, Modifier.animateItem()) { haptics.tick(); folded[g.key] = open }
            }
            if (open) {
                // A row ticked here sinks at once (a stable sort keeps the rest in their order).
                val rows = g.rows.sortedBy { pending[it.id] ?: it.done }
                items(rows, key = { "item-${it.id}" }) { row ->
                    ItemRow(row, pending[row.id] ?: row.done, Modifier.animateItem(), onTick = { tick(row, it) }, onRemove = { remove(row) })
                }
            }
        }
        // Slice 2e: "Save as a template for next time" (or "Update the Lisbon template"), a dashed button at the foot.
        v.saveTemplate?.let { save ->
            item(key = "save-template") {
                Column(Modifier.animateItem().padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    val dash = Meka.colors.hairline
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                            .drawBehind {
                                drawRoundRect(
                                    dash, cornerRadius = CornerRadius(MekaRadius.m.toPx()),
                                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                                )
                            }
                            .clickable(role = Role.Button) {
                                scope.launch {
                                    val r = runCatching { core.saveTripTemplate(tripId) }.getOrNull()
                                    if (r?.refused == false) haptics.light() else haptics.reject()
                                    templateSaid = r
                                }
                            }
                            .semantics { contentDescription = save }
                            .minTouch().testTag("trip-save-template"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(save, style = MekaType.itemMeta, color = Meka.colors.accent)
                    }
                    AnimatedVisibility(visible = templateSaid != null, enter = fadeIn(), exit = fadeOut()) {
                        templateSaid?.let { r ->
                            Text(r.line, style = MekaType.caption, color = if (r.refused) Meka.colors.accent else Meka.colors.textSecondary,
                                modifier = Modifier.fillMaxWidth().testTag("trip-template-said"))
                        }
                    }
                }
            }
        }
        item(key = "note") {
            Text(TripListRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

/**
 * Slice 3b: Obi's card for one tab: a small brass orb and his line, the chips (only [keys] still showing; a tap adds
 * that one), then Add all and Not now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestCard(card: TripSuggestion, keys: Set<String>, onAdd: (Set<String>) -> Unit, onNotNow: (Set<String>) -> Unit) {
    val accent = Meka.colors.accent
    Column(
        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface).border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(MekaRadius.m))
            .padding(MekaSpace.m).testTag("trip-suggest-${card.list.wire}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.Top,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "${TripSuggestRules.TITLE}. ${card.lead}" }) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(MekaRadius.pill))
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.35f)))))
            Text(card.lead, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (c in card.chips) {
                if (c.key !in keys) continue
                Text(
                    c.label, style = MekaType.caption, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .clickable(role = Role.Button, onClickLabel = "Add") { onAdd(setOf(c.key)) }
                        .semantics { contentDescription = "Add ${c.spoken}" }
                        .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("trip-suggest-chip-${c.key}"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(TripSuggestRules.ADD_ALL, style = MekaType.captionStrong, color = Meka.colors.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(accent)
                    .clickable(role = Role.Button) { onAdd(keys) }
                    .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("trip-suggest-add-all"))
            Text(TripSuggestRules.NOT_NOW, style = MekaType.captionStrong, color = Meka.colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button) { onNotNow(keys) }
                    .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("trip-suggest-not-now"))
        }
    }
}

/** "Bought" · "3 of 9" over a thin bar filling to its share. */
@Composable
private fun ProgressBar(p: TripProgress, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val fill by animateFloatAsState(p.fraction, MekaMotion.appear(reduced), label = "trip-bar")
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "${p.label} ${p.line}" }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(Modifier.fillMaxWidth()) {
            Text(p.label, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
            Text(p.line, style = MekaType.captionStrong, color = Meka.colors.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        }
    }
}

/** A group's head: its name, its count ("1 of 3", "All done" in green) and a chevron; a tap folds it. */
@Composable
private fun GroupHead(g: TripListGroup, open: Boolean, pending: Map<String, Boolean>, modifier: Modifier, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val done = g.rows.count { pending[it.id] ?: it.done }
    val allDone = g.rows.isNotEmpty() && done == g.rows.size
    val count = if (allDone) "All done" else "$done of ${g.rows.size}"
    val turn by animateFloatAsState(if (open) 180f else 0f, MekaMotion.expand(reduced), label = "trip-group-chev")
    Row(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = "${g.name}, $count, " + if (open) "tap to fold" else "tap to open" }
            .minTouch(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(g.name.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
        Text(count, style = MekaType.caption, color = if (allDone) Meka.colors.success else Meka.colors.textTertiary)
        Text("⌄", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.rotate(turn))
    }
}

/** One item: the ring (22 dp in a 48 dp target), its name (struck and faded once ticked), whose, and "× 5". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(row: TripListRow, done: Boolean, modifier: Modifier, onTick: (Boolean) -> Unit, onRemove: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fade by animateFloatAsState(if (done) 0.5f else 1f, MekaMotion.appear(reduced), label = "trip-item-fade")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .combinedClickable(role = Role.Checkbox, onClick = { onTick(!done) }, onLongClick = onRemove, onLongClickLabel = "Remove")
            .semantics(mergeDescendants = true) {
                contentDescription = row.spoken.removeSuffix(", ticked")
                stateDescription = if (done) "Ticked" else "Not ticked"
            }
            .minTouch().testTag("trip-item-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        TickRing(done, Modifier.size(22.dp))
        Column(Modifier.weight(1f).alpha(fade)) {
            Text(row.text, style = MekaType.taskTitle, color = Meka.colors.textPrimary,
                textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (row.sub.isNotEmpty()) Text(row.sub, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        if (row.qty.isNotEmpty()) Text(row.qty, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.alpha(fade))
    }
}
