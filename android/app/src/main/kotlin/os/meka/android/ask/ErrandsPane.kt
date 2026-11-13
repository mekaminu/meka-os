package os.meka.android.ask

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import os.meka.core.domain.ErrandAdded
import os.meka.core.domain.ErrandGroup
import os.meka.core.domain.ErrandPlaceRules
import os.meka.core.domain.ErrandRow
import os.meka.core.domain.ErrandRules
import os.meka.core.domain.ErrandsPageRules
import os.meka.core.facade.MekaCore

/** Errands' list, for the by-hand tests' bounded scroll. */
const val ERRANDS_LIST_TAG = "errands-list"
const val ERRANDS_ROW_SHEET_TAG = "errands-row-sheet"
const val ERRANDS_ALL_SHEET_TAG = "errands-all-sheet"

/**
 * Ask → Errands in the page shape (every page under Ask in one shape, slice 7; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (Things to do at a place, a field in the
 * sheet: "Tesco: milk, batteries"), three numbers (errands waiting, places, done this week), then Waiting (a row a
 * place, what's there and how many), Done lately and Near-me reminders. A place's row opens its sheet with each
 * errand's Done and Remove and, with near-me on, its spot's I'm here / Forget spot; a done row's sheet has Back and
 * Remove; the near-me row's sheet has the switch (precise, then "Allow all the time" location) and the privacy line.
 * Android reminds "Near Tesco · milk, batteries" on arriving ([ErrandProximity]).
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the answer line cross-fades (lit in the accent when refused, with a tick haptic; an add or Done a
 * light haptic); in a place's sheet the errands move on the expand spring as one is ticked off; the near-me line
 * cross-fades and blends to the accent when it needs a look; a spot's line cross-fades through "Finding where you
 * are…" to "Saved … here". Reduced motion: cross-fades.
 */
