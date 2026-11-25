package os.meka.android.ask

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.semantics.selected
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
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.AboutEditSheet
import os.meka.core.domain.AboutField
import os.meka.core.domain.AboutRow
import os.meka.core.domain.AboutSaved
import os.meka.core.domain.AboutYouRules
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val ABOUT_YOU_LIST_TAG = "about-you-list"

/**
 * Ask → Settings → About you (rule 13, slice 1): who's who, home and work, typed once and kept in Meka's own synced data
 * rather than MEKA's code. Each line is a card row showing what's held (or what the line is for) that opens its own
 * sheet to change it (walk-through week 5, A17: no open input boxes on a page); Save keeps it, Clear or a blank clears
 * it, with Undo under the row. The partner's "Watch for requests" switch, then the children (one row each with Remove,
 * and + Add a child, which opens its sheet: "Alex, U7, Oakfield School"). The Mac's About you sheet edits the same
 * profile.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; a row's sheet rises over a fading scrim;
 * the summary, each row's value and the line under it cross-fade (a refusal blends to the accent in the sheet with a
 * tick haptic; a save gives a light haptic); sections and rows stagger in 40 ms apart; children arrive and leave with
 * the list's item motion; the switch's pill blends its colour with a tick haptic. Reduced motion: cross-fades.
 */
@Composable
fun AboutYouPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val v by core.aboutYouView.collectAsState()
    // What the last save on each field said ("children" for the add-a-child field).
    val said = remember { mutableStateMapOf<String, AboutSaved>() }
    // The sheet open (a line's, or + Add a child's) and what its last save refused.
    var open by remember { mutableStateOf<AboutEditSheet?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }

    fun react(key: String, r: AboutSaved) {
        said[key] = r
        if (r.refused) haptics.tick() else haptics.light()
    }

    open?.let { sheet ->
        AboutEditSheetView(sheet, refusal,
            onSave = { typed ->
                scope.launch {
                    val field = sheet.field
                    val r = runCatching { if (field != null) core.setAboutYou(field, typed) else core.addAboutYouChild(typed) }.getOrNull() ?: return@launch
                    if (r.refused) { refusal = r.line; haptics.tick() } // the sheet stays open with the words to fix
                    else { open = null; refusal = null; react(field?.key ?: "children", r) }
                }
            },
            onClose = { open = null; refusal = null })
    }

    LazyColumn(
        Modifier.fillMaxSize().testTag(ABOUT_YOU_LIST_TAG),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text(AboutYouRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.ABOUT_YOU)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "about-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "about-${r.field.key}" }) { i, row ->
            // Each line is its own card, 8 dp apart; 32 dp more before a new group (Your name, Home, Your mobile), so the
            // groups read apart (quality gate slice 26).
            Column(Modifier.animateItem().padding(top = if (row.field == AboutField.SHOWN_NAME || row.field == AboutField.HOME || row.field == AboutField.MOBILE) MekaSpace.xl else MekaSpace.xxs)
                .appear(rememberAppearance(2 + i)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                AboutLine(row, said[row.field.key],
                    onOpen = { refusal = null; open = AboutYouRules.editSheet(row) },
                    onUndo = { back -> scope.launch { runCatching { core.setAboutYou(row.field, back) }.getOrNull()?.let { react(row.field.key, it) } } })
                if (row.field == AboutField.PARTNER_EMAIL) v.watchLine?.let { line ->
                    WatchRow(v.partnerWatch, line) {
                        haptics.tick()
                        scope.launch { runCatching { core.setAboutYouPartnerWatch(!v.partnerWatch) } }
                    }
                }
                // "Handle her requests for me" (2026-11-11): on by default, shown while her messages are watched.
                if (row.field == AboutField.PARTNER_EMAIL) v.handleLine?.let { line ->
                    WatchRow(v.partnerHandle, line, title = v.handleTitle) {
                        haptics.tick()
                        scope.launch { runCatching { core.setAboutYouPartnerHandle(!v.partnerHandle) } }
                    }
                }
            }
        }
        item(key = "children-label") {
            SectionLabel("Children", Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(2 + v.rows.size)))
        }
        v.childrenEmpty?.let { empty ->
            item(key = "children-empty") { Text(empty, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem()) }
        }
        itemsIndexed(v.children, key = { _, c -> "child-${c.index}-${c.name}" }) { i, c ->
            Row(
                Modifier.animateItem().appear(rememberAppearance(3 + v.rows.size + i)).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                    .background(Meka.colors.surface).minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = c.spoken },
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(c.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
                Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { scope.launch { runCatching { core.removeAboutYouChild(c.index) }.getOrNull()?.let { react("children", it) } } }
                        .semantics { contentDescription = "Remove ${c.name}" }
                        // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
                        .minTouch().padding(horizontal = MekaSpace.xs))
            }
        }
        item(key = "children-add") {
            val line = said["children"]
            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(AboutYouRules.ADD_CHILD, style = MekaType.metaStrong, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { refusal = null; open = AboutYouRules.childSheet() }
                        .semantics { contentDescription = v.childDescription }
                        .minTouch().padding(horizontal = MekaSpace.xs))
                SaidLine(line) { back -> scope.launch { runCatching { core.addAboutYouChild(back) }.getOrNull()?.let { react("children", it) } } }
            }
        }
    }
}

/**
 * One About you line: a card row with its label, what's held (or what the line is for) and ›, opening its sheet; the
 * line under it says what the last save did, with Undo after a clear.
 */
@Composable
private fun AboutLine(row: AboutRow, said: AboutSaved?, onOpen: () -> Unit, onUndo: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = AboutYouRules.rowSpoken(row) }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.label, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            Crossfade(row.value, animationSpec = MekaMotion.appear(reduced), label = "about-value") { value ->
                if (value != null) Text(value, style = MekaType.body, color = Meka.colors.textPrimary)
                else row.line?.let { Text(os.meka.core.domain.LoneWordGlue.kept(it), style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
        }
        Text("›", style = MekaType.body, color = Meka.colors.textTertiary)
    }
    if (said != null) SaidLine(said, onUndo)
}

/** What the last save said, lit in the accent when refused, with Undo after a clear or a removal. */
@Composable
private fun SaidLine(said: AboutSaved?, onUndo: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    val color by animateColorAsState(if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "about-said")
    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "about-line") { s ->
        if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            Text(s.line, style = MekaType.caption, color = color, modifier = Modifier.weight(1f, fill = false))
            s.undo?.let { back ->
                Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onUndo(back) }.minTouch())
            }
        }
    }
}

/** "Watch for requests": On/Off in a pill that blends to the accent, with what it means under it. */
@Composable
private fun WatchRow(on: Boolean, line: String, title: String = "Watch for requests", onToggle: () -> Unit) {
    val dot by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(Meka.reducedMotion), label = "about-watch")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch) { onToggle() }
            .semantics { contentDescription = title; selected = on }.minTouch().padding(vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MekaType.itemMeta, color = Meka.colors.textPrimary)
            Text(os.meka.core.domain.LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        os.meka.android.designsystem.SwitchPill(on, dot)
    }
}
