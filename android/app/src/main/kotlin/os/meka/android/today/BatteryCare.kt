package os.meka.android.today

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BatteryCareRules
import os.meka.core.domain.BatteryCareView
import os.meka.core.domain.BatteryFacts
import java.util.Calendar

/**
 * Samsung battery care on the Fold (Reliability first, slice 1). Every time MEKA runs (an open, a background sync, a
 * notification read) it records a heartbeat on this phone only; Today reads the phone's battery exemption on every
 * open and [BatteryCareRules] decides the line: a stop during the day in the critical colour, else an accent line
 * while Android may still restrict MEKA. Nothing here leaves the phone.
 */
object BatteryCare {
    private const val PREFS = "meka-battery"
    private const val BEATS = "beats"
    private const val DISMISSED = "dismissed-end"

    @Volatile private var lastBeatMs = 0L

    /** A heartbeat now (cheap: at most one write every five minutes per process). */
    fun beat(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastBeatMs < BatteryCareRules.BEAT_SPACING_MS) return
        lastBeatMs = now
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val beats = BatteryCareRules.record(BatteryCareRules.decode(prefs.getString(BEATS, null)), now)
            prefs.edit().putString(BEATS, BatteryCareRules.encode(beats)).apply()
        }
    }

    fun facts(context: Context): BatteryFacts {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() ?: true
        return BatteryFacts(exempt = exempt, samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true))
    }

    /** Today's line. [watching]: background sync is scheduled (this phone is connected), so silences mean something. */
    fun view(context: Context, watching: Boolean): BatteryCareView {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val beats = if (watching) BatteryCareRules.decode(prefs.getString(BEATS, null)) else emptyList()
        val dismissed = prefs.getLong(DISMISSED, 0L).takeIf { it > 0 }
        return BatteryCareRules.view(facts(context), beats, System.currentTimeMillis(), ::minuteOfDay, dismissed)
    }

    /** "Got it" on a stop: that one doesn't show again. */
    fun dismiss(context: Context, endMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(DISMISSED, endMs).apply()
    }

    private fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Android's own "Let MEKA always run in the background?" prompt; its settings list if the prompt won't open. */
    @SuppressLint("BatteryLife") // Distributed outside Play (ADR-010); Meka asked for MEKA to stay awake.
    fun allowInAndroid(context: Context) {
        val prompt = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        if (!start(context, prompt)) start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Samsung's Battery screen (Device care), where Background usage limits lives; else MEKA's app info. */
    fun openSamsung(context: Context) {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
            Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        tries.firstOrNull { start(context, it) }
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
}

/**
 * Today's battery line under the header: tapping it unfolds the steps in place (expand spring, tick haptic) with
 * "Allow in Android" and "Open Samsung battery" (light haptic, then the system's own screen) and, for a stop,
 * "Got it". The line cross-fades as it changes and its colour blends between the accent and the critical colour.
 */
@Composable
fun BatteryCareLine(view: BatteryCareView, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val line = view.line ?: return
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var open by rememberSaveable { mutableStateOf(false) }
    val color by animateColorAsState(if (view.critical) Meka.colors.critical else Meka.colors.accent, MekaMotion.appear(reduced), label = "battery-line")
    Column(modifier.fillMaxWidth()) {
        Crossfade(line, Modifier.fillMaxWidth(), MekaMotion.appear(reduced), label = "battery-text") { text ->
            Text(
                text, style = MekaType.caption, color = color,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); open = !open }.padding(vertical = MekaSpace.xs),
            )
        }
        AnimatedVisibility(
            open,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                view.steps.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l), modifier = Modifier.padding(top = MekaSpace.xxs)) {
                    if (view.allowInAndroid) {
                        Action("Allow in Android") { haptics.light(); BatteryCare.allowInAndroid(context) }
                    }
                    if (view.openSamsung) {
                        Action("Open Samsung battery") { haptics.light(); BatteryCare.openSamsung(context) }
                    }
                    if (view.gap != null) {
                        Action("Got it") { haptics.tick(); open = false; onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = Meka.colors.accent,
        modifier = Modifier.clickable(role = Role.Button) { onClick() }.padding(vertical = MekaSpace.xxs),
    )
}
