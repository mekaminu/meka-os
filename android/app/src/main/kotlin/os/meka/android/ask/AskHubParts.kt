package os.meka.android.ask

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.shell.MoreItem
import os.meka.android.shell.ShellNav
import os.meka.core.domain.AiStatusView
import os.meka.core.domain.AskHubRules
import os.meka.core.domain.AskTab
import os.meka.core.domain.SettingsBox
import os.meka.core.facade.MekaCore

/** The switch's own node, for the screen tests. */
const val ASK_TABS_TAG = "ask-tabs"

/** What TalkBack reads on Life's page arrows (and what the hand tests tap). */
const val ASK_PREV_PAGE = "Previous page of places"
const val ASK_NEXT_PAGE = "Next page of places"

/**
 * The Ask hub's Life · Daily · Settings switch (approved 2026-11-11, `docs/design/look/AskHub.dc.html`): one rounded
 * track, the lit pill (the brass tint over the surface) springs across to the tab picked with a tick haptic and the
 * labels' colours blend; each third is a 48 dp touch and TalkBack reads it as a tab with its state. Reduced motion: the
 * pill moves at once and colours cross-fade.
 */
@Composable
internal fun AskTabSwitch(tab: AskTab, modifier: Modifier = Modifier, onPick: (AskTab) -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val tabs = AskHubRules.TABS
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
            .heightIn(min = MekaSpace.touch),
    ) {
        val third = maxWidth / tabs.size
        val x by animateDpAsState(third * tabs.indexOf(tab), MekaMotion.expand(reduced), label = "ask-tab-pill")
        Box(
            Modifier.offset(x = x).width(third).height(MekaSpace.touch).padding(4.dp)
                .clip(RoundedCornerShape(MekaRadius.pill))
                .background(Meka.colors.accent.copy(alpha = 0.16f).compositeOver(Meka.colors.surface)),
        )
        Row(Modifier.fillMaxWidth().height(MekaSpace.touch).selectableGroup()) {
            tabs.forEach { t ->
                val lit = t == tab
                val ink by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.textSecondary,
                    MekaMotion.appear(reduced), label = "ask-tab-ink-${t.name}")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(MekaRadius.pill))
                        .clickable(role = Role.Tab) { if (!lit) { haptics.tick(); onPick(t) } }
                        .semantics { selected = lit },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t.label, style = if (lit) MekaType.metaStrong else MekaType.itemMeta, color = ink, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Under Life's tiles when they run past a page: "1–8 of 15 · today’s first", a dot per page (the one showing long and
 * lit) and ‹ › (48 dp, dimmed and still at the ends). A turn gives a tick haptic; the dots' widths spring.
 */
@Composable
internal fun LifePageTurner(page: Int, count: Int, columns: Int, modifier: Modifier = Modifier, onTurn: (Int) -> Unit) {
    val line = AskHubRules.pageLine(page, count, columns) ?: return
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val pages = AskHubRules.pages(count, columns)
    val shown = AskHubRules.page(page, count, columns)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(line, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(pages) { i ->
                val w by animateDpAsState(if (i == shown) 14.dp else 5.dp, MekaMotion.expand(reduced), label = "life-dot-$i")
                Box(Modifier.width(w).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i == shown) Meka.colors.accent else Meka.colors.hairline))
            }
        }
        listOf(-1 to "‹", 1 to "›").forEach { (by, glyph) ->
            val can = AskHubRules.canTurn(page, by, count, columns)
            Box(
                Modifier.size(MekaSpace.touch).clip(RoundedCornerShape(MekaRadius.pill))
                    .then(if (can) Modifier.clickable(role = Role.Button) { haptics.tick(); onTurn(AskHubRules.turn(page, by, count, columns)) } else Modifier)
                    .semantics { contentDescription = if (by < 0) ASK_PREV_PAGE else ASK_NEXT_PAGE },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised),
                    contentAlignment = Alignment.Center) {
                    Text(glyph, style = MekaType.itemMeta, color = if (can) Meka.colors.accent else Meka.colors.textTertiary)
                }
            }
        }
    }
}

/** A faint line between the rows of one card, inset like the rows' words. */
@Composable
internal fun CardHairline() {
    Box(Modifier.padding(horizontal = MekaSpace.m).fillMaxWidth().height(1.dp).background(Meka.colors.hairline))
}

/** Rows drawn in one card, parted by a faint line (Daily, and each Settings box once unfolded). */
@Composable
internal fun RowsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)) { content() }
}

/**
 * A Settings box (the Ask hub): its title, and while folded the names of what's in it on one line ("About you · Work
 * mode · Protected time…"); a tap unfolds its rows on the expand spring, the chevron turning with it (tick haptic).
 * TalkBack reads the title as a heading and the box as Open or Closed. Reduced motion: it opens at once.
 */
@Composable
internal fun SettingsBoxCard(
    box: SettingsBox, rows: List<MoreItem>, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
    row: @Composable (MoreItem) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val turn by animateFloatAsState(if (open) 90f else 0f, MekaMotion.expand(reduced), label = "box-chevron-${box.name}")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(reduced)),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { haptics.tick(); onToggle() }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "Open" else "Closed" }
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(box.title, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
                if (!open) {
                    Text(AskHubRules.peek(rows.map { it.label }), style = MekaType.caption, color = Meka.colors.textTertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("›", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                modifier = Modifier.padding(start = MekaSpace.xs).rotate(if (reduced) (if (open) 90f else 0f) else turn))
        }
        if (open) {
            rows.forEach { item ->
                CardHairline()
                row(item)
            }
        }
    }
}

/** Settings' four boxes as list items; a box with nothing to show (not connected yet) is left out. */
internal fun LazyListScope.settingsBoxes(
    connected: Boolean, open: Set<SettingsBox>, toggle: (SettingsBox) -> Unit, firstStep: Int,
    row: @Composable (MoreItem) -> Unit,
) {
    AskHubRules.BOXES.forEachIndexed { i, box ->
        val rows = ShellNav.settingsRows(box, connected)
        if (rows.isEmpty()) return@forEachIndexed
        item(key = "box-${box.name}") {
            SettingsBoxCard(box, rows, box in open, { toggle(box) },
                Modifier.animateItem().appear(rememberAppearance(firstStep + i)), row)
        }
    }
}

/**
 * One setting's row, the same in Ask's Settings tab and the Settings pane: Appearance unfolds in place, Navilife's AI
 * says how it is (and its month), everything else opens its own pane.
 */
@Composable
internal fun SettingRow(
    item: MoreItem, core: MekaCore, ai: AiStatusView?, pane: MoreItem?,
    appearanceOpen: Boolean, toggleAppearance: () -> Unit, openTopics: () -> Unit, playOpening: () -> Unit,
    open: (MoreItem) -> Unit,
) {
    val lists by core.listsView.collectAsState()
    val work by core.workMode.collectAsState()
    val health by core.healthView.collectAsState()
    val setup by core.setupView.collectAsState()
    when {
        ShellNav.unfoldsInPlace(item) -> AppearanceRow(appearanceOpen, Modifier, openTopics = openTopics, playOpening = playOpening, toggle = toggleAppearance, core = core)
        item == MoreItem.AI -> AiSpendRow(ai, Modifier)
        else -> MoreRow(
            item, ShellNav.moreLine(item, lists.dueCount, work.atWork, health?.summary, setup?.summary),
            ShellNav.moreLit(item, lists.dueCount, health?.attention ?: 0, setup?.toDo ?: 0), pane == item,
        ) { open(item) }
    }
}
