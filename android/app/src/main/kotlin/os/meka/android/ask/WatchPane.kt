package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import os.meka.android.designsystem.lightCardEdge
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import os.meka.android.goals.GoalAddRowView
import os.meka.android.goals.GoalAddSheetFrame
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.LinkedWatchRow
import os.meka.core.domain.WatchLinkRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Watch (Galaxy Watch, slice 1): "+ Link a watch" opens a sheet to type the 8-digit code the watch shows
 * (walk-through week 5, A17: no open box on the page), the linked watches ("Galaxy Watch · Linked today") and Unlink
 * in a tap. The watches live on MEKA's server.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; a shimmer until the server answers; the
 * summary and each line cross-fade; rows stagger in 40 ms apart; the sheet rises over a fading scrim and stays while the
 * server checks the code: linked, it gives a light haptic, closes and the watch's row staggers in; refused, the reason
 * unfolds in the sheet in the critical colour (expand spring) with a tick haptic; Unlink gives a tick haptic and the row
 * leaves. Reduced motion: cross-fades.
 */
@Composable
fun WatchPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val view by core.watchLinkView.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var linking by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { core.refreshWatches() } }
    val v = view
    val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
    val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

    fun link(code: String) {
        if (!WatchLinkRules.canLink(code, busy)) return
        busy = true
        scope.launch {
            try {
                if (core.linkWatch(code)) { haptics.light(); linking = false } else haptics.tick()
            } finally { busy = false }
        }
    }

    if (linking) WatchLinkSheetView(v?.problem, busy, onLink = ::link, onClose = { linking = false })

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text("Watch", style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WATCH)).appear(rememberAppearance(0)))
        Crossfade(v?.summary ?: "Checking…", animationSpec = MekaMotion.appear(reduced), label = "watch-summary") { s ->
            Text(os.meka.core.domain.LoneWordGlue.kept(s), style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
        }
        Text(os.meka.core.domain.LoneWordGlue.kept(v?.how ?: WatchLinkRules.HOW), style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(1)))
        // Walk-through week 5, A17: "+ Link a watch" is a card row opening the code's sheet, not an open box on the page.
        GoalAddRowView(WatchLinkRules.linkRow(v?.rows?.size ?: 0), Modifier.testTag(WATCH_LINK_ROW_TAG).appear(rememberAppearance(2))) { linking = true }
        // A wrong code is said in the sheet, under the code it's about; on the page only the other problems (A11).
        val problem = WatchLinkRules.problemShown(v?.problem, "")
        AnimatedVisibility(problem != null, enter = enter, exit = exit) {
            // A refused code is an error, in the critical colour, not the link gold (walk-through A22).
            Text(os.meka.core.domain.LoneWordGlue.kept(problem.orEmpty()), style = MekaType.caption, color = Meka.colors.critical)
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
internal fun WatchLinkRowView(row: LinkedWatchRow, enabled: Boolean, modifier: Modifier, onUnlink: () -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        // Walk-through 2026-11-15, A21 part 9: Light's 1 dp edge on the white row and the code field (none in Dark).
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).lightCardEdge(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface).testTag("watch-row")
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics(mergeDescendants = true) { contentDescription = "${row.title}. ${row.line}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Meka.colors.success))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Crossfade(row.line, animationSpec = MekaMotion.appear(reduced), label = "watch-row-line") { l ->
                Text(os.meka.core.domain.LoneWordGlue.kept(l), style = MekaType.caption, color = Meka.colors.textSecondary)
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

/** The tag the screen tests find Watch's "+ Link a watch" row by. */
const val WATCH_LINK_ROW_TAG = "watch-link-row"

/**
 * Watch's code sheet (walk-through week 5, A17): the code with an example (digits only), where the watch shows it,
 * Close · Link. Link stays dark until something is typed and while the server checks; the sheet stays open until the
 * watch is linked, a wrong code said under it in the critical colour (A22) while the code it's about is still there.
 */
@Composable
internal fun WatchLinkSheetView(problem: String?, busy: Boolean, onLink: (String) -> Unit, onClose: () -> Unit) {
    val reduced = Meka.reducedMotion
    var typed by rememberSaveable { mutableStateOf("") }
    GoalAddSheetFrame(
        WatchLinkRules.linkSheet, typed, { raw -> typed = WatchLinkRules.typedCode(raw) },
        add = { onLink(typed) }, onClose = onClose,
        canAdd = WatchLinkRules.canLink(typed, busy),
        maxLength = 32,
        closeOnAdd = false,
        keyboard = KeyboardType.NumberPassword,
    ) {
        val shown = WatchLinkRules.problemShown(problem, typed)
        val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
        val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))
        AnimatedVisibility(shown != null, enter = enter, exit = exit) {
            Text(os.meka.core.domain.LoneWordGlue.kept(shown.orEmpty()), style = MekaType.caption, color = Meka.colors.critical)
        }
    }
}
