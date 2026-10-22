package os.meka.android.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.work.SettingChip
import os.meka.core.domain.CompanionMemoryRules
import os.meka.core.domain.CompanionRules
import os.meka.core.domain.DeviceVoice
import os.meka.core.domain.DeviceVoiceRow
import os.meka.core.domain.DeviceVoiceRules
import os.meka.core.domain.DeviceVoiceSettings
import os.meka.core.domain.VoiceChoice
import os.meka.core.domain.VoicePickerRules
import os.meka.core.domain.VoicePickerView
import os.meka.core.facade.MekaCore

/**
 * Ask → More → MEKA's voice (Weather and a voice, item 2): MEKA's voices (Amazon Polly's British voices through MEKA's
 * own server) first, the default marked, then the phone's own voice; each with ▶ Sample ("Good morning, Meka…", MEKA's
 * own words). Choosing one sets the synced "MEKA's voice", so Talk, the spoken brief and the call assistant use it on
 * every device. Under the list: why MEKA's voices are missing (if they are), the month's characters, and how to get a
 * better free phone voice. Then "This phone's voices" (slice 10): Automatic (MEKA's pick) and each installed English voice
 * that speaks on the phone, best first (engine · accent · quality), each with ▶ Sample, and Speed and Pitch sliders;
 * kept on this phone only ([DeviceVoiceStore]), since each device has its own voices.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; a shimmer while the server answers; rows
 * stagger in 40 ms apart; the chosen row's ring and border blend to the accent with a tick haptic; Sample presses in
 * with a light haptic and its label cross-fades to "■ Stop" while it plays (Stop: tick haptic); the status and month
 * lines cross-fade; "+3 more" unfolds the rest of the phone's voices (they stagger in); a slider's line cross-fades as
 * it moves, a tick haptic on each step. Reduced motion: cross-fades. The companion, slice 3: "Navilife calls you" (By name ·
 * Sir, synced) at the top; the chosen pill blends to the accent with a tick haptic and its line cross-fades. The companion,
 * slice 5: "What Navilife remembers" under it (Remember · Don't remember, synced, then the kept notes, newest first, and
 * Forget all): the pill blends with a tick haptic, the notes cross-fade out on Forget all (medium haptic) or off.
 * The orb speed pass, slice 2: "Speed" at the foot (the last 20 hellos and answers, how long each took to start and
 * where the wait went; this phone only, numbers only), rising in with the rest; Clear (medium haptic) cross-fades it.
 */
