package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import os.meka.android.lists.Chip
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.DateNightRow
import os.meka.core.domain.DateNightRules
import os.meka.core.domain.LocalClock
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Date night (date night, slice 1): the evening picked once (a weekday chip, a start time, and which of
 * the next two weeks it starts), the next four nights with Skip this one / Keep it, and Turn off. The planner and Gym
 * bookings keep each night's evening clear; Today's header says "Date night tonight from 19:00" on the day.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the line after an action
 * cross-fade; the chip rows and the nights stagger in 40 ms apart; a chip's colour blends as it is chosen (tick
 * haptic); a night's line cross-fades between kept and skipped; the Starts row and Turn off unfold once it is on
 * (expand spring). Reduced motion: cross-fades.
 */
@Composable
fun DateNightPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.dateNightView.collectAsState()
    // What the last tap did ("Skipped Fri 23 Oct · the evening is free to plan").
    var said by remember { mutableStateOf<String?>(null) }

    fun set(weekday: Int, startMin: Int, firstDay: Long) {
        haptics.tick()
        scope.launch { runCatching { core.setDateNight(weekday, startMin, firstDay) }.getOrNull()?.let { said = null } }
    }
    // A night that stays one when only the time changes (so the fortnight isn't moved).
    fun anchor(weekday: Int): Long =
        v.nights.firstOrNull()?.day?.takeIf { weekday == v.weekday && v.on } ?: DateNightRules.firstNight(weekday, core.todayEpochDay())

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
            Text("Date night", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.DATE_NIGHT)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "date-night-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "evening") {
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SectionLabel("EVENING")
                ChipLine {
                    DateNightRules.WEEKDAY_CHOICES.forEachIndexed { i, label ->
                        val weekday = i + 1
                        Chip(label, v.on && v.weekday == weekday) { set(weekday, v.startMin, anchor(weekday)) }
                    }
                }
                ChipLine {
                    DateNightRules.START_CHOICES.forEach { m ->
                        Chip(LocalClock.formatMinute(m), v.startMin == m) { set(v.weekday, m, anchor(v.weekday)) }
                    }
                }
            }
        }
        item(key = "starts") {
            AnimatedVisibility(v.on && v.starts.isNotEmpty(), enter = unfold(), exit = fold()) {
                Column(Modifier.appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text("Starts", style = MekaType.caption, color = Meka.colors.textTertiary)
                    ChipLine { v.starts.forEach { s -> Chip(s.label, s.chosen) { set(v.weekday, v.startMin, s.day) } } }
                }
            }
        }
        if (v.nights.isNotEmpty()) {
            item(key = "label-nights") {
                SectionLabel("COMING UP", Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(4)))
            }
            itemsIndexed(v.nights, key = { _, r -> "night-${r.day}" }) { i, r ->
                NightRow(r, Modifier.animateItem().appear(rememberAppearance(5 + i))) {
                    haptics.tick()
                    scope.launch { said = runCatching { core.skipDateNight(r.day, !r.skipped) }.getOrNull() ?: said }
                }
            }
        }
        item(key = "said") {
            Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "date-night-said") { line ->
                line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
            }
        }
        item(key = "off") {
            AnimatedVisibility(v.on, enter = unfold(), exit = fold()) {
                Text(
                    DateNightRules.OFF_LABEL, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                        haptics.tick()
                        said = null
                        scope.launch { runCatching { core.dateNightOff() } }
                    }.minTouch(),
                )
            }
        }
        item(key = "note") {
            Text(DateNightRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}

@Composable
private fun NightRow(row: DateNightRow, modifier: Modifier, onToggle: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.label, style = MekaType.body, color = if (row.label == "Tonight") Meka.colors.accent else Meka.colors.textPrimary)
            Crossfade(row.line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "night-${row.day}") { l ->
                Text(l, style = MekaType.caption, color = if (row.skipped) Meka.colors.textTertiary else Meka.colors.textSecondary)
            }
        }
        Text(
            if (row.skipped) DateNightRules.KEEP_LABEL else DateNightRules.SKIP_LABEL,
            style = MekaType.caption, color = if (row.skipped) Meka.colors.accent else Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { contentDescription = (if (row.skipped) "Keep ${row.label}" else "Skip ${row.label}") }
                .padding(MekaSpace.xs),
        )
    }
}
