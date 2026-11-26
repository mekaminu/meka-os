package os.meka.android.work

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
import os.meka.android.designsystem.LightInk
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CommuteChoiceRules
import os.meka.core.domain.CommuteDayRow
import os.meka.core.domain.CommuteRules
import os.meka.core.domain.CommuteSetting

/** Work mode → Commute's rows (walk-through week 5, A17), for the screen tests. */
const val COMMUTE_TO_WORK_TAG = "commute-row:to-work"
const val COMMUTE_HOME_TAG = "commute-row:home"
const val COMMUTE_DAY_TAG = "commute-row:day"
const val COMMUTE_DAY_TO_WORK_TAG = "commute-row:day-to-work"
const val COMMUTE_DAY_HOME_TAG = "commute-row:day-home"

/** Which of the commute's sheets is open. */
private enum class CommuteSheet { TO_WORK, HOME, DAY, DAY_TO_WORK, DAY_HOME }

/**
 * The commute counts everywhere, slice 1: Work → Commute, the door-to-door time each way (home → office, office →
 * home), default about 1 h 20 until set. Synced, so the Mac shows the same. Counted on office days only; the wake
 * alarm uses it.
 *
 * Walk-through week 5, A17: each direction is one card row saying the time in use ("To work" · "1 h 20" ›, "About 1 h 20"
 * while it's Navilife's guess) opening a sheet of the times, the one in use lit, each saying what it does
 * ([CommuteChoiceRules]); picking the lit guess keeps it as his own. Motion: the sheet rises (reduced motion: a
 * cross-fade), a choice gives a tick haptic, the line cross-fades.
 */
@Composable
fun CommuteSection(
    commute: CommuteSetting,
    days: List<CommuteDayRow>,
    modifier: Modifier = Modifier,
    onSetDay: (epochDay: Long, toWork: Int, home: Int) -> Unit,
    onClearDay: (epochDay: Long) -> Unit,
    onSet: (toWork: Int, home: Int) -> Unit,
) {
    val reduced = Meka.reducedMotion
    var sheet by remember { mutableStateOf<CommuteSheet?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Crossfade(CommuteRules.summary(commute), animationSpec = MekaMotion.appear(reduced), label = "commute-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        NotifyChoiceRowView(CommuteChoiceRules.row(commute, true), Modifier.testTag(COMMUTE_TO_WORK_TAG)) { sheet = CommuteSheet.TO_WORK }
        NotifyChoiceRowView(CommuteChoiceRules.row(commute, false), Modifier.testTag(COMMUTE_HOME_TAG)) { sheet = CommuteSheet.HOME }
        Text(CommuteRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
        if (days.isNotEmpty()) OneDay(days, onSetDay, onClearDay)
    }
    when (sheet) {
        CommuteSheet.TO_WORK, CommuteSheet.HOME -> {
            val toWork = sheet == CommuteSheet.TO_WORK
            NotifyChoiceSheetView(CommuteChoiceRules.sheet(commute, toWork), chooseLit = !commute.chosen,
                onChoose = { name -> CommuteChoiceRules.named(commute, toWork, name)?.let { (to, home) -> onSet(to, home) } },
                onClose = { sheet = null })
        }
        else -> {}
    }
}

/**
 * Slice 6, "Just one day": the coming week's office days; the chosen day's two rows set its own times, "Use the usual"
 * puts it back. Walk-through week 5, A17: the day chips are one row ("Just one day" · "Choose a day" ›) opening a sheet
 * of the days (a day of its own marked •), and the day's times are rows opening their own sheets, lit only when the day
 * has its own times. Motion: the day's rows appear/disappear with the standard appear (reduced motion: a cross-fade),
 * the line cross-fades, a choice gives a tick haptic.
 */
@Composable
private fun OneDay(days: List<CommuteDayRow>, onSetDay: (Long, Int, Int) -> Unit, onClearDay: (Long) -> Unit) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheet by remember { mutableStateOf<CommuteSheet?>(null) }
    val row = days.firstOrNull { it.epochDay == picked }
    // Keeps the last day's rows on screen while they fold away.
    var shown by remember { mutableStateOf<CommuteDayRow?>(null) }
    if (row != null) shown = row
    ChipRowLabel(CommuteRules.ONE_DAY_TITLE)
    NotifyChoiceRowView(CommuteChoiceRules.dayRow(days, picked), Modifier.testTag(COMMUTE_DAY_TAG)) { sheet = CommuteSheet.DAY }
    AnimatedVisibility(
        row != null,
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
    ) {
        val r = row ?: shown ?: return@AnimatedVisibility
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Crossfade(r.line, animationSpec = MekaMotion.appear(reduced), label = "commute-day-line") { line ->
                Text(line, style = MekaType.itemMeta, color = if (r.changed) Meka.colors.accent else Meka.colors.textSecondary)
            }
            NotifyChoiceRowView(CommuteChoiceRules.oneDayRow(r, true), Modifier.testTag(COMMUTE_DAY_TO_WORK_TAG)) { sheet = CommuteSheet.DAY_TO_WORK }
            NotifyChoiceRowView(CommuteChoiceRules.oneDayRow(r, false), Modifier.testTag(COMMUTE_DAY_HOME_TAG)) { sheet = CommuteSheet.DAY_HOME }
            if (r.changed) {
                SettingChip("Use the usual", false, spoken = "${r.label}: use the usual commute") {
                    haptics.tick(); onClearDay(r.epochDay)
                }
            }
        }
    }
    Text(CommuteRules.ONE_DAY_NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
    when (sheet) {
        CommuteSheet.DAY -> NotifyChoiceSheetView(CommuteChoiceRules.daySheet(days, picked),
            onChoose = { name -> picked = CommuteChoiceRules.pickedDay(days, name) },
            onClose = { sheet = null })
        CommuteSheet.DAY_TO_WORK, CommuteSheet.DAY_HOME -> row?.let { r ->
            val toWork = sheet == CommuteSheet.DAY_TO_WORK
            NotifyChoiceSheetView(CommuteChoiceRules.oneDaySheet(r, toWork),
                onChoose = { name -> CommuteChoiceRules.oneDayNamed(r, toWork, name)?.let { (to, home) -> onSetDay(r.epochDay, to, home) } },
                onClose = { sheet = null })
        }
        else -> {}
    }
}

/**
 * A chip row's label ("To work", "Home"): 8 dp of air above it, so it reads as the next row's heading rather than
 * hugging the chips before it (quality gate slice 37's cover-screen finding); the chips sit 4 dp under it.
 */
@Composable
private fun ChipRowLabel(text: String) {
    // A31: a small grey word, so Light uses the secondary ink (tertiary is 3.9:1 on Light's page).
    Text(text, style = MekaType.caption, color = LightInk.small(Meka.colors, Meka.theme.isDark), modifier = Modifier.padding(top = MekaSpace.xs))
}
