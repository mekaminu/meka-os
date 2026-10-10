package os.meka.android.work

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.VoiceRecordingRules

/**
 * "Keep callers' recordings" (call assistant polish 8c), under Work → Call assistant: 7 days · 30 days · Don't keep.
 * Synced, so the Mac shows the same choice; MEKA's server deletes what is older at once. Motion catalogue "Call
 * assistant": the chips blend with a tick haptic and the line cross-fades.
 */
@Composable
fun RecordingKeepSection(days: Int, modifier: Modifier = Modifier, onChoose: (Int) -> Unit) {
    val haptics = rememberMekaHaptics()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(VoiceRecordingRules.SETTING_TITLE, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            VoiceRecordingRules.KEEP_CHOICES.forEach { d ->
                val label = VoiceRecordingRules.choiceLabel(d)
                SettingChip(label, d == days, spoken = "${VoiceRecordingRules.SETTING_TITLE}: $label") {
                    if (d != days) { haptics.tick(); onChoose(d) }
                }
            }
        }
        Crossfade(VoiceRecordingRules.settingLine(days), animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "keep-line") { line ->
            Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
    }
}
