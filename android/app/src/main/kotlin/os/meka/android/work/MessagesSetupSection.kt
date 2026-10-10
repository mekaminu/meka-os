package os.meka.android.work

import android.content.Intent
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.GroupMode
import os.meka.core.domain.MessagesSetupRow
import os.meka.core.domain.MessagesSetupRules
import os.meka.core.facade.MekaCore

/**
 * Work mode → Messages (V1, messages assistant slice 5): whether MEKA can read messages and use its AI, the WhatsApp
 * settings that keep groups quiet, the people and groups kept from MEKA's AI (and each group's Digest · Normal ·
 * Ignore), what goes to the AI and what it costs, and the limits. Everything here stays on this phone ([CaptureStore]).
 */
@Composable
internal fun MessagesSetupSection(core: MekaCore, store: CaptureStore, listening: Boolean, index: Int) {
    val context = LocalContext.current
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val items by store.items.collectAsState()
    val digest by store.digest.collectAsState()
    val settings by store.triageSettings.collectAsState()
    var aiOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { aiOn = runCatching { core.aiStatus().canAsk }.getOrNull() }
    var showSteps by rememberSaveable { mutableStateOf(!listening) }
    val list = remember(items, digest, settings) { MessagesSetupRules.rows(items + digest, settings, System.currentTimeMillis()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.data?.let { u ->
            runCatching {
                context.contentResolver.query(u, arrayOf(Phone.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
        if (!name.isNullOrBlank()) { haptics.light(); store.setNeverToAi(name, true) }
    }
    val unfold = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
    val fold = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

    Column(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(MessagesSetupRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        val lit = MessagesSetupRules.statusLit(listening, aiOn)
        val lineColor by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "msg-line-colour")
        Crossfade(MessagesSetupRules.statusLine(listening, aiOn), animationSpec = MekaMotion.appear(reduced), label = "msg-line") { line ->
            Text(line, style = MekaType.itemMeta, color = lineColor)
        }

        // WhatsApp's settings, folded once everything works.
        val chevron by animateFloatAsState(if (showSteps) 90f else 0f, if (reduced) MekaMotion.appear<Float>(true) else MekaMotion.expand<Float>(false), label = "msg-chevron")
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); showSteps = !showSteps }
                .semantics { stateDescription = if (showSteps) "Shown" else "Hidden" }
                .padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("WhatsApp settings", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.weight(1f))
            Text("›", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.rotate(chevron))
        }
        AnimatedVisibility(showSteps, enter = unfold, exit = fold) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                MessagesSetupRules.WHATSAPP_STEPS.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text("Open WhatsApp's notification settings", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); openWhatsAppNotifications(context) }
                        .padding(vertical = MekaSpace.xs))
            }
        }

        Text("Kept from MEKA's AI", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        Text(
            "A person or group kept from MEKA's AI still shows in Needs you, as FYI with nothing drafted; a kept group still has its digest, with no gist.",
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )
        if (list.isEmpty) Text(MessagesSetupRules.EMPTY, style = MekaType.caption, color = Meka.colors.textTertiary)
        list.people.forEach { row ->
            SetupRow(row, onPrivate = { on -> haptics.tick(); store.setNeverToAi(row.name, on) })
        }
        list.groups.forEach { row ->
            SetupRow(
                row,
                onPrivate = { on -> haptics.tick(); store.setNeverToAi(row.name, on) },
                onMode = { m -> haptics.tick(); store.setGroupMode(row.name, m) },
            )
        }
        Text("Keep someone else from the AI", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) {
                runCatching { pick.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
            }.padding(vertical = MekaSpace.xs))

        Text("Privacy and cost", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        (MessagesSetupRules.PRIVACY + MessagesSetupRules.COST).forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Text("What MEKA can't see", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        MessagesSetupRules.LIMITS.forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
}

@Composable
private fun SetupRow(row: MessagesSetupRow, onPrivate: (Boolean) -> Unit, onMode: ((GroupMode) -> Unit)? = null) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "setup-row-line") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
            SettingChip(
                if (row.private) "Kept from AI" else "Keep from AI", row.private,
                spoken = MessagesSetupRules.privateLine(row.name, row.private),
            ) { onPrivate(!row.private) }
        }
        if (onMode != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                GroupMode.entries.forEach { m ->
                    SettingChip(m.label, row.mode == m, spoken = "${row.name}: ${m.label}") { if (row.mode != m) onMode(m) }
                }
            }
        }
    }
}

/** A pill whose colour blends to the accent when on, with the caller's tick haptic (motion catalogue: Messages setup). */
@Composable
internal fun SettingChip(label: String, on: Boolean, spoken: String, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "setup-chip-bg")
    val fg by animateColorAsState(if (on) Meka.colors.onAccent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "setup-chip-fg")
    Text(
        label, style = MekaType.caption, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .clickable(role = Role.Switch) { onClick() }
            .semantics { contentDescription = spoken; stateDescription = if (on) "On" else "Off" }
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
    )
}

private fun openWhatsAppNotifications(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, "com.whatsapp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.recoverCatching {
        context.startActivity(context.packageManager.getLaunchIntentForPackage("com.whatsapp") ?: error("no WhatsApp"))
    }
}
