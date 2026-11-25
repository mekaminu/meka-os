package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.ProtectedChoiceRules
import os.meka.core.domain.ProtectedMailRules
import os.meka.core.domain.ProtectedRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Protected time (protected time, slice 1): evenings from a chosen time (Off · 18:00 … 20:00) and
 * weekends with bank holidays (Open · Protected). Plan my day and Move to later never put a task into protected time;
 * habits, booked sessions and date night are left as they are. The line about today ("Today is protected · tasks wait
 * for Mon 12 Oct" / "Protected from 19:00 tonight") unfolds once something is protected.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and today's line cross-fade;
 * the sections stagger in 40 ms apart; a chip's colour blends as it is chosen (tick haptic); today's line unfolds on
 * the expand spring. Reduced motion: cross-fades.
 *
 * Slice 3: WORK MAIL (Waits till morning · Shows as it comes) unfolds once something is protected, with "Held now ·
 * 2 business emails till tomorrow 07:00" cross-fading in while some wait.
 *
 * Slice 4: under today's line, "Open tonight, just this once" (or "… today" on a protected whole day) in the accent
 * lifts today's protected time just this once; it cross-fades to "Protect tonight again" with the line below saying what
 * changed (week 5 A8: verbs, so the button never reads as a status opposite today's line, which is in the text colour). Tick haptic; reduced motion: cross-fades.
 *
 * Walk-through week 5, A17: no open chip rows. Evenings from, Weekends and bank holidays and Work mail are each one card
 * row saying what's chosen ("Evenings from" · "19:00") that opens a sheet of the choices with what each does, the
 * chosen one lit (Notifications' [NotifyChoiceSheetView]: rises over a fading scrim, a tick haptic on a choice, which
 * saves and closes it; reduced motion: a cross-fade).
 *
 * Quality gate slice 49 (before A17): the chips wrapped onto a second line instead of scrolling sideways (on the cover screen the
 * evening's From 19:30 and From 20:00 sat off the edge), each answers a 48 dp touch with its pill drawn as before, and
 * the pane's lines keep their last words and dots together ([LoneWordGlue]).
 */
@Composable
fun ProtectedTimePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.protectedView.collectAsState()

    var openLine by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    fun setOpen(open: Boolean) {
        haptics.tick()
        scope.launch { runCatching { core.setProtectedOpenToday(open) }.getOrNull()?.let { openLine = it } }
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag("protected-pane"),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("Protected time", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.PROTECTED)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "protected-summary") { s ->
                Text(LoneWordGlue.kept(s), style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "today") {
            AnimatedVisibility(v.todayLine != null, enter = unfold(), exit = fold()) {
                Crossfade(v.todayLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "protected-today") { line ->
                    // Week 5 A8: today's status reads in the text colour; only the button below is in the accent.
                    Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.textPrimary)
                }
            }
        }
        item(key = "open") {
            // Slice 4: not protected tonight, just this once (Undo puts it back).
            val action = v.openAction ?: v.undoAction
            AnimatedVisibility(action != null, enter = unfold(), exit = fold()) {
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Crossfade(action.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "protected-open") { label ->
                        Text(label, style = MekaType.itemMeta, color = Meka.colors.accent,
                            modifier = Modifier.clickable(role = Role.Button) { setOpen(!v.openToday) }.minTouch())
                    }
                    AnimatedVisibility(openLine != null, enter = unfold(), exit = fold()) {
                        Text(LoneWordGlue.kept(openLine.orEmpty()), style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
            }
        }
        // Walk-through week 5, A17: the evening, weekend and work-mail choices are rows opening a sheet, not chip rows.
        item(key = "evenings") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                NotifyChoiceRowView(ProtectedChoiceRules.eveningsRow(v)) { sheet = Sheet.EVENINGS }
                NotifyChoiceRowView(ProtectedChoiceRules.weekendsRow(v)) { sheet = Sheet.WEEKENDS }
            }
        }
        item(key = "mail") {
            // Slice 3: work mail waits until morning (only while something is protected).
            AnimatedVisibility(v.on, enter = unfold(), exit = fold()) {
                Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    NotifyChoiceRowView(ProtectedChoiceRules.mailRow(v)) { sheet = Sheet.MAIL }
                    AnimatedVisibility(v.mailLine != null, enter = unfold(), exit = fold()) {
                        Crossfade(v.mailLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "protected-mail") { line ->
                            Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.accent)
                        }
                    }
                    Text(LoneWordGlue.kept(ProtectedMailRules.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        item(key = "note") {
            Text(LoneWordGlue.kept(ProtectedRules.NOTE), style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(4)))
        }
    }

    sheet?.let { open ->
        val choices = when (open) {
            Sheet.EVENINGS -> ProtectedChoiceRules.eveningsSheet(v)
            Sheet.WEEKENDS -> ProtectedChoiceRules.weekendsSheet(v)
            Sheet.MAIL -> ProtectedChoiceRules.mailSheet(v)
        }
        NotifyChoiceSheetView(choices, onChoose = { name ->
            // The sheet gives its own tick haptic on a choice.
            when (open) {
                Sheet.EVENINGS -> ProtectedChoiceRules.eveningNamed(name, v.fromMin)?.let { (on, from) ->
                    scope.launch { runCatching { core.setProtectedTime(on, from, v.weekends) } }
                }
                Sheet.WEEKENDS -> ProtectedChoiceRules.weekendNamed(name)?.let { w ->
                    scope.launch { runCatching { core.setProtectedTime(v.evenings, v.fromMin, w) } }
                }
                Sheet.MAIL -> ProtectedChoiceRules.mailNamed(name)?.let { hold ->
                    scope.launch { runCatching { core.setProtectedMail(hold) } }
                }
            }
        }, onClose = { sheet = null })
    }
}

private enum class Sheet { EVENINGS, WEEKENDS, MAIL }
