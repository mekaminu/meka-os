package os.meka.android.work

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.DonePill
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.MekaHaptics
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AfterWorkDone
import os.meka.core.domain.GroupChatRules
import os.meka.core.domain.AfterWorkSummary
import os.meka.core.domain.BlockedCallerRules
import os.meka.core.domain.BlockedCallersView
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.ScreenUnknownRules
import os.meka.core.domain.ContactNumbers
import os.meka.core.domain.HeldPreview
import os.meka.core.domain.HeldPreviewRow
import os.meka.core.domain.HeldPreviewRules
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.VoiceRecordingRules
import os.meka.core.domain.LocalClock
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.PersonSummary
import os.meka.core.domain.WorkSchedule
import os.meka.core.domain.CommuteRules
import os.meka.core.domain.LeavingRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import os.meka.android.shell.SharedMotion
import os.meka.android.shell.MoreItem
import os.meka.android.designsystem.sharedTitleInPane

internal const val STEP_MINUTES = 15

/** Under "While you're at work" (quality gate slice 37: drawn kept, so no word is left alone on the cover screen). */
internal const val AT_WORK_NOTE =
    "\"Urgent\" or \"emergency\" in a message, or anyone on your always-notify list, alerts you straight away. " +
        "Navilife never replies and never marks anything read. What it holds stays on this phone."

/** The hours' own switch line, either way. */
internal const val HOURS_ON_LINE = "Using these hours · tap to use the switch only"
internal const val HOURS_OFF_LINE = "Switch only · tap to use these hours"

/** Under the call assistant's switch. */
internal fun callAssistantNote() =
    "At work, family, your always-notify list and anyone who calls twice within 3 minutes ring. Other calls are " +
        "declined, and your network's \"forward when busy\" sends them to ${CallScreeningRules.assistantName()}, " +
        "which takes a message. Declined calls still show as missed calls."

/**
 * Work mode settings (build plan M1): the switch, work hours, notification access, alerts, the two people
 * lists and Messages (the messages assistant's setup). The switch and hours sync with the Mac; the lists and everything held stay on this phone.
 */
