package os.meka.android.today

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import os.meka.android.designsystem.minTouch
import androidx.compose.foundation.layout.sizeIn
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.ask.AboutEditSheetView
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.core.domain.NotifyChoiceRow
import os.meka.core.domain.WeatherPlaceChoiceRules
import os.meka.core.facade.MekaCore

/** The line under WEATHER (in Calendars' shots, quality gate slice 33). */
internal const val WEATHER_HINT = "Home and work for Today's weather line, the brief and Ask. Only the names are sent to Open-Meteo."

/**
 * Weather places (Calendars → Weather): **Home**, the town the forecast is for, About you's home unless Meka types
 * another, and **Work** (Places item 2), About you's work unless he types another (rule 13: no place in the code); on office days Today's weather line and the brief
 * say both, and the Day ring's rain follows where he'll be.
 *
 * Walk-through week 5, A17 (and g4a 9): no open "Your town" boxes on the page. Home and Work are each one card row
 * ("Home" · "Bedford", "Work" · "Not set") with the forecast's line under it, cross-fading as the server follows
 * ("Finding “Bedford”…" → "Forecast for Bedford · from Open-Meteo"), lit in the accent colour when the name couldn't be
 * found; with no place the line says what's shown, never "add it in About you" under the row that sets it. A tap opens
 * About you's edit sheet ([AboutEditSheetView]: rises over a fading scrim, reduced motion a cross-fade) holding the
 * town, what it's for and what's sent, and Close · Use About you's · Save; a name that can't be a town keeps the sheet
 * open with why (a tick haptic), a blank Save follows About you again. Synced.
 */
@Composable
internal fun WeatherPlaceSection(core: MekaCore, modifier: Modifier = Modifier) {
    val view by core.weatherView.collectAsState()
    var open by remember { mutableStateOf<PlaceSheet?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("WEATHER", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text(os.meka.core.domain.LoneWordGlue.kept(WEATHER_HINT),
            style = MekaType.caption, color = Meka.colors.textTertiary)
        PlaceRow(WeatherPlaceChoiceRules.homeRow(view.placeChoice), WeatherPlaceChoiceRules.line(view.placeChoice, home = true),
            view.placeChoice.lit, "home") { open = PlaceSheet.HOME }
        PlaceRow(WeatherPlaceChoiceRules.workRow(view.workChoice), WeatherPlaceChoiceRules.line(view.workChoice, home = false),
            view.workChoice.lit, "work") { open = PlaceSheet.WORK }
        HereSwitch(core, Modifier.padding(top = MekaSpace.xs))
    }
    open?.let { which ->
        val haptics = rememberMekaHaptics()
        var refusal by remember(which) { mutableStateOf<String?>(null) }
        val sheet = when (which) {
            PlaceSheet.HOME -> WeatherPlaceChoiceRules.homeSheet(view.placeChoice)
            PlaceSheet.WORK -> WeatherPlaceChoiceRules.workSheet(view.workChoice)
        }
        AboutEditSheetView(sheet, refusal, onSave = { typed ->
            val t = typed.trim()
            val why = WeatherPlaceChoiceRules.refusal(t)
            if (why != null) {
                refusal = why; haptics.tick()
            } else {
                TypingSaves.launch {
                    val ok = when (which) {
                        PlaceSheet.HOME -> core.setWeatherPlace(t)
                        PlaceSheet.WORK -> core.setWorkPlace(t)
                    }
                    if (ok) open = null else { refusal = WeatherPlaceChoiceRules.BAD; haptics.tick() }
                }
            }
        }, onClose = { open = null })
    }
}

private enum class PlaceSheet { HOME, WORK }

/** One place: its card row and the forecast's line under it. */
@Composable
private fun PlaceRow(row: NotifyChoiceRow, line: String, lit: Boolean, key: String, onOpen: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        NotifyChoiceRowView(row, onOpen = onOpen)
        AnimatedContent(
            targetState = line,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "place-line-$key",
            modifier = Modifier.padding(horizontal = MekaSpace.m),
        ) { l ->
            Text(os.meka.core.domain.LoneWordGlue.kept(l), style = MekaType.caption,
                color = if (lit) Meka.colors.accent else Meka.colors.textTertiary)
        }
    }
}

/**
 * "Where I am now" (Places item 3): the switch, kept on this phone, off by default. Turn on asks Android for
 * approximate location (once; if refused, the line is lit "Needs location · tap to allow" and a tap opens MEKA's app
 * settings); Turn off forgets the last answer at once. The status line cross-fades and its colour blends to the
 * accent when it needs a look; the privacy words sit under it.
 */
@Composable
private fun HereSwitch(core: MekaCore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(HereLocation.isOn(context)) }
    var permitted by remember { mutableStateOf(HereLocation.permitted(context)) }
    // Coming back from Android's settings: read the permission again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = HereLocation.permitted(context)
        if (on && !permitted) scope.launch { core.forgetHere() }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permitted = ok
        if (ok) scope.launch { HereLocation.refresh(context, core, null) }
    }
    val view = core.hereSetting(on, permitted)
    val statusColor by animateColorAsState(
        if (view.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "here-status",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("Where I am now", style = MekaType.caption, color = Meka.colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = view.statusLine,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "here-line",
                modifier = Modifier.weight(1f).then(
                    if (view.lit) Modifier.clickable(role = Role.Button) { haptics.tick(); openAppSettings(context) }.minTouch() else Modifier,
                ),
            ) { l -> Text(os.meka.core.domain.LoneWordGlue.kept(l), style = MekaType.caption, color = statusColor) }
            // Walk-through 2026-11-02 A9: the pill is drawn as before inside a whole 48 dp touch.
            Box(
                Modifier.padding(start = MekaSpace.m).sizeIn(minWidth = MekaSpace.touch, minHeight = MekaSpace.touch)
                    .clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button) {
                        haptics.tick()
                        if (on) {
                            on = false
                            HereLocation.setOn(context, false)
                            scope.launch { core.forgetHere() }
                        } else {
                            on = true
                            HereLocation.setOn(context, true)
                            if (permitted) scope.launch { HereLocation.refresh(context, core, null) }
                            else ask.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(view.actionLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
            }
        }
        Text(os.meka.core.domain.LoneWordGlue.kept(view.privacy), style = MekaType.caption, color = Meka.colors.textTertiary)
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