@Composable
fun VoicePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val context = LocalContext.current
    val speaker = remember { MekaSpeaker(context, core, scope) }
    DisposableEffect(speaker) { onDispose { speaker.release() } }
    var view by remember { mutableStateOf<VoicePickerView?>(null) }
    // The row whose sample is playing (null: none); a phone voice's row is keyed "device:<name>".
    var playing by remember { mutableStateOf<String?>(null) }
    var deviceVoices by remember { mutableStateOf<List<DeviceVoice>?>(null) }
    var deviceSettings by remember { mutableStateOf(DeviceVoiceStore.load(context)) }
    var showAll by remember { mutableStateOf(false) }
    // The companion, slice 3: "sir" or by name, synced.
    var sir by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { sir = runCatching { core.companionCallsSir() }.getOrDefault(false) }
    // The companion, slice 5: whether Navilife remembers chats, and what it keeps (synced).
    var remembers by remember { mutableStateOf<Boolean?>(null) }
    var memoryRows by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        remembers = runCatching { core.companionRemembers() }.getOrDefault(true)
        memoryRows = runCatching { core.companionMemoryRows() }.getOrDefault(emptyList())
    }
    // Ada's Live mode, slice 2: the Live switch, its month's meter, what goes to Google and the Live voice (synced).
    var live by remember { mutableStateOf<os.meka.core.domain.LiveSettingsView?>(null) }
    LaunchedEffect(Unit) { live = runCatching { core.liveSettings() }.getOrNull() }
    LaunchedEffect(Unit) {
        speaker.prepare()
        view = runCatching { core.voicePicker(mac = false) }.getOrNull()
            ?: VoicePickerRules.view(null, emptyList(), null, null, null, mac = false, connected = false)
    }
    LaunchedEffect(Unit) { deviceVoices = speaker.deviceVoices() }
    // The orb speed pass, slice 2: the last 20 hellos and answers, how long each took to start (this phone only).
    var speed by remember { mutableStateOf<os.meka.core.domain.TurnSpeedView?>(null) }
    LaunchedEffect(Unit) { speed = runCatching { core.turnSpeed("phone") }.getOrNull() }
    // Talking to Ada like a conversation, slice 2: the server's own timings of the last day's answers.
    var serverSpeed by remember { mutableStateOf<os.meka.core.domain.ServerSpeedView?>(null) }
    LaunchedEffect(Unit) { serverSpeed = runCatching { core.serverSpeed() }.getOrNull() }
    val reduced = Meka.reducedMotion

    fun choose(c: VoiceChoice) {
        if (c.selected) return
        haptics.tick()
        // Lit at once; the saved choice follows to every device.
        view = view?.let { v -> v.copy(choices = v.choices.map { it.copy(selected = it.id == c.id) }) }
        scope.launch { if (runCatching { core.chooseMekaVoice(c.id) }.getOrDefault(false)) view = runCatching { core.voicePicker(mac = false) }.getOrNull() ?: view }
    }

    fun callSir(on: Boolean) {
        if (sir == on) return
        haptics.tick()
        sir = on // lit at once; the choice follows to every device
        scope.launch { runCatching { core.setCompanionCallsSir(on) } }
    }

    fun remember(on: Boolean) {
        if (remembers == on) return
        haptics.tick()
        remembers = on // lit at once; off forgets the notes on every device
        if (!on) memoryRows = emptyList()
        scope.launch { runCatching { core.setCompanionRemembers(on) } }
    }

    fun forgetAll() {
        if (memoryRows.isEmpty()) return
        haptics.medium()
        memoryRows = emptyList()
        scope.launch { runCatching { core.forgetCompanionMemory() } }
    }

    fun sample(c: VoiceChoice) {
        if (playing == c.id && speaker.speaking) {
            haptics.tick()
            speaker.stop()
            playing = null
            return
        }
        haptics.light()
        playing = c.id
        speaker.sample(c.id) { playing = null }
    }

    fun setLive(on: Boolean) {
        val now = live ?: return
        if (now.on == on) return
        haptics.tick()
        live = now.copy(on = on, showVoices = on) // lit at once; the line follows from the server's meter
        scope.launch {
            runCatching { core.setLiveOn(on) }
            live = runCatching { core.liveSettings() }.getOrNull() ?: live
        }
    }

    fun liveVoice(name: String) {
        val now = live ?: return
        if (now.voices.any { it.selected && it.name == name }) return
        haptics.tick()
        live = now.copy(voices = now.voices.map { it.copy(selected = it.name == name) })
        scope.launch { runCatching { core.setLiveVoice(name) } }
    }

    fun saveDevice(next: DeviceVoiceSettings) {
        if (next == deviceSettings) return
        deviceSettings = next
        DeviceVoiceStore.save(context, next)
        speaker.useDeviceVoice(next)
    }

    fun chooseDevice(r: DeviceVoiceRow) {
        if (r.selected) return
        haptics.tick()
        saveDevice(deviceSettings.copy(voice = r.id.ifEmpty { null }))
    }

    fun sampleDevice(r: DeviceVoiceRow) {
        val key = "device:" + r.id
        if (playing == key && speaker.speaking) {
            haptics.tick()
            speaker.stop()
            playing = null
            return
        }
        haptics.light()
        playing = key
        speaker.sampleOnDevice(r.id) { playing = null }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { speaker.stop(); onClose() }.minTouch())
        Text(VoicePickerRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.VOICE)).appear(rememberAppearance(0)))
        Text(VoicePickerRules.INTRO, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(0)))
        sir?.let { on ->
            // The companion, slice 3: how Navilife addresses him in Ask, Talk and Hello.
            Column(Modifier.padding(vertical = MekaSpace.xs).appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(CompanionRules.CALL_TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SettingChip(CompanionRules.CALL_BY_NAME, on = !on, spoken = "Ada calls you by name") { callSir(false) }
                    SettingChip(CompanionRules.CALL_SIR_LABEL, on = on, spoken = "Ada calls you sir") { callSir(true) }
                }
                Crossfade(on, animationSpec = MekaMotion.appear(reduced), label = "call-line") { now ->
                    Text(CompanionRules.callLine(now), style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        remembers?.let { on ->
            // The companion, slice 5: what Navilife remembers of earlier chats, viewable and clearable.
            Column(Modifier.padding(vertical = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(CompanionMemoryRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SettingChip(CompanionMemoryRules.ON_LABEL, on = on, spoken = "Navilife remembers your chats") { remember(true) }
                    SettingChip(CompanionMemoryRules.OFF_LABEL, on = !on, spoken = "Navilife doesn't remember your chats") { remember(false) }
                }
                Crossfade(on to memoryRows, animationSpec = MekaMotion.appear(reduced), label = "memory") { (now, rows) ->
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(
                            if (!now) CompanionMemoryRules.OFF_LINE else CompanionMemoryRules.onLine(rows.size),
                            style = MekaType.caption, color = Meka.colors.textTertiary,
                        )
                        if (now && rows.isEmpty()) Text(CompanionMemoryRules.EMPTY, style = MekaType.caption, color = Meka.colors.textTertiary)
                        rows.forEach { row -> Text(row, style = MekaType.body, color = Meka.colors.textPrimary) }
                        if (rows.isNotEmpty()) {
                            Text(CompanionMemoryRules.FORGET_ALL, style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clickable(role = Role.Button) { forgetAll() }.minTouch())
                        }
                    }
                }
            }
        }
        live?.let { lv ->
            // Ada's Live mode, slice 2: real-time conversation on Gemini Live, off until he turns it on.
            Column(Modifier.padding(vertical = MekaSpace.xs).appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(lv.title.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textSecondary)
                Text(lv.intro, style = MekaType.caption, color = Meka.colors.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SettingChip(os.meka.core.domain.LiveTalkRules.OFF_LABEL, on = !lv.on, spoken = "Live off") { setLive(false) }
                    SettingChip(os.meka.core.domain.LiveTalkRules.ON_LABEL, on = lv.on, spoken = "Live on") { setLive(true) }
                }
                Crossfade(lv.line to lv.meterLine, animationSpec = MekaMotion.appear(reduced), label = "live-line") { (line, meter) ->
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text(line, style = MekaType.caption, color = if (lv.on) Meka.colors.accent else Meka.colors.textTertiary)
                        meter?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                    }
                }
                Text(lv.sharing, style = MekaType.caption, color = Meka.colors.textTertiary)
                androidx.compose.animation.AnimatedVisibility(
                    visible = lv.showVoices,
                    enter = fadeIn(MekaMotion.appear(reduced)) + androidx.compose.animation.expandVertically(),
                    exit = fadeOut(MekaMotion.appear(reduced)) + androidx.compose.animation.shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(os.meka.core.domain.LiveTalkRules.VOICE_TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textSecondary,
                            modifier = Modifier.padding(top = MekaSpace.xs))
                        lv.voices.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                                pair.forEach { voice ->
                                    SettingChip("${voice.name} · ${voice.detail}", on = voice.selected, spoken = "Ada's Live voice ${voice.name}, ${voice.detail}") { liveVoice(voice.name) }
                                }
                            }
                        }
                    }
                }
            }
        }
        val v = view
        if (v == null) {
            SkeletonRows(count = 4, rowHeight = 56.dp)
        } else {
            v.choices.forEachIndexed { i, c ->
                // Each provider's voices under their heading (Google's British, American, then Polly's).
                val group = c.group
                if (group != null && group != v.choices.getOrNull(i - 1)?.group) {
                    Text(group.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textSecondary,
                        modifier = Modifier.padding(top = if (i == 0) MekaSpace.xs else MekaSpace.m).appear(rememberAppearance(i + 1)))
                }
                VoiceRow(c, playing = playing == c.id && speaker.speaking, modifier = Modifier.appear(rememberAppearance(i + 1)),
                    choose = { choose(c) }, sample = { sample(c) })
            }
            val after = v.choices.size + 1
            AnimatedContent(
                targetState = listOfNotNull(v.statusLine, v.googleLine, v.sampleLine, v.usageLine),
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "voice-lines",
                modifier = Modifier.appear(rememberAppearance(after)),
            ) { lines ->
                Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    lines.forEachIndexed { i, line ->
                        Text(line, style = MekaType.caption,
                            color = when {
                                i == 0 && v.statusLine != null -> Meka.colors.accent
                                line == v.googleLine -> Meka.colors.textSecondary
                                else -> Meka.colors.textTertiary
                            })
                    }
                }
            }
            val voices = deviceVoices
            if (voices == null) {
                SkeletonRows(count = 2, rowHeight = 48.dp)
            } else {
                val d = DeviceVoiceRules.view(voices, deviceSettings, mac = false, expanded = showAll)
                Spacer(Modifier.height(MekaSpace.m))
                Text(d.title, style = MekaType.sectionLabel, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(after + 1)))
                d.rows.forEachIndexed { i, r ->
                    DeviceRow(r, playing = playing == "device:" + r.id && speaker.speaking,
                        modifier = Modifier.appear(rememberAppearance(after + 2 + i)),
                        choose = { chooseDevice(r) }, sample = { sampleDevice(r) })
                }
                d.moreLabel?.let { more ->
                    Text(more, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Show the other voices") { haptics.light(); showAll = true }
                            .padding(vertical = MekaSpace.xs))
                }
                d.emptyLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                val base = after + 2 + d.rows.size
                VoiceSlider(d.rateLine, deviceSettings.rate, DeviceVoiceRules.RATE_MIN, DeviceVoiceRules.RATE_MAX, DeviceVoiceRules::rate,
                    Modifier.appear(rememberAppearance(base)), onStep = { haptics.tick() }) { saveDevice(deviceSettings.copy(rate = DeviceVoiceRules.rate(it))) }
                VoiceSlider(d.pitchLine, deviceSettings.pitch, DeviceVoiceRules.PITCH_MIN, DeviceVoiceRules.PITCH_MAX, DeviceVoiceRules::pitch,
                    Modifier.appear(rememberAppearance(base + 1)), onStep = { haptics.tick() }) { saveDevice(deviceSettings.copy(pitch = DeviceVoiceRules.pitch(it))) }
                Text(d.note, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(base + 2)))
            }
            Spacer(Modifier.height(MekaSpace.m))
            Text(v.help, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(after + 4)))
        }
        speed?.let { sp ->
            // The orb speed pass, slice 2: where the wait goes, turn by turn (times only, nothing said).
            Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(5)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(sp.title.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textSecondary)
                Text(sp.intro, style = MekaType.caption, color = Meka.colors.textTertiary)
                Crossfade(sp, animationSpec = MekaMotion.appear(reduced), label = "speed") { now ->
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        now.summary?.let { Text(it, style = MekaType.body, color = Meka.colors.textPrimary) }
                        now.empty?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                        now.rows.forEach { row -> Text(row, style = MekaType.caption, color = Meka.colors.textSecondary) }
                        now.clear?.let { label ->
                            Text(label, style = MekaType.itemMeta, color = Meka.colors.accent,
                                modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Clear the speed log") {
                                    haptics.medium()
                                    scope.launch {
                                        runCatching { core.clearTurnSpeed() }
                                        speed = runCatching { core.turnSpeed("phone") }.getOrNull()
                                    }
                                }.minTouch())
                        }
                    }
                }
                serverSpeed?.let { sv ->
                    Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(6)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text(sv.title, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                        Text(sv.intro, style = MekaType.caption, color = Meka.colors.textTertiary)
                        sv.lines.forEach { line -> Text(line, style = MekaType.caption, color = Meka.colors.textSecondary) }
                        sv.empty?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(c: VoiceChoice, playing: Boolean, modifier: Modifier, choose: () -> Unit, sample: () -> Unit) {
    val reduced = Meka.reducedMotion
    val ring by animateColorAsState(if (c.selected) Meka.colors.accent else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "voice-ring")
    val fill by animateColorAsState(if (c.selected) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "voice-fill")
    val edge by animateColorAsState(if (c.selected) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "voice-edge")
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .border(1.dp, edge, RoundedCornerShape(MekaRadius.m))
            .semantics { selected = c.selected }
            .clickable(role = Role.RadioButton, onClickLabel = "Use ${c.label}") { choose() }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).border(1.5.dp, ring, CircleShape).padding(4.dp).clip(CircleShape).background(fill)) // rhythm: ok (the radio dot's own inset)
        Spacer(Modifier.width(MekaSpace.xs))
        Column(Modifier.weight(1f)) {
            // A setting you choose, not something to act on: the regular weight (type weight, 2026-10-06).
            Text(c.label, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(c.detail, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2)
        }
        if (c.sample) {
            Box(
                Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .clickable(role = Role.Button, onClickLabel = if (playing) "Stop the sample" else "Hear ${c.label}") { sample() }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            ) {
                Crossfade(targetState = playing, animationSpec = MekaMotion.appear(reduced), label = "voice-sample") { on ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) {
                            SamplePulse()
                            Spacer(Modifier.width(MekaSpace.xs))
                        }
                        Text(if (on) "■  Stop" else "▶  Sample", style = MekaType.itemMeta, color = Meka.colors.accent)
                    }
                }
            }
        }
    }
}

