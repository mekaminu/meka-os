package os.meka.android.ask

import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import os.meka.android.capture.VoiceNoteStore
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
import os.meka.core.domain.VoiceNote
import os.meka.core.domain.VoiceNoteRow
import os.meka.core.domain.VoiceNoteRules
import os.meka.core.facade.HandleOutcome
import os.meka.core.facade.MekaCore
import java.time.Instant
import java.time.ZoneId

/**
 * Ask → Daily → Voice notes (voice notes into actions, slice 1; the Fold only): the notes this phone keeps, newest
 * first — when, how long, the words (three lines; tap for all), ▶ Play / ■ Stop, Keep (past 30 days) or Let it go,
 * Make cards (the words through Handle this, when it hasn't answered yet) and Delete with Undo. Everything here stays on
 * the phone; only Make cards sends the words.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; rows stagger in 40 ms apart and leave with
 * the list's item motion; a note's words unfold on the expand spring; Play's label and the lines cross-fade. Reduced
 * motion: cross-fades.
 */
@Composable
fun VoiceNotesPane(core: MekaCore, onClose: () -> Unit, closeLabel: String = "Close") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val notes by VoiceNoteStore.notes.collectAsState()
    var playing by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<VoiceNote?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        VoiceNoteStore.sweep(context, System.currentTimeMillis())
        VoiceNoteStore.load(context)
    }

    fun stopPlaying() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
        VoiceNoteStore.clearPlayback(context)
    }

    DisposableEffect(Unit) { onDispose { stopPlaying() } }

    fun play(id: String) {
        if (playing == id) { haptics.tick(); stopPlaying(); return }
        stopPlaying()
        haptics.light()
        scope.launch {
            val f = VoiceNoteStore.playable(context, id)
            val p = f?.let { file ->
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(file.path)
                        setOnCompletionListener { stopPlaying() }
                        prepare()
                        start()
                    }
                }.getOrNull()
            }
            if (p == null) { line = VoiceNoteRules.PLAY_FAILED; haptics.reject(); return@launch }
            player = p
            playing = id
        }
    }

    fun makeCards(note: VoiceNote) {
        if (making != null) return
        haptics.light()
        making = note.id
        scope.launch {
            val minute = Instant.ofEpochMilli(note.atMs).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute }
            val out = runCatching { core.handleThis(note.transcript, VoiceNoteRules.subject(minute)) }.getOrNull()
            val count = when (out) {
                is HandleOutcome.Cards -> { line = out.line + " · in Needs you"; out.cards.size }
                is HandleOutcome.NothingToDo -> { line = out.line; 0 }
                is HandleOutcome.Unavailable -> { line = out.line; null }
                null -> null
            }
            if (count != null) VoiceNoteStore.update(context, note.id) { it.copy(cards = count) }
            making = null
        }
    }

    fun delete(note: VoiceNote) {
        haptics.tick()
        if (playing == note.id) stopPlaying()
        scope.launch {
            val gone = VoiceNoteStore.remove(context, note.id) ?: return@launch
            undo = gone
            line = "Deleted the note from " + rowFor(gone).title.substringBefore(" · ")
        }
    }

    val list = notes.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text(closeLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text(VoiceNoteRules.TITLE, style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.VOICE_NOTES)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(if (notes == null) "" else VoiceNoteRules.summary(list), animationSpec = MekaMotion.appear(reduced), label = "voice-notes-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "line") {
            Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "voice-notes-line") { l ->
                if (l != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(l, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite })
                    val u = undo
                    if (u != null) {
                        Text("Undo", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                haptics.tick()
                                scope.launch { VoiceNoteStore.restore(context, u); undo = null; line = "Put back" }
                            }.minTouch().padding(horizontal = MekaSpace.xs))
                    }
                }
            }
        }
        itemsIndexed(list, key = { _, n -> "vn-${n.id}" }) { i, n ->
            val row = rowFor(n)
            NoteRow(
                row, Modifier.animateItem().appear(rememberAppearance(2 + i)),
                expanded = open == n.id,
                playing = playing == n.id,
                canPlay = VoiceNoteStore.hasRecording(context, n.id),
                making = making == n.id,
                onExpand = { haptics.tick(); open = if (open == n.id) null else n.id },
                onPlay = { play(n.id) },
                onKeep = {
                    haptics.tick()
                    scope.launch { VoiceNoteStore.update(context, n.id) { it.copy(kept = !it.kept) } }
                },
                onMakeCards = { makeCards(n) },
                onDelete = { delete(n) },
            )
        }
        item(key = "note") {
            Text(VoiceNoteRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

/** The pane's row for [note] in the phone's time zone. */
private fun rowFor(note: VoiceNote): VoiceNoteRow {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(note.atMs).atZone(zone)
    val today = java.time.LocalDate.now(zone).toEpochDay()
    val expiry = VoiceNoteRules.expiresAtMs(note)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
    return VoiceNoteRules.row(note, at.toLocalDate().toEpochDay(), at.hour * 60 + at.minute, expiry, today)
}

@Composable
private fun NoteRow(
    row: VoiceNoteRow,
    modifier: Modifier,
    expanded: Boolean,
    playing: Boolean,
    canPlay: Boolean,
    making: Boolean,
    onExpand: () -> Unit,
    onPlay: () -> Unit,
    onKeep: () -> Unit,
    onMakeCards: () -> Unit,
    onDelete: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onExpand)
                .semantics(mergeDescendants = true) { contentDescription = row.title + ". " + row.text },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(row.title, style = MekaType.caption, color = Meka.colors.textSecondary)
            Text(
                row.text, style = MekaType.body, color = if (row.heard) Meka.colors.textPrimary else Meka.colors.textSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            )
            row.cardsLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
        AnimatedVisibility(
            expanded,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Text(row.keepLine, style = MekaType.caption, color = if (row.kept) Meka.colors.accent else Meka.colors.textTertiary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            if (canPlay) {
                Crossfade(playing, animationSpec = MekaMotion.appear(reduced), label = "voice-note-play") { p ->
                    Action(if (p) VoiceNoteRules.STOP_PLAYING else VoiceNoteRules.PLAY, accent = true, spoken = (if (p) "Stop " else "Play ") + row.title, onClick = onPlay)
                }
            }
            if (row.canMakeCards) {
                Action(if (making) "Reading it…" else "✦ " + VoiceNoteRules.MAKE_CARDS, accent = true, spoken = "Make cards from the note of " + row.title, onClick = onMakeCards)
            }
            Action(if (row.kept) "Kept" else VoiceNoteRules.KEEP, accent = row.kept,
                spoken = if (row.kept) VoiceNoteRules.UNKEEP else "Keep this note past 30 days", onClick = onKeep)
            Action(VoiceNoteRules.DELETE, accent = false, spoken = "Delete the note of " + row.title, onClick = onDelete)
        }
    }
}

@Composable
private fun Action(label: String, accent: Boolean, spoken: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = if (accent) Meka.colors.accent else Meka.colors.textTertiary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .minTouch().padding(horizontal = MekaSpace.xs),
    )
}
