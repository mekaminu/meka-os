package os.meka.android.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import os.meka.android.designsystem.PillSize
import os.meka.android.designsystem.minTouch
import os.meka.core.facade.AppRelease
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AppUpdateRules

/**
 * Today's update banner (self-updating phone app; Calmer Today, slice 2: a slim one-line banner instead of the 120 dp
 * card, as the approved mockup draws it): "Update ready · build 2221" with Later and Install at its end, rising in when
 * the Mac has published a newer build. Install downloads it (a thin bar under the line fills as chunks arrive; light
 * haptic on tap), then Android's own Install prompt asks once. Later hides this build on Today: for 5 s the line says
 * "Hidden till the next build · Health → Updates has it" with Undo (the line cross-fades), then the banner folds away;
 * Health → Updates still offers it. Reduced motion: the bar jumps, lines cross-fade.
 */
@Composable
internal fun UpdateCard(state: UpdateState, updater: AppUpdater, modifier: Modifier = Modifier) {
    val haptics = rememberMekaHaptics()
    val activity = LocalActivity.current
    UpdateBanner(
        state, modifier,
        install = { r -> haptics.light(); updater.start(r) },
        later = { r -> updater.later(r) },
        allow = { activity?.let { updater.openPermission(it) } },
        showPrompt = if (updater.hasPrompt) ({ updater.showPrompt(activity) }) else null,
        undo = { r -> haptics.light(); updater.undoLater(r) },
    )
}

/** The banner itself, with its taps passed in (so a test can draw it without an [AppUpdater]). */
@Composable
internal fun UpdateBanner(
    state: UpdateState,
    modifier: Modifier = Modifier,
    install: (AppRelease) -> Unit,
    later: (AppRelease) -> Unit,
    allow: () -> Unit,
    showPrompt: (() -> Unit)?,
    undo: (AppRelease) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val release = when (state) {
        is UpdateState.Ready -> state.release
        is UpdateState.NeedsPermission -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Confirming -> state.release
        is UpdateState.Failed -> state.release
        is UpdateState.Hidden -> state.release
        UpdateState.None -> return
    }
    val line = when (state) {
        is UpdateState.Ready -> AppUpdateRules.bannerLine(release.build)
        is UpdateState.NeedsPermission -> "Allow Navilife to install its updates (once), then Install."
        is UpdateState.Downloading -> AppUpdateRules.progressLine(state.chunksDone, release.chunkCount)
        is UpdateState.Confirming -> "Downloaded and checked. Tap Install in Android's prompt."
        is UpdateState.Failed -> state.reason
        is UpdateState.Hidden -> AppUpdateRules.LATER_NOTE
        UpdateState.None -> ""
    }
    // The quiet move first, the one to take last and lit (the mockup: Later · Install).
    val pills: List<Pair<String, () -> Unit>> = when (state) {
        is UpdateState.Ready -> listOf("Later" to { later(release) }, "Install" to { install(release) })
        is UpdateState.NeedsPermission -> listOf("Install" to { install(release) }, "Allow" to allow)
        is UpdateState.Confirming -> showPrompt?.let { listOf("Show Install" to it) } ?: emptyList()
        is UpdateState.Failed -> listOf("Later" to { later(release) }, "Try again" to { install(release) })
        is UpdateState.Hidden -> listOf("Undo" to { undo(release) })
        else -> emptyList()
    }
    Column(
        modifier.fillMaxWidth().testTag(UPDATE_BANNER_TAG).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(start = MekaSpace.m, end = MekaSpace.xs)
            .semantics { contentDescription = "${AppUpdateRules.TITLE}. $line" + ((state as? UpdateState.Failed)?.detail?.let { ". $it" } ?: "") },
    ) {
        Row(Modifier.fillMaxWidth().minTouch(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "update-line",
                modifier = Modifier.weight(1f),
            ) { l ->
                Text(l, style = MekaType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (state is UpdateState.Failed) Meka.colors.accent else Meka.colors.textPrimary)
            }
            pills.forEachIndexed { i, (label, action) -> BannerPill(label, lit = i == pills.lastIndex && state !is UpdateState.Hidden, onClick = action) }
        }
        // Android's own words under the line (build plan: phone updates sometimes fail to install), fading in with it.
        AnimatedVisibility(
            visible = state is UpdateState.Failed && state.detail != null,
            enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced)),
        ) {
            Text((state as? UpdateState.Failed)?.detail.orEmpty(), style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(end = MekaSpace.xs, bottom = MekaSpace.xs))
        }
        if (state is UpdateState.Downloading) {
            val target = AppUpdateRules.percent(state.chunksDone, release.chunkCount) / 100f
            val fill = remember { Animatable(0f) }
            LaunchedEffect(target) { if (reduced) fill.snapTo(target) else fill.animateTo(target, MekaMotion.replan(false)) }
            Box(
                Modifier.padding(end = MekaSpace.xs, bottom = MekaSpace.xs).fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface),
            ) {
                Box(Modifier.fillMaxWidth(fill.value.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
            }
        }
    }
}

/** A compact 36 dp pill in its 48 dp target ([PillSize]); lit = the accent fill, else quiet words. */
@Composable
private fun BannerPill(label: String, lit: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(role = Role.Button, onClick = onClick).minTouch().padding(start = MekaSpace.xxs), contentAlignment = Alignment.Center) {
        Text(
            label, style = if (lit) MekaType.captionStrong else MekaType.caption, maxLines = 1,
            color = if (lit) Meka.colors.onAccent else Meka.colors.textSecondary,
            modifier = Modifier.height(PillSize.DRAWN).clip(RoundedCornerShape(MekaRadius.pill))
                .background(if (lit) Meka.colors.accent else Color.Transparent)
                .padding(horizontal = MekaSpace.m).wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/** The banner, for the quality gate's one-line check (Calmer Today, slice 2). */
internal const val UPDATE_BANNER_TAG = "update-banner"
