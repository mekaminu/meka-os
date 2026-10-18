package os.meka.android.ask

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import os.meka.core.domain.QueueAdded
import os.meka.core.domain.QueueRow
import os.meka.core.domain.QueueRules
import os.meka.core.domain.ReaderPage
import os.meka.core.domain.ReaderRules
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Train queue (V1, train-time queue slice 1), also opened from Today's "For the train" line: links and
 * podcasts shared to MEKA (the share sheet's For the train) or pasted here, newest first. Tap one to open it in its
 * app or the browser; Done when read or listened to (it moves to "Done lately", with Back), Remove for good.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary and the field's line
 * cross-fade (the line blends to the accent when refused, with a tick haptic; a save gives a light haptic); rows
 * stagger in 40 ms apart and move between Waiting and Done lately with the list's item motion. Reduced motion:
 * cross-fades.
 */
@Composable
fun TrainQueuePane(core: MekaCore, onClose: () -> Unit, closeLabel: String = "Close") {
    val reduced = Meka.reducedMotion
    // Slice 2: an article whose words this phone saved opens in the reader, pushed in from the right like a place in
    // More (an eighth of the width, with a fade); back slides it away. Reduced motion: cross-fades.
    var reading by remember { mutableStateOf<String?>(null) }
    AnimatedContent(
        targetState = reading,
        transitionSpec = {
            if (reduced) fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
            else {
                val forward = targetState != null
                (slideInHorizontally(MekaMotion.replan(false)) { w -> if (forward) w / 8 else -w / 8 } + fadeIn(MekaMotion.appear(false))) togetherWith
                    (slideOutHorizontally(MekaMotion.replan(false)) { w -> if (forward) -w / 8 else w / 8 } + fadeOut(MekaMotion.appear(false)))
            }
        },
        label = "queue-reader",
    ) { id ->
        if (id == null) QueueList(core, onClose, closeLabel, onRead = { reading = it })
        else QueueReader(core, id, onBack = { reading = null })
    }
}

@Composable
private fun QueueList(core: MekaCore, onClose: () -> Unit, closeLabel: String, onRead: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val context = LocalContext.current
    val v by core.queueView.collectAsState()
    val pages by core.queuePages.collectAsState()
    // Completeness: an article whose words couldn't be kept says it opens online (and whether the phone tries again).
    val online by core.queueOnline.collectAsState()
    var text by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<QueueAdded?>(null) }
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "queue-said",
    )

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
            Text("Train queue", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.TRAIN_QUEUE)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "queue-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        item(key = "field") {
            Column(Modifier.padding(top = MekaSpace.xs).appear(rememberAppearance(2)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(QueueRules.HINT, value = text, onValue = { text = it }) { typed ->
                    scope.launch {
                        val r = runCatching { core.addToQueue(typed) }.getOrNull() ?: return@launch
                        said = r
                        if (r.refused) { haptics.tick(); text = typed } else haptics.light()
                    }
                }
                Crossfade(said?.line, animationSpec = MekaMotion.appear(reduced), label = "queue-line") { l ->
                    l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        if (v.waiting.isNotEmpty()) {
            item(key = "label-waiting") { SectionLabel("Waiting", Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(3))) }
        }
        itemsIndexed(v.waiting, key = { _, r -> "q-${r.id}" }) { i, r ->
            QueueRowView(
                r, Modifier.animateItem().appear(rememberAppearance(4 + i)),
                offline = pages[r.id],
                onlineOnly = online[r.id],
                onOpen = { if (pages[r.id] != null) { haptics.tick(); onRead(r.id) } else open(context, r.url) },
                primary = "Done",
                onPrimary = { haptics.light(); scope.launch { runCatching { core.queueDone(r.id, true) } } },
                onRemove = { haptics.tick(); scope.launch { runCatching { core.removeFromQueue(r.id) } } },
            )
        }
        if (v.done.isNotEmpty()) {
            item(key = "label-done") {
                SectionLabel("Done lately", Modifier.animateItem().padding(top = MekaSpace.l).appear(rememberAppearance(4 + v.waiting.size)))
            }
        }
        itemsIndexed(v.done, key = { _, r -> "q-${r.id}" }) { i, r ->
            QueueRowView(
                r, Modifier.animateItem().appear(rememberAppearance(5 + v.waiting.size + i)),
                offline = pages[r.id],
                onOpen = { if (pages[r.id] != null) { haptics.tick(); onRead(r.id) } else open(context, r.url) },
                primary = "Back",
                onPrimary = { haptics.tick(); scope.launch { runCatching { core.queueDone(r.id, false) } } },
                onRemove = { haptics.tick(); scope.launch { runCatching { core.removeFromQueue(r.id) } } },
            )
        }
        item(key = "note") {
            Text(QueueRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

/** Opens a saved link in its own app (a podcast app) or the browser. */
private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone opens it; the row stays.
    }
}

@Composable
private fun QueueRowView(
    row: QueueRow,
    modifier: Modifier,
    offline: String?,
    onOpen: () -> Unit,
    primary: String,
    onlineOnly: String? = null,
    onPrimary: () -> Unit,
    onRemove: () -> Unit,
) {
    val ink = if (row.done) Meka.colors.textSecondary else Meka.colors.textPrimary
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) {
                    contentDescription = (if (offline != null) "Read " else "Open ") + row.spoken +
                        ((offline ?: onlineOnly)?.let { ", $it" } ?: "")
                },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(row.title, style = MekaType.body, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(row.meta, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // "6 min read · saved to read offline" fades in once the phone has kept the article's words.
            AnimatedVisibility(offline != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
                Text(offline.orEmpty(), style = MekaType.caption, color = Meka.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // "Online only for now · …" fades in (tertiary) when the phone couldn't keep the words: a paywall, an app page.
            AnimatedVisibility(offline == null && onlineOnly != null, enter = fadeIn(MekaMotion.appear(Meka.reducedMotion)), exit = fadeOut(MekaMotion.appear(Meka.reducedMotion))) {
                Text(onlineOnly.orEmpty(), style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            primary, style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onPrimary)
                .semantics { contentDescription = "$primary: ${row.title}" }
                .padding(MekaSpace.xs),
        )
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                .padding(MekaSpace.xs),
        )
    }
}

/**
 * The reader (slice 2): a saved article's words, read here without a signal. Title, "bbc.co.uk · 6 min read", its
 * headings and paragraphs (staggering in), then Done (light haptic; off the queue and back to the list) and Open online.
 */
@Composable
private fun QueueReader(core: MekaCore, id: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val context = LocalContext.current
    val v by core.queueView.collectAsState()
    val row = (v.waiting + v.done).firstOrNull { it.id == id }
    var page by remember(id) { mutableStateOf<ReaderPage?>(null) }
    var missing by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        page = runCatching { core.queuePage(id) }.getOrNull()
        missing = page == null
    }
    BackHandler { haptics.tick(); onBack() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "back") {
            Text("‹ Train queue", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); onBack() }.minTouch())
        }
        val p = page
        item(key = "title") {
            Text(p?.title ?: row?.title.orEmpty(), style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.appear(rememberAppearance(0)))
        }
        item(key = "meta") {
            val meta = listOfNotNull(row?.meta?.substringBefore(" · "), p?.minutes).joinToString(" · ")
            Text(meta, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
        }
        if (p == null && missing) {
            item(key = "missing") {
                Text("This article's words aren't on this phone any more. Open it online.", style = MekaType.body,
                    color = Meka.colors.textSecondary)
            }
        }
        if (p != null) {
            itemsIndexed(p.blocks, key = { i, _ -> "b-$i" }) { i, b ->
                Text(
                    b.text,
                    style = if (b.heading) MekaType.itemTitle else MekaType.body,
                    color = Meka.colors.textPrimary,
                    modifier = Modifier.padding(top = if (b.heading) MekaSpace.xs else 0.dp)
                        .appear(rememberAppearance(2 + i)),
                )
            }
            if (p.cut) {
                item(key = "cut") { Text(ReaderRules.CUT_LINE, style = MekaType.caption, color = Meka.colors.textTertiary) }
            }
        }
        item(key = "actions") {
            Row(Modifier.padding(top = MekaSpace.l), horizontalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
                if (row != null && !row.done) {
                    Text("Done", style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) {
                            haptics.light()
                            scope.launch { runCatching { core.queueDone(id, true) } }
                            onBack()
                        }.minTouch().semantics { contentDescription = "Done: ${row.title}" })
                }
                if (row != null) {
                    Text(ReaderRules.OPEN_ONLINE, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                        modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); open(context, row.url) }.minTouch()
                            .semantics { contentDescription = "Open ${row.title} online" })
                }
            }
        }
    }
}
