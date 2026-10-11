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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.LeavingRules
import os.meka.core.domain.LeavingView
import os.meka.core.domain.PeopleLists

/**
 * "Leaving now" (V1), under Work → Leaving now: on or off, who the draft goes to (from the Family and always-notify
 * lists; Jeanette by default) and the usual journey home. Synced, so the Mac shows the same. Motion catalogue
 * "Leaving now": the chips blend with a tick haptic, the line cross-fades and the choices fold away when off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LeavingNowSection(view: LeavingView, lists: PeopleLists, modifier: Modifier = Modifier, onSet: (Boolean, String, Int) -> Unit) {
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
                    if (n.any { LeavingRules.samePerson(view.to, it) }) n else listOf(view.to) + n
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    names.forEach { name ->
                        val chosen = LeavingRules.samePerson(view.to, name) && (name == view.to || names.none { it == view.to })
                        SettingChip(name, chosen, spoken = "Send to $name") {
                            if (!chosen) { haptics.tick(); onSet(true, name, view.journeyMin) }
                        }
                    }
                }
                Text("Journey home", style = MekaType.caption, color = Meka.colors.textTertiary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    LeavingRules.JOURNEY_CHOICES.forEach { m ->
                        val label = LeavingRules.journeyLabel(m)
                        SettingChip(label, m == view.journeyMin, spoken = "Journey home: $label") {
                            if (m != view.journeyMin) { haptics.tick(); onSet(true, view.to, m) }
                        }
                    }
                }
            }
        }
        Text(LeavingRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}
