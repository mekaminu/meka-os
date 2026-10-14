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
import os.meka.core.domain.FindView
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
 *
 * Motion: the pane springs up (MekaPane) with the tile's title travelling in; lines cross-fade ("Finding where you
 * are…" → "Saved where the Golf is · ±6 m", the accent when refused, light haptic on a save, a tick when refused);
 * parked cards arrive and leave with the list's item motion; Find my car unfolds with the expand spring and its arrow
 * turns on a soft spring (the shortest way round), brightening to the accent once within 15 m. Reduced motion: the
 * arrow snaps, unfold and fades become cross-fades.
 */
@Composable
fun CarPane(core: MekaCore, onClose: () -> Unit, onDates: () -> Unit, findFirst: Boolean = false) {
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
            if (r.saved) { spots = r.spots; undo = null; haptics.light() } else haptics.tick()
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
                r, open = finding == r.carId,
                modifier = Modifier.animateItem().appear(rememberAppearance(3 + i)),
                onFind = { haptics.tick(); finding = if (finding == r.carId) null else r.carId },
                onLabel = { text ->
                    spots = ParkingRules.withLabel(spots, r.carId, text)
                    ParkingStore.store(context, spots)
                    haptics.light()
                },
                onDroveOff = {
                    haptics.tick()
                    undo = spots[r.carId]
                    spots = ParkingRules.droveOff(spots, r.carId)
                    ParkingStore.store(context, spots)
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
        item(key = "privacy") {
            Text(ParkingRules.PRIVACY, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
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
    row: ParkedRow, open: Boolean, modifier: Modifier,
    onFind: () -> Unit, onLabel: (String) -> Unit, onDroveOff: () -> Unit,
) {
    val reduced = Meka.reducedMotion
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
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(ParkingRules.FIND, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onFind)
                    .semantics { contentDescription = "${ParkingRules.FIND}: ${row.title}" }.minTouch().padding(MekaSpace.xs))
            Text(ParkingRules.DROVE_OFF, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onDroveOff)
                    .semantics { contentDescription = "${ParkingRules.DROVE_OFF}: ${row.title}" }.minTouch().padding(MekaSpace.xs))
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            FindMyCar(row.spot)
        }
        Field(ParkingRules.LABEL_HINT, Modifier.padding(top = MekaSpace.xxs), onDone = onLabel)
    }
}

/** The arrow and the distance, live while shown: GPS and the compass run only while this is on screen. */
@Composable
private fun FindMyCar(spot: ParkedSpot) {
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
