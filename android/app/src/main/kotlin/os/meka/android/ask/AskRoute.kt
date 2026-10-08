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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import os.meka.android.activity.ActivityPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
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
import os.meka.android.today.BriefPane
import os.meka.android.today.CalendarsPane
import os.meka.android.today.ShutdownPane
import os.meka.android.work.WorkPane
import os.meka.core.facade.MekaCore

/**
 * ASK (build plan M1, Four tabs, one front door). The fourth tab and the front door to everything that isn't a tab.
 * Until the AI layer lands (Needs Meka #3) asking is searching: the field opens Search everything. Below it, More
 * lists every place behind Ask (Lists, Goals and habits, Review, Vault: they open with the shell's forward slide and
 * keep Ask lit) and the settings-like panes, which spring up over Ask as they do over Today.
 * Motion: the title, field and More rows stagger in 40 ms apart; a lit Lists line blends its colour; a row's label
 * travels into the title of what it opens (and back). Reduced motion cross-fades.
 */
@Composable
fun AskRoute(core: MekaCore, connected: Boolean, openPlace: (ShellDestination) -> Unit, openItem: (OpenItem) -> Unit) {
    val lists by core.listsView.collectAsState()
    val work by core.workMode.collectAsState()
    // Appearance unfolds its three choices in its own row.
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var pane by rememberSaveable { mutableStateOf<MoreItem?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val items = ShellNav.more(connected)
    // Back closes whatever sprang up over Ask before it leaves the app.
    BackHandler(enabled = pane != null || showSearch) { if (showSearch) showSearch = false else pane = null }

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
                Column(Modifier.appear(rememberAppearance(1))) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(MekaRadius.pill))
                            .background(Meka.colors.surfaceRaised)
                            .clickable(role = Role.Button) { showSearch = true }
                            .semantics { contentDescription = "Search everything" }
                            .padding(horizontal = MekaSpace.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Search everything", style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                    }
                    Text(
                        "Tasks, events, lists, goals and habits. Asking in your own words comes with the AI layer.",
                        style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.xs, start = MekaSpace.xxs, bottom = MekaSpace.l),
                    )
                }
            }
            item(key = "more") {
                Text("MORE", style = MekaType.sectionLabel, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(bottom = MekaSpace.xxs).appear(rememberAppearance(2)))
            }
            itemsIndexed(items, key = { _, it -> it.name }) { i, item ->
                if (ShellNav.unfoldsInPlace(item)) {
                    AppearanceRow(appearanceOpen, Modifier.appear(rememberAppearance(3 + i)), openTopics = { pane = MoreItem.NEWS }) { appearanceOpen = !appearanceOpen }
                } else {
                    MoreRow(item, ShellNav.moreLine(item, lists.dueCount, work.atWork), ShellNav.moreLit(item, lists.dueCount),
                        pane == item, Modifier.appear(rememberAppearance(3 + i))) {
                        val d = item.destination
                        if (d != null) openPlace(d) else pane = item
                    }
                }
            }
        }
        MekaPane(visible = pane == MoreItem.BRIEF) { BriefPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.NEWS) { NewsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.SHUTDOWN) { ShutdownPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.WORK) { WorkPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.NOTIFICATIONS) { NotificationsPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.ACTIVITY) { ActivityPane(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.YOUR_DATA) { YourData(core, onClose = { pane = null }) }
        MekaPane(visible = pane == MoreItem.CALENDARS) { CalendarsPane(core, onClose = { pane = null }) }
        MekaPane(visible = showSearch) {
            SearchPane(core, onClose = { showSearch = false }, openItem = { item -> showSearch = false; openItem(item) })
        }
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
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
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
        Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.padding(start = MekaSpace.s))
    }
}

/**
 * Appearance (Today clarity, slice 2: the theme moved here from Today's header). The row unfolds Dark · Light · Auto
 * in place; the chosen chip's colour blends across with a tick haptic and every colour on screen blends with it
 * (`themeBlend`). Reduced motion: the chips appear at once and colours cross-fade.
 */
@Composable
private fun AppearanceRow(open: Boolean, modifier: Modifier, openTopics: () -> Unit, toggle: () -> Unit) {
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
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(MoreItem.APPEARANCE.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
                Text("${theme.choice.label} · ${MoreItem.APPEARANCE.line}", style = MekaType.caption, color = Meka.colors.textSecondary)
            }
            val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.appear(Meka.reducedMotion), label = "appearance-chevron")
            Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(start = MekaSpace.s).rotate(turn))
        }
        if (open) {
            Row(
                Modifier.padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.s),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                ThemeChoice.entries.forEach { c ->
                    Chip(c.label, theme.choice == c) { haptics.tick(); theme.set(c) }
                }
            }
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
                Modifier.fillMaxWidth().padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.xxs, bottom = MekaSpace.s),
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
        }
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
