package os.meka.android.work

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SlideSwitch
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CallsSettingsRules
import os.meka.core.domain.CallsSettingsView
import os.meka.core.domain.CallsSwitch
import os.meka.core.domain.CallsToggle
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore

const val OBI_CALLS_TAG = "obi-calls"
const val OBI_CALLS_ENTRY_TAG = "obi-calls-entry"

/**
 * Work mode → Call assistant's "Obi on calls ›" row: the page's name and the live line under it (spend against the
 * cap), cross-fading as it changes. A tap opens [ObiCallsPane].
 */
@Composable
internal fun ObiCallsEntry(line: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val haptics = rememberMekaHaptics()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .clickable(role = Role.Button) { haptics.tick(); onOpen() }
            .semantics { contentDescription = CallsSettingsRules.ENTRY }
            .testTag(OBI_CALLS_ENTRY_TAG).minTouch().padding(MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(CallsSettingsRules.ENTRY, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
            Crossfade(line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "obi-calls-entry-line") { l ->
                Text(LoneWordGlue.kept(l), style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
}

/**
 * Obi on calls, slice 4 (mockup `docs/design/calls/settings.dc.html`): THIS MONTH with spent, calls and booked against
 * the cap and the £10 · £20 · £30 · £50 chips; WHEN SOMEONE CALLS YOU's three switches; WHAT CALLERS MAY KNOW's chips;
 * the fixed BOOKING CALLS limits; RECORDINGS; "Obi always says he's your AI assistant". Everything is synced with the
 * Mac. Motion: sections stagger in 40 ms apart; the spend bar grows from the left to its share; chips and switches
 * blend on the appear spring with a tick haptic; lines cross-fade. Reduced motion: cross-fades, the bar drawn at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObiCallsPane(core: MekaCore, onClose: () -> Unit) {
    val work by core.workMode.collectAsState()
    var view by remember { mutableStateOf<CallsSettingsView?>(null) }
    LaunchedEffect(work) { view = core.callsSettings() }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    val v = view
    Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag(OBI_CALLS_TAG), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = WORK_COLUMN_MAX).fillMaxHeight().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            Text("‹ Work mode", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            Text(CallsSettingsRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.semantics { heading() }.appear(rememberAppearance(0)))
            Text(CallsSettingsRules.SUB, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))
            if (v != null) {
            MonthCard(v, Modifier.appear(rememberAppearance(1))) { gbp -> scope.launch { core.setCallLiveCap(gbp) } }

            Section(CallsSettingsRules.WHEN_CALLS, 2)
            Column(Modifier.appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                v.toggles.forEach { t ->
                    ToggleRow(t) {
                        scope.launch {
                            when (t.kind) {
                                CallsSwitch.LIVE -> core.setCallLive(!t.on)
                                CallsSwitch.SLOTS -> core.setCallSlots(!t.on)
                                CallsSwitch.BOOKING -> core.setCallBooking(!t.on)
                            }
                        }
                    }
                }
            }

            Section(CallsSettingsRules.KNOWS_TITLE, 3)
            Card(Modifier.appear(rememberAppearance(3))) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    val haptics = rememberMekaHaptics()
                    v.knows.forEach { c ->
                        SettingChip(c.label, c.on, spoken = "${CallsSettingsRules.KNOWS_TITLE}: ${c.label}") {
                            haptics.tick(); scope.launch { core.toggleCallKnows(c.key) }
                        }
                    }
                }
                Text(LoneWordGlue.kept(CallsSettingsRules.NEVER), style = MekaType.caption, color = Meka.colors.textTertiary)
            }

            Section(CallsSettingsRules.LIMITS_TITLE, 4)
            Column(Modifier.appear(rememberAppearance(4)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                v.limits.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        pair.forEach { l -> LimitTile(l.label, l.value, Modifier.weight(1f)) }
                    }
                }
            }

            Section(CallsSettingsRules.RECORDINGS_TITLE, 5)
            Card(Modifier.appear(rememberAppearance(5))) {
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    val haptics = rememberMekaHaptics()
                    v.keeps.forEach { c ->
                        SettingChip(c.label, c.on, spoken = "${CallsSettingsRules.RECORDINGS_TITLE}: ${c.label}") {
                            if (!c.on) { haptics.tick(); scope.launch { core.setRecordingDays(c.key.toInt()) } }
                        }
                    }
                }
                Text(LoneWordGlue.kept(CallsSettingsRules.RECORDINGS_NOTE), style = MekaType.caption, color = Meka.colors.textTertiary)
            }

            Text(CallsSettingsRules.FOOTER, style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(top = MekaSpace.l, bottom = MekaSpace.xl).appear(rememberAppearance(6)),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Section(text: String, index: Int) {
    Text(text.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
        modifier = Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(index)))
}

@Composable
private fun MonthCard(v: CallsSettingsView, modifier: Modifier, onCap: (Int) -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val share by animateFloatAsState(if (shown) v.fraction else 0f, MekaMotion.expand(reduced), label = "obi-calls-bar")
    Column(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface).padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CallsSettingsRules.THIS_MONTH.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
            Crossfade(v.capLine, animationSpec = MekaMotion.appear(reduced), label = "obi-calls-cap") { l ->
                Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            v.tiles.forEach { t -> LimitTile(t.label, t.value, Modifier.weight(1f), raised = true) }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Meka.colors.surfaceRaised)
            .semantics { contentDescription = "${v.tiles.first().value} of £${v.capGbp}" }) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                .background(if (v.atCap) Meka.colors.critical else Meka.colors.accent))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            v.caps.forEach { c ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SettingChip(c.label, c.on, spoken = "Monthly cap ${c.label}") {
                        if (!c.on) { haptics.tick(); onCap(c.key.toInt()) }
                    }
                }
            }
        }
        Text(LoneWordGlue.kept(CallsSettingsRules.CAP_NOTE), style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun LimitTile(label: String, value: String, modifier: Modifier, raised: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.s)).background(if (raised) Meka.colors.surfaceRaised else Meka.colors.surface).padding(MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary, maxLines = 1)
        Crossfade(value, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "obi-calls-tile") { t ->
            Text(t, style = MekaType.upNextCardTitle, color = Meka.colors.textPrimary)
        }
    }
}

/** A section's card (the mockup's): the surface, rounded, 16 dp inside, its lines 8 dp apart. */
@Composable
private fun Card(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface).padding(MekaSpace.m),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) { content() }
}

@Composable
private fun ToggleRow(t: CallsToggle, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .clickable(role = Role.Switch) { haptics.tick(); onToggle() }
            .semantics { contentDescription = t.title; selected = t.on }
            .minTouch().padding(MekaSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(t.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(LoneWordGlue.kept(t.sub), style = MekaType.caption, color = Meka.colors.textTertiary)
            Crossfade(t.line, animationSpec = MekaMotion.appear(reduced), label = "obi-calls-switch-line") { l ->
                if (l != null) Text(LoneWordGlue.kept(l), style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        SlideSwitch(t.on)
    }
}