/**
 * Natural voice, slice 2: three thin brass bars that rise and fall out of step while a sample plays, so it reads as
 * "speaking" at a glance. Reduced motion: the bars stand still at three heights.
 */
@Composable
private fun SamplePulse() {
    val reduced = Meka.reducedMotion
    val transition = rememberInfiniteTransition(label = "voice-pulse")
    Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) { // rhythm: ok (a glyph's own spacing)
        listOf(0, 140, 280).forEachIndexed { i, delay ->
            val still = listOf(0.55f, 1f, 0.7f)[i]
            val level = if (reduced) still else transition.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420, delayMillis = delay), RepeatMode.Reverse),
                label = "voice-pulse-$i",
            ).value
            Box(Modifier.width(2.dp).height(14.dp).graphicsLayer { scaleY = level }.clip(RoundedCornerShape(1.dp)).background(Meka.colors.accent))
        }
    }
}

/** One of the phone's own voices: the same row as MEKA's voices (dot, label, detail, ▶ Sample). */
@Composable
private fun DeviceRow(r: DeviceVoiceRow, playing: Boolean, modifier: Modifier, choose: () -> Unit, sample: () -> Unit) {
    VoiceRow(VoiceChoice(r.id, r.label, r.detail, r.selected), playing, modifier, choose, sample)
}

