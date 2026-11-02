package os.meka.android.ask

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
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
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.HelpPlace
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.WorkingOnCard
import os.meka.core.domain.WorkingOnRules
import os.meka.core.domain.WorkingOnSwitch
import os.meka.core.domain.WorkingOnView
import os.meka.core.facade.MekaCore

/**
 * Ask → Daily → Obi is working on (Ada plans with you, part 3 slice 1): a simple list, what needs him first, each a
 * card with WHAT I DID and, when there is one, WHAT I NEED FROM YOU (in the accent) and the button to the screen that
 * holds it; then STANDING ERRANDS with Turn off / Turn on for each (synced). The words are the core's
 * [WorkingOnRules.view], the same as the Mac's sheet. No status lights, nothing sent from here.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; cards and switches stagger in 40 ms apart;
 * a switch presses in with a tick haptic and the list resizes on the expand spring as a card comes or goes. Reduced
 * motion: cross-fades and no resize spring.
 */
@Composable
fun WorkingOnPane(core: MekaCore, onClose: () -> Unit, onOpen: (HelpPlace) -> Unit) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<WorkingOnView?>(null) }
    LaunchedEffect(Unit) { view = runCatching { core.workingOnView() }.getOrNull() }
    val reduced = Meka.reducedMotion
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter).testTag("working-on-pane")
            .let { if (reduced) it else it.animateContentSize(MekaMotion.expand(false)) },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        Text(MoreItem.WORKING_ON.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.WORKING_ON)).appear(rememberAppearance(0)))
        val v = view ?: return@Column
        Text(LoneWordGlue.kept(v.summary), style = MekaType.body,
            color = if (v.needCount > 0) Meka.colors.accent else Meka.colors.textSecondary,
            modifier = Modifier.appear(rememberAppearance(0)).testTag("working-on-summary"))
        Text(LoneWordGlue.kept(WorkingOnRules.INTRO), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.appear(rememberAppearance(0)))
        v.cards.forEachIndexed { i, c ->
            ErrandCard(c, Modifier.appear(rememberAppearance(i + 1))) { onOpen(c.errand.place) }
        }
        Text(WorkingOnRules.SWITCHES.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).semantics { heading() }.appear(rememberAppearance(v.cards.size + 1)))
        v.switches.forEachIndexed { i, s ->
            ErrandSwitch(s, Modifier.appear(rememberAppearance(v.cards.size + 2 + i))) {
                haptics.tick()
                scope.launch { view = runCatching { core.setStandingErrand(s.errand.code, !s.on) }.getOrNull() ?: view }
            }
        }
        Text(LoneWordGlue.kept(WorkingOnRules.NEXT), style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(v.cards.size + 2 + v.switches.size)))
    }
}

@Composable
private fun ErrandCard(c: WorkingOnCard, modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surfaceRaised).padding(MekaSpace.m)
            .semantics(mergeDescendants = false) { contentDescription = c.spoken }
            .testTag("working-on-card"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(c.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
        Text(WorkingOnRules.DID.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text(LoneWordGlue.kept(c.did), style = MekaType.body, color = Meka.colors.textSecondary)
        c.need?.let { need ->
            Text(WorkingOnRules.NEED.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.xs))
            Text(LoneWordGlue.kept(need), style = MekaType.body, color = Meka.colors.accent)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TouchPill(c.errand.openLabel, Meka.colors.surface, Meka.colors.accent, spoken = "${c.errand.openLabel}: ${c.title}", onClick = onOpen)
        }
    }
}

@Composable
private fun ErrandSwitch(s: WorkingOnSwitch, modifier: Modifier, onToggle: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(s.errand.title, style = MekaType.body, color = if (s.on) Meka.colors.textPrimary else Meka.colors.textTertiary)
        Text(LoneWordGlue.kept(s.line), style = MekaType.caption, color = Meka.colors.textSecondary)
        TouchPill(s.action, Meka.colors.surfaceRaised, Meka.colors.accent, spoken = "${s.action}: ${s.errand.title}", onClick = onToggle)
    }
}
