package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.LoneWordGlue
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
 * Slice 4: under today's line, "Not protected tonight" (or "… today" on a protected whole day) in the accent lifts
 * today's protected time just this once; it cross-fades to "Undo · protect it again" with the line below saying what
 * changed. Tick haptic; reduced motion: cross-fades.
 *
 * Quality gate slice 49: the chips wrap onto a second line instead of scrolling sideways (on the cover screen the
 * evening's From 19:30 and From 20:00 sat off the edge), each answers a 48 dp touch with its pill drawn as before, and
 * the pane's lines keep their last words and dots together ([LoneWordGlue]).
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

    var openLine by remember { mutableStateOf<String?>(null) }

    fun setOpen(open: Boolean) {
        haptics.tick()
        scope.launch { runCatching { core.setProtectedOpenToday(open) }.getOrNull()?.let { openLine = it } }
    }

    fun setMail(hold: Boolean) {
        haptics.tick()
        scope.launch { runCatching { core.setProtectedMail(hold) } }
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
                    Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.accent)
                }
            }
        }
        item(key = "open") {
            // Slice 4: not protected tonight, just this once (Undo puts it back).
            val action = v.openAction ?: if (v.openToday) "Undo · protect it again" else null
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
        item(key = "evenings") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                // Slice 50: "EVENINGS FROM" over chips that read just the time, two rows on the cover screen (was three).
                SectionLabel(ProtectedRules.EVENINGS_FROM_LABEL.uppercase())
                ChipLine {
                    ProtectedChip("Off", !v.evenings) { set(false, v.fromMin, v.weekends) }
                    ProtectedRules.FROM_CHOICES.forEach { m ->
                        ProtectedChip(ProtectedRules.fromChip(m), v.evenings && v.fromMin == m, spoken = ProtectedRules.fromSpoken(m)) {
                            set(true, m, v.weekends)
                        }
                    }
                }
            }
        }
        item(key = "weekends") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SectionLabel(ProtectedRules.WEEKENDS_LABEL.uppercase())
                ChipLine {
                    ProtectedChip("Open", !v.weekends) { set(v.evenings, v.fromMin, false) }
                    ProtectedChip("Protected", v.weekends) { set(v.evenings, v.fromMin, true) }
                }
            }
        }
        item(key = "mail") {
            // Slice 3: work mail waits until morning (only while something is protected).
            AnimatedVisibility(v.on, enter = unfold(), exit = fold()) {
                Column(Modifier.padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SectionLabel(ProtectedMailRules.LABEL.uppercase())
                    ChipLine {
                        ProtectedChip(ProtectedMailRules.ON, v.holdMail) { setMail(true) }
                        ProtectedChip(ProtectedMailRules.OFF, !v.holdMail) { setMail(false) }
                    }
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
}

/** The chips wrap to a new line rather than scroll sideways; each chip's 48 dp touch already spaces the lines apart. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}

/** A choice chip: the pill drawn as Lists' chips, answering a 48 dp touch (quality gate slice 49). */
@Composable
private fun ProtectedChip(label: String, lit: Boolean, spoken: String? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "protected-chip")
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.semantics { selected = lit; if (spoken != null) contentDescription = spoken }.clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button) { onClick() }.minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
