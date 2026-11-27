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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AlarmRules
import os.meka.core.domain.DurationGlue
import os.meka.core.domain.ShutdownRules
import os.meka.core.domain.TomorrowPreview
import os.meka.core.domain.WakeBufferChoiceRules
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore

internal const val WAKE_BUFFER_ROW_TAG = "wake-buffer-row"

/**
 * The smart wake alarm in the evening shutdown (Alarms, slice 1): MEKA's suggestion from tomorrow's first commitment
 * less the get-ready buffer, ‹ › to move it five minutes (tick haptic, digits roll), Set alarm / Turn off, "Use 06:30"
 * when something earlier came in, and the buffer as a row opening its sheet. Synced with the Mac. Without "Alarms & reminders" allowed
 * the line under it says it may ring up to 5 minutes late, with a one-tap grant (ADR-007).
 * Motion: the digits slide the way the time moved, the line cross-fades, chips blend; reduced motion cross-fades.
 */
@Composable
internal fun WakeSection(core: MekaCore, modifier: Modifier = Modifier, under: TomorrowPreview? = null) {
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
        // A15: under Shut down's tomorrow block the day is already said, so only a different day ("This morning · …") shows.
        (if (under != null) ShutdownRules.wakeDayShown(w.dayLabel, under) else w.dayLabel)?.let {
            Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.testTag("wake-day"))
        }
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
        ) { line -> Text(LoneWordGlue.kept(DurationGlue.kept(line)), style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
        AnimatedVisibility(w.useSuggestionLabel != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
            Text(
                w.useSuggestionLabel ?: "", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.padding(top = MekaSpace.xs).clickable(role = Role.Button) { haptics.light(); act { core.useSuggestedWake() } }.minTouch(),
            )
        }
        // Tomorrow morning's trains in trouble (the commute counts everywhere, slice 7): the line, and the offer to wake
        // ten minutes earlier. Nothing moves until it's tapped.
        AnimatedVisibility(w.trains != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
            val t = w.trains
            Column(Modifier.padding(top = MekaSpace.xs)) {
                AnimatedContent(
                    targetState = t?.line ?: "",
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "wake-trains",
                ) { line -> Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.accent) }
                t?.actionLabel?.let { label ->
                    Text(
                        label, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.padding(top = MekaSpace.xxs).clickable(role = Role.Button) {
                            haptics.light(); draft = null; act { core.wakeEarlierForTrains(null) }
                        }.minTouch(),
                    )
                }
            }
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
                }.minTouch(),
            )
        }
        // Walk-through week 5, A17: the get-ready buffer is a row opening its sheet, not an open chip row (whose fifth
        // chip ran off the cover's edge). A pick moves the suggestion only, never an alarm already set.
        var choosing by rememberSaveable { mutableStateOf(false) }
        NotifyChoiceRowView(
            WakeBufferChoiceRules.row(w), Modifier.padding(top = MekaSpace.m).testTag(WAKE_BUFFER_ROW_TAG),
        ) { haptics.tick(); choosing = true }
        if (choosing) NotifyChoiceSheetView(
            WakeBufferChoiceRules.sheet(w),
            onChoose = { name -> WakeBufferChoiceRules.named(w, name)?.let { b -> act { core.setWakeBuffer(b) } } },
            onClose = { choosing = false },
        )
    }
}

@Composable
private fun WakeStep(glyph: String, description: String, onClick: () -> Unit) {
    Text(
        glyph, style = MekaType.upNextTitle, color = Meka.colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).semantics { contentDescription = description }
            .clickable(role = Role.Button) { onClick() }.minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
    )
}

@Composable
private fun WakePill(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = if (filled) Meka.colors.onAccent else Meka.colors.textPrimary,
        // Quality gate slice 47: a 48 dp touch; the pill itself stays its drawn size.
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) { onClick() }.minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