/**
 * Speed or pitch: the line ("Speed · 1.2× faster") cross-fades as the thumb moves on [DeviceVoiceRules.STEP] steps,
 * a tick haptic per step; saved as it moves.
 */
@Composable
private fun VoiceSlider(
    line: String, value: Float, min: Float, max: Float, snap: (Float) -> Float, modifier: Modifier,
    onStep: () -> Unit, onChange: (Float) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val steps = (((max - min) / DeviceVoiceRules.STEP) + 0.5f).toInt() - 1
    Column(modifier.fillMaxWidth()) {
        Crossfade(targetState = line, animationSpec = MekaMotion.appear(reduced), label = "voice-slider-line") { l ->
            Text(l, style = MekaType.body, color = Meka.colors.textPrimary)
        }
        Slider(
            value = value,
            onValueChange = { v ->
                val snapped = snap(v)
                if (snapped != value) { onStep(); onChange(snapped) }
            },
            valueRange = min..max,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Meka.colors.accent,
                activeTrackColor = Meka.colors.accent,
                inactiveTrackColor = Meka.colors.surfaceRaised,
                activeTickColor = Meka.colors.accent,
                inactiveTickColor = Meka.colors.textTertiary,
            ),
            modifier = Modifier.semantics { contentDescription = line },
        )
    }
}
