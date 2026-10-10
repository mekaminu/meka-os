package os.meka.android.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Chip
import os.meka.core.domain.AppUpdateRules

/**
 * Today's update card (self-updating phone app): rises in when the Mac has published a newer build. Install
 * downloads it (the bar fills as chunks arrive; light haptic on tap), then Android's own Install prompt asks once.
 * Later hides this build. Lines cross-fade between states. Reduced motion: the bar jumps, lines cross-fade.
 */
@Composable
internal fun UpdateCard(state: UpdateState, updater: AppUpdater, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val activity = LocalActivity.current
    val reduced = Meka.reducedMotion
    val release = when (state) {
        is UpdateState.Ready -> state.release
        is UpdateState.NeedsPermission -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Confirming -> state.release
        is UpdateState.Failed -> state.release
        UpdateState.None -> return
    }
    val line = when (state) {
        is UpdateState.Ready -> AppUpdateRules.line(release.build)
        is UpdateState.NeedsPermission -> "Allow MEKA to install its own updates (once), then tap Install."
        is UpdateState.Downloading -> AppUpdateRules.progressLine(state.chunksDone, release.chunkCount)
        is UpdateState.Confirming -> "Downloaded and checked. Tap Install in Android's prompt."
        is UpdateState.Failed -> state.reason
        UpdateState.None -> ""
    }
    val install: () -> Unit = { haptics.light(); scope.launch { updater.install(release) } }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised).padding(MekaSpace.l)
            .semantics { contentDescription = "${AppUpdateRules.TITLE}. $line" },
    ) {
        Text(AppUpdateRules.TITLE, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        AnimatedContent(
            targetState = line,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "update-line",
        ) { l ->
            Text(l, style = MekaType.itemMeta, color = if (state is UpdateState.Failed) Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.padding(top = MekaSpace.xxs))
        }
        if (state is UpdateState.Downloading) {
            val target = AppUpdateRules.percent(state.chunksDone, release.chunkCount) / 100f
            val fill = remember { Animatable(0f) }
            LaunchedEffect(target) { if (reduced) fill.snapTo(target) else fill.animateTo(target, MekaMotion.replan(false)) }
            Box(
                Modifier.padding(top = MekaSpace.xs).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill))
                    .background(Meka.colors.surface),
            ) {
                Box(Modifier.fillMaxWidth(fill.value.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
            }
        }
        val chips: List<Pair<String, () -> Unit>> = when (state) {
            is UpdateState.Ready -> listOf("Install" to install, "Later" to { updater.later(release) })
            is UpdateState.NeedsPermission -> listOf("Allow" to { activity?.let { updater.openPermission(it) }; Unit }, "Install" to install)
            is UpdateState.Confirming -> if (updater.hasPrompt) listOf("Show Install" to { updater.showPrompt(activity); Unit }) else emptyList()
            is UpdateState.Failed -> listOf("Try again" to install, "Later" to { updater.later(release) })
            else -> emptyList()
        }
        if (chips.isNotEmpty()) {
            Row(
                Modifier.padding(top = MekaSpace.m).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                chips.forEachIndexed { i, (label, action) -> Chip(label, lit = i == 0, onClick = action) }
            }
        }
    }
}
