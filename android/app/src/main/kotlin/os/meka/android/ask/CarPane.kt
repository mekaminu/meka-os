package os.meka.android.ask

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.catch
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
import os.meka.android.lists.Chip
import os.meka.android.lists.Field
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.CarBluetoothRules
import os.meka.core.domain.CarLink
import os.meka.core.domain.DeviceChoice
import os.meka.core.domain.FindView
import os.meka.core.domain.PairedDevice
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.LocationFix
import os.meka.core.domain.ParkedRow
import os.meka.core.domain.ParkedSpot
import os.meka.core.domain.ParkingRules
import os.meka.core.domain.ParkingTimerRules
import os.meka.core.domain.TimerRow
import os.meka.core.facade.MekaCore

/**
 * Ask → Life → Car (car and parking, slice 1; the Fold only): where each car is parked and how to walk back to it,
 * over each car's dates. "I've parked here" (with two cars, "Which car?" chips first) takes a few seconds of precise
 * fixes and keeps the best on this phone ([ParkingStore]; never synced or sent); the bay typed from the sign rides on
 * the spot ("Level 3 · Row F"). Find my car unfolds an arrow and the distance that follow the phone's compass and GPS
 * while it is open, says "Within 15 m · look around" with one buzz, and offers Walk there in Maps (his maps app, only
 * on his tap). Drove off clears the spot with Undo. Under them each car's summary with Dates › (Lists → Renewals).
 * [findFirst]: opened from Talk ("where did I park?"): the first parked car's Find my car is already open.
 * [parkFirst]: opened from "Parked the Golf?" (slice 3, the car's Bluetooth disconnected): that car's spot is saved
 * straight away. BLUETOOTH links each car to one of the phone's paired devices ([CarBluetooth], on this phone only).
 * Slice 4: "Photo of the bay" opens the phone's camera app (no camera permission: the camera app takes it) and keeps
 * the picture beside the spot ([ParkingStore.keepPhoto], on this phone only); the card shows it small (a tap shows it
 * full width, with Remove photo) and Find my car shows it over the arrow. With no bay typed, the sign's words, read on
 * the phone ([ParkingStore.readSign], ML Kit), fill it in ("Photo kept · read “Level 3 · Row F” from the sign"). [photoFirst]: opened from "Photo of the bay"
 * on the parked notification: the camera opens for that car straight away.
 *
 * Motion: the pane springs up (MekaPane) with the tile's title travelling in; lines cross-fade ("Finding where you
 * are…" → "Saved where the Golf is · ±6 m", the accent when refused, light haptic on a save, a tick when refused);
 * parked cards arrive and leave with the list's item motion; Find my car unfolds with the expand spring and its arrow
 * turns on a soft spring (the shortest way round), brightening to the accent once within 15 m. Reduced motion: the
 * arrow snaps, unfold and fades become cross-fades. Slice 4: the photo fades in once loaded (a new one cross-fades
 * over the old), a tap grows it to full width and back; a kept photo gives a light haptic, a refusal blends the line
 * to the accent with a tick. Reduced motion: cross-fades.
 */
