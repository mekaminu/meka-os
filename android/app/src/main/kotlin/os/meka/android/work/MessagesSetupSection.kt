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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import os.meka.android.designsystem.LightInk
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
import os.meka.core.domain.ChatShadeRules
import os.meka.core.domain.MessagesChoiceRules
import os.meka.core.domain.MessagesSetupRow
import os.meka.core.domain.MessagesSetupRules
import os.meka.core.facade.MekaCore

/**
 * Work mode → Messages (V1, messages assistant slice 5): whether MEKA can read messages and use its AI, the WhatsApp
 * settings that keep groups quiet, the people and groups kept from MEKA's AI (and each group's Digest · Normal ·
 * Ignore), what goes to the AI and what it costs, and the limits. Everything here stays on this phone ([CaptureStore]).
 *
 * Walk-through week 5, A17: each person's Keep from AI pill, each group's Digest · Normal · Ignore chips and its pill,
 * and Mark read's On/Off pill are card rows saying what's chosen ("Alex" · "Sent to AI" ›, "Football parents" ·
 * "Digest" ›, "Navilife's AI" · "Reads it" ›) opening a sheet of the choices, the one in use lit, each saying what it
 * does ([MessagesChoiceRules]). Motion: the sheet rises (reduced motion: a cross-fade), a choice gives a tick haptic and
 * the row's line cross-fades. A chat's Stop under Cleared from the shade stays a button (an action, not a choice).
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
    var sheet by remember { mutableStateOf<MessagesSheet?>(null) }
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
                .minTouch(),
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
                        .minTouch())
            }
        }

        Text("Kept from Navilife's AI", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        Text(
            "A person or group kept from Navilife's AI still shows in Needs you, as FYI with nothing drafted; a kept group still has its digest, with no gist.",
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )
        if (list.isEmpty) Text(MessagesSetupRules.EMPTY, style = MekaType.caption, color = Meka.colors.textTertiary)
        list.people.forEach { row -> SetupRow(row, onOpen = { sheet = it }) }
        list.groups.forEach { row -> SetupRow(row, onOpen = { sheet = it }) }
        Text("Keep someone else from the AI", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) {
                runCatching { pick.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
            }.minTouch())

        // Messages grouped per chat, slice 2: Done on a chat's card also marks it read (off: blue ticks).
        Column(Modifier.fillMaxWidth().padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            NotifyChoiceRowView(MessagesChoiceRules.markReadRow(settings.markReadOnDone), Modifier.testTag(MESSAGES_MARK_READ_TAG)) {
                sheet = MessagesSheet.MarkRead
            }
            Crossfade(if (settings.markReadOnDone) ChatShadeRules.SETTING_ON else ChatShadeRules.SETTING_OFF,
                animationSpec = MekaMotion.appear(reduced), label = "mark-read-line") { line ->
                Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }

        // Slice 2b: chats cleared from the shade as they come (switched on a chat's card under Show all).
        if (settings.clearOnArrival.isNotEmpty()) {
            Text(ChatShadeRules.ARRIVAL_SETTING_TITLE, style = MekaType.itemMeta, color = Meka.colors.textPrimary,
                modifier = Modifier.padding(top = MekaSpace.m))
            Text(ChatShadeRules.ARRIVAL_SETTING_LINE, style = MekaType.caption, color = Meka.colors.textTertiary)
            settings.clearOnArrival.entries.sortedBy { it.value }.forEach { (key, title) ->
                Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    SettingChip("Stop", false, spoken = "Stop clearing $title from the shade") {
                        haptics.tick(); store.setClearOnArrival(settings.clearOnArrival - key)
                    }
                }
            }
        }

        Text("Privacy and cost", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        (MessagesSetupRules.PRIVACY + MessagesSetupRules.COST).forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Text("What Navilife can't see", style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.padding(top = MekaSpace.m))
        MessagesSetupRules.LIMITS.forEach { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
    // The open sheet reads the row as it is now, so a choice made elsewhere (a digest card's mode) is the one lit.
    fun current(r: MessagesSetupRow) = (list.people + list.groups).firstOrNull { it.name == r.name } ?: r
    // The lists are sealed with the phone's keystore; a save that can't seal still leaves the choice in force.
    when (val s = sheet) {
        is MessagesSheet.Ai -> {
            val r = current(s.row)
            NotifyChoiceSheetView(if (r.group) MessagesChoiceRules.groupAiSheet(r) else MessagesChoiceRules.personSheet(r),
                onChoose = { name -> MessagesChoiceRules.privateNamed(r, name)?.let { on -> runCatching { store.setNeverToAi(r.name, on) } } },
                onClose = { sheet = null })
        }
        is MessagesSheet.Mode -> {
            val r = current(s.row)
            NotifyChoiceSheetView(MessagesChoiceRules.groupSheet(r),
                onChoose = { name -> MessagesChoiceRules.modeNamed(r, name)?.let { m -> runCatching { store.setGroupMode(r.name, m) } } },
                onClose = { sheet = null })
        }
        MessagesSheet.MarkRead -> NotifyChoiceSheetView(MessagesChoiceRules.markReadSheet(settings.markReadOnDone),
            onChoose = { name -> MessagesChoiceRules.markReadNamed(settings.markReadOnDone, name)?.let { on -> runCatching { store.setMarkReadOnDone(on) } } },
            onClose = { sheet = null })
        null -> {}
    }
}

/** Work mode → Messages' rows (walk-through week 5, A17), for the screen tests. */
const val MESSAGES_MARK_READ_TAG = "messages-row:mark-read"
const val MESSAGES_ROW_TAG = "messages-row"