@Composable
fun WorkPane(core: MekaCore, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as MekaApplication).captures
    val work by core.workMode.collectAsState()
    val lists by store.lists.collectAsState()
    val watch by store.watch.collectAsState()
    val notify by core.notificationSettings.collectAsState()
    val leaving by core.leavingView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var listening by remember { mutableStateOf(hasNotificationAccess(context)) }
    var alertsOk by remember { mutableStateOf(WorkAlerts.canPost(context)) }
    var screening by remember { mutableStateOf(MekaCallScreeningService.roleHeld(context)) }
    LaunchedEffect(Unit) {
        // Re-check when Meka comes back from the system settings screen.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            listening = hasNotificationAccess(context)
            alertsOk = WorkAlerts.canPost(context)
            screening = MekaCallScreeningService.roleHeld(context)
        }
    }
    val askScreening = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        screening = MekaCallScreeningService.roleHeld(context)
    }
    val askAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        alertsOk = WorkAlerts.canPost(context)
        if (alertsOk) WorkAlerts.ensureChannel(context)
    }
    var adding by remember { mutableStateOf<ListKind?>(null) }
    // Picks a contact's phone number (no contacts permission: the picker grants this one entry), so the call
    // assistant can recognise their calls; messages are still matched by the name.
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val kind = adding
        adding = null
        val picked = result.data?.data?.let { u -> PickedContact.read(context, u) }
        val name = picked?.name
        if (kind != null && picked != null && !name.isNullOrEmpty()) store.setLists(kind.add(lists, name).withNumbers(name, picked.numbers))
    }
    fun pick(kind: ListKind) {
        adding = kind
        runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)) }
    }
    val schedule = work.schedule
    fun save(s: WorkSchedule) = scope.launch { core.setWorkSchedule(s.days.sorted(), s.startMinute, s.endMinute, s.enabled) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("Work mode", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WORK)).appear(rememberAppearance(0)))
        Text(LoneWordGlue.kept(work.line), style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))

        Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            PillButton(if (work.atWork) "Stop work now" else "Start work now", filled = true) {
                haptics.tick(); scope.launch { core.setWorkSwitch(!work.atWork) }
            }
            if (work.switchedManually) {
                Text("Back to my hours", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { scope.launch { core.workBackToSchedule() } }.padding(vertical = MekaSpace.xs))
            }
        }

        Spacer(Modifier.height(MekaSpace.m))
        Label("Hours", 2)
        Row(Modifier.appear(rememberAppearance(2)), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            (1..7).forEach { d ->
                DayChip(d, d in schedule.days) {
                    val days = if (d in schedule.days) schedule.days - d else schedule.days + d
                    save(schedule.copy(days = days))
                }
            }
        }
        TimeStepper("Usual start", schedule.startMinute, Modifier.appear(rememberAppearance(2))) { save(schedule.copy(startMinute = it)) }
        TimeStepper("Usual end", schedule.endMinute, Modifier.appear(rememberAppearance(2))) { save(schedule.copy(endMinute = it)) }
        WorkDayHoursSection(schedule, Modifier.appear(rememberAppearance(2)),
            onSet = { d, a, b -> scope.launch { core.setWorkDayHours(d, a, b) } },
            onClear = { d -> scope.launch { core.clearWorkDayHours(d) } })
        Text(
            LoneWordGlue.kept(if (schedule.enabled) HOURS_ON_LINE else HOURS_OFF_LINE),
            style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { save(schedule.copy(enabled = !schedule.enabled)) }.padding(vertical = MekaSpace.xxs),
        )

        Spacer(Modifier.height(MekaSpace.m))
        Label(CommuteRules.TITLE, 3)
        CommuteSection(
            work.commute, work.commuteDays, Modifier.appear(rememberAppearance(3)),
            onSetDay = { d, to, home -> scope.launch { core.setCommuteDay(d, to, home) } },
            onClearDay = { d -> scope.launch { core.clearCommuteDay(d) } },
        ) { to, home ->
            scope.launch { core.setCommute(to, home) }
        }

        Spacer(Modifier.height(MekaSpace.m))
        Label(LeavingRules.TITLE, 3)
        LeavingNowSection(leaving, lists, Modifier.appear(rememberAppearance(3))) { on, to, journey ->
            scope.launch { core.setLeavingNow(on, to, journey) }
        }

        Spacer(Modifier.height(MekaSpace.m))
        Label("While you're at work", 3)
        Text(
            LoneWordGlue.kept(
                if (listening) "Navilife is holding WhatsApp, texts and missed calls for after work."
                else "Give Navilife notification access so it can hold WhatsApp, texts and missed calls for after work.",
            ),
            style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(3)),
        )
        if (!listening) {
            PillButton("Allow notification access", filled = false) { openListenerSettings(context) }
            Text(
                "If Android says the setting is restricted: App info → ⋮ → Allow restricted settings, then try again.",
                style = MekaType.caption, color = Meka.colors.textTertiary,
            )
        }
        if (!alertsOk) {
            PillButton("Allow urgent alerts", filled = false) {
                if (Build.VERSION.SDK_INT >= 33) askAlerts.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppNotificationSettings(context)
            }
        }
        Text(
            LoneWordGlue.kept(AT_WORK_NOTE),
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )

        Spacer(Modifier.height(MekaSpace.m))
        Label("Call assistant", 4)
        Crossfade(
            CallScreeningRules.statusLine(work.callAssistant, work.atWork, screening, work.callAssistantPaused, work.screenUnknownAlways),
            animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "call-line",
        ) { line ->
            Text(LoneWordGlue.kept(line), style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(4)))
        }
        PillButton(if (work.callAssistant) "Turn the call assistant off" else "Turn the call assistant on", filled = false) {
            haptics.tick(); scope.launch { core.setCallAssistant(!work.callAssistant) }
        }
        AnimatedVisibility(
            work.callAssistant && !screening,
            enter = if (Meka.reducedMotion) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (Meka.reducedMotion) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            PillButton("Let Navilife screen calls", filled = true) {
                MekaCallScreeningService.roleRequest(context)?.let { runCatching { askScreening.launch(it) } }
            }
        }
        Text(
            LoneWordGlue.kept(callAssistantNote()),
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )
        // The number is About you's (rule 13); without it the line says where to add it.
        Text(LoneWordGlue.kept(CallScreeningRules.testLine()), style = MekaType.caption, color = Meka.colors.textSecondary)
        ScreenUnknownRow(work.screenUnknownAlways, work.callAssistant, Modifier.appear(rememberAppearance(4))) {
            haptics.tick(); scope.launch { core.setScreenUnknownAlways(!work.screenUnknownAlways) }
        }
        Spacer(Modifier.height(MekaSpace.m))
        RecordingKeepSection(work.recordingDays, Modifier.appear(rememberAppearance(4))) { d -> scope.launch { core.setRecordingDays(d) } }
        Spacer(Modifier.height(MekaSpace.m))
        SpamProtectionSection(core, 4)

        Spacer(Modifier.height(MekaSpace.m))
        PeopleSection(ListKind.FAMILY, lists, store::setLists, 5) { pick(ListKind.FAMILY) }
        Spacer(Modifier.height(MekaSpace.m))
        PeopleSection(ListKind.ALWAYS, lists, store::setLists, 6) { pick(ListKind.ALWAYS) }
        Spacer(Modifier.height(MekaSpace.m))
        RequestWatchSection(
            lists, watch, listening, 7, store::setWatch,
            notifiesNow = { notify.notifiesNow(it) },
            setNotifyNow = { name, on -> scope.launch { core.setRequestNotifyNow(name, on) } },
        )
        Spacer(Modifier.height(MekaSpace.m))
        MessagesSetupSection(core, store, listening, 8)
        Spacer(Modifier.height(MekaSpace.m))
        DiscordSetupSection(core, 9)
        Spacer(Modifier.height(MekaSpace.xl))
    }
}

