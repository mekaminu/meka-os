package os.meka.android.notify

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.work.WorkAlerts
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.LocalClock
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.NotificationSettings
import os.meka.core.facade.MekaCore
import os.meka.android.shell.SharedMotion
import os.meka.android.shell.MoreItem
import os.meka.android.designsystem.sharedTitleInPane

private const val STEP_MINUTES = 15

/**
 * Notification settings (governor v1): what this phone posts, quiet hours, the two digests, and which tier each kind
 * of notice uses. Quiet hours, digests and tiers sync with the Mac; "This phone" is this phone's own.
 *
 * Motion: sections stagger in; a chosen chip's colour blends across; the quiet-hour times unfold when turned on; the
 * "what happens next" lines cross-fade as settings change. Reduced motion: cross-fades only.
 */
@Composable
fun NotificationsPane(core: MekaCore, onClose: () -> Unit) {
    val context = LocalContext.current
    val governor = (context.applicationContext as MekaApplication).governor
    val settings by core.notificationSettings.collectAsState()
    val preview by core.notificationPreview.collectAsState()
    val device by governor.device.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var allowed by remember { mutableStateOf(WorkAlerts.canPost(context)) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { allowed = WorkAlerts.canPost(context) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = WorkAlerts.canPost(context) }
    val quiet = settings.quiet
    fun saveQuiet(enabled: Boolean = quiet.enabled, start: Int = quiet.startMinute, end: Int = quiet.endMinute) =
        scope.launch { core.setQuietHours(enabled, start, end) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("Notifications", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.NOTIFICATIONS)).appear(rememberAppearance(0)))
        Column(Modifier.appear(rememberAppearance(0))) {
            Crossfade(preview.quietLine, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "quiet-line") {
                Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            }
            Crossfade(preview.digestLine, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "digest-line") {
                Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(MekaSpace.m))
        Label("This phone", 1)
        ChipRow(DeviceAlerts.entries.map { it.label }, DeviceAlerts.entries.indexOf(device), Modifier.appear(rememberAppearance(1))) {
            haptics.tick(); governor.setDevice(DeviceAlerts.entries[it])
        }
        if (!allowed && device != DeviceAlerts.OFF) {
            PillButton("Allow notifications") {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                else runCatching {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
        }

        Spacer(Modifier.height(MekaSpace.m))
        Label("Quiet hours", 2)
        ToggleRow(if (quiet.enabled) "On · ${quiet.summary}" else "Off", quiet.enabled, Modifier.appear(rememberAppearance(2))) {
            haptics.tick(); saveQuiet(enabled = !quiet.enabled)
        }
        AnimatedVisibility(
            quiet.enabled,
            enter = if (Meka.reducedMotion) fadeIn() else expandVertically() + fadeIn(),
            exit = if (Meka.reducedMotion) fadeOut() else shrinkVertically() + fadeOut(),
        ) {
            Column {
                TimeStepper("From", quiet.startMinute) { saveQuiet(start = it) }
                TimeStepper("Until", quiet.endMinute) { saveQuiet(end = it) }
                Text(
                    "Heads-ups that fall in quiet hours wait for the next digest. Only critical things come through.",
                    style = MekaType.caption, color = Meka.colors.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(MekaSpace.m))
        Label("Digests", 3)
        listOf(NotificationSettings.MIDDAY to "Midday", NotificationSettings.EVENING to "Evening").forEach { (minute, name) ->
            val on = settings.hasDigest(minute)
            ToggleRow("$name · ${LocalClock.formatMinute(minute)}", on, Modifier.appear(rememberAppearance(3))) {
                haptics.tick(); scope.launch { core.setDigest(minute, !on) }
            }
        }
        Text("One quiet round-up of what's due: renewals, things to chase, decisions to review, overdue tasks.",
            style = MekaType.caption, color = Meka.colors.textTertiary)

        Spacer(Modifier.height(MekaSpace.m))
        Label("What goes where", 4)
        NoticeSource.entries.forEach { source ->
            val choices = NoticeSource.CHOICES.filter { it.ordinal >= source.defaultTier.ordinal }
            val current = settings.tierFor(source)
            Column(Modifier.appear(rememberAppearance(4)).padding(vertical = MekaSpace.xxs)) {
                Text(source.label, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                ChipRow(choices.map { it.label }, choices.indexOf(current).coerceAtLeast(0), Modifier.padding(top = MekaSpace.xxs)) { i ->
                    haptics.tick(); scope.launch { core.setNoticeTier(source, choices[i]) }
                }
            }
        }
        Text(
            "Heads-up: a notification at the time. Digest: in the midday or evening round-up. App only: never a " +
                "notification. Nothing here sends anything for you.",
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )
        Spacer(Modifier.height(MekaSpace.xl))
    }
}

@Composable
private fun Label(text: String, index: Int) {
    Text(text.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(index)))
}

/** Chips with one lit; the lit colour blends across as the choice changes. */
@Composable
private fun ChipRow(labels: List<String>, selectedIndex: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            val reduced = Meka.reducedMotion
            val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "chip-bg")
            val fg by animateColorAsState(if (on) Meka.colors.onAccent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "chip-fg")
            Text(
                label, style = MekaType.caption, color = fg,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                    .clickable(role = Role.RadioButton) { if (!on) onSelect(i) }
                    .semantics { selected = on }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val dot by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(reduced), label = "toggle")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onToggle() }
            .semantics { contentDescription = label; selected = on }
            .padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
        Text(if (on) "On" else "Off", style = MekaType.caption, color = Meka.colors.onAccent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(dot).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs))
    }
}

@Composable
private fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    val day = LocalClock.MINUTES_PER_DAY
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Text("−", style = MekaType.itemTitle, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onChange((minute - STEP_MINUTES + day) % day) }
                .semantics { contentDescription = "$label 15 minutes earlier" }.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
        Text(LocalClock.formatMinute(minute), style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text("+", style = MekaType.itemTitle, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onChange((minute + STEP_MINUTES) % day) }
                .semantics { contentDescription = "$label 15 minutes later" }.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = Meka.colors.accent,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}
