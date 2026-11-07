package os.meka.android.ask

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.capture.CaptureListener
import os.meka.android.capture.ListeningLine
import os.meka.android.capture.VoiceNoteRecorder
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TouchPill
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.RoutineDraft
import os.meka.core.domain.RoutineDraftStep
import os.meka.core.domain.RoutineRules
import os.meka.core.domain.RoutineSentence
import os.meka.core.domain.RoutineStepMode
import os.meka.core.domain.TalkProblem

/**
 * Routines, slice 4: New routine (the approved mockup's view). ‹ Routines, the title, one field he types or says the
 * routine into (the 🎙 listens in the page as Capture does: his words in the field as he speaks, nothing recorded or
 * kept), the foot line, then OBI MADE THESE STEPS: the WHEN · STARTS WITH tiles and each step with its place and label
 * (a tap moves it on: Just show → Asks you → Obi does it, the last only for Inbox autopilot), the note, anything left
 * out because it would send, pay, book or trade, what's missing, and Save routine. The draft is the core's
 * [RoutineSentence.draft] of the words and the labels he chose; Save hands those two strings to the core.
 *
 * Motion: the steps stagger in 40 ms apart as the draft first appears and the page resizes on the expand spring as
 * steps come and go while he types; a label blends to its new colour with a tick haptic; Save is a light haptic.
 * Reduced motion: no resize spring, colours jump.
 */
@Composable
internal fun NewRoutinePage(onBack: () -> Unit, onSave: (text: String, modes: String) -> Unit, said: String?, start: String = "") {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    // Slice 5: an idea from his week opens with its sentence filled in.
    var typed by rememberSaveable { mutableStateOf(start) }
    var modes by rememberSaveable { mutableStateOf("") }
    val draft = remember(typed, modes) { RoutineSentence.draft(typed, modes) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val listener = remember {
        CaptureListener(
            context, scope,
            onHeard = { words, _, _ -> if (words.isBlank()) problem = RoutineSentence.HEARD_NOTHING else { typed = words.trim(); modes = "" } },
            onNothing = { line -> problem = line ?: RoutineSentence.HEARD_NOTHING },
        )
    }
    DisposableEffect(Unit) { onDispose { listener.release() } }
    LaunchedEffect(listener.partial) { if (listener.listening && listener.partial.isNotBlank()) { typed = listener.partial; modes = "" } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { problem = null; listener.start() } else problem = TalkProblem.NO_PERMISSION.line
    }
    val listen: () -> Unit = {
        haptics.tick()
        when {
            listener.listening -> listener.finishNow()
            !VoiceNoteRecorder.micAllowed(context) -> askMic.launch(Manifest.permission.RECORD_AUDIO)
            else -> { problem = null; listener.start() }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter).testTag(NEW_ROUTINE_TAG)
            .let { if (reduced) it else it.animateContentSize(MekaMotion.expand(false)) },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("‹ ${RoutineRules.BACK}", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onBack() }.minTouch().semantics { contentDescription = "Back to ${RoutineRules.BACK}" })
        Text(RoutineSentence.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.semantics { heading() }.appear(rememberAppearance(0)))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .padding(MekaSpace.m).appear(rememberAppearance(1)),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f).heightIn(min = 72.dp)) {
                if (typed.isEmpty()) Text(RoutineSentence.HINT, style = MekaType.body, color = Meka.colors.textTertiary)
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it.take(RoutineSentence.MAX_TEXT); modes = "" },
                    textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                    cursorBrush = SolidColor(Meka.colors.accent),
                    modifier = Modifier.fillMaxWidth().testTag("new-routine-field").semantics { contentDescription = RoutineSentence.TITLE },
                )
            }
            val on = listener.listening
            Box(
                Modifier.testTag("new-routine-mic").size(MekaSpace.touch).clip(CircleShape)
                    .background(if (on) Meka.colors.accent else Meka.colors.surface)
                    .clickable(role = Role.Button, onClick = listen)
                    .clearAndSetSemantics { contentDescription = if (on) RoutineSentence.MIC_DONE else RoutineSentence.MIC_SPOKEN; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { MicGlyph(if (on) Meka.colors.onAccent else Meka.colors.accent) }
        }
        if (listener.listening) ListeningLine(listener.engine, listener.resting, listener.level, reduced)
        problem?.takeIf { !listener.listening }?.let {
            Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text(LoneWordGlue.kept(RoutineSentence.FOOT), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.appear(rememberAppearance(1)))
        said?.let {
            Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.testTag("new-routine-said").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (typed.isNotBlank()) DraftPart(draft, onLabel = { i ->
            haptics.tick()
            modes = RoutineSentence.relabel(draft, i)
        }, onSave = {
            haptics.light()
            onSave(typed, draft.modes)
        })
    }
}

/** The page's own column, for the hand tests. */
const val NEW_ROUTINE_TAG = "new-routine"

@Composable
private fun DraftPart(d: RoutineDraft, onLabel: (Int) -> Unit, onSave: () -> Unit) {
    Text(RoutineSentence.MADE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
        modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(2)))
    Row(Modifier.fillMaxWidth().appear(rememberAppearance(2)), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        DraftTile(RoutineSentence.WHEN_TILE, d.whenTile, Modifier.weight(1f).testTag("new-routine-when"))
        DraftTile(RoutineSentence.STARTS_TILE, d.startsWith, Modifier.weight(1f))
    }
    d.steps.forEachIndexed { i, st -> key(i, st.title) { DraftStepRow(st, Modifier.appear(rememberAppearance(i + 3))) { onLabel(i) } } }
    if (d.steps.isNotEmpty()) {
        Text(LoneWordGlue.kept(RoutineSentence.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary)
    }
    d.leftOut?.let {
        Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.testTag("new-routine-left-out"))
    }
    d.problem?.let {
        Text(LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textSecondary,
            modifier = Modifier.testTag("new-routine-problem").semantics { liveRegion = LiveRegionMode.Polite })
    }
    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
        TouchPill(
            RoutineSentence.SAVE,
            if (d.canSave) Meka.colors.accent else Meka.colors.surfaceRaised,
            if (d.canSave) Meka.colors.onAccent else Meka.colors.textTertiary,
            modifier = Modifier.weight(1f).testTag("new-routine-save"),
            spoken = if (d.canSave) "${RoutineSentence.SAVE}: ${d.name}, ${d.whenTile}, ${d.steps.size} ${if (d.steps.size == 1) "step" else "steps"}" else "${RoutineSentence.SAVE}. ${d.problem}",
        ) { if (d.canSave) onSave() }
    }
}