/**
 * "While you were at work": one screen, grouped by person, urgent first then family. Tapping a person opens
 * everything they sent. Done clears MEKA's copy only.
 */
@Composable
fun AfterWorkPane(
    summary: AfterWorkSummary,
    onDone: () -> Unit,
    onClose: () -> Unit,
    blocked: BlockedCallersView = BlockedCallersView.EMPTY,
    onBlock: (PersonSummary) -> Unit = {},
    onReport: (String) -> Unit = {},
    /** A kept voice message's recording (polish 8c), fetched from MEKA's server when Play is pressed. */
    audio: suspend (String) -> ByteArray? = { null },
    /** The "Keep callers' recordings" note ([VoiceRecordingRules.privacy] for the synced choice). */
    privacy: String = VoiceRecordingRules.PRIVACY,
    /** ▶ Play on an urgent alert: this message's caller opens unfolded and it starts playing ([onAutoPlayed] once). */
    autoPlay: String? = null,
    onAutoPlayed: () -> Unit = {},
    /** Done on one card (its pill or a swipe either way): clears only that card, with Undo ([AfterWorkDone]). */
    onDoneOne: (PersonSummary) -> Unit = {},
) {
    val haptics = rememberMekaHaptics()
    var open by rememberSaveable { mutableStateOf<String?>(CallAssistantRules.personWith(summary, autoPlay)?.cardKey) }
    LaunchedEffect(autoPlay) {
        CallAssistantRules.personWith(summary, autoPlay)?.let { open = it.cardKey }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text(summary.title, style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(0)))
        // Clear all sits beside the headline (Meka, 2026-10-15 20:28), not at the foot of a long sheet.
        Row(Modifier.fillMaxWidth().appear(rememberAppearance(0)), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContentText(summary.headline, Modifier.weight(1f))
            if (!summary.isEmpty) {
                Text(AfterWorkDone.CLEAR_ALL, style = MekaType.captionStrong, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) { haptics.light(); onDone() }
                        .minTouch().padding(horizontal = MekaSpace.xs))
            }
        }
        if (!summary.isEmpty) Text(AfterWorkDone.SWIPE_HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        Spacer(Modifier.height(MekaSpace.xs))
        // Email-triage style: people sort into place with a stagger; a card cleared folds away and the rest close up.
        summary.people.forEachIndexed { i, p ->
            // A group chat is one card (Meka, 2026-10-15 20:28), keyed apart from any one-to-one chat with a sender.
            val key = p.cardKey
            androidx.compose.runtime.key(key) {
                SwipeToDone(AfterWorkDone.doneLabel(p), onDone = { onDoneOne(p) }, modifier = Modifier.appear(rememberAppearance(i + 1))) {
                    PersonCard(p, expanded = open == key, Modifier, blocked.has(p.blockNumber), onBlock, onReport, audio, autoPlay, onAutoPlayed,
                        onDone = { haptics.light(); onDoneOne(p) }) {
                        open = if (open == key) null else key
                    }
                }
            }
        }
        Spacer(Modifier.height(MekaSpace.m))
        Text(AfterWorkDone.HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
        if (summary.people.any { p -> p.items.any { it.hasAudio } }) {
            Text(privacy, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Spacer(Modifier.height(MekaSpace.xl))
    }
}

/** A calm card for Needs you: what's waiting after work, or how much is being held during it. */
@Composable
fun AfterWorkCard(
    core: MekaCore,
    modifier: Modifier = Modifier,
    /** Clear all from the card itself (Meka, 2026-10-15 20:28), with Undo on Needs you's bar. */
    undoLine: (String, (suspend () -> Unit)?) -> Unit = { _, _ -> },
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val store = (context.applicationContext as MekaApplication).captures
    val synced by core.afterWork.collectAsState()
    val lists by store.lists.collectAsState()
    val work by core.workMode.collectAsState()
    val cardHaptics = rememberMekaHaptics()
    if (synced.isEmpty) return
    val summary = remember(synced, lists) { synced.withLists(lists, CallerLookup.names(context)) }
    if (work.atWork) {
        val preview = remember(summary, work) { core.heldPreview(summary) }
        HeldPreviewLine(preview, modifier)
        return
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onOpen() }.padding(MekaSpace.m),
    ) {
        // Title with an Open › affordance, then the first two names over the counts (Fold review 2026-10-12 17:56, item 3).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            val app = context.applicationContext as MekaApplication
            Text(AfterWorkDone.CLEAR_ALL, style = MekaType.captionStrong, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button) { cardHaptics.light(); clearAfterWorkCards(app, summary.people, AfterWorkDone.clearedAll(summary), undoLine) }
                    .minTouch().padding(horizontal = MekaSpace.xs))
            Text(AfterWorkSummary.OPEN_LABEL + " ›", style = MekaType.captionStrong, color = Meka.colors.accent)
        }
        summary.namesLine?.let { Text(it, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(summary.headline, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        if (summary.urgentPeople > 0) {
            Text("${summary.urgentPeople} urgent", style = MekaType.captionStrong, color = Meka.colors.critical)
        }
    }
}

/**
 * "At work · 3 held for later" (Fold review 2026-10-09, item 5): tapping it unfolds what's held in place — sender ·
 * first line · time, newest first, at most five — with the expand spring and a tick haptic; the chevron turns. A
 * preview only: nothing is marked read or cleared (the after-work summary and its Done are unchanged).
 */
@Composable
internal fun HeldPreviewLine(preview: HeldPreview, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduced) MekaMotion.appear<Float>(true) else MekaMotion.expand<Float>(false), label = "held-chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s))
                .clickable(role = Role.Button, enabled = !preview.isEmpty) { haptics.tick(); open = !open }
                .semantics { contentDescription = preview.label + ". " + if (open) HeldPreviewRules.HIDE_HINT else HeldPreviewRules.SHOW_HINT }
                .padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Text(preview.label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f, fill = false))
            Text("›", style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(
            visible = open && !preview.isEmpty,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut() else shrinkVertically(MekaMotion.expand(false)) + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                preview.rows.forEachIndexed { i, r -> HeldPreviewRowView(r, Modifier.appear(rememberAppearance(i))) }
                preview.moreLine?.let {
                    Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(preview.rows.size)))
                }
                Text(preview.caption, style = MekaType.caption, color = Meka.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun HeldPreviewRowView(r: HeldPreviewRow, modifier: Modifier) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(r.who, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Text("${r.app.label} · ${r.time}", style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1)
        }
        Text(r.line, style = MekaType.body, color = if (r.urgent) Meka.colors.critical else Meka.colors.textSecondary, maxLines = 2)
    }
}