@Composable
fun CarPane(
    core: MekaCore, onClose: () -> Unit, onDates: () -> Unit,
    findFirst: Boolean = false, parkFirst: String? = null, photoFirst: String? = null,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
    val cars by core.carsView.collectAsState()
    val cal = remember { LocalCalendar.fixedOffset(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()) }
    var spots by remember { mutableStateOf(ParkingStore.spots(context)) }
    var said by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var pendingCar by remember { mutableStateOf<String?>(null) }
    var finding by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<ParkedSpot?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = System.currentTimeMillis() } }
    val rows = ParkingRules.rows(spots, cars, now, cal)
    val timers by core.parkingTimersView.collectAsState()
    var timerCar by remember { mutableStateOf<String?>(null) }
    var timerSaid by remember { mutableStateOf<String?>(null) }
    var timerRefused by remember { mutableStateOf(false) }
    var timerUndo by remember { mutableStateOf<String?>(null) }
    val timerSaidColor by animateColorAsState(
        if (timerRefused) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "timer-said-color",
    )
    LaunchedEffect(findFirst) { if (findFirst) finding = rows.firstOrNull()?.carId }

    fun park(carId: String) {
        val title = ParkingRules.titleOf(carId, cars)
        saving = true
        refused = false
        said = ParkingRules.SAVING
        choosing = false
        scope.launch {
            val r = ParkingStore.parkHere(context, carId, title)
            saving = false
            said = r.line
            refused = !r.saved
            if (r.saved) { spots = r.spots; undo = null; ParkingStore.tidyPhotos(context, spots); haptics.light() } else haptics.tick()
        }
    }
    val askPrecise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val car = pendingCar
        pendingCar = null
        if (ParkingStore.precise(context) && car != null) park(car) else { said = ParkingRules.NO_PERMISSION; refused = true }
    }
    fun startPark(carId: String) {
        if (ParkingStore.precise(context)) park(carId) else {
            pendingCar = carId
            askPrecise.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val saidColor by animateColorAsState(
        if (refused) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "park-said",
    )
    LaunchedEffect(parkFirst) { parkFirst?.let { startPark(it) } }
    // Car and parking, slice 4: the bay's photo, by the phone's camera app, kept on this phone only.
    var photoFor by remember { mutableStateOf<String?>(null) }
    var photoTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { ParkingStore.tidyPhotos(context, spots, undo) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val car = photoFor
        photoFor = null
        if (car != null && !ok) { said = ParkingRules.PHOTO_NONE; refused = true }
        if (car != null && ok) {
            scope.launch {
                if (ParkingStore.keepPhoto(context, car)) {
                    spots = ParkingRules.withPhoto(ParkingStore.spots(context), car, System.currentTimeMillis())
                    ParkingStore.store(context, spots)
                    photoTick++
                    said = ParkingRules.PHOTO_KEPT; refused = false
                    haptics.light()
                    // The sign read on the phone fills the bay when none was typed.
                    if (spots[car]?.label == null) {
                        val read = ParkingRules.labelFromSign(ParkingStore.readSign(context, car))
                        if (read != null && spots[car]?.label == null) {
                            spots = ParkingRules.withLabel(spots, car, read)
                            ParkingStore.store(context, spots)
                            said = ParkingRules.photoKeptLine(read)
                        }
                    }
                } else {
                    said = ParkingRules.PHOTO_FAILED; refused = true
                    haptics.tick()
                }
            }
        }
    }
    fun takePhoto(carId: String) {
        if (spots[carId] == null) return
        photoFor = carId
        try {
            takePicture.launch(ParkingStore.captureUri(context))
        } catch (e: android.content.ActivityNotFoundException) {
            photoFor = null; said = ParkingRules.NO_CAMERA; refused = true
        } catch (e: IllegalArgumentException) {
            photoFor = null; said = ParkingRules.PHOTO_FAILED; refused = true
        }
    }
    LaunchedEffect(photoFirst) { photoFirst?.let { takePhoto(it) } }
    // Car and parking, slice 3: each car's Bluetooth device (kept on this phone only).
    var links by remember { mutableStateOf(CarBluetooth.links(context)) }
    var picking by remember { mutableStateOf<String?>(null) }
    var btAllowed by remember { mutableStateOf(CarBluetooth.allowed(context)) }
    var always by remember { mutableStateOf(CarBluetooth.always(context)) }
    LaunchedEffect(cars) {
        val pruned = CarBluetoothRules.prune(links, cars.cars.map { it.carId })
        if (pruned != links) { links = pruned; CarBluetooth.store(context, pruned) }
    }
    val askNearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        btAllowed = ok || CarBluetooth.allowed(context)
    }
    val askAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { always = CarBluetooth.always(context) }
    val askPreciseForAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ParkingStore.precise(context)) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

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
            Text(ParkingRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.CAR)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            val summary = ParkingRules.tileLine(rows, now)?.text ?: ParkingRules.NOT_PARKED
            Crossfade(summary, animationSpec = MekaMotion.appear(reduced), label = "park-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "park") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                val choices = ParkingRules.choices(cars)
                PillAction(if (saving) ParkingRules.SAVING else ParkingRules.PARK, enabled = !saving) {
                    haptics.tick()
                    if (choices.isEmpty()) startPark(ParkingRules.onlyCar(cars)) else choosing = !choosing
                }
                AnimatedVisibility(
                    choosing && choices.isNotEmpty(),
                    enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                    exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text(ParkingRules.WHICH_CAR, style = MekaType.caption, color = Meka.colors.textTertiary)
                        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                            choices.forEach { (id, title) -> Chip(title, false) { startPark(id) } }
                        }
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "park-said-line") { s ->
                    s?.let { Text(it, style = MekaType.caption, color = saidColor, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                undo?.let { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Drove off · ${ParkingRules.titleOf(u.carId, cars)}", style = MekaType.caption, color = Meka.colors.textSecondary,
                            modifier = Modifier.weight(1f))
                        Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                haptics.light()
                                spots = spots + (u.carId to u)
                                ParkingStore.store(context, spots)
                                undo = null
                            }.minTouch().padding(MekaSpace.xs))
                    }
                }
            }
        }
        // Car and parking, slice 2: the parking timer (synced; the Mac shows it too).
        item(key = "timer-label") { SectionLabel(ParkingTimerRules.HEADING, Modifier.animateItem().padding(top = MekaSpace.m)) }
        itemsIndexed(timers.rows, key = { _, t -> "timer-${t.carId}" }) { _, t ->
            TimerCard(
                t, showTitle = cars.cars.size > 1, modifier = Modifier.animateItem(),
                onExtend = { m ->
                    haptics.light()
                    scope.launch { core.extendParkingTimer(t.carId, m)?.let { timerSaid = it; timerRefused = false } }
                },
                onEnd = {
                    haptics.tick()
                    scope.launch { core.endParkingTimer(t.carId)?.let { timerSaid = it; timerRefused = false; timerUndo = t.carId } }
                },
            )
        }
        item(key = "timer-add") {
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                if (timers.rows.isEmpty()) Text(ParkingTimerRules.EMPTY_LINE, style = MekaType.caption, color = Meka.colors.textSecondary)
                val choices = ParkingRules.choices(cars)
                val forCar = timerCar?.takeIf { id -> choices.any { it.first == id } }
                    ?: rows.singleOrNull()?.carId?.takeIf { id -> choices.any { it.first == id } }
                    ?: ParkingRules.onlyCar(cars).takeIf { choices.isEmpty() } ?: choices.firstOrNull()?.first ?: ParkingRules.onlyCar(cars)
                if (choices.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        choices.forEach { (id, title) -> Chip(title, id == forCar) { timerCar = id } }
                    }
                }
                Field(ParkingTimerRules.HINT) { text ->
                    scope.launch {
                        val line = core.setParkingTimer(forCar, text)
                        timerSaid = line ?: ParkingTimerRules.NOT_READ
                        timerRefused = line == null
                        timerUndo = null
                        if (line != null) haptics.light() else haptics.tick()
                    }
                }
                // Slice 5: a RingGo/JustPark confirmation pasted into the field (or shared to Navilife) is read here too.
                Text(os.meka.core.domain.ParkingConfirmationRules.PASTE_HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
                Crossfade(timerSaid, animationSpec = MekaMotion.appear(reduced), label = "timer-said") { s ->
                    s?.let { Text(it, style = MekaType.caption, color = timerSaidColor, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                timerUndo?.let { id ->
                    Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                            haptics.light()
                            scope.launch { if (core.undoEndParkingTimer(id)) timerSaid = null; timerUndo = null }
                        }.minTouch().padding(MekaSpace.xs))
                }
            }
        }
        if (rows.isNotEmpty()) {
            item(key = "parked-label") { SectionLabel("PARKED", Modifier.animateItem().padding(top = MekaSpace.m)) }
        }
        itemsIndexed(rows, key = { _, r -> "parked-${r.carId}" }) { i, r ->
            ParkedCard(
                r, open = finding == r.carId, photoTick = photoTick,
                modifier = Modifier.animateItem().appear(rememberAppearance(3 + i)),
                onFind = { haptics.tick(); finding = if (finding == r.carId) null else r.carId },
                onLabel = { text ->
                    spots = ParkingRules.withLabel(spots, r.carId, text)
                    ParkingStore.store(context, spots)
                    haptics.light()
                },
                onPhoto = { haptics.tick(); takePhoto(r.carId) },
                onRemovePhoto = {
                    haptics.tick()
                    spots = ParkingRules.withoutPhoto(spots, r.carId)
                    ParkingStore.store(context, spots)
                    ParkingStore.tidyPhotos(context, spots, undo)
                    photoTick++
                    said = ParkingRules.PHOTO_REMOVED; refused = false
                },
                onDroveOff = {
                    haptics.tick()
                    undo = spots[r.carId]
                    spots = ParkingRules.droveOff(spots, r.carId)
                    ParkingStore.store(context, spots)
                    ParkingStore.tidyPhotos(context, spots, undo)
                    if (finding == r.carId) finding = null
                    said = null
                },
            )
        }
        item(key = "dates-label") { SectionLabel("DATES", Modifier.animateItem().padding(top = MekaSpace.m)) }
        itemsIndexed(cars.cars, key = { _, c -> "dates-${c.carId}" }) { _, c ->
            Row(
                Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                    .clickable(role = Role.Button) { onDates() }.minTouch()
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(c.title, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(c.summary.ifEmpty { ParkingRules.DATES_LINE }, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text(ParkingRules.DATES_ACTION, style = MekaType.caption, color = Meka.colors.accent)
            }
        }
        // Car and parking, slice 3: each car's Bluetooth saves the spot when it disconnects.
        item(key = "bt-label") { SectionLabel(CarBluetoothRules.HEADING, Modifier.animateItem().padding(top = MekaSpace.m)) }
        item(key = "bt-intro") {
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(CarBluetoothRules.INTRO, style = MekaType.caption, color = Meka.colors.textSecondary)
                if (!btAllowed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(CarBluetoothRules.NEEDS_PERMISSION, style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.weight(1f))
                        Text(CarBluetoothRules.ALLOW, style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                haptics.tick(); askNearby.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }.minTouch().padding(MekaSpace.xs))
                    }
                } else if (links.isNotEmpty()) {
                    Text(CarBluetoothRules.modeLine(always), style = MekaType.caption, color = Meka.colors.textSecondary)
                    if (!always) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(CarBluetoothRules.ALWAYS_LINE, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
                            Text(CarBluetoothRules.ALLOW, style = MekaType.caption, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                    haptics.tick()
                                    if (ParkingStore.precise(context)) askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    else askPreciseForAlways.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }.minTouch().padding(MekaSpace.xs))
                        }
                    }
                }
            }
        }
        if (btAllowed) {
            itemsIndexed(cars.cars, key = { _, c -> "bt-${c.carId}" }) { _, c ->
                BluetoothRow(
                    title = c.title, link = links[c.carId], open = picking == c.carId,
                    choices = if (picking == c.carId) CarBluetoothRules.choices(CarBluetooth.paired(context), links, c.carId, cars) else emptyList(),
                    bluetoothOn = CarBluetooth.bluetoothOn(context),
                    modifier = Modifier.animateItem(),
                    onToggle = { haptics.tick(); picking = if (picking == c.carId) null else c.carId },
                    onPick = { d ->
                        haptics.light()
                        links = if (d == null) CarBluetoothRules.unlink(links, c.carId)
                        else CarBluetoothRules.link(links, c.carId, PairedDevice(d.address, d.name))
                        CarBluetooth.store(context, links)
                        picking = null
                    },
                )
            }
        }
        item(key = "privacy") {
            Text(ParkingRules.PRIVACY, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

/**
 * One car's Bluetooth: "Bluetooth · MY VW 1234" (or not linked) with Pick/Change, unfolding the phone's paired devices
 * (car-looking ones first; one used by the other car says so) and None.
 */
@Composable
private fun BluetoothRow(
    title: String, link: CarLink?, open: Boolean, choices: List<DeviceChoice>, bluetoothOn: Boolean, modifier: Modifier,
    onToggle: () -> Unit, onPick: (DeviceChoice?) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(title, style = MekaType.body, color = Meka.colors.textPrimary)
                Crossfade(CarBluetoothRules.rowLine(link), animationSpec = MekaMotion.appear(reduced), label = "bt-line") { l ->
                    Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
            Text(if (link == null) CarBluetoothRules.PICK else CarBluetoothRules.CHANGE, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onToggle)
                    .semantics { contentDescription = "Bluetooth for $title" }.minTouch().padding(MekaSpace.xs))
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                when {
                    choices.isEmpty() && !bluetoothOn -> Text(CarBluetoothRules.BLUETOOTH_OFF, style = MekaType.caption, color = Meka.colors.accent)
                    choices.isEmpty() -> Text(CarBluetoothRules.NO_DEVICES, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                choices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onPick(d) }.minTouch()
                            .padding(vertical = MekaSpace.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(d.name, style = MekaType.body, color = if (d.chosen) Meka.colors.accent else Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                        d.linkedTo?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                    }
                }
                if (link != null) {
                    Text(CarBluetoothRules.NONE, style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onPick(null) }.minTouch().padding(MekaSpace.xs))
                }
            }
        }
    }
}

