package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.LinkedWatchRow
import os.meka.core.domain.WatchLinkRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Watch (Galaxy Watch, slice 1): type the 8-digit code the watch shows to link it to MEKA, see the
 * linked watches ("Galaxy Watch · Linked today") and unlink one in a tap. The watches live on MEKA's server.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; a shimmer until the server answers; the
 * summary and each line cross-fade; rows stagger in 40 ms apart; Link gives a light haptic once linked, the field clears
 * and the watch's row staggers in; a problem unfolds in the accent (expand spring) with a tick haptic; Unlink gives a
 * tick haptic and the row leaves. Reduced motion: cross-fades.
 */
@Composable
fun WatchPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val view by core.watchLinkView.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { core.refreshWatches() } }
    val v = view
    val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
    val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

    fun link() {
        if (busy || typed.isBlank()) return
        busy = true
        scope.launch {
            try {
                if (core.linkWatch(typed)) { haptics.light(); typed = "" } else haptics.tick()
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("Watch", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WATCH)).appear(rememberAppearance(0)))
        Crossfade(v?.summary ?: "Checking…", animationSpec = MekaMotion.appear(reduced), label = "watch-summary") { s ->
            Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
        }
        Text(WatchLinkRules.HOW, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(1)))
        Row(
            Modifier.appear(rememberAppearance(2)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .minTouch().padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
            ) {
                if (typed.isEmpty()) Text("Code from the watch", style = MekaType.body, color = Meka.colors.textTertiary)
                BasicTextField(
                    value = typed,
                    onValueChange = { raw -> typed = raw.filter { it.isDigit() || it == ' ' }.take(9) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                    cursorBrush = SolidColor(Meka.colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { link() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Code from the watch" },
                )
            }
            Text("Link", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button, enabled = !busy && typed.isNotBlank()) { link() }.padding(MekaSpace.xs))
        }
        val problem = v?.problem
        AnimatedVisibility(problem != null, enter = enter, exit = exit) {
            Text(problem.orEmpty(), style = MekaType.caption, color = Meka.colors.accent)
        }
        if (v == null) {
            SkeletonRows(1, Modifier.padding(top = MekaSpace.xs))
        } else {
            v.rows.forEachIndexed { i, row ->
                androidx.compose.runtime.key(row.id) {
                    WatchLinkRowView(row, enabled = !busy, modifier = Modifier.appear(rememberAppearance(i + 3))) {
                        haptics.tick()
                        busy = true
                        scope.launch {
                            try { core.unlinkWatch(row.id) } finally { busy = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchLinkRowView(row: LinkedWatchRow, enabled: Boolean, modifier: Modifier, onUnlink: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "${row.title}. ${row.line}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Meka.colors.success))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "watch-row-line") { l ->
                Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        Box(
            Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                .clickable(enabled = enabled, role = Role.Button, onClick = onUnlink)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        ) {
            Text("Unlink", style = MekaType.itemMeta, color = Meka.colors.critical)
        }
    }
}
