package os.meka.android.work

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
import os.meka.core.domain.LeavingChoiceRules
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

/** Work mode → Leaving now's rows (walk-through week 5, A17), for the screen tests. */
const val LEAVING_SWITCH_TAG = "leaving-row:switch"
const val LEAVING_TO_TAG = "leaving-row:to"
const val LEAVING_WHEN_TAG = "leaving-row:when"

/** Which of Leaving now's sheets is open. */
private enum class LeavingSheet { SWITCH, TO, WHEN }

/**
 * "Leaving now" (V1), under Work → Leaving now: on or off, who the draft goes to (from the Family and always-notify
 * lists) and, since the commute slice 4, the journey home read from
 * Work mode's commute (no chips of its own). Synced, so the Mac shows the same. Motion catalogue
 * "Leaving now": the chips blend with a tick haptic, the line cross-fades and the choices fold away when off.
 *
 * "When I leave work" (Needs Meka #24): When work ends · When I leave work, then, with the second, [area]'s line
 * (what location this phone still needs, or that the work area is watched) and its one button ([onAreaAction]:
 * Allow, I'm at work, Forget work area), cross-fading and unfolding like the rest.
 *
 * Walk-through week 5, A17: the On/Off pill, the To chips and the When chips are each one card row saying what's chosen
 * ("Leaving now" · "On" ›, "To" · "Sam" ›, "When" · "When work ends" ›) opening a sheet of the choices, the one in use
 * lit, each saying what it does ([LeavingChoiceRules]). Motion: the sheet rises (reduced motion: a cross-fade), a choice
 * gives a tick haptic, the line cross-fades and the rows fold away when off.
 */
@Composable
fun LeavingNowSection(
    view: LeavingView, lists: PeopleLists, modifier: Modifier = Modifier,
    area: WorkAreaLine? = null, onByArea: (Boolean) -> Unit = {}, onAreaAction: () -> Unit = {},
    onSet: (Boolean, String, Int) -> Unit,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var sheet by remember { mutableStateOf<LeavingSheet?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Crossfade(view.summary, animationSpec = MekaMotion.appear(reduced), label = "leaving-line") { line ->
            Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        NotifyChoiceRowView(LeavingChoiceRules.switchRow(view), Modifier.testTag(LEAVING_SWITCH_TAG)) { sheet = LeavingSheet.SWITCH }
        AnimatedVisibility(
            view.on,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                val canChoose = LeavingChoiceRules.names(view, lists).isNotEmpty()
                NotifyChoiceRowView(LeavingChoiceRules.toRow(view, lists), Modifier.testTag(LEAVING_TO_TAG), enabled = canChoose) {
                    sheet = LeavingSheet.TO
                }
                // The commute counts everywhere, slice 4: the journey home is the commute home, set under Commute above.
                Crossfade(LeavingRules.journeyNote(view.journeyMin), animationSpec = MekaMotion.appear(reduced), label = "leaving-journey") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                if (area != null) {
                    NotifyChoiceRowView(LeavingChoiceRules.whenRow(view), Modifier.testTag(LEAVING_WHEN_TAG)) { sheet = LeavingSheet.WHEN }
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
    when (sheet) {
        LeavingSheet.SWITCH -> NotifyChoiceSheetView(LeavingChoiceRules.switchSheet(view),
            onChoose = { name -> LeavingChoiceRules.switchNamed(view, name)?.let { on -> onSet(on, view.to, view.journeyMin) } },
            onClose = { sheet = null })
        LeavingSheet.TO -> NotifyChoiceSheetView(LeavingChoiceRules.toSheet(view, lists),
            onChoose = { name -> LeavingChoiceRules.toNamed(view, lists, name)?.let { to -> onSet(true, to, view.journeyMin) } },
            onClose = { sheet = null })
        LeavingSheet.WHEN -> NotifyChoiceSheetView(LeavingChoiceRules.whenSheet(view),
            onChoose = { name -> LeavingChoiceRules.whenNamed(view, name)?.let(onByArea) },
            onClose = { sheet = null })
        null -> {}
    }
}
