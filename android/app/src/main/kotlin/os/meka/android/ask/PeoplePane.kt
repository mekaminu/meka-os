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
import androidx.compose.ui.platform.testTag
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
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.PersonOwed
import os.meka.core.domain.PersonRow
import os.meka.core.facade.MekaCore

/**
 * Ask → More → People (People, slice 2): one row per person in a promise either way or a birthday in Gifts — the
 * name, "You promised 2 · Waiting for 1", the next birthday and the last contact MEKA has seen ("Last message Tue
 * 13:02 · WhatsApp"). Tap a row to open it: what you promised (each with Kept) and what you're waiting for from them.
 * Promises are made in Lists → Promises and birthdays in Gifts; this pane only gathers them by person.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary cross-fades; rows stagger in
 * 40 ms apart and arrive, move and leave with the list's item motion; an opened row grows on the expand spring and its
 * lists fade in; a lit row's line blends to the accent; Kept gives a light haptic. Reduced motion: cross-fades.
 */
/** People's list, for the by-hand tests' bounded scroll. */
const val PEOPLE_LIST_TAG = "people-list"

@Composable
fun PeoplePane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.peopleView.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().testTag(PEOPLE_LIST_TAG),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("People", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.PEOPLE)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "people-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        itemsIndexed(v.rows, key = { _, r -> "person-${r.key}" }) { i, r ->
            PersonRowView(
                row = r,
                open = open == r.key,
                modifier = Modifier.animateItem().appear(rememberAppearance(2 + i)),
                onToggle = { open = if (open == r.key) null else r.key },
                onKept = { id -> haptics.light(); scope.launch { runCatching { core.keptPromise(id) } } },
            )
        }
        item(key = "note") {
            Text(
                "Promises are made in Lists → Promises, birthdays in Gifts, and Family, Always notify and Watch for requests in Work mode → People (those lists stay on this phone). Last contact comes from the messages Navilife has already seen; nothing new is read.",
                style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l),
            )
        }
    }
}

@Composable
private fun PersonRowView(
    row: PersonRow,
    open: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onKept: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val lineColor by animateColorAsState(
        if (row.lit) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "person-line",
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
            Text(row.name, style = MekaType.body, color = Meka.colors.textPrimary)
            row.listed?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
            row.owedLine?.let { Text(it, style = MekaType.caption, color = lineColor) }
            row.birthday?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
            row.lastContact?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
        AnimatedVisibility(
            visible = open && (row.promised.isNotEmpty() || row.waiting.isNotEmpty()),
            enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                if (row.promised.isNotEmpty()) {
                    SectionLabel("YOU PROMISED")
                    row.promised.forEach { p -> OwedLine(p, keptLabel = "Kept", onKept = { onKept(p.id) }) }
                }
                if (row.waiting.isNotEmpty()) {
                    SectionLabel("WAITING FOR")
                    row.waiting.forEach { w -> OwedLine(w, keptLabel = null, onKept = {}) }
                }
            }
        }
    }
}

@Composable
private fun OwedLine(item: PersonOwed, keptLabel: String?, onKept: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(item.meta, style = MekaType.caption, color = if (item.due) Meka.colors.accent else Meka.colors.textSecondary)
        }
        keptLabel?.let { label ->
            Text(
                label, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button, onClick = onKept)
                    .semantics { contentDescription = "Kept: ${item.title}" }
                    .minTouch().padding(MekaSpace.xs),
            )
        }
    }
}