/**
 * Hosts the after-work summary (synced, so the Mac shows the same one), so Needs you only passes visibility. Done
 * clears it on both devices and drops this phone's sealed copy.
 */
@Composable
fun AfterWorkHost(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MekaApplication
    val store = app.captures
    val synced by app.core.afterWork.collectAsState()
    val lists by store.lists.collectAsState()
    val summary = remember(synced, lists) { synced.withLists(lists, CallerLookup.names(context)) }
    val scope = rememberCoroutineScope()
    val blocked by app.core.blockedCallers.collectAsState()
    val work by app.core.workMode.collectAsState()
    LaunchedEffect(Unit) { app.nudger.dismiss() } // he's reading it: the nudge has done its job
    val autoPlay by app.playVoiceMessage.collectAsState()
    DisposableEffect(Unit) { onDispose { app.playVoiceMessage.value = null } } // an ask for a message gone stays no longer
    val undo = os.meka.android.calendar.rememberEventUndo()
    // Done per card and Clear all (Meka, 2026-10-15 20:28): the cards leave at once, Undo for 5 s, then cleared on
    // both devices (the core waits out the Undo); this phone's sealed copy and a call's notification go with them.
    val clear: (List<PersonSummary>, String) -> Unit = { people, line -> clearAfterWorkCards(app, people, line, undo::show) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    AfterWorkPane(summary, onDone = {
        clear(summary.people, AfterWorkDone.clearedAll(summary))
    }, onDoneOne = { p -> clear(listOf(p), AfterWorkDone.clearedOne(p)) }, onClose = onClose, blocked = blocked,
        onBlock = { p -> scope.launch { runCatching { app.core.blockHeldCaller(p) } } },
        onReport = { number -> reportScamCall(context, number) },
        audio = { id -> app.core.voiceMessageAudio(id) },
        privacy = VoiceRecordingRules.privacy(work.recordingDays),
        autoPlay = autoPlay, onAutoPlayed = { app.playVoiceMessage.value = null })
    os.meka.android.calendar.EventUndoBar(undo, Modifier.align(Alignment.BottomCenter))
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun time(ms: Long) = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
internal fun PersonCard(
    p: PersonSummary,
    expanded: Boolean,
    modifier: Modifier,
    isBlocked: Boolean,
    onBlock: (PersonSummary) -> Unit,
    onReport: (String) -> Unit,
    audio: suspend (String) -> ByteArray?,
    autoPlay: String?,
    onAutoPlayed: () -> Unit,
    onDone: () -> Unit,
    onTap: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onTap() }.padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        // Done at the far right edge, in the same place on every card (Meka, 2026-10-20 17:33): the name, its
        // labels and the time keep the rest of the row, so a long name never moves it.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(p.personName, style = MekaType.itemTitle, color = Meka.colors.textPrimary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.urgent) Text("Urgent", style = MekaType.captionStrong, color = Meka.colors.critical)
                    if (p.isFamily) Text("Family", style = MekaType.caption, color = Meka.colors.accent)
                    if (p.mentioned) Text(GroupChatRules.MENTIONED, style = MekaType.captionStrong, color = Meka.colors.accent)
                    Text(time(p.latestAtMs), style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                Text("${p.line} · ${p.apps.joinToString(", ") { it.label }}", style = MekaType.caption, color = Meka.colors.textSecondary)
                if (!expanded) p.latestText?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 2) }
            }
            // Its own Done (Meka, 2026-10-15 20:28): clears just this card, Undo on the bar.
            DonePill(AfterWorkDone.doneLabel(p), label = AfterWorkDone.DONE, tag = "after-work-done-${p.cardKey}", onDone = onDone)
        }
        AnimatedVisibility(
            expanded,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                p.shownItems.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(time(item.atMs), style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.width(44.dp))
                        val body = item.displayLine
                        Column(Modifier.weight(1f)) {
                            // In a group's card each line names who wrote it.
                            p.itemLabel(item)?.let { Text(it, style = MekaType.captionStrong, color = Meka.colors.textSecondary) }
                            Text(body, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                            if (item.hasAudio) {
                                VoiceMessagePlayer(item.id, audio, autoStart = item.id == autoPlay, onAutoStarted = onAutoPlayed)
                            }
                        }
                    }
                }
                p.blockNumber?.let { number -> BlockAndReport(number, isBlocked, haptics, onBlock = { onBlock(p) }, onReport = { onReport(number) }) }
            }
        }
    }
}

