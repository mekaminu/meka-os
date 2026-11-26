package os.meka.android.goals

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.GoalAddRow
import os.meka.core.domain.GoalAddRules
import os.meka.core.domain.GoalAddSheet
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.GoalRules
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.LoneWordGlue

/** The tag the screen tests find Goals' add sheets by. */
const val GOAL_ADD_SHEET_TAG = "goal-add-sheet"

/**
 * Goals' "+ New habit" / "+ New goal" card row (walk-through week 5, A17: no open input boxes on a page): the label in
 * the accent with what it adds under it, one tall target opening its sheet.
 */
@Composable
internal fun GoalAddRowView(row: GoalAddRow, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { haptics.light(); onOpen() }
            .semantics { contentDescription = GoalAddRules.rowSpoken(row) }
            .minTouch().padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        Text(row.label, style = MekaType.itemTitle, color = Meka.colors.accent)
        Text(LoneWordGlue.kept(row.line), style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

/**
 * A habit's add sheet: the name (with an example), How often and When as chips, the note, Close · Add. Add (or Done on
 * the keyboard) hands [onAdd] the cleaned name and the choices, and the sheet goes; Add stays dark until a name is typed.
 */
@Composable
internal fun HabitAddSheetView(onAdd: (String, Int, HabitTiming) -> Unit, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var perWeek by rememberSaveable { mutableStateOf(GoalAddRules.DEFAULT_PER_WEEK) }
    var timing by rememberSaveable { mutableStateOf(GoalAddRules.DEFAULT_TIMING) }
    GoalAddSheetFrame(
        GoalAddRules.habitSheet, title, { title = it },
        add = { GoalAddRules.cleaned(title)?.let { onAdd(it, perWeek, timing) } },
        onClose = onClose,
    ) {
        Chips(GoalAddRules.HOW_OFTEN, GoalRules.TARGET_CHOICES.map { it.label to (it.perWeek == perWeek) }) { i -> perWeek = GoalRules.TARGET_CHOICES[i].perWeek }
        Chips(GoalAddRules.WHEN, GoalRules.TIMINGS.map { GoalRules.timingLabel(it) to (it == timing) }) { i -> timing = GoalRules.TIMINGS[i] }
    }
}

/** A goal's add sheet: the name, what done looks like (optional), By when as chips, the note, Close · Add. */
@Composable
internal fun GoalAddSheetView(onAdd: (String, String?, GoalHorizon) -> Unit, onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var horizon by rememberSaveable { mutableStateOf(GoalAddRules.DEFAULT_HORIZON) }
    val add = { GoalAddRules.cleaned(title)?.let { onAdd(it, GoalAddRules.cleanedTarget(target), horizon) } }
    GoalAddSheetFrame(GoalAddRules.goalSheet, title, { title = it }, add = { add() }, onClose = onClose) {
        SheetField(GoalAddRules.DONE_LOOKS_LIKE, GoalAddRules.DONE_LOOKS_LIKE, target, { target = it.take(GoalAddRules.MAX_TITLE) }) { if (GoalAddRules.canAdd(title)) add() }
        Chips(GoalAddRules.HORIZON, GoalRules.HORIZONS.map { GoalRules.horizonLabel(it) to (it == horizon) }) { i -> horizon = GoalRules.HORIZONS[i] }
    }
}

/**
 * The sheet both share. Motion: rises over a fading scrim (reduced motion: a cross-fade); the scrim, Close or Back
 * closes it; Add closes it at once (light haptic).
 */
@Composable
private fun GoalAddSheetFrame(
    sheet: GoalAddSheet, title: String, onTitle: (String) -> Unit,
    add: () -> Unit, onClose: () -> Unit,
    choices: @Composable () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, MekaMotion.appear(reduced)) }
    val close: () -> Unit = { scope.launch { rise.animateTo(0f, MekaMotion.expand(reduced)); onClose() } }
    val canAdd = GoalAddRules.canAdd(title)
    val save: () -> Unit = { if (canAdd) { haptics.light(); add(); onClose() } }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = rise.value }
                    .background(Color.Black.copy(alpha = MotionMath.scrimAlpha(1f)))
                    .pointerInput(Unit) { detectTapGestures { close() } }
                    .semantics { contentDescription = "Close ${sheet.title}" },
            )
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .graphicsLayer {
                        alpha = rise.value
                        if (!reduced) translationY = (1f - rise.value) * 48.dp.toPx()
                    }
                    .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                    .background(Meka.colors.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .heightIn(max = 600.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(MekaSpace.m)
                    .testTag(GOAL_ADD_SHEET_TAG),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
            ) {
                Text(sheet.title, style = MekaType.nowTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                SheetField(sheet.hint, sheet.spokenHint, title, { onTitle(it.take(GoalAddRules.MAX_TITLE)) }) { save() }
                choices()
                Text(LoneWordGlue.kept(sheet.note), style = MekaType.caption, color = Meka.colors.textTertiary)
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Text(GoalAddRules.CLOSE, style = MekaType.metaStrong, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = close)
                            .minTouch().padding(horizontal = MekaSpace.m))
                    Text(
                        sheet.addLabel, style = MekaType.metaStrong,
                        color = if (canAdd) Meka.colors.onAccent else Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                            .clickable(role = Role.Button, enabled = canAdd, onClick = save)
                            .semantics { contentDescription = "${sheet.addLabel}, ${sheet.title}" }
                            .minTouch().clip(RoundedCornerShape(MekaRadius.pill))
                            .background(if (canAdd) Meka.colors.accent else Meka.colors.surfaceRaised)
                            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
                    )
                }
            }
        }
    }
}

/** A one-line field in a raised pill inside a sheet; Done on the keyboard runs [onDone]. */
@Composable
private fun SheetField(hint: String, spoken: String, value: String, onValue: (String) -> Unit, onDone: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        if (value.isEmpty()) Text(hint, style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
        )
    }
}
