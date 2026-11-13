package os.meka.android.ask

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import os.meka.android.lists.Field
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.work.SettingChip
import os.meka.core.domain.BinAdded
import os.meka.core.domain.BinCouncilRules
import os.meka.core.domain.BinRules
import os.meka.core.domain.BinsPageRules
import os.meka.core.domain.LocalClock
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val BINS_LIST_TAG = "bins-list"
const val BINS_ROW_SHEET_TAG = "bins-row-sheet"
const val BINS_ALL_SHEET_TAG = "bins-all-sheet"

/**
 * Ask → Life → Bins in the page shape (every page under Ask in one shape, slice 5; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A bin · A one-off, each a field in the
 * sheet: "Food caddy every Tuesday", "Black bin Tue fortnightly from 13 Oct", "No black bin 29 Dec"; The council's
 * dates opens Android's file picker for the bin calendar (.ics) downloaded from the council's bin-day page, read here,
 * nothing sent), three numbers (the next collection, the bins, the one-offs to come), then Your bins (soonest first,
 * brass for tonight's), One-offs and Reminder and dates. A bin's row opens its sheet (pattern, next collection,
 * Remove); a one-off's its Remove (the collection goes on its usual day again); Reminder its times (17:00 to 21:00,
 * synced so the Mac reminds at the same time); Council's dates Use the council's dates / Use my own days. The evening
 * before, Today says "Black bin and food caddy out tonight" and a heads-up comes at the reminder's time.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the added line cross-fades over the summary (a refused line lit in the accent, in the sheet); an add
 * gives a light haptic (a tick when refused), Remove and a time a tick. Reduced motion: cross-fades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BinsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.binsView.collectAsState()
    val page = remember(v) { core.binsPage(v) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allOpen by remember { mutableStateOf(false) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<BinAdded?>(null) }
    val context = LocalContext.current
    val pickCalendar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { s ->
                        s.readBytes().takeIf { it.size <= BinCouncilRules.MAX_CHARS * 2 }?.decodeToString()
                    }
                }.getOrNull()
            }
            val r = if (text == null) BinAdded(BinCouncilRules.TOO_BIG, refused = true)
            else runCatching { core.useCouncilBins(text) }.getOrNull()?.let { BinAdded(it.line, it.refused) }
            if (r?.refused == false) haptics.light() else haptics.tick()
            said = r
        }
    }
    fun openCouncilPicker() = pickCalendar.launch(arrayOf("text/calendar", "text/x-vcalendar", "application/ics", "application/octet-stream", "text/plain"))
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "bins-said",
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(BINS_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                LifePageHeader(
                    page.title, page.info, infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.BINS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = null; said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                // The last thing done (added, a one-off, the council's dates) cross-fades over the next collection.
                val line = said?.takeIf { !it.refused && !addOpen }?.line ?: v.summary
                Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "bins-summary") { s ->
                    Text(s, style = MekaType.caption, color = Meka.colors.textSecondary,
                        modifier = Modifier.appear(rememberAppearance(1)).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allOpen = true },
                        onRow = { r -> haptics.tick(); said = null; rowKey = r.key },
                    )
                }
            }
        }
        // + Add: a bin or a one-off typed once, or the council's own calendar file.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c ->
                haptics.tick(); said = null
                if (BinsPageRules.takesText(c.key)) addChosen = c.key
                else { addOpen = false; openCouncilPicker() }
            }
            addChosen?.let { choice ->
                Field(BinsPageRules.hint(choice), description = BinRules.ADD_DESCRIPTION) { text ->
                    scope.launch {
                        val r = runCatching { core.addBin(text) }.getOrNull()
                        if (r?.refused == false) { haptics.light(); said = r; addOpen = false }
                        else { haptics.tick(); said = r ?: BinAdded(BinRules.NOT_READ, true) }
                    }
                }
                said?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every bin, a tap opens its sheet.
        LifeSheet(allOpen, BinsPageRules.YOURS, BINS_ALL_SHEET_TAG, onClose = { allOpen = false }) {
            v.rows.forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allOpen = false; rowKey = BinsPageRules.binKey(r.id) }
                        .semantics(mergeDescendants = true) { contentDescription = r.spoken }
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A row's sheet: a bin, a one-off, the reminder's times or the council's dates.
        val key = rowKey
        val bin = key?.let { BinsPageRules.bin(v, it) }
        val oneOff = key?.let { BinsPageRules.oneOff(v, it) }
        val sheetTitle = when {
            bin != null -> bin.name
            oneOff != null -> oneOff.name
            key == BinsPageRules.KEY_REMIND -> BinsPageRules.REMINDER
            key == BinsPageRules.KEY_COUNCIL -> BinsPageRules.COUNCIL
            else -> BinsPageRules.TITLE
        }
        val sheetOpen = bin != null || oneOff != null || key == BinsPageRules.KEY_REMIND || key == BinsPageRules.KEY_COUNCIL
        LifeSheet(sheetOpen, sheetTitle, BINS_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            when {
                bin != null -> {
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = bin.spoken },
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text(bin.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                        bin.note?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                    }
                    Text("To change its day, + Add it again with the same name.", style = MekaType.caption, color = Meka.colors.textTertiary)
                    RemoveLink("Remove ${bin.name}") {
                        haptics.tick(); rowKey = null
                        scope.launch { runCatching { core.removeBin(bin.id) } }
                    }
                }
                oneOff != null -> {
                    Text(oneOff.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    Text("Remove it and the collection goes on its usual day again.", style = MekaType.caption, color = Meka.colors.textTertiary)
                    RemoveLink("Remove the one-off for ${oneOff.name}, ${oneOff.line}") {
                        haptics.tick(); rowKey = null
                        scope.launch { runCatching { core.removeBinMove(oneOff.id) } }
                    }
                }
                key == BinsPageRules.KEY_REMIND -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        v.remindChoices.forEach { m ->
                            val label = LocalClock.formatMinute(m)
                            SettingChip(label, m == v.remindMinute, "Bins reminder at $label") {
                                haptics.tick()
                                scope.launch {
                                    runCatching { core.setBinReminder(m) }.getOrNull()?.let { said = BinAdded(it, refused = false) }
                                }
                            }
                        }
                    }
                    Crossfade(v.remindLine, animationSpec = MekaMotion.appear(reduced), label = "bins-remind") { line ->
                        Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                }
                key == BinsPageRules.KEY_COUNCIL -> {
                    v.councilLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                    Text(v.footLine, style = MekaType.caption, color = Meka.colors.textTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        SettingChip(if (v.councilLine == null) "Use the council's dates" else "Pick a newer calendar", false,
                            "Use the council's dates: pick the bin calendar file from your council") {
                            haptics.tick(); rowKey = null
                            openCouncilPicker()
                        }
                        if (v.councilLine != null) {
                            SettingChip("Use my own days", false, "Use the bin days you typed instead of the council's") {
                                haptics.tick()
                                scope.launch {
                                    runCatching { core.useTypedBins() }.getOrNull()?.let { said = BinAdded(it.line, it.refused) }
                                }
                            }
                        }
                    }
                    said?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                }
            }
        }
    }
}

@Composable
private fun RemoveLink(label: String, onRemove: () -> Unit) {
    Text(
        "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = label }
            // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
            .minTouch().padding(horizontal = MekaSpace.xs),
    )
}