private enum class ListKind(val title: String, val hint: String) {
    FAMILY("Family", "Shown first after work. Their calls ring while the call assistant screens."),
    ALWAYS("Always notify", "Messages and missed calls from these people alert you straight away, and their calls ring.");

    fun names(l: PeopleLists) = if (this == FAMILY) l.family else l.alwaysNotify
    fun add(l: PeopleLists, name: String) = if (this == FAMILY) l.copy(family = l.family + name) else l.copy(alwaysNotify = l.alwaysNotify + name)
    fun remove(l: PeopleLists, name: String) = if (this == FAMILY) l.copy(family = l.family - name) else l.copy(alwaysNotify = l.alwaysNotify - name)
}

@Composable
private fun PeopleSection(kind: ListKind, lists: PeopleLists, set: (PeopleLists) -> Unit, index: Int, onAdd: () -> Unit) {
    Label(kind.title, index)
    Text(LoneWordGlue.kept(kind.hint), style = MekaType.caption, color = Meka.colors.textTertiary)
    kind.names(lists).sortedBy { it.lowercase() }.forEach { name ->
        Row(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                if (!lists.hasNumber(name)) {
                    // Call assistant polish 7: a contact with no number can't be recognised when they call.
                    Text(ContactNumbers.NO_NUMBER, style = MekaType.caption, color = Meka.colors.critical)
                    Text("Pick another entry", style = MekaType.caption, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { onAdd() }.padding(vertical = MekaSpace.xxs))
                }
            }
            Text("Remove", style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { set(kind.remove(lists, name)) }.padding(MekaSpace.xs))
        }
    }
    Text("Add from contacts", style = MekaType.itemMeta, color = Meka.colors.accent,
        modifier = Modifier.clickable(role = Role.Button) { onAdd() }.padding(vertical = MekaSpace.xs))
}

