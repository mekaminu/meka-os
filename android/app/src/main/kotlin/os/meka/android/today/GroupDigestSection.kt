package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.work.CaptureStore
import os.meka.android.work.DigestGist
import os.meka.core.domain.GroupDigestCard
import os.meka.core.domain.GroupDigestRules
import os.meka.core.domain.GroupDigestView
import os.meka.core.domain.GroupMode
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local midnight and the minute of the day, moved on each minute while Needs you shows. */
private data class DigestClock(val dayStartMs: Long, val minute: Int)

private fun digestClock(): DigestClock {
    val zone = ZoneId.systemDefault()
    val t = LocalTime.now(zone)
    return DigestClock(LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli(), t.hour * 60 + t.minute)
}

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun hhmm(ms: Long) = hm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * Needs you's group digest for this phone's kept chatter, or null when no group has news. Caught up on the Mac counts
 * too (the synced cards' time, slice 4b). When it comes due, the gist is made once for the slot ([DigestGist]).
 */
@Composable
internal fun rememberGroupDigest(store: CaptureStore, core: MekaCore): GroupDigestView? {
    val items by store.digest.collectAsState()
    val settings by store.triageSettings.collectAsState()
    val local by store.digestSeen.collectAsState()
    val synced by core.groupDigestCaughtUp.collectAsState()
    val seen = remember(local, synced) { (local.keys + synced.keys).associateWith { k -> maxOf(local[k] ?: 0L, synced[k] ?: 0L) } }
    val clock by produceState(digestClock()) {
        while (true) { delay(60_000L - System.currentTimeMillis() % 60_000L); value = digestClock() }
    }
    val view = remember(items, settings, seen, clock) {
        GroupDigestRules.view(items, settings, seen, clock.dayStartMs, clock.minute)
    }
    val app = LocalContext.current.applicationContext as MekaApplication
    val due = view?.due == true
    LaunchedEffect(due, clock.minute >= GroupDigestRules.EVENING_MINUTE) { if (due) DigestGist.run(app) }
    return view
}

/**
 * The group digest (V1, messages slice 4): busy groups' chatter that didn't name Meka, kept on this phone with no AI.
 * From 12:30 and 18:30 it sits open at the top of Needs you ("Lunchtime digest · 3 groups · 64 messages", then one card
 * per group, busiest first); in between, one quiet line opens it on demand. A group's card shows who wrote and its
 * latest lines; Show unfolds the thread in place (expand spring) with the group's switch: Digest · Normal · Ignore.
 * Caught up (on the section or one card) folds the cards away until something new comes, with Undo.
 */
@Composable
internal fun GroupDigestSection(
    view: GroupDigestView, store: CaptureStore, core: MekaCore, undo: (String, (suspend () -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable(view.due) { mutableStateOf(view.due) }
    // The AI's gist per group (slice 4b), from the synced cards: a line under the group's title.
    val gists by core.groupGists.collectAsState()
    val gistOf = remember(gists) { gists.mapNotNull { g -> g.gist?.let { g.groupKey to it } }.toMap() }
    val catchUp: (List<GroupDigestCard>) -> Unit = { cards ->
        haptics.light()
        val before = store.digestSeen.value
        val keys = cards.map { it.groupKey }
        store.caughtUp(keys)
        // The synced cards too, so the Mac's digest clears with it.
        val synced = scope.async { runCatching { core.catchUpGroupDigest(keys) }.getOrNull() }
        val what = if (cards.size == 1) cards[0].title else view.title
        undo("$what · ${GroupDigestRules.CAUGHT_UP.lowercase()}") {
            store.restoreDigestSeen(before)
            synced.await()?.let { runCatching { core.undoCatchUpGroupDigest(it) } }
        }
    }
    Column(modifier.fillMaxWidth().testTag("group-digest"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { haptics.tick(); open = !open }
                .semantics(mergeDescendants = true) { contentDescription = view.spoken },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel(view.title)
                Text(view.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            }
            if (open) DigestPill(GroupDigestRules.CAUGHT_UP, spoken = "${GroupDigestRules.CAUGHT_UP} with every group") { catchUp(view.cards) }
            else DigestPill(GroupDigestRules.SHOW, quiet = true, spoken = "Show the group digest") { haptics.tick(); open = true }
        }
        // Opening it (or the digest coming due) unfolds the group cards in place (expand spring; reduced motion: cross-fade).
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                view.cards.forEach { card ->
                    androidx.compose.runtime.key(card.groupKey) {
                        GroupDigestCardView(card, gistOf[card.groupKey], store, onCaughtUp = { catchUp(listOf(card)) }, onMode = { mode ->
                            haptics.tick()
                            val was = store.triageSettings.value.modeOf(card.title)
                            store.setGroupMode(card.title, mode)
                            undo(GroupDigestRules.modeLine(card.title, mode)) { store.setGroupMode(card.title, was) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDigestCardView(card: GroupDigestCard, gist: String?, store: CaptureStore, onCaughtUp: () -> Unit, onMode: (GroupMode) -> Unit) {
    val haptics = rememberMekaHaptics()
    var expanded by rememberSaveable(card.groupKey) { mutableStateOf(false) }
    val items by store.digest.collectAsState()
    val seen by store.digestSeen.collectAsState()
    val settings by store.triageSettings.collectAsState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.m).testTag("digest-${card.groupKey}"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text("${card.title} · ${card.countLine}", style = MekaType.body, color = Meka.colors.textPrimary,
            modifier = Modifier.semantics { contentDescription = card.spoken })
        // MEKA's gist of the chat (slice 4b) fades in under the title once it is made.
        AnimatedVisibility(gist != null, enter = fadeIn(), exit = fadeOut()) {
            Text(gist.orEmpty(), style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.testTag("digest-gist-${card.groupKey}"))
        }
        Text(card.people, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!expanded) {
            card.recent.forEach { line ->
                Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Show unfolds the group's latest messages in place, with its switch under them.
        AnimatedVisibility(expanded, enter = unfold(), exit = fold()) {
            val thread = remember(items, seen, card.groupKey) {
                GroupDigestRules.expanded(items, card.groupKey, seen[card.groupKey] ?: 0L, ::hhmm)
            }
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                thread.earlierLine?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                thread.lines.forEach { l ->
                    Column(Modifier.semantics(mergeDescendants = true) { }) {
                        Text(l.who, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                        Text(l.text, style = MekaType.body, color = Meka.colors.textPrimary)
                    }
                }
                val mode = settings.modeOf(card.title)
                Row(Modifier.padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    GroupMode.entries.forEach { m ->
                        DigestPill(m.label, filled = m == mode, quiet = m != mode, spoken = "${card.title}: ${m.label}") { if (m != mode) onMode(m) }
                    }
                }
                Text(GroupDigestRules.modeHint(mode), style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            }
        }
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            val label = if (expanded) GroupDigestRules.HIDE else GroupDigestRules.SHOW
            DigestPill(label, spoken = "$label ${card.title}'s messages") { haptics.tick(); expanded = !expanded }
            DigestPill(GroupDigestRules.CAUGHT_UP, quiet = true, spoken = "${GroupDigestRules.CAUGHT_UP} with ${card.title}") { onCaughtUp() }
        }
    }
}

@Composable
private fun DigestPill(label: String, spoken: String, filled: Boolean = false, quiet: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta,
        color = when { filled -> Meka.colors.onAccent; quiet -> Meka.colors.textSecondary; else -> Meka.colors.accent },
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
            .background(if (filled) Meka.colors.accent else Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
