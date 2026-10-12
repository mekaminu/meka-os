package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import os.meka.core.domain.GiftAdded
import os.meka.core.domain.GiftRow
import os.meka.core.domain.GiftRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Gifts (gift planner, slice 1): each birthday or anniversary typed once — "Mum's birthday 3 Nov",
 * "Wedding anniversary 20 Aug 2015", "… delivery 5 days" — a row per occasion, soonest first, with its day, age and
 * order-by day (lit within two weeks while the gift isn't sorted). Tap a row to open it: its gift ideas (each with ×),
 * a field to note another, Gift sorted / Not sorted yet, and Remove. Two weeks ahead and on the order-by day a heads-up
 * comes at 09:00; MEKA never orders anything.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the line under the field
 * cross-fade (a refused line lit in the accent); rows stagger in 40 ms apart and arrive, move and leave with the list's
 * item motion; an opened row grows on the expand spring and its ideas fade in; the order-by note's colour blends to the
 * accent; Add and Gift sorted give a light haptic (a tick when refused), Remove and × a tick. Reduced motion: cross-fades.
 */
@Composable
fun GiftsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.giftsView.collectAsState()
    var said by remember { mutableStateOf<GiftAdded?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "gifts-said",
    )

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
            Text("Gifts", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.GIFTS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "gifts-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(GiftRules.ADD_HINT) { text ->
                    scope.launch {
                        val r = runCatching { core.addOccasion(text) }.getOrNull()
                        if (r?.refused == false) haptics.light() else haptics.tick()
                        said = r
                    }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "gifts-added") { r ->
                    r?.let { Text(it.line, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        if (v.rows.isNotEmpty()) {
            item(key = "label") {
                SectionLabel("BIRTHDAYS AND ANNIVERSARIES", Modifier.animateItem().padding(top = MekaSpace.m).appear(rememberAppearance(3)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "gift-${r.id}" }) { i, r ->
            GiftRowView(
                row = r,
                open = open == r.id,
                modifier = Modifier.animateItem().appear(rememberAppearance(4 + i)),
                onToggle = { open = if (open == r.id) null else r.id },
                onIdea = { text ->
                    scope.launch {
                        if (runCatching { core.addGiftIdea(r.id, text) }.getOrDefault(false)) haptics.light() else haptics.tick()
                    }
                },
                onRemoveIdea = { id -> haptics.tick(); scope.launch { runCatching { core.removeGiftIdea(id) } } },
                onSorted = { sorted -> haptics.light(); scope.launch { runCatching { core.setGiftSorted(r.id, sorted) } } },
                onRemove = { haptics.tick(); open = null; scope.launch { runCatching { core.removeOccasion(r.id) } } },
            )
        }
        item(key = "note") {
            Text(GiftRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun GiftRowView(
    row: GiftRow,
    open: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onIdea: (String) -> Unit,
    onRemoveIdea: (String) -> Unit,
    onSorted: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val noteColor by animateColorAsState(
        if (row.noteLit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "gift-note",
    )
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .animateContentSize(MekaMotion.expand(reduced))
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle).minTouch()
                .semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(row.note, style = MekaType.caption, color = noteColor)
            if (!open && row.ideas.isNotEmpty()) {
                Text("Ideas: " + row.ideas.joinToString(", ") { it.text }, style = MekaType.caption,
                    color = Meka.colors.textTertiary, maxLines = 1)
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SectionLabel("GIFT IDEAS")
                if (row.ideas.isEmpty()) {
                    Text("None yet", style = MekaType.caption, color = Meka.colors.textTertiary)
                }
                for (idea in row.ideas) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        Text(idea.text, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                        Text("×", style = MekaType.body, color = Meka.colors.textTertiary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) { onRemoveIdea(idea.id) }
                                .semantics { contentDescription = "Remove the idea ${idea.text}" }
                                .minTouch().padding(MekaSpace.xs))
                    }
                }
                Field(GiftRules.IDEA_HINT) { text -> onIdea(text) }
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (row.sorted) "Not sorted yet" else "Gift sorted", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button) { onSorted(!row.sorted) }.minTouch().padding(MekaSpace.xs),
                    )
                    Text(
                        "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                            .clickable(role = Role.Button, onClick = onRemove)
                            .semantics { contentDescription = "Remove ${row.title}" }
                            .minTouch().padding(MekaSpace.xs),
                    )
                }
            }
        }
    }
}
