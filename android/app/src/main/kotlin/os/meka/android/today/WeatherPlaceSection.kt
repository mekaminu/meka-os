package os.meka.android.today

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.WeatherPlaceRules
import os.meka.core.facade.MekaCore

/**
 * Weather place setting (Calendars → Weather): the town the forecast is for, Biggleswade (home) unless Meka types
 * another. Done saves it (a name that can't be a town says so with a tick haptic); a changed name also saves when the
 * pane closes (typing is never lost). The line under it cross-fades as the server follows ("Finding “Bedford”…" →
 * "Forecast for Bedford · from Open-Meteo"), lit in the accent colour when the name couldn't be found. Synced.
 */
@Composable
internal fun WeatherPlaceSection(core: MekaCore, modifier: Modifier = Modifier) {
    val view by core.weatherView.collectAsState()
    val choice = view.placeChoice
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val focus = LocalFocusManager.current
    var text by rememberSaveable(choice.name) { mutableStateOf(choice.name) }
    var bad by remember(choice.name) { mutableStateOf(false) }

    fun save(name: String, done: (Boolean) -> Unit = {}) = TypingSaves.launch { done(core.setWeatherPlace(name)) }

    val typed by rememberUpdatedState(text)
    val saved by rememberUpdatedState(choice.name)
    DisposableEffect(Unit) {
        onDispose {
            val t = typed.trim()
            if (t != saved && WeatherPlaceRules.accepts(t)) save(t)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("WEATHER", style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text("The town Today's weather line, the brief and Ask use. Only the name is sent to Open-Meteo.",
            style = MekaType.caption, color = Meka.colors.textTertiary)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
        ) {
            if (text.isEmpty()) Text(WeatherPlaceRules.HOME, style = MekaType.body, color = Meka.colors.textTertiary)
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
                        save(t) { ok -> bad = !ok; if (ok) haptics.light() else haptics.tick() }
                    }
                }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Weather for which town" },
            )
        }
        val line = if (bad) "That doesn't look like a town · try Bedford" else choice.line
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "weather-place-line",
                modifier = Modifier.weight(1f),
            ) { l ->
                Text(l, style = MekaType.caption, color = when {
                    bad -> Meka.colors.critical
                    choice.lit -> Meka.colors.accent
                    else -> Meka.colors.textTertiary
                })
            }
            if (!WeatherPlaceRules.isHome(choice.name)) {
                Text("Use home", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) {
                        haptics.tick(); text = WeatherPlaceRules.HOME; bad = false; save("")
                    }.padding(start = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.xxs))
            }
        }
    }
}