@Composable
private fun PillAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).minTouch()
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MekaType.metaStrong, color = if (enabled) Meka.colors.accent else Meka.colors.textTertiary)
    }
}

/**
 * One car's parking timer: the time left (brass in its last ten minutes and once ended), the note he typed, +15 min /
 * +30 min / +1 h and Ended. The line cross-fades as the minutes go.
 */
@Composable
private fun TimerCard(row: TimerRow, showTitle: Boolean, modifier: Modifier, onExtend: (Int) -> Unit, onEnd: () -> Unit) {
    val reduced = Meka.reducedMotion
    val lineColor by animateColorAsState(
        if (row.lit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "timer-line",
    )
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            if (showTitle) Text(row.title, style = MekaType.caption, color = Meka.colors.textTertiary)
            Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "timer-left") { l ->
                Text(l, style = MekaType.body, color = lineColor)
            }
            row.note?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            ParkingTimerRules.EXTEND_MIN.forEach { m -> Chip(ParkingTimerRules.extendLabel(m), false) { onExtend(m) } }
            Text(ParkingTimerRules.ENDED, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onEnd)
                    .semantics { contentDescription = "${ParkingTimerRules.ENDED}: ${row.title}" }.minTouch().padding(MekaSpace.xs))
        }
    }
}

/** One parked car: its title, the bay, when and how sure; Find my car (unfolding the arrow), the bay field and Drove off. */
@Composable
private fun ParkedCard(
    row: ParkedRow, open: Boolean, photoTick: Int, modifier: Modifier,
    onFind: () -> Unit, onLabel: (String) -> Unit, onPhoto: () -> Unit, onRemovePhoto: () -> Unit, onDroveOff: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    var big by remember(row.carId) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            row.label?.let { Text(it, style = MekaType.metaStrong, color = Meka.colors.accent) }
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        if (row.spot.photoAtMs != null) {
            BayPhoto(
                row.carId, "${row.spot.photoAtMs}-$photoTick", big = big,
                modifier = Modifier.clickable(role = Role.Button) { big = !big }
                    .semantics { contentDescription = "${ParkingRules.PHOTO}: ${row.title}. ${ParkingRules.PHOTO_HINT}" },
            )
            AnimatedVisibility(
                big,
                enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
            ) {
                Text(ParkingRules.REMOVE_PHOTO, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { big = false; onRemovePhoto() }
                        .semantics { contentDescription = "${ParkingRules.REMOVE_PHOTO}: ${row.title}" }.minTouch().padding(MekaSpace.xs))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(ParkingRules.FIND, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onFind)
                    .semantics { contentDescription = "${ParkingRules.FIND}: ${row.title}" }.minTouch().padding(MekaSpace.xs))
            Text(ParkingRules.photoAction(row.spot), style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onPhoto)
                    .semantics { contentDescription = "${ParkingRules.photoAction(row.spot)}: ${row.title}" }.minTouch().padding(MekaSpace.xs))
            Text(ParkingRules.DROVE_OFF, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onDroveOff)
                    .semantics { contentDescription = "${ParkingRules.DROVE_OFF}: ${row.title}" }.minTouch().padding(MekaSpace.xs))
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            FindMyCar(row.spot, photoKey = "${row.spot.photoAtMs}-$photoTick")
        }
        Field(ParkingRules.LABEL_HINT, Modifier.padding(top = MekaSpace.xxs), onDone = onLabel)
    }
}

