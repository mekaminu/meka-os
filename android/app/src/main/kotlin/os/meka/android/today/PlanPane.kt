package os.meka.android.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.calendar.EventUndo
import os.meka.android.designsystem.CountUpText
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.SharedMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.core.domain.DayPlanner
import os.meka.core.domain.PlanCalendarSetting
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun t(ms: Long) = hm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** One line of the plan's timeline: a fixed event, a suggested block for a task, room made for a habit, or a meal kept free. */
private data class PlanRow(val startMs: Long, val time: String, val title: String, val taskId: String?, val habit: Boolean = false)

/**
 * "Plan my day": a suggested timeline of tasks fitted around calendar events and fixtures. Nothing changes until
 * the owner taps Apply (autonomy level 1: suggest). On Apply, [onApplying] names the tasks being placed; their block
 * titles then fly into their rows in Today as the pane closes (see [landing]).
 *
 * Calendar editing slice 2e: where an account allows editing, "Also add the blocks to Google" (off by default, synced)
 * makes Apply add each task's block to that calendar too; [undo] then offers "Adding 3 blocks to Google · Undo".
 */
@Composable
fun PlanPane(
    core: MekaCore, landing: Set<String> = emptySet(), onApplying: (Set<String>) -> Unit = {}, undo: EventUndo? = null,
    onClose: () -> Unit,
) {
    var plan by remember { mutableStateOf<DayPlanner.Plan?>(null) }
    var toCalendar by remember { mutableStateOf(PlanCalendarSetting.OFF) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    LaunchedEffect(Unit) { plan = core.planDay() }
    LaunchedEffect(Unit) {
        runCatching { core.refreshCalendarAccounts() }
        toCalendar = core.planCalendarSetting()
    }

    Column(Modifier.fillMaxSize().padding(MekaSpace.gutter).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.padding(vertical = MekaSpace.s))
        Text("Your day", style = MekaType.greeting, color = Meka.colors.textPrimary)
        val p = plan
        if (p == null) {
            SkeletonRows(count = 4)
            return@Column
        }
        if (p.isBlank) {
            Text("Nothing to plan: every open task is already scheduled.", style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            return@Column
        }
        Text("A suggestion. Nothing changes until you apply it.", style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        Spacer(Modifier.height(MekaSpace.s))

        // One timeline: fixed events and suggested tasks, in time order.
        val rows = p.busy.map { PlanRow(it.startAtMs, "${t(it.startAtMs)}–${t(it.endAtMs)}", it.title, null) } +
            p.placements.map { PlanRow(it.startMs, "${t(it.startMs)}–${t(it.endMs)}", it.task.title, it.task.id) } +
            p.habits.map { PlanRow(it.startMs, "${t(it.startMs)}–${t(it.endMs)}", "↻ " + it.title + if (it.behind) " · behind" else "", null, habit = true) } +
            p.meals.map { PlanRow(it.startMs, "${t(it.startMs)}–${t(it.endMs)}", "◐ " + it.title, null, habit = true) }
        // Timeline blocks cascade in, 40 ms apart.
        rows.sortedBy { it.startMs }.forEachIndexed { i, row ->
            val suggested = row.taskId != null || row.habit
            Row(
                Modifier.appear(rememberAppearance(i)).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .background(if (suggested) Meka.colors.surfaceRaised else Meka.colors.background)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
            ) {
                Text(row.time, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.width(104.dp))
                // Once applied, this title is the one that travels into Today.
                val travels = row.taskId != null && row.taskId in landing
                Text(
                    row.title, style = MekaType.itemTitle, color = if (suggested) Meka.colors.textPrimary else Meka.colors.textTertiary,
                    modifier = if (travels) Modifier.sharedTitleInPane(SharedMotion.taskKey(row.taskId!!)) else Modifier,
                )
            }
        }
        if (p.unplaced.isNotEmpty()) {
            Spacer(Modifier.height(MekaSpace.s))
            Text("Won't fit today: " + p.unplaced.joinToString(", ") { it.title }, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        if (p.habits.isNotEmpty()) {
            Text("↻ Room for habits that are due. Tick them in Goals when they're done.", style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        if (p.meals.isNotEmpty()) {
            Text("◐ Kept free for your fast's meals. Nothing is planned over them.", style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        if (p.habitsUnplaced.isNotEmpty()) {
            Text("No room today for: " + p.habitsUnplaced.joinToString(", ") { it.title }, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        CountUpText(p.freeMinutesLeft, style = MekaType.caption, color = Meka.colors.textTertiary) { "${it / 60} h ${it % 60} min still free." }
        Spacer(Modifier.height(MekaSpace.m))
        if (!p.isEmpty && toCalendar.available) {
            PlanCalendarSwitch(toCalendar) { on ->
                haptics.tick()
                toCalendar = toCalendar.copy(on = on)
                scope.launch { toCalendar = core.setPlanToCalendar(on) }
            }
        }
        if (!p.isEmpty) {
            Text(
                "Apply plan", style = MekaType.itemTitle, color = Meka.colors.onAccent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(role = Role.Button) {
                        haptics.light()
                        onApplying(p.placements.map { it.task.id }.toSet())
                        scope.launch {
                            val applied = core.applyPlan(p)
                            onClose()
                            val line = applied.line
                            if (line != null && undo != null) undo.show(line) { core.undoPlanBlocks(applied.editIds) }
                        }
                    }
                    .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
            )
        }
    }
}

/**
 * "Also add the blocks to Google": an On/Off pill whose colour blends (like Ring as an alarm), with the line under it
 * cross-fading between what Apply will do. The screen reader hears it as a switch.
 */
@Composable
private fun PlanCalendarSwitch(s: PlanCalendarSetting, onChange: (Boolean) -> Unit) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (s.on) Meka.colors.accent else Meka.colors.background, MekaMotion.themeBlend(reduced), label = "plan-calendar")
    Column(Modifier.fillMaxWidth().padding(bottom = MekaSpace.s)) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = s.on, role = Role.Switch) { onChange(it) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.label, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                if (s.on) "On" else "Off", style = MekaType.itemMeta, color = if (s.on) Meka.colors.onAccent else Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                    .border(1.dp, Meka.colors.accent.copy(alpha = 0.6f), RoundedCornerShape(MekaRadius.pill))
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
        AnimatedContent(
            targetState = s.line,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "plan-calendar-line",
        ) { line ->
            Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
        }
    }
}
