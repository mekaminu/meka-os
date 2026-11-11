package os.meka.android.today

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.ask.MekaSpeaker
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.MorningReadOutRules
import os.meka.core.facade.MekaCore

/**
 * The morning read-out (Meka 2026-11-11 06:40: "My start of day reading out is no longer happening"): the first open
 * of Today in the morning reads the brief once in Obi's voice, without the Morning brief card the Today rebuild took
 * away. Which opens read is the core's ([MekaCore.morningReadOutPlays]); this device keeps the day it last read, and
 * the wake alarm's read counts ([MorningReadOutStore.markRead]).
 */
object MorningReadOutStore {
    private const val PREFS = "morning_read_out"
    private const val KEY = "last_day"

    fun lastDay(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, -1L).takeIf { it >= 0 }

    fun markRead(context: Context, day: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, day).apply()
    }

    /** The phone's own state the rules need: on a call, ringer on silent or vibrate, headphones or the car. */
    data class Phone(val onCall: Boolean, val silent: Boolean, val private: Boolean)

    fun phone(context: Context): Phone = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val call = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
        val private = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        Phone(call, am.ringerMode != AudioManager.RINGER_MODE_NORMAL, private)
    } catch (_: Exception) {
        Phone(onCall = false, silent = true, private = false) // can't tell: stay quiet
    }
}

/** The read-out's state for Today: whether it is getting the words ready or speaking, and Stop. */
class MorningReadOut internal constructor(private val speaker: MekaSpeaker) {
    internal var preparing by mutableStateOf<Job?>(null)
    val speaking: Boolean get() = speaker.speaking
    val showing: Boolean get() = preparing != null || speaker.speaking

    fun stop() {
        preparing?.cancel(); preparing = null
        speaker.stop()
    }
}

/**
 * Starts the read-out on the first morning open of Today, once the brief has its day in. [briefPaneReading] holds it
 * back when the wake alarm has already opened the brief reading itself (that read counts for the day).
 */
@Composable
fun rememberMorningReadOut(core: MekaCore, briefReady: Boolean, briefPaneReading: () -> Boolean): MorningReadOut {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speaker = remember { MekaSpeaker(context, core, scope) }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    val readOut = remember(speaker) { MorningReadOut(speaker) }
    LaunchedEffect(briefReady) {
        if (!briefReady) return@LaunchedEffect
        delay(START_AFTER_MS) // Today's opening has begun, and a wake alarm's brief has had its moment to spring up
        if (briefPaneReading()) return@LaunchedEffect
        val p = MorningReadOutStore.phone(context)
        if (!core.morningReadOutPlays(MorningReadOutStore.lastDay(context), p.onCall, p.silent, p.private)) return@LaunchedEffect
        MorningReadOutStore.markRead(context, core.todayEpochDay())
        readOut.preparing = scope.launch {
            val text = runCatching { core.briefScript() }.getOrNull()
            readOut.preparing = null
            if (text != null) speaker.say(text, reading = true)
        }
    }
    return readOut
}

private const val START_AFTER_MS = 900L

/** "Reading your morning brief · Stop" over the top of Today while it reads; slides down in, fades away when done. */
@Composable
fun MorningReadOutLine(readOut: MorningReadOut, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        readOut.showing, modifier,
        // Motion catalogue: appear (rise from above); reduced motion cross-fades only.
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else fadeIn(MekaMotion.appear(false)) + slideInVertically(MekaMotion.appear(false)) { -it / 2 },
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else fadeOut(MekaMotion.appear(false)) + slideOutVertically(MekaMotion.appear(false)) { -it / 2 },
    ) {
        Row(
            Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
                .padding(start = MekaSpace.l, end = MekaSpace.xs).testTag("morning-readout")
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(MekaSpace.xs).clip(CircleShape).background(Meka.colors.accent))
            Text(MorningReadOutRules.line(preparing = readOut.preparing != null), style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(start = MekaSpace.xs))
            Text(" · ", style = MekaType.caption, color = Meka.colors.textTertiary)
            Text(MorningReadOutRules.STOP, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClickLabel = "Stop reading the morning brief") { haptics.tick(); readOut.stop() }
                    .padding(horizontal = MekaSpace.xs).minTouch().testTag("morning-readout-stop"))
        }
    }
}
