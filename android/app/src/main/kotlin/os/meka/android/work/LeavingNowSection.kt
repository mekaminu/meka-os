package os.meka.android.work

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.LeavingRules
import os.meka.core.domain.LeavingView
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.WorkAreaLine

/**
 * "Leaving now" (V1), under Work → Leaving now: on or off, who the draft goes to (from the Family and always-notify
 * lists) and, since the commute slice 4, the journey home read from
 * Work mode's commute (no chips of its own). Synced, so the Mac shows the same. Motion catalogue
 * "Leaving now": the chips blend with a tick haptic, the line cross-fades and the choices fold away when off.
 *
 * "When I leave work" (Needs Meka #24): When work ends · When I leave work, then, with the second, [area]'s line
 * (what location this phone still needs, or that the work area is watched) and its one button ([onAreaAction]:
 * Allow, I'm at work, Forget work area), cross-fading and unfolding like the rest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeavingNowSection(
    view: LeavingView, lists: PeopleLists, modifier: Modifier = Modifier,
    area: WorkAreaLine? = null, onByArea: (Boolean) -> Unit = {}, onAreaAction: () -> Unit = {},
    onSet: (Boolean, String, Int) -> Unit,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Crossfade(view.summary, animationSpec = MekaMotion.appear(reduced), label = "leaving-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        SettingChip(if (view.on) "On" else "Off", view.on, spoken = "${LeavingRules.TITLE}, ${if (view.on) "on" else "off"}") {
            haptics.tick(); onSet(!view.on, view.to, view.journeyMin)
        }
        AnimatedVisibility(
            view.on,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text("To", style = MekaType.caption, color = Meka.colors.textTertiary)
                val names = (lists.family + lists.alwaysNotify).toList().let { n ->
                    if (view.to.isBlank() || n.any { LeavingRules.samePerson(view.to, it) }) n else listOf(view.to) + n
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    names.forEach { name ->
                        val chosen = LeavingRules.samePerson(view.to, name) && (name == view.to || names.none { it == view.to })
                        SettingChip(name, chosen, spoken = "Send to $name") {
                            if (!chosen) { haptics.tick(); onSet(true, name, view.journeyMin) }
                        }
                    }
                }
                // The commute counts everywhere, slice 4: the journey home is the commute home, set under Commute above.
                Crossfade(LeavingRules.journeyNote(view.journeyMin), animationSpec = MekaMotion.appear(reduced), label = "leaving-journey") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                if (area != null) {
                    Text("When", style = MekaType.caption, color = Meka.colors.textTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        SettingChip("When work ends", !view.byArea, spoken = "Draft when work ends") {
                            if (view.byArea) { haptics.tick(); onByArea(false) }
                        }
                        SettingChip("When I leave work", view.byArea, spoken = "Draft when I leave the work area") {
                            if (!view.byArea) { haptics.tick(); onByArea(true) }
                        }
                    }
                    AnimatedVisibility(
                        view.byArea,
                        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Crossfade(area.line, animationSpec = MekaMotion.appear(reduced), label = "leaving-area", modifier = Modifier.weight(1f)) { l ->
                                    Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
                                }
                                val action = area.action
                                if (action != null) {
                                    Box(
                                        // Works by hand (quality gate): the slim pill drawn inside its 48 dp touch.
                                        Modifier.padding(start = MekaSpace.m).clip(RoundedCornerShape(MekaRadius.pill))
                                            .clickable(role = Role.Button) { haptics.tick(); onAreaAction() }
                                            .minTouch()
                                            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                                    ) { Text(action, style = MekaType.itemMeta, color = Meka.colors.accent) }
                                }
                            }
                            Text(LeavingRules.AREA_PRIVACY, style = MekaType.caption, color = Meka.colors.textTertiary)
                        }
                    }
                }
            }
        }
        Text(LeavingRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}
