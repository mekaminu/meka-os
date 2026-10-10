package os.meka.android.activity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * row's line cross-fades to "Undone at 09:12"; MEKA's AI today ("Talk 6 · Ask 2 · 3¢") and MEKA's voice lines (how quickly it answered on this phone lately, the
 * month's characters) fade in once known. Reduced motion: cross-fades.
 */
@Composable
fun ActivityPane(core: MekaCore, onClose: () -> Unit) {
    val view by core.activityView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    // What the last undo did, when it wasn't simply "Undone" (that shows on the row itself).
    var note by remember { mutableStateOf<String?>(null) }
    // MEKA's voice this month ("MEKA's voice · Amy · 12,400 of 1,000,000 characters in October"); null: nothing to say.
    var voiceLine by remember { mutableStateOf<String?>(null) }
    // How quickly MEKA's voice answered on this phone lately ("Time to MEKA's voice · 1.8 s · 0.9 s · late"); null: none yet.
    var timingLine by remember { mutableStateOf<String?>(null) }
    // MEKA's AI today ("MEKA's AI today · Talk 6 · Ask 2 · 3¢"); null: nothing asked today or the server can't say.
    var aiLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        timingLine = runCatching { core.voiceTimingLine() }.getOrNull()
        voiceLine = runCatching { core.voiceUsageLine() }.getOrNull()
        aiLine = runCatching { core.aiTodayLine() }.getOrNull()
    }
    val reduced = Meka.reducedMotion

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("Activity", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ACTIVITY)).appear(rememberAppearance(0)))
        Text(
            if (view.isEmpty) view.emptyLine else "What MEKA did and why. ${view.weekLine}.",
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
            targetState = timingLine,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "activity-voice-timing",
        ) { line ->
            if (line != null) Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) else Spacer(Modifier.height(0.dp))
        }
        note?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        view.days.forEachIndexed { i, day ->
            Column(Modifier.fillMaxWidth().appear(rememberAppearance(i + 1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
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
            Text(row.summary, style = MekaType.body, color = Meka.colors.textPrimary)
            row.detail?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
            Text(row.why, style = MekaType.caption, color = Meka.colors.textTertiary)
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
