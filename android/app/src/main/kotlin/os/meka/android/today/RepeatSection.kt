package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.ChecklistItem
import os.meka.core.domain.RepeatChoice
import os.meka.core.domain.Task

/**
 * Repeat row in the task detail: shows the rule and expands into the picker (motion: the list unfolds from the row;
 * reduced motion cross-fades). Choosing closes it again.
 */
@Composable
internal fun RepeatSection(task: Task, actions: TodayActions) {
    val reduced = Meka.reducedMotion
    var open by rememberSaveable(task.id) { mutableStateOf(false) }
    var choices by remember(task.id) { mutableStateOf<List<RepeatChoice>>(emptyList()) }
    LaunchedEffect(task.id, task.recurrenceRule, task.occurrenceDay, task.scheduledAtMs, task.dueAtMs, open) {
        if (open) choices = runCatching { actions.repeatChoices(task.id) }.getOrDefault(emptyList())
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button) { open = !open }.padding(vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Repeat", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(task.repeatLabel ?: "Doesn't repeat", style = MekaType.itemMeta, color = Meka.colors.accent)
    }
    AnimatedVisibility(
        visible = open && choices.isNotEmpty(),
        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                .padding(vertical = MekaSpace.xs),
        ) {
            choices.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { actions.setRepeat(task.id, c.rule); open = false }
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.label, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    if (c.selected) Text("✓", style = MekaType.itemMeta, color = Meka.colors.accent)
                }
            }
        }
    }
}

/**
 * Steps. A repeating task with steps is a routine: every new occurrence brings its steps back unticked.
 * Motion: a tick draws its check like completing a task and pops with a spring ([TickRing]; Off: at once); light haptic.
 */
@Composable
internal fun StepsSection(task: Task, actions: TodayActions) {
    SectionLabel(if (task.isRepeating) "Routine steps" else "Steps", Modifier.padding(top = MekaSpace.l))
    task.checklist.forEach { step -> key(step.id) { StepRow(step, actions) } }
    var text by rememberSaveable(task.id) { mutableStateOf("") }
    Box(Modifier.fillMaxWidth().padding(vertical = MekaSpace.s)) {
        if (text.isEmpty()) Text("Add a step…", style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { actions.addStep(task.id, text); text = "" } }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add a step" },
        )
    }
}

@Composable
private fun StepRow(step: ChecklistItem, actions: TodayActions) {
    val haptics = rememberMekaHaptics()
    Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        TickRing(
            step.checked,
            Modifier.size(22.dp).clip(CircleShape)
                .semantics { contentDescription = (if (step.checked) "Untick " else "Tick ") + step.text }
                .clickable(role = Role.Checkbox) { haptics.light(); actions.setStepDone(step.id, !step.checked) },
        )
        Spacer(Modifier.width(MekaSpace.m))
        Text(
            step.text, style = MekaType.body,
            color = if (step.checked) Meka.colors.textTertiary else Meka.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { actions.removeStep(step.id) }
                .padding(MekaSpace.xs),
        )
    }
}

/**
 * Goal: link this task to one of your goals, so finishing it counts towards the goal's progress. Shown only once a
 * goal exists. The lit chip is the current link; "No goal" unlinks.
 */
@Composable
internal fun GoalSection(task: Task, actions: TodayActions) {
    val goals by actions.goals.collectAsState()
    if (goals.goals.isEmpty() && task.goalId == null) return
    SectionLabel("Goal", Modifier.padding(top = MekaSpace.l))
    val options = listOf<Pair<String?, String>>(null to "No goal") + goals.goals.map { it.id to it.title }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        options.forEach { (id, label) ->
            val lit = id == task.goalId
            val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "goal-chip")
            Text(
                label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                    .semantics { selected = lit }
                    .clickable(role = Role.Button) { if (!lit) actions.setGoal(task.id, id) }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}
