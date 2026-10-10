package os.meka.android.today

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AlarmRules
import os.meka.core.facade.MekaCore

/**
 * The smart wake alarm in the evening shutdown (Alarms, slice 1): MEKA's suggestion from tomorrow's first commitment
 * less the get-ready buffer, ‹ › to move it five minutes (tick haptic, digits roll), Set alarm / Turn off, "Use 06:30"
 * when something earlier came in, and the buffer as chips. Synced with the Mac. Without "Alarms & reminders" allowed
 * the line under it says it may ring up to 5 minutes late, with a one-tap grant (ADR-007).
 * Motion: the digits slide the way the time moved, the line cross-fades, chips blend; reduced motion cross-fades.
 */
@Composable
internal fun WakeSection(core: MekaCore, modifier: Modifier = Modifier) {
    val w by core.wakeView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
    // The time picked before the alarm is set; nothing is written until Set alarm.
    var draft by remember(w.epochDay) { mutableStateOf<Int?>(null) }
    val minute = w.setMinute ?: draft ?: w.minute
    val act: (suspend () -> Unit) -> Unit = { block -> scope.launch { runCatching { block() } } }
    val step: (Int) -> Unit = { by ->
        haptics.tick()
        val m = AlarmRules.step(minute, by)
        if (w.isSet) act { core.setWake(m) } else draft = m
    }

    Column(modifier.fillMaxWidth()) {
        SectionLabel("Wake alarm")
        Text(w.dayLabel, style = MekaType.caption, color = Meka.colors.textTertiary)
        Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            WakeStep("‹", "Five minutes earlier") { step(-1) }
            AnimatedContent(
                targetState = minute,
                transitionSpec = {
                    val later = targetState > initialState
                    if (reduced) ContentTransform(fadeIn(MekaMotion.appear(true)), fadeOut(MekaMotion.appear(true)))
                    else (slideInVertically(MekaMotion.replan(false)) { if (later) it / 2 else -it / 2 } + fadeIn(MekaMotion.appear(false))) togetherWith
                        (slideOutVertically(MekaMotion.replan(false)) { if (later) -it / 2 else it / 2 } + fadeOut(MekaMotion.appear(false)))
                },
                label = "wake-time",
            ) { m ->
                Text(
                    "%02d:%02d".format(m / 60, m % 60), style = MekaType.upNextTitle,
                    color = if (w.isSet) Meka.colors.accent else Meka.colors.textPrimary,
                    modifier = Modifier.padding(horizontal = MekaSpace.xs),
                )
            }
            WakeStep("›", "Five minutes later") { step(1) }
            Spacer(Modifier.weight(1f))
            if (w.isSet) {
                WakePill("Turn off", filled = false) { haptics.tick(); draft = null; act { core.wakeOff() } }
            } else {
                WakePill("Set alarm", filled = true) { haptics.light(); act { core.setWake(minute) } }
            }
        }
        AnimatedContent(
            targetState = w.line,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "wake-line",
        ) { line -> Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
        AnimatedVisibility(w.useSuggestionLabel != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
            Text(
                w.useSuggestionLabel ?: "", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.padding(top = MekaSpace.xs).clickable(role = Role.Button) { haptics.light(); act { core.useSuggestedWake() } },
            )
        }
        val app = context.applicationContext as? MekaApplication
        val onTime = remember(w) { app?.alarms?.onTime() ?: true }
        if (w.isSet && !onTime) {
            Text(
                "May ring up to 5 min late · Allow on time", style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.padding(top = MekaSpace.xs).clickable(role = Role.Button) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }
        Text("Time to get ready", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.m))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = MekaSpace.xxs),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            w.bufferChoices.forEach { b ->
                val chosen = b == w.bufferMin
                val bg by androidx.compose.animation.animateColorAsState(
                    if (chosen) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.themeBlend(reduced), label = "wake-buffer",
                )
                Text(
                    AlarmRules.duration(b), style = MekaType.caption,
                    color = if (chosen) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                        .clickable(role = Role.Button) { haptics.tick(); act { core.setWakeBuffer(b) } }
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                )
            }
        }
    }
}

@Composable
private fun WakeStep(glyph: String, description: String, onClick: () -> Unit) {
    Text(
        glyph, style = MekaType.upNextTitle, color = Meka.colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).semantics { contentDescription = description }
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun WakePill(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = if (filled) Meka.colors.onAccent else Meka.colors.textPrimary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
