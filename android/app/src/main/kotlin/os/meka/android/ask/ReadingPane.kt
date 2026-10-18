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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
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
import os.meka.android.today.SectionLabel
import os.meka.core.domain.ReadingRow
import os.meka.core.domain.ReadingRules
import os.meka.core.domain.ReadingSaid
import os.meka.core.facade.MekaCore

/**
 * Ask → Daily → Reading (reading and learning, slice 1): one field adds a book, podcast or course; ON THE GO lists
 * the ones started with how far (a brass bar) and a field for how far he's got, with Finished and Put down; UP NEXT
 * with Start; FINISHED LATELY and PUT DOWN with Pick up again; Remove on every row; each answer with Undo.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary, the month line and the
 * answer line cross-fade (the answer blends to the accent when refused, with a tick haptic; a save gives a light
 * haptic); rows stagger in 40 ms apart and move between sections with the list's item motion; a bar's fill grows on
 * the expand spring to its new length. Reduced motion: cross-fades, the bar jumps.
 */
@Composable
fun ReadingPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.readingView.collectAsState()
    var text by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<ReadingSaid?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "reading-said",
    )
    fun act(block: suspend () -> ReadingSaid, light: Boolean = false) {
        scope.launch {
            val r = runCatching { block() }.getOrNull() ?: return@launch
            said = r
            if (r.refused) haptics.tick() else if (light) haptics.light() else haptics.tick()
        }
    }

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
            Text(ReadingRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.READING)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "reading-summary") { s ->
                    Text(s, style = MekaType.body, color = Meka.colors.textSecondary)
                }
                Crossfade(v.month, animationSpec = MekaMotion.appear(reduced), label = "reading-month") { m ->
                    m?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                }
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(ReadingRules.HINT, value = text, onValue = { text = it }) { typed ->
                    scope.launch {
                        val r = runCatching { core.addReading(typed) }.getOrNull() ?: return@launch
                        said = r
                        if (r.refused) { haptics.tick(); text = typed } else haptics.light()
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "reading-line") { s ->
                    if (s != null) Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.line, style = MekaType.caption, color = saidColor, modifier = Modifier.weight(1f, fill = false))
                        s.undo?.let { token ->
                            Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                    haptics.tick()
                                    scope.launch {
                                        val ok = runCatching { core.undoReading(token) }.getOrDefault(false)
                                        said = ReadingSaid(if (ok) "Taken back" else "Too late to undo · change it by hand", !ok)
                                    }
                                }.minTouch().semantics { contentDescription = "Undo: ${s.line}" })
                        }
                    }
                }
            }
        }
        section("go", "On the go", v.onTheGo, 3) { i, r ->
            ReadingCard(
                r, Modifier.animateItem().appear(rememberAppearance(4 + i)),
                actions = listOf("Finished" to { act({ core.finishReading(r.id) }, light = true) }, "Put down" to { act({ core.putDownReading(r.id) }) }),
                onProgress = { typed -> act({ core.readingProgress(r.id, typed) }, light = true) },
                onRemove = { act({ core.removeReading(r.id) }) },
            )
        }
        section("next", "Up next", v.upNext, 4 + v.onTheGo.size) { i, r ->
            ReadingCard(
                r, Modifier.animateItem().appear(rememberAppearance(5 + v.onTheGo.size + i)),
                actions = listOf("Start" to { act({ core.startReading(r.id) }, light = true) }),
                onProgress = null,
                onRemove = { act({ core.removeReading(r.id) }) },
            )
        }
        section("finished", "Finished lately", v.finished, 5 + v.onTheGo.size + v.upNext.size) { i, r ->
            ReadingCard(
                r, Modifier.animateItem(),
                actions = listOf("Again" to { act({ core.startReading(r.id) }) }),
                onProgress = null,
                onRemove = { act({ core.removeReading(r.id) }) },
            )
        }
        section("down", "Put down", v.putDown, 6 + v.onTheGo.size + v.upNext.size) { i, r ->
            ReadingCard(
                r, Modifier.animateItem(),
                actions = listOf("Pick up" to { act({ core.startReading(r.id) }) }),
                onProgress = null,
                onRemove = { act({ core.removeReading(r.id) }) },
            )
        }
        item(key = "note") {
            Text(ReadingRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    key: String, label: String, rows: List<ReadingRow>, step: Int,
    row: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int, ReadingRow) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "label-$key") {
        SectionLabel(label, Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(step)))
    }
    itemsIndexed(rows, key = { _, r -> "r-${r.id}" }) { i, r -> row(i, r) }
}

@Composable
private fun ReadingCard(
    row: ReadingRow,
    modifier: Modifier,
    actions: List<Pair<String, () -> Unit>>,
    onProgress: ((String) -> Unit)?,
    onRemove: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val ended = row.state == "finished" || row.state == "put_down"
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.title, style = MekaType.body, color = if (ended) Meka.colors.textSecondary else Meka.colors.textPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.meta, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.progress, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val f = row.fraction
        AnimatedVisibility(f != null, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
            val shown by animateFloatAsState(f ?: 0f, MekaMotion.expand(reduced), label = "reading-bar")
            Box(Modifier.padding(vertical = MekaSpace.xxs).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
            }
        }
        if (onProgress != null) {
            var typed by remember(row.id) { mutableStateOf("") }
            Field(ReadingRules.PROGRESS_HINT, value = typed, onValue = { typed = it }) { onProgress(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { (label, go) ->
                Text(label, style = MekaType.caption, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = go)
                        .minTouch().semantics { contentDescription = "$label: ${row.title}" }.padding(MekaSpace.xs))
            }
            Text("Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onRemove)
                    .minTouch().semantics { contentDescription = "Remove ${row.title}" }.padding(MekaSpace.xs))
        }
    }
}
