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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CommuteDayRow
import os.meka.core.domain.CommuteRules
import os.meka.core.domain.CommuteSetting

/**
 * The commute counts everywhere, slice 1: Work → Commute, the door-to-door time each way (home → office, office →
 * home), default about 1 h 20 until set. Synced, so the Mac shows the same. Counted on office days only; the wake
 * alarm uses it. Motion catalogue "Leaving now"/settings chips: a chip blends with a tick haptic, the line cross-fades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommuteSection(
    commute: CommuteSetting,
    days: List<CommuteDayRow>,
    modifier: Modifier = Modifier,
    onSetDay: (epochDay: Long, toWork: Int, home: Int) -> Unit,
    onClearDay: (epochDay: Long) -> Unit,
    onSet: (toWork: Int, home: Int) -> Unit,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Crossfade(CommuteRules.summary(commute), animationSpec = MekaMotion.appear(reduced), label = "commute-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        ChipRowLabel("To work")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CommuteRules.choices(commute.toWorkMin).forEach { m ->
                val label = CommuteRules.label(m)
                val chosen = commute.chosen && m == commute.toWorkMin
                SettingChip(label, chosen, spoken = "Commute to work: $label") {
                    if (!chosen) { haptics.tick(); onSet(m, commute.homeMin) }
                }
            }
        }
        ChipRowLabel("Home")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            CommuteRules.choices(commute.homeMin).forEach { m ->
                val label = CommuteRules.label(m)
                val chosen = commute.chosen && m == commute.homeMin
                SettingChip(label, chosen, spoken = "Commute home: $label") {
                    if (!chosen) { haptics.tick(); onSet(commute.toWorkMin, m) }
                }
            }
        }
        Text(CommuteRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xxs))
        if (days.isNotEmpty()) OneDay(days, onSetDay, onClearDay)
    }
}

/**
 * Slice 6, "Just one day": the coming week's office days as chips (a one-off day lit with a dot); the chosen day's two
 * chip rows set its own times, "Use the usual" puts it back. Motion: the day's chips appear/disappear with the
 * standard appear (reduced motion: instant), the line cross-fades, a chip blends with a tick haptic.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OneDay(days: List<CommuteDayRow>, onSetDay: (Long, Int, Int) -> Unit, onClearDay: (Long) -> Unit) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    val row = days.firstOrNull { it.epochDay == picked }
    // Keeps the last day's chips on screen while they fold away.
    var shown by remember { mutableStateOf<CommuteDayRow?>(null) }
    if (row != null) shown = row
    ChipRowLabel(CommuteRules.ONE_DAY_TITLE)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        days.forEach { d ->
            val label = if (d.changed) "${d.label} •" else d.label
            SettingChip(label, d.epochDay == picked, spoken = d.line) {
                haptics.tick()
                picked = if (picked == d.epochDay) null else d.epochDay
            }
        }
    }
    AnimatedVisibility(
        row != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
    ) {
        val r = row ?: shown ?: return@AnimatedVisibility
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "commute-day-line") { line ->
                Text(line, style = MekaType.itemMeta, color = if (r.changed) Meka.colors.accent else Meka.colors.textSecondary)
            }
            ChipRowLabel("To work")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                CommuteRules.choices(r.toWorkMin).forEach { m ->
                    val label = CommuteRules.label(m)
                    val on = r.changed && m == r.toWorkMin
                    SettingChip(label, on, spoken = "${r.label}, commute to work: $label") {
                        if (!on) { haptics.tick(); onSetDay(r.epochDay, m, r.homeMin) }
                    }
                }
            }
            ChipRowLabel("Home")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                CommuteRules.choices(r.homeMin).forEach { m ->
                    val label = CommuteRules.label(m)
                    val on = r.changed && m == r.homeMin
                    SettingChip(label, on, spoken = "${r.label}, commute home: $label") {
                        if (!on) { haptics.tick(); onSetDay(r.epochDay, r.toWorkMin, m) }
                    }
                }
            }
            if (r.changed) {
                SettingChip("Use the usual", false, spoken = "${r.label}: use the usual commute") {
                    haptics.tick(); onClearDay(r.epochDay)
                }
            }
        }
    }
    Text(CommuteRules.ONE_DAY_NOTE, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xxs))
}

/**
 * A chip row's label ("To work", "Home"): 8 dp of air above it, so it reads as the next row's heading rather than
 * hugging the chips before it (quality gate slice 37's cover-screen finding); the chips sit 4 dp under it.
 */
@Composable
private fun ChipRowLabel(text: String) {
    Text(text, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
}
