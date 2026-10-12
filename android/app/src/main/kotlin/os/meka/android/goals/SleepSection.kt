package os.meka.android.goals

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import os.meka.core.domain.SleepRules
import os.meka.core.domain.WorkoutRules
import os.meka.core.facade.MekaCore

/**
 * Goals → Sleep (Health Connect, slice 3): off by default. Turn on asks Health Connect for "Sleep" (read only); once
 * allowed, last night's sleep is read as Goals opens (and before Plan my day plans) and the line says how long it was;
 * after a short night (under 6 h) Plan my day plans a lighter day. Read on this phone only, kept in memory, never synced.
 * Motion as Workouts: the line cross-fades as the state changes (reduced motion: the same cross-fade).
 */
@Composable
internal fun SleepSection(core: MekaCore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var access by remember { mutableStateOf<WorkoutRules.Access?>(null) }
    var slept by remember { mutableStateOf<Int?>(null) }

    suspend fun reread() {
        access = HealthSleep.access(context)
        slept = HealthSleep.refresh(context, core)
    }

    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { reread() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { reread() } }

    val a = access ?: return
    if (a == WorkoutRules.Access.UNAVAILABLE) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        SectionLabel("Sleep")
        AnimatedContent(
            targetState = SleepRules.settingLine(a, slept),
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "sleep-line",
        ) { line -> Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
            when (a) {
                WorkoutRules.Access.OFF -> Action("Turn on") {
                    haptics.light()
                    HealthSleep.setOn(context, true)
                    scope.launch {
                        if (HealthSleep.permitted(context)) reread()
                        else {
                            access = WorkoutRules.Access.NEEDS_PERMISSION
                            ask.launch(setOf(HealthSleep.READ_SLEEP))
                        }
                    }
                }
                WorkoutRules.Access.NEEDS_PERMISSION -> {
                    Action("Allow") { haptics.light(); ask.launch(setOf(HealthSleep.READ_SLEEP)) }
                    Action("Turn off") { haptics.tick(); HealthSleep.setOn(context, false); scope.launch { reread() } }
                }
                WorkoutRules.Access.ON -> Action("Turn off") {
                    haptics.tick(); HealthSleep.setOn(context, false); scope.launch { reread() }
                }
                WorkoutRules.Access.UNAVAILABLE -> Unit
            }
        }
    }
}
