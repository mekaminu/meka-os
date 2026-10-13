package os.meka.android.work

import androidx.compose.animation.Crossfade
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
import os.meka.core.domain.CommuteRules
import os.meka.core.domain.CommuteSetting

/**
 * The commute counts everywhere, slice 1: Work → Commute, the door-to-door time each way (home → office, office →
 * home), default about 1 h 20 until set. Synced, so the Mac shows the same. Counted on office days only; the wake
 * alarm uses it. Motion catalogue "Leaving now"/settings chips: a chip blends with a tick haptic, the line cross-fades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommuteSection(commute: CommuteSetting, modifier: Modifier = Modifier, onSet: (toWork: Int, home: Int) -> Unit) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Crossfade(CommuteRules.summary(commute), animationSpec = MekaMotion.appear(reduced), label = "commute-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Text("To work", style = MekaType.caption, color = Meka.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CommuteRules.choices(commute.toWorkMin).forEach { m ->
                val label = CommuteRules.label(m)
                val chosen = commute.chosen && m == commute.toWorkMin
                SettingChip(label, chosen, spoken = "Commute to work: $label") {
                    if (!chosen) { haptics.tick(); onSet(m, commute.homeMin) }
                }
            }
        }
        Text("Home", style = MekaType.caption, color = Meka.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CommuteRules.choices(commute.homeMin).forEach { m ->
                val label = CommuteRules.label(m)
                val chosen = commute.chosen && m == commute.homeMin
                SettingChip(label, chosen, spoken = "Commute home: $label") {
                    if (!chosen) { haptics.tick(); onSet(commute.toWorkMin, m) }
                }
            }
        }
        Text(CommuteRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}
