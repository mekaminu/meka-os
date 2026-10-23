package os.meka.android.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.ActivityRow
import os.meka.core.domain.ActivityFilter
import os.meka.core.domain.ActivityRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.StoppedCallRules
import os.meka.core.domain.StoppedCallerRow
import os.meka.android.lists.Chip
import os.meka.core.facade.MekaCore
import os.meka.android.shell.SharedMotion
import os.meka.android.shell.MoreItem
import os.meka.android.designsystem.sharedTitleInPane

/**
 * What MEKA did and why (V1 activity log): every reminder and digest that reached you, and later every change MEKA
 * makes for you, newest first by day, each with the rule or setting behind it. A change can be undone here (only
 * what is still as MEKA left it). Opened from Today's header.
 *
 * Motion: the pane springs up (MekaPane); day sections stagger in 40 ms apart; Undo gives a light haptic and the
 * row's line cross-fades to "Undone at 09:12"; the filter chips (All · Reminders · Done for you · Calendar · Calls ·
 * Other, only the kinds there are) blend their colour with a tick haptic and the days cross-fade; Blocked calls (spam
 * calls still ring, slice 4: numbers whose calls the block list stopped, with Unblock / Block again) fades in under the
 * filter for All and Calls, and a row's line cross-fades between "Blocked …" and "Unblocked …" with a light haptic; Recent
 * calls (call notifications, slice 4) follows it, a tap unfolding a call's detail on the expand spring; MEKA's AI today ("Talk 6 · Ask 2 · 3¢") and MEKA's voice lines (how quickly it answered on this phone lately, the
 * month's characters) fade in once known. Reduced motion: cross-fades.
 */