/** The arrow and the distance, live while shown: GPS and the compass run only while this is on screen. */
@Composable
private fun FindMyCar(spot: ParkedSpot, photoKey: String) {
    val context = LocalContext.current
    val reduced = Meka.reducedMotion
    var here by remember { mutableStateOf<LocationFix?>(null) }
    var heading by remember { mutableStateOf<Double?>(null) }
    var wasClose by remember { mutableStateOf(false) }
    LaunchedEffect(spot) { ParkingStore.whereIAm(context).catch { }.collect { here = it } }
    LaunchedEffect(spot) { ParkingStore.heading(context) { here }.catch { }.collect { heading = it } }
    val view: FindView = ParkingRules.find(spot, here, heading)
    LaunchedEffect(view.close) {
        if (ParkingRules.buzz(wasClose, view)) ParkingStore.buzz(context)
        wasClose = view.close
    }
    // The arrow turns the shortest way round on a soft spring; reduced motion snaps.
    val turn = remember { Animatable(0f) }
    LaunchedEffect(view.arrowDeg) {
        val target = view.arrowDeg?.toFloat() ?: return@LaunchedEffect
        val from = turn.value
        val to = from + ParkingRules.norm180((target - from).toDouble()).toFloat()
        if (reduced) turn.snapTo(to) else turn.animateTo(to, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow))
    }
    val arrowColor by animateColorAsState(
        if (view.close) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "find-arrow",
    )
    Column(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        // Slice 4: the bay's photo over the arrow (underground it is the best guide).
        if (spot.photoAtMs != null) BayPhoto(spot.carId, photoKey, big = false, modifier = Modifier.clearAndSetSemantics { })
        val ring = Meka.colors.hairline
        if (view.arrowDeg != null) {
            Canvas(Modifier.size(120.dp).rotate(turn.value).clearAndSetSemantics { }) {
                val w = size.minDimension
                drawCircle(ring, w * 0.46f, style = Stroke(1.dp.toPx()))
                val p = Path().apply {
                    moveTo(w * 0.5f, w * 0.1f); lineTo(w * 0.7f, w * 0.62f); lineTo(w * 0.5f, w * 0.52f); lineTo(w * 0.3f, w * 0.62f); close()
                }
                drawPath(p, arrowColor)
                drawLine(arrowColor, Offset(w * 0.5f, w * 0.56f), Offset(w * 0.5f, w * 0.86f), 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Crossfade(view.line, animationSpec = MekaMotion.appear(reduced), label = "find-line") { l ->
            Text(l, style = MekaType.nowTitle, color = if (view.close) Meka.colors.accent else Meka.colors.textPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        view.accuracy?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        if (view.arrowDeg == null && here != null) Text(ParkingRules.COMPASS_HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        Text(ParkingRules.MAPS, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ParkingRules.mapsUrl(spot))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }.minTouch().padding(MekaSpace.xs))
    }
}

/**
 * The bay's photo (slice 4), read off the phone's files away from the main thread: 96 dp tall and cropped on the card
 * and over Find my car; full width at its own shape when [big]. [key] changes when a new photo is kept, so it reloads
 * (and cross-fades over the old one).
 */
@Composable
private fun BayPhoto(carId: String, key: String, big: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val reduced = Meka.reducedMotion
    var image by remember(carId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(carId, key) {
        image = ParkingStore.loadPhoto(context, carId, ParkingRules.PHOTO_MAX_PX)?.asImageBitmap()
    }
    Crossfade(image, animationSpec = MekaMotion.appear(reduced), label = "bay-photo") { img ->
        if (img != null) {
            val size = if (big) Modifier.fillMaxWidth().heightIn(max = 480.dp) else Modifier.fillMaxWidth().height(96.dp)
            Image(
                img, contentDescription = null,
                contentScale = if (big) ContentScale.Fit else ContentScale.Crop,
                modifier = modifier.then(size).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised),
            )
        }
    }
}
