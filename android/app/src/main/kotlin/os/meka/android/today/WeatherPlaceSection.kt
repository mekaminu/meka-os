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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.PlacesRules
import os.meka.core.domain.WeatherPlaceRules
import os.meka.core.domain.WeatherPlaceView
import os.meka.core.facade.MekaCore

/**
 * Weather places (Calendars → Weather): **Home**, the town the forecast is for, Biggleswade unless Meka types another,
 * and **Work** (Places item 2), Canary Wharf unless he types another; on office days Today's weather line and the brief
 * say both, and the Day ring's rain follows where he'll be. Done saves a field (a name that can't be a town says so
 * with a tick haptic); a changed name also saves when the pane closes (typing is never lost). The line under each
 * cross-fades as the server follows ("Finding “Bedford”…" → "Forecast for Bedford · from Open-Meteo"), lit in the
 * accent colour when the name couldn't be found. Synced.
 */
@Composable
internal fun WeatherPlaceSection(core: MekaCore, modifier: Modifier = Modifier) {
    val view by core.weatherView.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("WEATHER", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text("Home and work for Today's weather line, the brief and Ask. Only the names are sent to Open-Meteo.",
            style = MekaType.caption, color = Meka.colors.textTertiary)
        PlaceField(
            label = "Home", choice = view.placeChoice, fallback = WeatherPlaceRules.HOME,
            isDefault = WeatherPlaceRules::isHome, resetLabel = "Use home", spokenLabel = "Weather for which town",
            save = { core.setWeatherPlace(it) },
        )
        PlaceField(
            label = "Work", choice = view.workChoice, fallback = PlacesRules.WORK,
            isDefault = PlacesRules::isDefaultWork, resetLabel = "Use ${PlacesRules.WORK}", spokenLabel = "Where work is",
            save = { core.setWorkPlace(it) },
            modifier = Modifier.padding(top = MekaSpace.xs),
        )
        HereSwitch(core, Modifier.padding(top = MekaSpace.s))
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
                    if (view.lit) Modifier.clickable(role = Role.Button) { haptics.tick(); openAppSettings(context) } else Modifier,
                ),
            ) { l -> Text(l, style = MekaType.caption, color = statusColor) }
            Box(
                Modifier.padding(start = MekaSpace.m).clip(RoundedCornerShape(MekaRadius.pill))
                    .background(Meka.colors.surfaceRaised)
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
                    }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) { Text(view.actionLabel, style = MekaType.itemMeta, color = Meka.colors.accent) }
        }
        Text(view.privacy, style = MekaType.caption, color = Meka.colors.textTertiary)
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

/** One place: its label, the field, and the line under it (with a way back to the default). */
@Composable
private fun PlaceField(
    label: String,
    choice: WeatherPlaceView,
    fallback: String,
    isDefault: (String?) -> Boolean,
    resetLabel: String,
    spokenLabel: String,
    save: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val focus = LocalFocusManager.current
    var text by rememberSaveable(choice.name) { mutableStateOf(choice.name) }
    var bad by remember(choice.name) { mutableStateOf(false) }
    val saver by rememberUpdatedState(save)

    fun store(name: String, done: (Boolean) -> Unit = {}) = TypingSaves.launch { done(saver(name)) }

    val typed by rememberUpdatedState(text)
    val saved by rememberUpdatedState(choice.name)
    DisposableEffect(Unit) {
        onDispose {
            val t = typed.trim()
            if (t != saved && WeatherPlaceRules.accepts(t)) store(t)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(label, style = MekaType.caption, color = Meka.colors.textSecondary)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
        ) {
            if (text.isEmpty()) Text(fallback, style = MekaType.body, color = Meka.colors.textTertiary)
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(WeatherPlaceRules.MAX_NAME); bad = false },
                singleLine = true,
                textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                cursorBrush = SolidColor(Meka.colors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val t = text.trim()
                    if (t.isNotEmpty() && !WeatherPlaceRules.accepts(t)) {
                        bad = true; haptics.tick()
                    } else {
                        focus.clearFocus()
                        store(t) { ok -> bad = !ok; if (ok) haptics.light() else haptics.tick() }
                    }
                }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = spokenLabel },
            )
        }
        val line = if (bad) "That doesn't look like a town · try Bedford" else choice.line
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "place-line-$label",
                modifier = Modifier.weight(1f),
            ) { l ->
                Text(l, style = MekaType.caption, color = when {
                    bad -> Meka.colors.critical
                    choice.lit -> Meka.colors.accent
                    else -> Meka.colors.textTertiary
                })
            }
            if (!isDefault(choice.name)) {
                Text(resetLabel, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) {
                        haptics.tick(); text = fallback; bad = false; store("")
                    }.padding(start = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.xxs))
            }
        }
    }
}
