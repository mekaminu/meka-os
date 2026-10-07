package os.meka.android.ask

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import os.meka.android.notify.NotificationsPane
import os.meka.android.search.SearchPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.OpenItem
import os.meka.android.shell.ShellDestination
import os.meka.android.shell.ShellNav
import os.meka.android.today.CalendarsPane
import os.meka.android.work.WorkPane
import os.meka.core.facade.MekaCore

/**
 * ASK (build plan M1, Four tabs, one front door). The fourth tab and the front door to everything that isn't a tab.
 * Until the AI layer lands (Needs Meka #3) asking is searching: the field opens Search everything. Below it, More
 * lists every place behind Ask (Lists, Goals and habits, Review, Vault: they open with the shell's forward slide and
 * keep Ask lit) and the settings-like panes, which spring up over Ask as they do over Today.
 * Motion: the title, field and More rows stagger in 40 ms apart; a lit Lists line blends its colour. Reduced motion
 * cross-fades.
 */
@Composable
fun AskRoute(core: MekaCore, connected: Boolean, go: (ShellDestination) -> Unit, openItem: (OpenItem) -> Unit) {
    val lists by core.listsView.collectAsState()
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
                MoreRow(item, lists.dueCount, Modifier.appear(rememberAppearance(3 + i))) {
                    val d = item.destination
                    if (d != null) go(d) else pane = item
                }
            }
        }
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
private fun MoreRow(item: MoreItem, listsDue: Int, modifier: Modifier, onClick: () -> Unit) {
    val lit = ShellNav.moreLit(item, listsDue)
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
            Text(item.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
            Text(ShellNav.moreLine(item, listsDue), style = MekaType.caption, color = lineColor, maxLines = 2)
        }
        Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.padding(start = MekaSpace.s))
    }
}
