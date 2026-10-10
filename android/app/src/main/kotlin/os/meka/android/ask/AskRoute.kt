package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import os.meka.android.activity.ActivityPane
import os.meka.android.calendar.EventUndoBar
import os.meka.android.calendar.rememberEventUndo
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.export.YourData
import os.meka.android.news.NewsPane
import os.meka.android.news.TickerChoice
import os.meka.android.news.rememberTickerMode
import os.meka.core.domain.TickerMode
import os.meka.android.notify.NotificationsPane
import os.meka.android.search.SearchPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.PlaceVia
import os.meka.android.shell.SharedMotion
import os.meka.android.designsystem.sharedPlace
import os.meka.android.designsystem.sharedTitle
import os.meka.android.shell.OpenItem
import os.meka.android.shell.ShellDestination
import os.meka.android.shell.ShellNav
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.MotionChoice
import os.meka.core.domain.MotionCheckRules
import os.meka.core.domain.MotionRules
import os.meka.core.domain.AppUpdateRules
import os.meka.android.MekaApplication
import os.meka.android.designsystem.MotionControl
import os.meka.android.designsystem.MotionPrefs
import os.meka.android.today.BriefPane
import os.meka.android.today.CalendarsPane
import os.meka.android.today.ShutdownPane
import os.meka.android.work.WorkPane
import os.meka.core.facade.MekaCore

/**
 * ASK (build plan M1, Four tabs, one front door). The fourth tab and the front door to everything that isn't a tab.
 * One field asks MEKA in your own words (Return; [AskMekaSection], V1 AI layer slice 3b) and searches as you type, the
 * best matches under it and "See all" opening Search everything (Fold review 2026-10-09 07:26, item 8). Below it, More
 * lists, in three sections (Places · Daily · Settings), every place behind Ask (Lists, Goals and habits, Review,
 * Vault: they open with the shell's forward slide and keep Ask lit) and the panes, which spring up over Ask as they
 * do over Today. Motion: the title, field and More's sections stagger in 40 ms apart (each section a step after the
 * one before began, its label leading its rows); a lit Lists line blends its colour; a row's label
 * travels into the title of what it opens (and back). Reduced motion cross-fades.
 */