@Composable
private fun Label(text: String, index: Int) {
    Text(text.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(index)))
}

@Composable
private fun DayChip(isoDay: Int, on: Boolean, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "day-bg")
    val fg by animateColorAsState(if (on) Meka.colors.onAccent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "day-fg")
    val name = LocalClock.DAY_SHORT[isoDay - 1]
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .clickable(role = Role.Checkbox) { onToggle() }
            .semantics { contentDescription = name; selected = on },
        contentAlignment = Alignment.Center,
    ) { Text(name.take(1), style = MekaType.captionStrong, color = fg) }
}

@Composable
internal fun TimeStepper(label: String, minute: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val day = LocalClock.MINUTES_PER_DAY
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = if (filled) Meka.colors.onAccent else Meka.colors.accent,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    )
}

private fun hasNotificationAccess(context: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

internal fun openListenerSettings(context: Context) {
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, WorkCaptureService::class.java).flattenToString())
    runCatching { context.startActivity(detail) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
}

/**
 * A contact picked from the phone's picker (call assistant polish 7): the entry's name and number, plus every other
 * number the contact has when Meka let MEKA read contacts (the picker alone grants only the one entry). Read on the
 * phone only.
 */
private class PickedContact(val name: String?, val numbers: List<String?>) {
    companion object {
        fun read(context: Context, uri: android.net.Uri): PickedContact? = runCatching {
            val first = context.contentResolver.query(
                uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.CONTACT_ID), null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) null
                else Triple(c.getString(0)?.trim(), listOf(c.getString(2), c.getString(1)), c.getLong(3))
            } ?: return@runCatching null
            val (name, numbers, contactId) = first
            val all = if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    context.contentResolver.query(
                        Phone.CONTENT_URI, arrayOf(Phone.NORMALIZED_NUMBER, Phone.NUMBER), "${Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()), null,
                    )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0) ?: c.getString(1)) } }
                }.getOrNull().orEmpty()
            } else emptyList()
            PickedContact(name, numbers + all)
        }.getOrNull()
    }
}

