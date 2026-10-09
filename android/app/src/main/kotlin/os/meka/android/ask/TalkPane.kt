package os.meka.android.ask

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.TalkSetupSection
import os.meka.core.domain.TalkStartRules

/**
 * Ask → More → Talk (Talk without tapping the mic, slice 1): how to start talking to MEKA without the mic. The side
 * button: whether MEKA is the phone's digital assistant app (Android's role manager, re-read each time MEKA comes back
 * to the front, so returning from Settings updates it) and the steps to make it so, with "Open default apps"; the
 * headphones and the car (slice 2: opening MEKA with Bluetooth audio or in car mode listens); the Talk widget (slice 2);
 * and the safety line. The words are the core's [TalkStartRules].
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; sections stagger in 40 ms apart; the side
 * button's status line blends to the accent once MEKA is the assistant; Open default apps presses in with a tick haptic.
 * Reduced motion: cross-fades.
 */
@Composable
fun TalkPane(onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    var held by remember { mutableStateOf(isAssistant(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) held = isAssistant(context) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val view = TalkStartRules.setup(mac = false, assistantHeld = held, samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text(view.title, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.TALK)).appear(rememberAppearance(0)))
        Text(view.intro, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))
        view.sections.forEachIndexed { i, s ->
            TalkSection(s, Modifier.appear(rememberAppearance(i + 1))) {
                haptics.tick()
                openDefaultApps(context)
            }
        }
    }
}

@Composable
private fun TalkSection(s: TalkSetupSection, modifier: Modifier, onAction: () -> Unit) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (s.lit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "talk-status",
    )
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.s), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(s.label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.semantics { heading() })
        Text(s.status, style = MekaType.body, color = statusColor)
        s.steps.forEach { step -> Text(step, style = MekaType.caption, color = Meka.colors.textSecondary) }
        s.action?.let { label ->
            Spacer(Modifier.height(MekaSpace.xs))
            Box(
                Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) {
                Text(label, style = MekaType.itemMeta, color = Meka.colors.accent)
            }
        }
    }
}

/** MEKA holds Android's assistant role (chosen as the digital assistant app), so the side key's hold reaches it. */
private fun isAssistant(context: Context): Boolean =
    runCatching { context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false)

/** Android's default apps screen (the assistant role can't be asked for from an app); else Settings itself. */
private fun openDefaultApps(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
