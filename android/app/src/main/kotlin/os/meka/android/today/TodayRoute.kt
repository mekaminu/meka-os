package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.NeedsYouReason
import os.meka.core.domain.Task
import os.meka.core.domain.Today
import os.meka.core.facade.ConflictChoice
import os.meka.core.facade.MekaCore
import os.meka.core.sync.SyncStatus
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * TODAY (brief §4). Closed Fold: one calm column. Open Fold / wide windows (≥ 600dp): Today | selected item.
 * State survives fold/unfold because selection is saveable and everything else comes from MekaCore flows.
 */
@Composable
fun TodayRoute(core: MekaCore, connect: ConnectHook? = null) {
    val today by core.today.collectAsState()
    val sync by core.syncStatus.collectAsState()
    val conflicts by core.conflicts.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCalendars by rememberSaveable { mutableStateOf(false) }
    var showPlan by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val openCalendars: (() -> Unit)? = if (connect == null) ({ showCalendars = true }) else null
    val openPlan: () -> Unit = { showPlan = true }

    val actions = TodayActions(
        add = { title -> scope.launch { runCatching { core.addTask(title) } } },
        complete = { id -> scope.launch { core.complete(id); if (selectedId == id) selectedId = null } },
        select = { id -> selectedId = id },
        rename = { id, t -> scope.launch { runCatching { core.rename(id, t) } } },
        delete = { id -> scope.launch { core.delete(id); selectedId = null } },
        resolve = { c, v -> scope.launch { core.resolve(c, v) } },
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background).safeDrawingPadding()) {
        val twoPane = maxWidth >= 600.dp
        val all = (today.needsYou.map { it.task } + listOfNotNull(today.upNext) + today.yourDay)
        val selected = all.firstOrNull { it.id == selectedId }
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                TodayPane(today, sync, actions, Modifier.weight(0.55f).fillMaxHeight(), connect, openCalendars, openPlan)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Meka.colors.hairline))
                DetailPane(selected, conflicts.filter { it.taskId == selected?.id }, actions, Modifier.weight(0.45f).fillMaxHeight())
            }
        } else {
            TodayPane(today, sync, actions, Modifier.fillMaxSize(), connect, openCalendars, openPlan)
            if (selected != null) {
                // Single-pane: detail slides over Today; back/tap-outside closes.
                Box(Modifier.fillMaxSize().background(Meka.colors.background)) {
                    DetailPane(selected, conflicts.filter { it.taskId == selected.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
        }
        if (showPlan) {
            Box(Modifier.fillMaxSize().background(Meka.colors.background)) {
                PlanPane(core, onClose = { showPlan = false })
            }
        }
        if (showCalendars) {
            Box(Modifier.fillMaxSize().background(Meka.colors.background)) {
                CalendarsPane(core, onClose = { showCalendars = false })
            }
        }
    }
}

/** Present only while the device isn't enrolled for sync. */
data class ConnectHook(val defaultUrl: String, val connect: suspend (url: String, code: String) -> String?)

data class TodayActions(
    val add: (String) -> Unit,
    val complete: (String) -> Unit,
    val select: (String) -> Unit,
    val rename: (String, String) -> Unit,
    val delete: (String) -> Unit,
    val resolve: (ConflictChoice, String) -> Unit,
)

@Composable
private fun TodayPane(
    today: Today, sync: SyncStatus, actions: TodayActions, modifier: Modifier, connect: ConnectHook?, openCalendars: (() -> Unit)?,
    openPlan: () -> Unit,
) {
    Column(modifier.imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "greeting") {
                Column(Modifier.padding(bottom = MekaSpace.l)) {
                    Text(greeting(), style = MekaType.greeting, color = Meka.colors.textPrimary)
                    SyncLine(sync)
                    if (connect != null) ConnectCard(connect.defaultUrl, connect.connect, Modifier.padding(top = MekaSpace.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l), modifier = Modifier.padding(top = MekaSpace.xs)) {
                        Text(
                            "Plan my day", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { openPlan() }.padding(vertical = MekaSpace.xxs),
                        )
                        if (openCalendars != null) {
                            Text(
                                "Calendars", style = MekaType.caption, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                    .clickable(role = Role.Button) { openCalendars() }.padding(vertical = MekaSpace.xxs),
                            )
                        }
                    }
                }
            }
            if (today.isClear) {
                item(key = "clear") {
                    Text("You're clear.", style = MekaType.upNextTitle, color = Meka.colors.textSecondary,
                        modifier = Modifier.animateItem())
                }
            }
            if (today.needsYou.isNotEmpty()) {
                item(key = "h-needs") { SectionLabel("Needs you", Modifier.animateItem()) }
                items(today.needsYou, key = { "n-" + it.task.id }) { n ->
                    TaskRow(n.task, actions, reason = n.reason, modifier = Modifier.animateItem())
                }
                item(key = "s-needs") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            today.upNext?.let { t ->
                item(key = "h-next") { SectionLabel("Up next", Modifier.animateItem()) }
                item(key = "u-" + t.id) { UpNextCard(t, actions, Modifier.animateItem()) }
                item(key = "s-next") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            if (today.events.isNotEmpty()) {
                item(key = "h-cal") { SectionLabel("Calendar", Modifier.animateItem()) }
                items(today.events, key = { "e-" + it.id }) { e -> EventRow(e, Modifier.animateItem()) }
                item(key = "s-cal") { Spacer(Modifier.height(MekaSpace.l)) }
            }
            if (today.yourDay.isNotEmpty()) {
                item(key = "h-day") { SectionLabel("Your day", Modifier.animateItem()) }
                items(today.yourDay, key = { "d-" + it.id }) { t -> TaskRow(t, actions, modifier = Modifier.animateItem()) }
            }
            if (today.doneToday.isNotEmpty()) {
                item(key = "done") {
                    Text("${today.doneToday.size} done today", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.l).animateItem())
                }
            }
        }
        QuickCapture(actions.add)
    }
}

@Composable
private fun SyncLine(sync: SyncStatus) {
    // Silence is the default: only say something when the user would want to know.
    val text = when (sync) {
        is SyncStatus.Offline -> if (sync.pending > 0) "Offline · ${sync.pending} change(s) waiting to sync" else null
        is SyncStatus.Failing -> sync.reason
        else -> null
    }
    AnimatedVisibility(text != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
        Text(text.orEmpty(), style = MekaType.caption, color = Meka.colors.offline, modifier = Modifier.padding(top = MekaSpace.xs))
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = modifier.padding(bottom = MekaSpace.xxs))
}

@Composable
private fun UpNextCard(t: Task, actions: TodayActions, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.l))
            .background(Meka.colors.surfaceRaised)
            .clickable { actions.select(t.id) }
            .padding(MekaSpace.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MekaType.upNextTitle, color = Meka.colors.textPrimary)
            meta(t)?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
        }
        CompleteButton(t, actions.complete)
    }
}

