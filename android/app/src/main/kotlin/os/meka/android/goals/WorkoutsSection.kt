package os.meka.android.goals

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.SectionLabel
import os.meka.core.domain.WorkoutRules
import os.meka.core.facade.MekaCore

/**
 * Goals → Workouts (Health Connect, slice 1): off by default. Turn on asks Health Connect for "Exercise" (read only);
 * once allowed, today's workouts are read as Goals or Today opens and tick the habits they match (the habit's ring
 * draws its check as any tick does). The line under the label cross-fades as the state changes; the last tick's line
 * unfolds under it. Turn off stops reading (MEKA's access stays in Health Connect until removed there). Reduced motion:
 * cross-fades only. Slice 2: "Allow in the background" asks Health Connect for reading while MEKA is closed (every 15
 * minutes), so a workout ticks Gym before "Did you go?", which then waits half an hour after the slot for it.
 */
@Composable
internal fun WorkoutsSection(core: MekaCore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val view by core.goalsView.collectAsState()
    var access by remember { mutableStateOf<WorkoutRules.Access?>(null) }
    var last by remember { mutableStateOf(HealthWorkouts.lastLine(context)) }
    var background by remember { mutableStateOf(false) }
    var offered by remember { mutableStateOf(HealthWorkouts.backgroundOffered(context)) }

    suspend fun reread(force: Boolean) {
        val now = HealthWorkouts.access(context)
        access = now
        background = HealthWorkouts.sync(context, core)
        offered = HealthWorkouts.backgroundOffered(context)
        if (now == WorkoutRules.Access.ON) {
            if (HealthWorkouts.refresh(context, core, force).isNotEmpty()) haptics.light()
            last = HealthWorkouts.lastLine(context)
        }
    }

    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { reread(force = true) }
    }
    val askBackground = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        HealthWorkouts.backgroundAsked(context, HealthWorkouts.READ_IN_BACKGROUND in granted)
        scope.launch { reread(force = true) }
    }
    fun turnOff() {
        haptics.tick(); HealthWorkouts.setOn(context, false); access = WorkoutRules.Access.OFF
        scope.launch { background = HealthWorkouts.sync(context, core) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { reread(force = false) } }

    val a = access ?: return
    if (a == WorkoutRules.Access.UNAVAILABLE) return
    val titles = remember(view) { core.workoutHabits() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        SectionLabel("Workouts")
        AnimatedContent(
            targetState = WorkoutRules.settingLine(a, titles, background),
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "workouts-line",
        ) { line -> Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        AnimatedVisibility(
            a == WorkoutRules.Access.ON && last != null,
            enter = fadeIn(MekaMotion.appear(reduced)) + expandVertically(),
            exit = fadeOut(MekaMotion.appear(reduced)) + shrinkVertically(),
        ) { Text("Last: ${last.orEmpty()} · Undo in Activity", style = MekaType.caption, color = Meka.colors.textTertiary) }
        // Slice 2: without background reading, a quiet line says what it would add; it folds away once allowed.
        val bgLine = WorkoutRules.backgroundLine(a, background, offered)
        AnimatedVisibility(
            bgLine != null,
            enter = fadeIn(MekaMotion.appear(reduced)) + expandVertically(),
            exit = fadeOut(MekaMotion.appear(reduced)) + shrinkVertically(),
        ) { Text(bgLine.orEmpty(), style = MekaType.caption, color = Meka.colors.textTertiary) }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            when (a) {
                WorkoutRules.Access.OFF -> Action("Turn on") {
                    haptics.light()
                    HealthWorkouts.setOn(context, true)
                    scope.launch {
                        if (HealthWorkouts.permitted(context)) reread(force = true)
                        else {
                            access = WorkoutRules.Access.NEEDS_PERMISSION
                            ask.launch(setOf(HealthWorkouts.READ_EXERCISE))
                        }
                    }
                }
                WorkoutRules.Access.NEEDS_PERMISSION -> {
                    Action("Allow") { haptics.light(); ask.launch(setOf(HealthWorkouts.READ_EXERCISE)) }
                    Action("Turn off") { turnOff() }
                }
                WorkoutRules.Access.ON -> {
                    if (!background && offered) Action("Allow in the background") {
                        haptics.light()
                        askBackground.launch(setOf(HealthWorkouts.READ_EXERCISE, HealthWorkouts.READ_IN_BACKGROUND))
                    }
                    Action("Turn off") { turnOff() }
                }
                WorkoutRules.Access.UNAVAILABLE -> Unit
            }
        }
    }
}