@Composable
fun ErrandsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.errandsView.collectAsState()
    var said by remember { mutableStateOf<ErrandAdded?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var nearOn by remember { mutableStateOf(ErrandProximity.isOn(context)) }
    var precise by remember { mutableStateOf(ErrandProximity.precise(context)) }
    var always by remember { mutableStateOf(ErrandProximity.always(context)) }
    var spots by remember { mutableStateOf(ErrandProximity.saved(context)) }
    var spotSaid by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun rearm() {
        precise = ErrandProximity.precise(context)
        always = ErrandProximity.always(context)
        spots = ErrandProximity.saved(context)
        runCatching { ErrandProximity.arm(context, core.errandsView.value) }
    }
    // Coming back from Android's settings (where "Allow all the time" is given): read the permissions again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { rearm() }
    val askAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { rearm() }
    val askPrecise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        rearm()
        if (precise && !always) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    val near = ErrandPlaceRules.setting(nearOn, precise, always, spots.size)
    val page = remember(v, near) { core.errandsPage(v, near) }
    val nearColor by animateColorAsState(
        if (near.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "errand-near",
    )
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "errand-said",
    )
    fun done(id: String, isDone: Boolean) {
        if (isDone) haptics.light() else haptics.tick()
        scope.launch { runCatching { core.errandDone(id, isDone) } }
    }
    fun remove(id: String) { haptics.tick(); scope.launch { runCatching { core.removeErrand(id) } } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(ERRANDS_LIST_TAG),
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
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ERRANDS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = ErrandsPageRules.ADD_CHOICES.singleOrNull()?.key; said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "errand-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    // What the last add said, while no sheet is open.
                    if (!addOpen) Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "errand-line") { l ->
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
                        onRow = { r -> haptics.tick(); spotSaid = null; rowKey = r.key },
                    )
                }
            }
            item(key = "note") {
                Text(ErrandRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: the place, a colon, then what to do there.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(ErrandsPageRules.hint(choice)) { typed ->
                    scope.launch {
                        val r = runCatching { core.addErrand(typed) }.getOrNull() ?: return@launch
                        said = r
                        if (r.refused) haptics.tick() else { haptics.light(); addOpen = false }
                    }
                }
                said?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every row of one card, a tap opens its sheet.
        val all = allCard
        LifeSheet(all != null, all ?: ErrandsPageRules.TITLE, ERRANDS_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) ErrandsPageRules.allRows(v, all).forEach { r ->
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
        // A row's sheet: a place's errands, one done errand, or the near-me switch.
        val openKey = rowKey
        val place = openKey?.let { ErrandsPageRules.place(v, it) }
        val errand = openKey?.let { ErrandsPageRules.errand(v, it) }
        val isNear = openKey == ErrandsPageRules.KEY_NEAR
        val sheetTitle = place?.place ?: errand?.shown ?: if (isNear) ErrandsPageRules.NEAR else ErrandsPageRules.TITLE
        LifeSheet(openKey != null, sheetTitle, ERRANDS_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            when {
                place != null -> {
                    Column(Modifier.animateContentSize(MekaMotion.expand(reduced)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        place.rows.forEach { r ->
                            key(r.id) {
                                ErrandRowView(r, Modifier, primary = "Done", onPrimary = { done(r.id, true) }, onRemove = { remove(r.id) })
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = nearOn,
                        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                    ) {
                        PlaceSpotLine(
                            place, spots.containsKey(place.key), spotSaid?.takeIf { it.first == place.key }?.second,
                            onAction = {
                                haptics.tick()
                                if (spots.containsKey(place.key)) {
                                    ErrandProximity.forget(context, place.key, core.errandsView.value)
                                    spots = ErrandProximity.saved(context)
                                    spotSaid = null
                                } else if (!precise) {
                                    askPrecise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                } else {
                                    spotSaid = place.key to ErrandPlaceRules.SAVING
                                    scope.launch {
                                        val r = ErrandProximity.saveHere(context, place.place, core.errandsView.value)
                                        spots = r.places
                                        spotSaid = place.key to r.line
                                        if (r.saved) haptics.light() else haptics.tick()
                                    }
                                }
                            },
                        )
                    }
                }
                errand != null -> {
                    ErrandRowView(
                        errand, Modifier,
                        primary = if (errand.done) "Back" else "Done",
                        onPrimary = { done(errand.id, !errand.done) },
                        onRemove = { rowKey = null; remove(errand.id) },
                    )
                }
                isNear -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Crossfade(near.statusLine, animationSpec = MekaMotion.appear(reduced), label = "errand-near-line",
                            modifier = Modifier.weight(1f).then(
                                if (near.lit) Modifier.clickable(role = Role.Button) { haptics.tick(); openAppSettings(context) }.minTouch() else Modifier,
                            )) { l -> Text(l, style = MekaType.caption, color = nearColor) }
                        Box(
                            // Works by hand (quality gate): the slim pill drawn inside its 48 dp touch.
                            Modifier.padding(start = MekaSpace.m).clip(RoundedCornerShape(MekaRadius.pill))
                                .clickable(role = Role.Button) {
                                    haptics.tick()
                                    nearOn = !nearOn
                                    ErrandProximity.setOn(context, nearOn)
                                    rearm()
                                    if (nearOn && !precise) {
                                        askPrecise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                    } else if (nearOn && !always) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                                .minTouch()
                                .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                        ) { Text(near.actionLabel, style = MekaType.itemMeta, color = Meka.colors.accent) }
                    }
                    Text(near.privacy, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                openKey != null -> Text(ErrandsPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
    }
}

/** Under a place's label, with near-me on: "Spot saved · …" or "No spot saved · …", and I'm here / Forget spot. */
@Composable
private fun PlaceSpotLine(place: ErrandGroup, saved: Boolean, said: String?, onAction: () -> Unit) {
    val reduced = Meka.reducedMotion
    val line = said ?: ErrandPlaceRules.placeLine(saved)
    val action = ErrandPlaceRules.placeAction(saved)
    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "errand-spot", modifier = Modifier.weight(1f)) { l ->
            Text(l, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            action, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onAction)
                .semantics { contentDescription = "$action: ${place.place}" }
                .minTouch()
                .padding(MekaSpace.xs),
        )
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun ErrandRowView(row: ErrandRow, modifier: Modifier, primary: String, onPrimary: () -> Unit, onRemove: () -> Unit) {
    val ink = if (row.done) Meka.colors.textSecondary else Meka.colors.textPrimary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.shown, style = MekaType.body, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.meta?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            primary, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onPrimary)
                .semantics { contentDescription = "$primary: ${row.shown}" }
                .minTouch()
                .padding(MekaSpace.xs),
        )
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.shown}" }
                .minTouch()
                .padding(MekaSpace.xs),
        )
    }
}
