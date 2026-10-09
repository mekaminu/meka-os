package os.meka.android.work

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
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
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AfterWorkSummary
import os.meka.core.domain.CallScreeningRules
import os.meka.core.domain.ContactNumbers
import os.meka.core.domain.HeldPreview
import os.meka.core.domain.HeldPreviewRow
import os.meka.core.domain.HeldPreviewRules
import os.meka.core.domain.LocalClock
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.PersonSummary
import os.meka.core.domain.WorkSchedule
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import os.meka.android.shell.SharedMotion
import os.meka.android.shell.MoreItem
import os.meka.android.designsystem.sharedTitleInPane

internal const val STEP_MINUTES = 15

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
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text("Work mode", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WORK)).appear(rememberAppearance(0)))
        Text(work.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))

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
            if (schedule.enabled) "Using these hours · tap to use the switch only" else "Switch only · tap to use these hours",
            style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { save(schedule.copy(enabled = !schedule.enabled)) }.padding(vertical = MekaSpace.xxs),
        )

        Spacer(Modifier.height(MekaSpace.m))
        Label("While you're at work", 3)
        Text(
            if (listening) "MEKA is holding WhatsApp, texts and missed calls for after work."
            else "Give MEKA notification access so it can hold WhatsApp, texts and missed calls for after work.",
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
            "\"Urgent\" or \"emergency\" in a message, or anyone on your always-notify list, alerts you straight away. " +
                "MEKA never replies and never marks anything read. What it holds stays on this phone.",
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )

        Spacer(Modifier.height(MekaSpace.m))
        Label("Call assistant", 4)
        Crossfade(
            CallScreeningRules.statusLine(work.callAssistant, work.atWork, screening),
            animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "call-line",
        ) { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(4)))
        }
        PillButton(if (work.callAssistant) "Turn the call assistant off" else "Turn the call assistant on", filled = false) {
            haptics.tick(); scope.launch { core.setCallAssistant(!work.callAssistant) }
        }
        AnimatedVisibility(
            work.callAssistant && !screening,
            enter = if (Meka.reducedMotion) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (Meka.reducedMotion) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            PillButton("Let MEKA screen calls", filled = true) {
                MekaCallScreeningService.roleRequest(context)?.let { runCatching { askScreening.launch(it) } }
            }
        }
        Text(
            "At work, family, your always-notify list and anyone who calls twice within 3 minutes ring. Other calls are " +
                "declined, and your network's \"forward when busy\" sends them to MEKA's assistant " +
                "(${CallScreeningRules.ASSISTANT_NUMBER}), which takes a message. Declined calls still show as missed calls.",
            style = MekaType.caption, color = Meka.colors.textTertiary,
        )
        Text("Test it: ring ${CallScreeningRules.ASSISTANT_NUMBER} from another phone", style = MekaType.caption, color = Meka.colors.textSecondary)
        Spacer(Modifier.height(MekaSpace.s))
        SpamProtectionSection(core, 4)

        Spacer(Modifier.height(MekaSpace.m))
        PeopleSection(ListKind.FAMILY, lists, store::setLists, 5) { pick(ListKind.FAMILY) }
        Spacer(Modifier.height(MekaSpace.s))
        PeopleSection(ListKind.ALWAYS, lists, store::setLists, 6) { pick(ListKind.ALWAYS) }
        Spacer(Modifier.height(MekaSpace.m))
        RequestWatchSection(
            lists, watch, listening, 7, store::setWatch,
            notifiesNow = { notify.notifiesNow(it) },
            setNotifyNow = { name, on -> scope.launch { core.setRequestNotifyNow(name, on) } },
        )
        Spacer(Modifier.height(MekaSpace.m))
        MessagesSetupSection(core, store, listening, 8)
        Spacer(Modifier.height(MekaSpace.xl))
    }
}

/**
 * "While you were at work": one screen, grouped by person, urgent first then family. Tapping a person opens
 * everything they sent. Done clears MEKA's copy only.
 */
@Composable
fun AfterWorkPane(summary: AfterWorkSummary, onDone: () -> Unit, onClose: () -> Unit) {
    val haptics = rememberMekaHaptics()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text(summary.title, style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(0)))
        Text(summary.headline, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))
        Spacer(Modifier.height(MekaSpace.xs))
        // Email-triage style: people sort into place with a stagger.
        summary.people.forEachIndexed { i, p ->
            val key = p.items.first().personKey
            PersonCard(p, expanded = open == key, Modifier.appear(rememberAppearance(i + 1))) { open = if (open == key) null else key }
        }
        Spacer(Modifier.height(MekaSpace.m))
        if (!summary.isEmpty) PillButton("Done", filled = true) { haptics.light(); onDone() }
        Text("Done clears MEKA's copy on the Fold and the Mac. WhatsApp and Messages are untouched.", style = MekaType.caption, color = Meka.colors.textTertiary)
        Spacer(Modifier.height(MekaSpace.xl))
    }
}

/** A calm card for Needs you: what's waiting after work, or how much is being held during it. */
@Composable
fun AfterWorkCard(core: MekaCore, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as MekaApplication).captures
    val synced by core.afterWork.collectAsState()
    val lists by store.lists.collectAsState()
    val work by core.workMode.collectAsState()
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
        Text(summary.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(summary.headline, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        if (summary.urgentPeople > 0) {
            Text("${summary.urgentPeople} urgent", style = MekaType.caption.copy(fontWeight = FontWeight.SemiBold), color = Meka.colors.critical)
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
                verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
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
            Text(r.who, style = MekaType.itemMeta.copy(fontWeight = FontWeight.SemiBold), color = Meka.colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
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
    LaunchedEffect(Unit) { app.nudger.dismiss() } // he's reading it: the nudge has done its job
    AfterWorkPane(summary, onDone = {
        scope.launch { runCatching { app.core.clearAfterWork() } }
        store.clear()
        onClose()
    }, onClose = onClose)
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun time(ms: Long) = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
private fun PersonCard(p: PersonSummary, expanded: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onTap() }.padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Text(p.personName, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f, fill = false))
            if (p.urgent) Text("Urgent", style = MekaType.caption.copy(fontWeight = FontWeight.SemiBold), color = Meka.colors.critical)
            if (p.isFamily) Text("Family", style = MekaType.caption, color = Meka.colors.accent)
            Spacer(Modifier.weight(1f))
            Text(time(p.latestAtMs), style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Text("${p.line} · ${p.apps.joinToString(", ") { it.label }}", style = MekaType.caption, color = Meka.colors.textSecondary)
        if (!expanded) p.latestText?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 2) }
        AnimatedVisibility(
            expanded,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                p.items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                        Text(time(item.atMs), style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.width(44.dp))
                        val body = item.displayLine
                        Column(Modifier.weight(1f)) {
                            item.conversation?.let { Text("in $it", style = MekaType.caption, color = Meka.colors.textTertiary) }
                            Text(body, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                        }
                    }
                }
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
    Text(kind.hint, style = MekaType.caption, color = Meka.colors.textTertiary)
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
    ) { Text(name.take(1), style = MekaType.caption.copy(fontWeight = FontWeight.SemiBold), color = fg) }
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

private fun openListenerSettings(context: Context) {
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