/** Which of Messages' sheets is open. */
private sealed interface MessagesSheet {
    data class Ai(val row: MessagesSetupRow) : MessagesSheet
    data class Mode(val row: MessagesSetupRow) : MessagesSheet
    data object MarkRead : MessagesSheet
}

/**
 * A person's or group's rows: a person is one row ("Alex" · "Sent to AI"); a group is its mode row ("Football parents" ·
 * "Digest") with its AI row under it ("Navilife's AI" · "Reads it"); the row's line under them, cross-fading.
 */
@Composable
private fun SetupRow(row: MessagesSetupRow, onOpen: (MessagesSheet) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        if (row.group) {
            NotifyChoiceRowView(MessagesChoiceRules.groupRow(row), Modifier.testTag(MESSAGES_ROW_TAG)) { onOpen(MessagesSheet.Mode(row)) }
            NotifyChoiceRowView(MessagesChoiceRules.groupAiRow(row), Modifier.testTag(MESSAGES_ROW_TAG)) { onOpen(MessagesSheet.Ai(row)) }
        } else {
            NotifyChoiceRowView(MessagesChoiceRules.personRow(row), Modifier.testTag(MESSAGES_ROW_TAG)) { onOpen(MessagesSheet.Ai(row)) }
        }
        Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "setup-row-line") { line ->
            Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}

/**
 * A pill whose colour blends to the accent when on, with the caller's tick haptic (motion catalogue: Messages setup).
 * Every chip is drawn 32 dp high inside a whole 48 × 48 touch (quality gate slice 59 for Obi's voice; walk-through
 * 2026-11-02 A9 made it every chip: the Week ahead's On and 16:00–20:00, Commute's, Leaving now's, Messages' chips
 * were about 21–23 dp to hit). Tagged `setting-chip` so the shots can measure the drawn pill.
 */
@Composable
internal fun SettingChip(label: String, on: Boolean, spoken: String, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "setup-chip-bg")
    val fg by animateColorAsState(if (on) Meka.colors.onAccent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "setup-chip-fg")
    // A31: an off chip is surfaceRaised on Light's page (1.1:1), so in Light it has a 1 dp edge in the track ink.
    val edge = if (on) null else LightInk.pillEdge(Meka.colors, Meka.theme.isDark)
    androidx.compose.foundation.layout.Box(
        Modifier.sizeIn(minWidth = MekaSpace.touch, minHeight = MekaSpace.touch)
            .clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Switch) { onClick() }
            .semantics { contentDescription = spoken; stateDescription = if (on) "On" else "Off" },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            label, style = MekaType.caption, color = fg, maxLines = 1,
            // Drawn at the Done pill's 32 dp (walk-through A11: these read 20–23 dp tall), the word centred.
            modifier = Modifier.testTag("setting-chip").clip(RoundedCornerShape(MekaRadius.pill))
                .then(if (edge != null) Modifier.border(1.dp, edge, RoundedCornerShape(MekaRadius.pill)) else Modifier)
                .background(bg)
                .heightIn(min = 32.dp).wrapContentHeight(Alignment.CenterVertically)
                .padding(horizontal = MekaSpace.m),
        )
    }
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
