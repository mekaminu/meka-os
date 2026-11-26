package os.meka.android.ask

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import os.meka.android.designsystem.lightCardEdge
import androidx.compose.ui.platform.testTag
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
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.BargeInRules
import os.meka.core.domain.HelloWakeRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.TalkOnOpenRules
import os.meka.core.domain.TalkSetupSection
import os.meka.core.domain.TalkStartRules
import os.meka.core.domain.TalkChoiceRules

/**
 * Ask → More → Talk (Talk without tapping the mic, slice 1): how to start talking to MEKA without the mic. The side
 * button: whether MEKA is the phone's digital assistant app (Android's role manager, re-read each time MEKA comes back
 * to the front, so returning from Settings updates it) and the steps to make it so, with "Open default apps"; the
 * headphones and the car (slice 2: opening MEKA with Bluetooth audio or in car mode listens); the Talk widget (slice 2);
 * and the safety line. The words are the core's [TalkStartRules]. Slice 4: "When I open MEKA" ([TalkOnOpenRules.section],
 * kept on this phone, off by default) with Turn on / Turn off; turning it on asks for the microphone if needed. "Talk
 * over MEKA" ([BargeInRules.section], kept on this phone, on by default) after it, with Turn on / Turn off.
 * "Hello, Navilife" slice 3: Say “Hello Navilife” ([HelloWakeRules.section], kept on this phone, off by default) after
 * When I open Navilife; turning it on asks for the microphone if needed; without on-device speech it says so, no switch.
 *
 * Walk-through week 5, A17: each switch (When I open, Say “Hey Obi”, Talk over) is a card row under its label saying
 * what's chosen ("Talk over Navilife" · "On" ›) opening an On · Off sheet ([TalkChoiceRules]), not an open Turn on /
 * Turn off pill; the side button's Open default apps stays a button (an action, not a choice).
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
    var listenOnOpen by remember { mutableStateOf(TalkAutoListen.listenOnOpen(context)) }
    fun setListen(on: Boolean) {
        TalkAutoListen.setListenOnOpen(context, on)
        listenOnOpen = on
    }
    var talkOver by remember { mutableStateOf(TalkAutoListen.talkOver(context)) }
    var wake by remember { mutableStateOf(TalkAutoListen.helloWake(context)) }
    fun setWake(on: Boolean) {
        TalkAutoListen.setHelloWake(context, on)
        wake = on
    }
    val wakeAvailable = remember { HelloWake.available(context) }
    // Which switch asked for the microphone, so the answer turns on the right one.
    var micFor by remember { mutableStateOf("") }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { if (micFor == HelloWakeRules.LABEL) setWake(true) else setListen(true) }
    }
    // "When I open MEKA" sits after the side button (the other way to start without the mic).
    // "Talk over MEKA" follows it (what happens once MEKA is talking).
    val sections = view.sections.toMutableList().apply {
        add(minOf(1, size), TalkOnOpenRules.section(listenOnOpen))
        add(minOf(2, size), HelloWakeRules.section(wake, mac = false, available = wakeAvailable))
        add(minOf(3, size), BargeInRules.section(talkOver))
    }

    // The switch a row opened, by its section's label (walk-through week 5, A17).
    var sheet by remember { mutableStateOf<String?>(null) }
    fun turn(label: String, on: Boolean) {
        when (label) {
            BargeInRules.LABEL -> {
                talkOver = on
                TalkAutoListen.setTalkOver(context, on)
            }
            HelloWakeRules.LABEL -> when {
                !on -> setWake(false)
                TalkAutoListen.micAllowed(context) -> setWake(true)
                else -> { micFor = HelloWakeRules.LABEL; askMic.launch(Manifest.permission.RECORD_AUDIO) }
            }
            TalkOnOpenRules.LABEL -> when {
                !on -> setListen(false)
                TalkAutoListen.micAllowed(context) -> setListen(true)
                else -> { micFor = TalkOnOpenRules.LABEL; askMic.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }
    }
    sheet?.let { label ->
        val now = sections.firstOrNull { it.label == label }?.let(TalkChoiceRules::isOn) ?: false
        TalkChoiceRules.sheet(label, now, mac = false)?.let { sh ->
            os.meka.android.notify.NotifyChoiceSheetView(sh,
                onChoose = { name -> TalkChoiceRules.named(now, name)?.let { turn(label, it) } }, onClose = { sheet = null })
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text(view.title, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.TALK)).appear(rememberAppearance(0)))
        Text(view.intro, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))
        sections.forEachIndexed { i, s ->
            TalkSection(s, Modifier.appear(rememberAppearance(i + 1)), onOpen = { haptics.tick(); sheet = s.label }) {
                haptics.tick()
                openDefaultApps(context)
            }
        }
    }
}

/** Settings → Talk's switch rows (walk-through week 5, A17), by section label, for the screen tests. */
fun talkRowTag(label: String): String = "talk-row-" + label.filter { it.isLetter() }.lowercase()

@Composable
internal fun TalkSection(s: TalkSetupSection, modifier: Modifier, onOpen: () -> Unit = {}, onAction: () -> Unit) {
    val reduced = Meka.reducedMotion
    val statusColor by animateColorAsState(
        if (s.lit) Meka.colors.accent else Meka.colors.textPrimary, MekaMotion.appear(reduced), label = "talk-status",
    )
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(s.label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.semantics { heading() })
        // A switch is a row saying what's chosen, opening its On · Off sheet (walk-through week 5, A17).
        val row = TalkChoiceRules.row(s)
        if (row != null) {
            os.meka.android.notify.NotifyChoiceRowView(row, Modifier.testTag(talkRowTag(s.label)), raised = true, onOpen = onOpen)
        }
        Text(LoneWordGlue.kept(s.status), style = MekaType.body, color = statusColor)
        s.steps.forEach { step -> Text(LoneWordGlue.kept(step), style = MekaType.caption, color = Meka.colors.textSecondary) }
        s.action?.takeIf { row == null }?.let { label ->
            Spacer(Modifier.height(MekaSpace.xs))
            Box(
                // Walk-through 2026-11-15, A21 part 9: the pale pill gets Light's 1 dp edge on the off-white page (none in Dark).
                Modifier.clip(RoundedCornerShape(MekaRadius.pill)).lightCardEdge(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .testTag("talk-action").clickable(role = Role.Button, onClick = onAction)
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