@Composable
fun AskRoute(
    core: MekaCore, connected: Boolean, openPlace: (ShellDestination) -> Unit, openItem: (OpenItem) -> Unit,
    /** Appearance → Play the opening: back to Today, which replays its opening. */
    playOpening: () -> Unit = {},
) {
    val lists by core.listsView.collectAsState()
    val work by core.workMode.collectAsState()
    val health by core.healthView.collectAsState()
    val setup by core.setupView.collectAsState()
    // Appearance unfolds its three choices in its own row.
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var pane by rememberSaveable { mutableStateOf<MoreItem?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    // What Ask's field hands Search everything: the query typed and a task to open over the results.
    var searchSeed by rememberSaveable { mutableStateOf("") }
    var searchTask by rememberSaveable { mutableStateOf<String?>(null) }
    var searchEpoch by rememberSaveable { mutableStateOf(0) }
    val closeSearch = { showSearch = false; searchEpoch += 1 }
    val sections = ShellNav.moreSections(connected)
    val undo = rememberEventUndo()
    // Today's health line → Open Health (Reliability first, item 3).
    val healthApp = LocalContext.current.applicationContext as? os.meka.android.MekaApplication
    val openHealth by remember(healthApp) { healthApp?.openHealth ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openHealth) {
        if (openHealth) { pane = MoreItem.HEALTH; healthApp?.openHealth?.value = false }
    }
    // Today's setup card → Open Setup (Setup checklist).
    val openSetup by remember(healthApp) { healthApp?.openSetup ?: kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()
    LaunchedEffect(openSetup) {
        if (openSetup) { pane = MoreItem.SETUP; healthApp?.openSetup?.value = false }
    }
    // Back closes whatever sprang up over Ask before it leaves the app.
    BackHandler(enabled = pane != null || showSearch) { if (showSearch) closeSearch() else pane = null }

    Box(Modifier.fillMaxSize().background(Meka.colors.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "title") {
                Text("Ask", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.padding(bottom = MekaSpace.m).appear(rememberAppearance(0)))
            }
            item(key = "field") {
                // Ask MEKA (V1 AI layer, slice 3b): one field asks in your own words (Return) and searches as you type
                // (Fold review 2026-10-09 07:26, item 8); the matches open where they live, "See all" opens Search.
                AskMekaSection(
                    core, undo,
                    openSearch = { q, task -> searchSeed = q; searchTask = task; showSearch = true },
                    modifier = Modifier.appear(rememberAppearance(1)),
                    openItem = openItem,
                    searchEpoch = searchEpoch,
                )
            }
            // More in sections (Fold review 2026-10-08, item 10): Places · Daily · Settings, a small label over each;
            // each section staggers in one step after the one before began.
            sections.forEachIndexed { s, section ->
                item(key = "more-${section.group.name}") {
                    Text(section.group.label.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                        modifier = Modifier
                            .padding(top = if (s == 0) 0.dp else MekaSpace.m, bottom = MekaSpace.xxs)
                            .semantics { heading() }
                            .appear(rememberAppearance(ShellNav.moreLabelStep(s))))
                }
                itemsIndexed(section.items, key = { _, it -> it.name }) { i, item ->
                    val step = ShellNav.moreRowStep(s, i)
                    if (ShellNav.unfoldsInPlace(item)) {
                        AppearanceRow(appearanceOpen, Modifier.appear(rememberAppearance(step)), openTopics = { pane = MoreItem.NEWS }, playOpening = playOpening) { appearanceOpen = !appearanceOpen }
                    } else {
                        MoreRow(item, ShellNav.moreLine(item, lists.dueCount, work.atWork, health?.summary, setup?.summary),
                            ShellNav.moreLit(item, lists.dueCount, health?.attention ?: 0, setup?.toDo ?: 0),
                            pane == item, Modifier.appear(rememberAppearance(step))) {
                            val d = item.destination
                            if (d != null) openPlace(d) else pane = item
                        }
                    }
                }
            }
        }
        MekaPane(visible = pane == MoreItem.BRIEF) { BriefPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.NEWS) { NewsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.SHUTDOWN) { ShutdownPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.SCHOOL) { SchoolPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.WORK) { WorkPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.NOTIFICATIONS) { NotificationsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.ACTIVITY) { ActivityPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.YOUR_DATA) { YourData(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.CALENDARS) { CalendarsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.FAMILY) { FamilyPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.WATCH) { WatchPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.VOICE) { VoicePane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.TALK) { TalkPane(onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.HEALTH) {
            HealthPane(core, onClose = { pane = null }, openPane = { f ->
                pane = when (f) {
                    os.meka.core.domain.HealthFix.CALENDARS -> MoreItem.CALENDARS
                    os.meka.core.domain.HealthFix.WORK -> MoreItem.WORK
                    else -> pane
                }
            })
        }
        MekaPane(visible = pane == MoreItem.SETUP) {
            SetupPane(core, onClose = { pane = null }, openPane = { f ->
                pane = when (f) {
                    os.meka.core.domain.SetupFix.CALENDARS -> MoreItem.CALENDARS
                    os.meka.core.domain.SetupFix.WORK -> MoreItem.WORK
                    os.meka.core.domain.SetupFix.VOICE -> MoreItem.VOICE
                    else -> pane
                }
            })
        }
        MekaPane(visible = showSearch) {
            SearchPane(core, onClose = closeSearch, openItem = { item -> showSearch = false; openItem(item) },
                initialQuery = searchSeed, initialTask = searchTask)
        }
        // An Ask card's undo bar ("Added “Milk” · Undo") rises at the foot of Ask.
        EventUndoBar(undo, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MoreRow(item: MoreItem, line: String, lit: Boolean, paneOpen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val lineColor by animateColorAsState(
        if (lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(Meka.reducedMotion), label = "more-line",
    )
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Places and settings you open, not things you act on: the regular body weight (type weight, 2026-10-06).
            // The label travels into what it opens: a place's title across the shell's slide, a pane's title as it
            // springs up (it steps aside while the pane has it).
            val d = item.destination
            val travel = if (d != null) Modifier.sharedPlace(SharedMotion.placeKey(d, PlaceVia.MORE))
            else Modifier.sharedTitle(SharedMotion.paneKey(item), visible = !paneOpen)
            Text(item.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = travel)
            Text(line, style = MekaType.caption, color = lineColor, maxLines = 2)
        }
        Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.padding(start = MekaSpace.xs))
    }
}

/**
 * Appearance (Today clarity, slice 2: the theme moved here from Today's header). The row unfolds Dark · Light · Auto
 * in place; the chosen chip's colour blends across with a tick haptic and every colour on screen blends with it
 * (`themeBlend`). Motion (motion pass 2): Expressive · Subtle · Off, the line under it cross-fades. Reduced motion:
 * the chips appear at once and colours cross-fade.
 */
@Composable
private fun AppearanceRow(open: Boolean, modifier: Modifier, openTopics: () -> Unit, playOpening: () -> Unit, toggle: () -> Unit) {
    val theme = Meka.theme
    val context = LocalContext.current.applicationContext
    val tickerMode = rememberTickerMode()
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(Meka.reducedMotion))
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = toggle)
                .semantics { stateDescription = "${theme.choice.label}, ${if (open) "expanded" else "collapsed"}" }
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(MoreItem.APPEARANCE.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                Text("${theme.choice.label} · ${MoreItem.APPEARANCE.line}", style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.appear(Meka.reducedMotion), label = "appearance-chevron")
            Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(start = MekaSpace.xs).rotate(turn))
        }
        if (open) {
            Row(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                ThemeChoice.entries.forEach { c ->
                    Chip(c.label, theme.choice == c) { haptics.tick(); theme.set(c) }
                }
            }
            // Motion (motion pass 2): MEKA's own motion on this phone, whatever the phone's animator scale says.
            val motion = Meka.motion
            Text("Motion", style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(start = MekaSpace.m, top = MekaSpace.xs))
            Row(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                MotionChoice.entries.forEach { m ->
                    // Nothing chosen shows Expressive lit: it is what plays (Meka, 2026-10-08).
                    Chip(m.label, MotionRules.lit(motion.stored) == m) { haptics.tick(); motion.set(m) }
                }
            }
            AnimatedContent(
                targetState = motion.line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "motion-line", modifier = Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
            ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
            MotionCheckSection(motion, playOpening)
            // News ticker (news ticker, slice 2): how the strip under Today's header moves, on this device; Topics
            // opens News (the topics and their sources).
            Text("News ticker", style = MekaType.caption, color = Meka.colors.textSecondary,
                modifier = Modifier.padding(start = MekaSpace.m, top = MekaSpace.xs))
            Row(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                TickerMode.entries.forEach { m ->
                    Chip(m.label, tickerMode == m) { haptics.tick(); TickerChoice.set(context, m) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = tickerMode.line,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "ticker-line", modifier = Modifier.weight(1f),
                ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
                Text("Topics ›", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = openTopics)
                        .padding(MekaSpace.xxs))
            }
            // The build on this phone (Meka, 2026-10-08), so we can tell whether it has the latest motion work.
            val app = context as? MekaApplication
            if (app != null) {
                val latest by app.updater.latestCode.collectAsState()
                LaunchedEffect(Unit) { runCatching { app.updater.check() } }
                AnimatedContent(
                    targetState = AppUpdateRules.versionLine(app.updater.installedName, app.updater.installedCode, latest),
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "build-line", modifier = Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.xs),
                ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
        }
    }
}

/**
 * Motion check (Meka, 2026-10-08: "the animation is something I have not seen work"): what MEKA sees on this phone
 * (its own Motion choice, the phone's animator scale, power saving) and the result; when animations are off the
 * result is lit and a tap turns MEKA's own Expressive motion on. "Play the opening" goes back to Today and replays
 * its opening. The values are read each time Appearance unfolds; the result cross-fades as the choice changes.
 */
@Composable
private fun MotionCheckSection(motion: MotionControl, playOpening: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scale = remember { MotionPrefs.animatorScale(context) }
    val saving = remember { MotionPrefs.powerSave(context) }
    val check = MotionCheckRules.phone(motion.stored, scale, saving)
    Text("Motion check", style = MekaType.caption, color = Meka.colors.textSecondary,
        modifier = Modifier.padding(start = MekaSpace.m, top = MekaSpace.xs))
    check.rows.forEach { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.m)) {
            Text(row.label, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
            Text(row.value, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
    }
    val fix = check.fix
    AnimatedContent(
        targetState = check.result,
        transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        label = "motion-check", modifier = Modifier.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
    ) { line ->
        Text(
            line, style = MekaType.caption,
            color = if (fix != null) Meka.colors.accent else Meka.colors.textPrimary,
            modifier = if (fix != null) Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button) { haptics.tick(); motion.set(fix) } else Modifier,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(MotionCheckRules.PLAY_OPENING_LINE, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.weight(1f))
        Text(MotionCheckRules.PLAY_OPENING, style = MekaType.caption, color = Meka.colors.onAccent,
            modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                .clickable(role = Role.Button) { haptics.light(); playOpening() }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
    }
}

/** A choice chip: the chosen one's colour blends across (`themeBlend`). */
@Composable
private fun Chip(label: String, chosen: Boolean, choose: () -> Unit) {
    val bg by animateColorAsState(
        if (chosen) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.themeBlend(Meka.reducedMotion), label = "chip-bg",
    )
    val fg by animateColorAsState(
        if (chosen) Meka.colors.onAccent else Meka.colors.textPrimary, MekaMotion.themeBlend(Meka.reducedMotion), label = "chip-fg",
    )
    Text(
        label, style = MekaType.caption, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .clickable(role = Role.RadioButton) { if (!chosen) choose() }
            .semantics { selected = chosen }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