@Composable
private fun TaskRow(t: Task, actions: TodayActions, reason: NeedsYouReason? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable { actions.select(t.id) }.padding(vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompleteButton(t, actions.complete)
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
            val line = when (reason) {
                NeedsYouReason.CONFLICT -> "Edited on two devices — choose a version"
                NeedsYouReason.OVERDUE -> "Overdue"
                NeedsYouReason.DUE_TODAY_UNSCHEDULED -> "Due today · not scheduled"
                null -> meta(t)
            }
            line?.let {
                val color = if (reason == NeedsYouReason.CONFLICT || reason == NeedsYouReason.OVERDUE) Meka.colors.critical else Meka.colors.textSecondary
                Text(it, style = MekaType.itemMeta, color = color)
            }
        }
    }
}

/** Completion motion (brief §3): ring fills → check → row compresses and leaves via animateItem. */
@Composable
private fun CompleteButton(t: Task, onComplete: (String) -> Unit) {
    var pressed by remember(t.id) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val reduced = Meka.reducedMotion
    val fill by animateColorAsState(if (pressed) Meka.colors.accent else Meka.colors.background, MekaMotion.complete(reduced), label = "fill")
    val scale by animateFloatAsState(if (pressed && !reduced) 0.86f else 1f, MekaMotion.complete(reduced), label = "scale",
        finishedListener = { if (pressed) onComplete(t.id) })
    Box(
        Modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, if (pressed) Meka.colors.accent else Meka.colors.textTertiary, CircleShape)
            .semantics { contentDescription = "Complete ${t.title}" }
            .clickable(role = Role.Checkbox) {
                if (!pressed) {
                    pressed = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (reduced) onComplete(t.id)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (pressed) Text("✓", color = Meka.colors.onAccent, style = MekaType.caption)
    }
}

@Composable
private fun QuickCapture(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.s)
            .clip(RoundedCornerShape(MekaRadius.pill))
            .background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        if (text.isEmpty()) Text("Capture anything…", style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text); text = "" } }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Capture" },
        )
    }
}