/**
 * Block · Report to 7726 under a held message from someone nobody knows (call assistant polish 8b b, e). Block presses
 * in with a light haptic and the row's line cross-fades to "Blocked · their calls won't ring"; Report gives a tick
 * haptic and hands over to Messages with the text to 7726 filled in (Meka sends it). Reduced motion: cross-fades.
 */
@Composable
private fun BlockAndReport(number: String, isBlocked: Boolean, haptics: MekaHaptics, onBlock: () -> Unit, onReport: () -> Unit) {
    Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            Crossfade(isBlocked, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "held-block") { done ->
                if (done) Text(BlockedCallerRules.BLOCKED_LINE, style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(vertical = MekaSpace.xs))
                else Text(BlockedCallerRules.BLOCK_LABEL, style = MekaType.itemMeta, color = Meka.colors.critical,
                    modifier = Modifier.clickable(role = Role.Button) { haptics.light(); onBlock() }.padding(vertical = MekaSpace.xs)
                        .semantics { contentDescription = "Block ${BlockedCallerRules.display(number)}" })
            }
            Text(BlockedCallerRules.REPORT_LABEL, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); onReport() }.padding(vertical = MekaSpace.xs))
        }
        Text(BlockedCallerRules.REPORT_HINT, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** Opens Messages with "Call 01632960691" to 7726; Meka presses send. Nothing is sent by MEKA. */
internal fun reportScamCall(context: Context, number: String) {
    val text = BlockedCallerRules.reportText(number) ?: return
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + BlockedCallerRules.REPORT_TO))
        .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * "Screen unknown callers all the time" (Meka 2026-10-22): a switch row under the call assistant; the On/Off pill
 * fills gold on the appear spring and the line under it cross-fades. Needs the call assistant on to do anything.
 */
@Composable
internal fun ScreenUnknownRow(on: Boolean, assistantOn: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(reduced), label = "screen-unknown")
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onToggle() }
                .semantics { contentDescription = ScreenUnknownRules.TITLE; selected = on }
                .minTouch().padding(vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ScreenUnknownRules.TITLE, style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                if (on) "On" else "Off", style = MekaType.caption, color = Meka.colors.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
            )
        }
        Crossfade(ScreenUnknownRules.line(on, assistantOn), animationSpec = MekaMotion.appear(reduced), label = "screen-unknown-line") { line ->
            Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        Text(LoneWordGlue.kept(ScreenUnknownRules.TRADE_OFF), style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}