@Composable
private fun DraftTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, maxLines = 1)
        Text(os.meka.core.domain.DateGlue.kept(value), style = MekaType.itemMeta, color = Meka.colors.textPrimary)
    }
}

/** One step Obi made: its number, title and place, and its label as a pill (a tap moves it on). */
@Composable
private fun DraftStepRow(st: RoutineDraftStep, modifier: Modifier, onLabel: () -> Unit) {
    val ink by animateColorAsState(
        when (st.mode) {
            RoutineStepMode.SHOW -> Meka.colors.textSecondary
            RoutineStepMode.ASK -> Meka.colors.accent
            RoutineStepMode.DOES -> Meka.colors.textPrimary
        },
        MekaMotion.appear(Meka.reducedMotion), label = "new-routine-label",
    )
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("new-routine-step"),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Meka.colors.surface), contentAlignment = Alignment.Center) {
            Text(st.number, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        Column(Modifier.weight(1f)) {
            Text(LoneWordGlue.kept(st.title), style = MekaType.body, color = Meka.colors.textPrimary)
            Text(st.place, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        if (st.nextCode != null) {
            TouchPill(st.badge, Meka.colors.surface, ink, spoken = st.spoken, onClick = onLabel)
        } else {
            Text(st.badge, style = MekaType.caption, color = ink, modifier = Modifier.semantics { contentDescription = st.spoken })
        }
    }
}