@Composable
fun ActivityPane(core: MekaCore, onClose: () -> Unit, closeLabel: String = "Close") {
    val view by core.activityView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    // What the last undo did, when it wasn't simply "Undone" (that shows on the row itself).
    var note by remember { mutableStateOf<String?>(null) }
    // MEKA's voice this month ("MEKA's voice · Amy · 12,400 of 1,000,000 characters in October"); null: nothing to say.
    var voiceLine by remember { mutableStateOf<String?>(null) }
    // How quickly MEKA's voice answered on this phone lately ("Time to MEKA's voice · 1.8 s · 0.9 s · late"); null: none yet.
    var timingLine by remember { mutableStateOf<String?>(null) }
    // The mic fix, step 3: how quickly what he said was added ("Speech to Added · 0.6 s · 0.9 s"); null: none yet.
    var heardLine by remember { mutableStateOf<String?>(null) }
    // MEKA's AI today ("MEKA's AI today · Talk 6 · Ask 2 · 3¢"); null: nothing asked today or the server can't say.
    var aiLine by remember { mutableStateOf<String?>(null) }
    // Ada's Live mode, slice 2b: Live this month ("Ada's Live · 3 talks · 13 min · £0.42 of £15 in October"); null: nothing to say.
    var liveLine by remember { mutableStateOf<String?>(null) }
    // Completeness: how long entries are kept and shown, and what Export saves; null while the log is empty.
    var keptLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(view) { keptLine = runCatching { core.activityKeptLine() }.getOrNull() }
    // Completeness: Export — the whole log as a CSV, saved where he picks (the system's save sheet).
    val context = androidx.compose.ui.platform.LocalContext.current
    var exportCsv by remember { mutableStateOf<String?>(null) }
    val saveCsv = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(os.meka.core.domain.TradingExportRules.MIME),
    ) { uri: android.net.Uri? ->
        val csv = exportCsv
        exportCsv = null
        if (uri == null || csv == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(csv.encodeToByteArray()) } != null }.getOrDefault(false)
            }
            if (ok) haptics.light()
            note = if (ok) "Saved Activity as a spreadsheet · open it in Sheets or Excel" else "Couldn't save it there · try another place"
        }
    }
    LaunchedEffect(Unit) {
        timingLine = runCatching { core.voiceTimingLine() }.getOrNull()
        heardLine = runCatching { core.heardTimingLine() }.getOrNull()
        voiceLine = runCatching { core.voiceUsageLine() }.getOrNull()
        aiLine = runCatching { core.aiTodayLine() }.getOrNull()
        liveLine = runCatching { core.liveActivityLine() }.getOrNull()
    }
    val reduced = Meka.reducedMotion

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(closeLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("Activity", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ACTIVITY)).appear(rememberAppearance(0)))
        Text(
            if (view.isEmpty) view.emptyLine else "What Navilife did and why. ${view.weekLine}.",
            style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)),
        )
        AnimatedContent(
            targetState = aiLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "activity-ai-today",
        ) { line ->
            if (line != null) Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) else Spacer(Modifier.height(0.dp))
        }
        AnimatedContent(
            targetState = voiceLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "activity-voice",
        ) { line ->
            if (line != null) Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) else Spacer(Modifier.height(0.dp))
        }
        AnimatedContent(
            targetState = liveLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "activity-live",
        ) { line ->
            if (line != null) Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) else Spacer(Modifier.height(0.dp))
        }
        AnimatedContent(
            targetState = timingLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "activity-voice-timing",
        ) { line ->
            if (line != null) Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) else Spacer(Modifier.height(0.dp))
        }
        AnimatedContent(
            targetState = heardLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "activity-heard-timing",
        ) { line ->
            if (line != null) Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) else Spacer(Modifier.height(0.dp))
        }
        note?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        // Filter (slice 3): only shown when there's more than one kind of thing; this phone's choice, never synced.
        var filterName by rememberSaveable { mutableStateOf(ActivityRules.filterNamed(null).name) }
        val chips = ActivityRules.filters(view)
        // Call notifications, slice 5: opened from a call's notification — Calls, with the call unfolded below.
        val callApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? os.meka.android.MekaApplication
        val callRequest by remember(callApp) { callApp?.openCall ?: kotlinx.coroutines.flow.MutableStateFlow<CallOpen?>(null) }.collectAsState()
        LaunchedEffect(callRequest) {
            if (callRequest == null) return@LaunchedEffect
            filterName = ActivityFilter.CALLS.name
            kotlinx.coroutines.delay(1_500L) // the Calls section has picked it up by now (both sides of the cross-fade)
            callApp?.openCall?.value = null
        }
        val filter = ActivityRules.chosen(view, ActivityRules.filterNamed(filterName))
        if (chips.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = MekaSpace.xs)
                    .appear(rememberAppearance(1)),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                chips.forEach { c ->
                    Chip(c.label, c.filter == filter) {
                        if (c.filter != filter) { haptics.tick(); filterName = c.filter.name }
                    }
                }
            }
        }
        val blocked by core.blockedCallers.collectAsState()
        AnimatedContent(
            targetState = filter,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "activity-filter",
        ) { f ->
            val shown = ActivityRules.filtered(view, f)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                // Blocked calls (spam calls still ring, slice 4): with All or Calls, the numbers the list stopped lately.
                val stopped = blocked.stopped
                if (stopped.isNotEmpty() && (f == ActivityFilter.ALL || f == ActivityFilter.CALLS)) {
                    Column(Modifier.fillMaxWidth().appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Spacer(Modifier.height(MekaSpace.m))
                        Text(StoppedCallRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
                        Text(
                            listOfNotNull(blocked.stoppedLine, StoppedCallRules.HINT).joinToString(". "),
                            style = MekaType.caption, color = Meka.colors.textTertiary,
                        )
                        stopped.forEach { row ->
                            StoppedCallRowView(row) {
                                haptics.light()
                                scope.launch {
                                    runCatching { if (row.blocked) core.unblockCaller(row.key) else core.reblockCaller(row.key) }
                                        .onFailure { note = "Couldn't change that. Try again." }
                                }
                            }
                        }
                    }
                }
                // Recent calls (call notifications, slice 4): with All or Calls, each call with its detail.
                if (f == ActivityFilter.ALL || f == ActivityFilter.CALLS) CallLogSection(index = 2) { note = it }
                shown.days.forEachIndexed { i, day ->
                    Column(Modifier.fillMaxWidth().appear(rememberAppearance(i + 2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Spacer(Modifier.height(MekaSpace.m))
                        Text(day.label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
                        day.rows.forEach { row ->
                            ActivityRowView(row) {
                                haptics.light()
                                scope.launch {
                                    val line = runCatching { core.undoActivity(row.id) }.getOrElse { "Couldn't undo that. Try again." }
                                    note = line.takeIf { it != "Undone" }
                                }
                            }
                        }
                    }
                }
                // Completeness: what's kept, and Export (the whole log, not just these days).
                keptLine?.let { kept ->
                    Column(Modifier.fillMaxWidth().appear(rememberAppearance(shown.days.size + 2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Spacer(Modifier.height(MekaSpace.m))
                        Text(kept, style = MekaType.caption, color = Meka.colors.textTertiary)
                        Text(
                            os.meka.core.domain.ActivityExportRules.LABEL, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                scope.launch {
                                    val f = runCatching { core.activityExport() }.getOrNull() ?: return@launch
                                    note = f.line
                                    if (f.csv != null && f.fileName != null) { exportCsv = f.csv; runCatching { saveCsv.launch(f.fileName!!) } }
                                }
                            }.minTouch().padding(vertical = MekaSpace.xxs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRowView(row: ActivityRow, undo: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Text(row.time, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        Spacer(Modifier.width(MekaSpace.xs))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            // Context, not something to act on: the regular body weight (Meka, 2026-10-06).
            Text(LoneWordGlue.kept(row.summary), style = MekaType.body, color = Meka.colors.textPrimary)
            row.detail?.let { Text(LoneWordGlue.kept(it), style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
            Text(LoneWordGlue.kept(row.why), style = MekaType.caption, color = Meka.colors.textTertiary)
            AnimatedContent(
                targetState = row.undoneLine to row.canUndo,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "activity-undo",
            ) { (undone, canUndo) ->
                when {
                    undone != null -> Text(undone, style = MekaType.caption, color = Meka.colors.textSecondary)
                    canUndo -> Text(
                        "Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { undo() }
                            .padding(vertical = MekaSpace.xxs),
                    )
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
        }
    }
}

/** One row of Blocked calls: the number, how many calls were stopped, why it's blocked, and Unblock / Block again. */
@Composable
private fun StoppedCallRowView(row: StoppedCallerRow, toggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.number, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(LoneWordGlue.kept(row.line), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            AnimatedContent(
                targetState = row.why to row.blocked,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "activity-blocked-call",
            ) { (why, isBlocked) ->
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(LoneWordGlue.kept(why), style = MekaType.caption, color = Meka.colors.textTertiary)
                    Text(
                        if (isBlocked) StoppedCallRules.UNBLOCK_LABEL else StoppedCallRules.BLOCK_AGAIN_LABEL,
                        style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { toggle() }
                            .padding(vertical = MekaSpace.xxs),
                    )
                }
            }
        }
    }
}