@Composable
private fun DetailPane(task: Task?, conflicts: List<ConflictChoice>, actions: TodayActions, modifier: Modifier, onClose: (() -> Unit)? = null) {
    Column(modifier.padding(MekaSpace.gutter)) {
        if (onClose != null) {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable { onClose() }.padding(vertical = MekaSpace.s))
        }
        if (task == null) {
            Spacer(Modifier.weight(1f))
            Text("Select something to see it here.", style = MekaType.body, color = Meka.colors.textTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))
        } else {
            TaskDetail(task, conflicts, actions)
        }
    }
}

@Composable
private fun ColumnScope.TaskDetail(task: Task, conflicts: List<ConflictChoice>, actions: TodayActions) {
    var title by remember(task.id, task.title) { mutableStateOf(task.title) }
    BasicTextField(
        value = title,
        onValueChange = { title = it },
        textStyle = MekaType.upNextTitle.copy(color = Meka.colors.textPrimary),
        cursorBrush = SolidColor(Meka.colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.rename(task.id, title) }),
        modifier = Modifier.fillMaxWidth().padding(vertical = MekaSpace.m),
    )
    meta(task)?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }

    conflicts.forEach { c ->
        Spacer(Modifier.height(MekaSpace.l))
        SectionLabel("Edited on two devices")
        c.options.forEach { option ->
            Text(
                option, style = MekaType.itemTitle, color = Meka.colors.textPrimary,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .background(Meka.colors.surfaceRaised).clickable { actions.resolve(c, option) }.padding(MekaSpace.m),
            )
            Spacer(Modifier.height(MekaSpace.xs))
        }
    }

    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
        Text("Done", style = MekaType.itemTitle, color = Meka.colors.accent, modifier = Modifier.clickable { actions.complete(task.id) })
        Text("Delete", style = MekaType.itemTitle, color = Meka.colors.critical, modifier = Modifier.clickable { actions.delete(task.id) })
    }
}

/** One calendar event: time on the left, title and source on the right. Finished events step back. */
@Composable
private fun EventRow(e: CalendarEvent, modifier: Modifier = Modifier) {
    val past = !e.allDay && e.endAtMs < System.currentTimeMillis()
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        Text(
            eventTime(e), style = MekaType.itemMeta,
            color = if (past) Meka.colors.textTertiary else Meka.colors.textSecondary,
            modifier = Modifier.width(92.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MekaType.itemTitle, color = if (past) Meka.colors.textTertiary else Meka.colors.textPrimary)
            val source = listOfNotNull(e.location, providerLabel(e.provider)).joinToString(" · ")
            Text(source, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}

private fun eventTime(e: CalendarEvent): String =
    if (e.allDay) "All day"
    else timeFmt.format(Instant.ofEpochMilli(e.startAtMs).atZone(ZoneId.systemDefault())) + "–" +
        timeFmt.format(Instant.ofEpochMilli(e.endAtMs).atZone(ZoneId.systemDefault()))

internal fun providerLabel(p: String) = when (p) { "google" -> "Google"; "microsoft" -> "Outlook"; "fixtures" -> "Fixtures"; else -> p }

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun meta(t: Task): String? {
    val parts = buildList {
        t.scheduledAtMs?.let { add(timeFmt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))) }
        t.estimateMinutes?.let { add("$it min") }
        t.checklist.takeIf { it.isNotEmpty() }?.let { cl -> add("${cl.count { it.checked }}/${cl.size}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning, Meka"
    in 12..17 -> "Good afternoon, Meka"
    else -> "Good evening, Meka"
}
