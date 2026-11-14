package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import os.meka.android.lists.Field
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.ReadingGoalRules
import os.meka.core.domain.ReadingPageRules
import os.meka.core.domain.ReadingRules
import os.meka.core.domain.ReadingSaid
import os.meka.core.facade.MekaCore

/** The pane's own list, for the hand tests' bounded scrolls (quality gate (3)). */
const val READING_LIST_TAG = "reading-list"
const val READING_ROW_SHEET_TAG = "reading-row-sheet"
const val READING_ALL_SHEET_TAG = "reading-all-sheet"

/**
 * Ask → Daily → Reading in the page shape (every page under Ask in one shape, slice 6; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A book, podcast or course · A reading
 * goal, each a field in the sheet), three numbers (on the go, up next, the year's goal or what finished lately), then
 * On the go (how far on the right), Up next, Finished lately and Reading goal. A book's row opens its sheet: what it
 * is and how far, its bar, the how-far field while on the go, Start / Finished / Put down / Again / Pick up (the
 * sheet stays on it as it moves, with Undo) and Remove; the goal's row the books a year and Clear the goal.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); the answer line cross-fades (lit in the accent when refused, with a tick haptic; a save, Start or
 * Finished a light haptic); a bar grows on the expand spring to its new length. Reduced motion: cross-fades, the bar jumps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.readingView.collectAsState()
    val page = remember(v) { core.readingPage(v) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<ReadingSaid?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "reading-said",
    )
    fun act(light: Boolean = false, after: (ReadingSaid) -> Unit = {}, block: suspend () -> ReadingSaid) {
        scope.launch {
            val r = runCatching { block() }.getOrNull() ?: return@launch
            said = r
            if (r.refused) haptics.tick() else if (light) haptics.light() else haptics.tick()
            after(r)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(READING_LIST_TAG),
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
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.READING)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = null; said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "reading-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Crossfade(v.month, animationSpec = MekaMotion.appear(reduced), label = "reading-month") { m ->
                        m?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                    }
                    // The last thing done from + Add or a sheet, with its Undo, while no sheet is open.
                    if (!addOpen && rowKey == null) SaidLine(said, saidColor) { token -> undo(core, token, scope) { said = it } }
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allCard = c.key },
                        onRow = { r -> haptics.tick(); said = null; rowKey = r.key },
                    )
                }
            }
            item(key = "note") {
                Text(ReadingRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: a book, podcast or course typed once, or the year's goal.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(ReadingPageRules.hint(choice)) { typed ->
                    act(light = true, after = { if (!it.refused) addOpen = false }) {
                        if (choice == ReadingPageRules.ADD_GOAL) core.setReadingGoal(typed) else core.addReading(typed)
                    }
                }
                said?.takeIf { it.refused }?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
            }
        }
        // See all: every row of one card, a tap opens its sheet.
        val all = allCard
        LifeSheet(all != null, all ?: ReadingPageRules.TITLE, READING_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) ReadingPageRules.allRows(v, all).forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allCard = null; said = null; rowKey = r.key }
                        .semantics(mergeDescendants = true) {}
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(listOf(r.line, r.whenText).filter { it.isNotEmpty() }.joinToString(" · "), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A row's sheet: a book, podcast or course wherever it has moved to, or the reading goal.
        val key = rowKey
        val item = key?.let { ReadingPageRules.item(v, it) }
        val isGoal = key == ReadingPageRules.KEY_GOAL
        LifeSheet(key != null, item?.title ?: if (isGoal) ReadingPageRules.GOAL else ReadingPageRules.TITLE, READING_ROW_SHEET_TAG,
            onClose = { rowKey = null }) {
            when {
                item != null -> {
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = item.spoken },
                        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Text(item.meta, style = MekaType.caption, color = Meka.colors.textSecondary)
                        Crossfade(item.progress, animationSpec = MekaMotion.appear(reduced), label = "reading-progress") { p ->
                            Text(p, style = MekaType.caption, color = Meka.colors.textSecondary)
                        }
                    }
                    ProgressBar(item.fraction, Meka.colors.accent)
                    if (ReadingPageRules.takesProgress(item)) {
                        var typed by remember(item.id) { mutableStateOf("") }
                        Field(ReadingRules.PROGRESS_HINT, value = typed, onValue = { typed = it }) { t ->
                            act(light = true, after = { if (!it.refused) typed = "" }) { core.readingProgress(item.id, t) }
                        }
                        Text(ReadingPageRules.PROGRESS_NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        ReadingPageRules.acts(item).forEach { a ->
                            SheetLink(a.label, Meka.colors.accent, "${a.label}: ${item.title}") {
                                when (a.key) {
                                    ReadingPageRules.ACT_FINISH -> act(light = true) { core.finishReading(item.id) }
                                    ReadingPageRules.ACT_DOWN -> act { core.putDownReading(item.id) }
                                    else -> act(light = true) { core.startReading(item.id) }
                                }
                            }
                        }
                        SheetLink("Remove", Meka.colors.textTertiary, "Remove ${item.title}") {
                            rowKey = null
                            act { core.removeReading(item.id) }
                        }
                    }
                    SaidLine(said, saidColor) { token -> undo(core, token, scope) { said = it } }
                }
                isGoal -> {
                    v.goal?.let { g ->
                        val lineColor by animateColorAsState(if (g.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "goal-line")
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = g.spoken },
                            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(g.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                                Text(g.count, style = MekaType.caption, color = Meka.colors.textSecondary)
                            }
                            Crossfade(g.line, animationSpec = MekaMotion.appear(reduced), label = "goal-pace") { line ->
                                Text(line, style = MekaType.caption, color = lineColor)
                            }
                        }
                        ProgressBar(g.fraction, Meka.colors.accent)
                    }
                    Field(ReadingGoalRules.HINT) { t -> act(light = true) { core.setReadingGoal(t) } }
                    Text(ReadingGoalRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
                    if (v.goalTarget > 0) {
                        SheetLink("Clear the goal", Meka.colors.textTertiary, "Clear the reading goal") { act { core.setReadingGoal("none") } }
                    }
                    said?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                }
                key != null -> Text(ReadingPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
    }
}

private fun undo(core: MekaCore, token: String, scope: kotlinx.coroutines.CoroutineScope, onSaid: (ReadingSaid) -> Unit) {
    scope.launch {
        val ok = runCatching { core.undoReading(token) }.getOrDefault(false)
        onSaid(ReadingSaid(if (ok) "Taken back" else "Too late to undo · change it by hand", !ok))
    }
}

/** What the last command said, with Undo when it can be taken back. */
@Composable
private fun SaidLine(said: ReadingSaid?, color: Color, onUndo: (String) -> Unit) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "reading-line") { s ->
        if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            Text(s.line, style = MekaType.caption, color = color, modifier = Modifier.weight(1f, fill = false))
            // Only the line being shown offers Undo: the one fading out (a command earlier) can't be tapped or found,
            // so a quick Undo always takes back the latest command, never the one before it.
            s.undo?.takeIf { s == said }?.let { token ->
                Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { haptics.tick(); onUndo(token) }
                        .minTouch().semantics { contentDescription = "Undo: ${s.line}" })
            }
        }
    }
}

@Composable
private fun SheetLink(label: String, color: Color, description: String, onClick: () -> Unit) {
    Text(label, style = MekaType.caption, color = color,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            // Works by hand (quality gate (3)): a 48 dp touch, the word drawn as before.
            .minTouch().padding(horizontal = MekaSpace.xs))
}

/** How far, in brass; it fades in and out with a fraction and grows on the expand spring. */
@Composable
private fun ProgressBar(fraction: Float?, fill: Color) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(fraction != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
        val shown by animateFloatAsState(fraction ?: 0f, MekaMotion.expand(reduced), label = "reading-bar")
        Box(Modifier.padding(vertical = MekaSpace.xxs).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).clip(RoundedCornerShape(MekaRadius.pill)).background(fill))
        }
    }
}
