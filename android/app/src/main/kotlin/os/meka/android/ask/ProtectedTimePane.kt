package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
import os.meka.android.lists.Chip
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.LocalClock
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
 */
@Composable
fun ProtectedTimePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.protectedView.collectAsState()

    fun set(evenings: Boolean, fromMin: Int, weekends: Boolean) {
        haptics.tick()
        scope.launch { runCatching { core.setProtectedTime(evenings, fromMin, weekends) } }
    }

    fun setMail(hold: Boolean) {
        haptics.tick()
        scope.launch { runCatching { core.setProtectedMail(hold) } }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
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
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "today") {
            AnimatedVisibility(v.todayLine != null, enter = unfold(), exit = fold()) {
                Crossfade(v.todayLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "protected-today") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.accent)
                }
            }
        }
        item(key = "evenings") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SectionLabel(ProtectedRules.EVENINGS_LABEL.uppercase())
                ChipLine {
                    Chip("Off", !v.evenings) { set(false, v.fromMin, v.weekends) }
                    ProtectedRules.FROM_CHOICES.forEach { m ->
                        Chip("From ${LocalClock.formatMinute(m)}", v.evenings && v.fromMin == m) { set(true, m, v.weekends) }
                    }
                }
            }
        }
        item(key = "weekends") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SectionLabel(ProtectedRules.WEEKENDS_LABEL.uppercase())
                ChipLine {
                    Chip("Open", !v.weekends) { set(v.evenings, v.fromMin, false) }
                    Chip("Protected", v.weekends) { set(v.evenings, v.fromMin, true) }
                }
            }
        }
        item(key = "mail") {
            // Slice 3: work mail waits until morning (only while something is protected).
            AnimatedVisibility(v.on, enter = unfold(), exit = fold()) {
                Column(Modifier.padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SectionLabel(ProtectedMailRules.LABEL.uppercase())
                    ChipLine {
                        Chip(ProtectedMailRules.ON, v.holdMail) { setMail(true) }
                        Chip(ProtectedMailRules.OFF, !v.holdMail) { setMail(false) }
                    }
                    AnimatedVisibility(v.mailLine != null, enter = unfold(), exit = fold()) {
                        Crossfade(v.mailLine.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "protected-mail") { line ->
                            Text(line, style = MekaType.caption, color = Meka.colors.accent)
                        }
                    }
                    Text(ProtectedMailRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        item(key = "note") {
            Text(ProtectedRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(4)))
        }
    }
}

@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}
