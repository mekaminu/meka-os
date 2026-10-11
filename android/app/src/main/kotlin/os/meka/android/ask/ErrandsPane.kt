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
import androidx.compose.foundation.lazy.itemsIndexed
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
import os.meka.android.today.SectionLabel
import os.meka.core.domain.ErrandAdded
import os.meka.core.domain.ErrandGroup
import os.meka.core.domain.ErrandPlaceRules
import os.meka.core.domain.ErrandRow
import os.meka.core.domain.ErrandRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Errands (V1, errands by location slice 1): things that wait for a place, not a time. Type
 * "Tesco: milk, batteries" or "when I'm near the post office: parcel"; each place is a section with its errands, oldest
 * first. Done ticks one off (it moves to "Done lately", with Back); Remove takes it off for good.
 *
 * Slice 2 (the Fold only): "Near-me reminders" (off by default) asks for precise, then "Allow all the time" location;
 * once on, each place's section says whether its spot is saved, with "I'm here" (one precise fix, kept on this phone)
 * or "Forget spot". Android then reminds "Near Tesco · milk, batteries" on arriving ([ErrandProximity]).
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the field's line
 * cross-fade (the line blends to the accent when refused, with a tick haptic; an add gives a light haptic); each
 * place's label and rows stagger in 40 ms apart and move with the list's item motion. The near-me line cross-fades and
 * blends to the accent when it needs a look; each place's spot line unfolds (expand spring) when the switch turns on and
 * cross-fades through "Finding where you are…" to "Saved … here" (light haptic; a tick when refused). Reduced motion:
 * cross-fades.
 */
@Composable
fun ErrandsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.errandsView.collectAsState()
    var text by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<ErrandAdded?>(null) }
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
    val nearColor by animateColorAsState(
        if (near.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "errand-near",
    )
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "errand-said",
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("Errands", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ERRANDS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "errand-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(ErrandRules.HINT, value = text, onValue = { text = it }) { typed ->
                    scope.launch {
                        val r = runCatching { core.addErrand(typed) }.getOrNull() ?: return@launch
                        said = r
                        if (r.refused) { haptics.tick(); text = typed } else haptics.light()
                    }
                }
                Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "errand-line") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        item(key = "near") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text("Near-me reminders", style = MekaType.caption, color = Meka.colors.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Crossfade(near.statusLine, animationSpec = MekaMotion.appear(reduced), label = "errand-near-line",
                        modifier = Modifier.weight(1f).then(
                            if (near.lit) Modifier.clickable(role = Role.Button) { haptics.tick(); openAppSettings(context) } else Modifier,
                        )) { l -> Text(l, style = MekaType.caption, color = nearColor) }
                    Box(
                        Modifier.padding(start = MekaSpace.m).clip(RoundedCornerShape(MekaRadius.pill))
                            .background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button) {
                                haptics.tick()
                                nearOn = !nearOn
                                ErrandProximity.setOn(context, nearOn)
                                rearm()
                                if (nearOn && !precise) {
                                    askPrecise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                } else if (nearOn && !always) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                    ) { Text(near.actionLabel, style = MekaType.itemMeta, color = Meka.colors.accent) }
                }
                Text(near.privacy, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
        var step = 4
        v.places.forEach { place ->
            val labelStep = step++
            item(key = "place-${place.key}") {
                Column(Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(labelStep))) {
                    SectionLabel(place.place)
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
            }
            val base = step
            itemsIndexed(place.rows, key = { _, r -> "e-${r.id}" }) { i, r ->
                ErrandRowView(
                    r, Modifier.animateItem().appear(rememberAppearance(base + i)),
                    primary = "Done",
                    onPrimary = { haptics.light(); scope.launch { runCatching { core.errandDone(r.id, true) } } },
                    onRemove = { haptics.tick(); scope.launch { runCatching { core.removeErrand(r.id) } } },
                )
            }
            step += place.rows.size
        }
        if (v.done.isNotEmpty()) {
            val labelStep = step++
            item(key = "label-done") {
                SectionLabel("Done lately", Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(labelStep)))
            }
            val base = step
            itemsIndexed(v.done, key = { _, r -> "e-${r.id}" }) { i, r ->
                ErrandRowView(
                    r, Modifier.animateItem().appear(rememberAppearance(base + i)),
                    primary = "Back",
                    onPrimary = { haptics.tick(); scope.launch { runCatching { core.errandDone(r.id, false) } } },
                    onRemove = { haptics.tick(); scope.launch { runCatching { core.removeErrand(r.id) } } },
                )
            }
        }
        item(key = "note") {
            Text(ErrandRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
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
            Text(row.title, style = MekaType.itemTitle, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            row.meta?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            primary, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onPrimary)
                .semantics { contentDescription = "$primary: ${row.title}" }
                .padding(MekaSpace.xs),
        )
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                .padding(MekaSpace.xs),
        )
    }
}
