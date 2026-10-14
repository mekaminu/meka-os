package os.meka.android.activity

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.MekaApplication
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.work.CallerLookup
import os.meka.android.work.PhoneCallLog
import os.meka.android.work.VoiceMessagePlayer
import os.meka.core.domain.CallDetail
import os.meka.core.domain.CallLogRow
import os.meka.core.domain.CallLogRules

/**
 * Call notifications, slice 5: a call notification's tap. [heldId] is the held message it was about ("" when none),
 * [key] the caller's block-list key; Activity opens on Calls with that call unfolded ([CallLogRules.target]).
 */
data class CallOpen(val heldId: String, val key: String?)

/**
 * Activity → Calls (call notifications, slice 4): RECENT CALLS — the call assistant's messages, the calls missed at
 * work and (slice 6) the calls this phone screened (strangers' calls that rang through, calls sent to the assistant
 * with no message, calls stopped before they rang) from the last 7 days, named from this phone's contacts, one line each with a block chip. A tap (tick haptic)
 * unfolds the call's detail in place on the expand spring: the small timeline, the AI's summary, the words, the spam
 * flag, whether it's blocked now and in future, ▶ Listen (the recording, played from memory), Call back (the dialer),
 * Block / Unblock / Not spam (light haptic; the chip and the block line cross-fade). Reduced motion: cross-fades.
 */
@Composable
internal fun CallLogSection(index: Int, onNote: (String?) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MekaApplication
    val core = app.core
    val calls by core.heldCalls.collectAsState()
    val blocked by core.blockedCallers.collectAsState()
    val lists by app.captures.lists.collectAsState()
    // Slice 6: with the calls this phone screened (rang through, sent to the assistant, stopped), kept on the phone only.
    val phone by remember { PhoneCallLog.calls(context) }.collectAsState()
    val view = remember(calls, blocked, lists, phone) { core.callLogOf(calls, CallerLookup.names(context), lists, phone) }
    if (view.isEmpty) return
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // Slice 5: opened from a call's notification — unfold that call and bring it into view.
    val request by app.openCall.collectAsState()
    var reveal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(request, view) {
        val r = request ?: return@LaunchedEffect
        CallLogRules.target(view, r.heldId, r.key)?.let { open = it; reveal = it }
    }
    Column(Modifier.fillMaxWidth().appear(rememberAppearance(index)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Spacer(Modifier.height(MekaSpace.m))
        Text(CallLogRules.TITLE.uppercase(), style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Text(listOfNotNull(view.line, CallLogRules.HINT).joinToString(". "), style = MekaType.caption, color = Meka.colors.textTertiary)
        view.days.forEach { day ->
            Text(day.label, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
            day.rows.forEach { row ->
                CallRowView(
                    row, expanded = open == row.id, reveal = reveal == row.id,
                    onToggle = { haptics.tick(); open = if (open == row.id) null else row.id },
                    fetch = { id -> core.voiceMessageAudio(id) },
                    onCallBack = { number ->
                        haptics.light()
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    onBlock = {
                        haptics.light()
                        scope.launch {
                            val number = row.detail.number
                            val ok = runCatching {
                                // Slice 6: a call the phone screened isn't a held message, so it's blocked by its number.
                                if (CallLogRules.isPhone(row.id) && number != null) core.blockCaller(number, row.detail.blockWhy)
                                else core.blockLoggedCaller(row.id)
                            }.getOrDefault(false)
                            if (!ok) onNote("Couldn't block that number. Try again.")
                        }
                    },
                    onUnblock = { key ->
                        haptics.light()
                        scope.launch { if (!runCatching { core.unblockCaller(key) }.getOrDefault(false)) onNote("Couldn't unblock that number. Try again.") }
                    },
                    onNotSpam = { key ->
                        haptics.light()
                        scope.launch { if (!runCatching { core.dismissSuspectedCaller(key) }.getOrDefault(false)) onNote("Couldn't change that. Try again.") }
                    },
                )
            }
        }
    }
}

@Composable
private fun CallRowView(
    row: CallLogRow,
    expanded: Boolean,
    reveal: Boolean,
    onToggle: () -> Unit,
    fetch: suspend (String) -> ByteArray?,
    onCallBack: (String) -> Unit,
    onBlock: () -> Unit,
    onUnblock: (String) -> Unit,
    onNotSpam: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal) {
            kotlinx.coroutines.delay(if (reduced) 120L else 420L) // after the pane springs up and the row unfolds
            runCatching { bring.bringIntoView() }
        }
    }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Fold away" else "Show what happened") { onToggle() }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(row.time, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
            Spacer(Modifier.width(MekaSpace.xs))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Text(row.who, style = MekaType.body, color = Meka.colors.textPrimary)
                Text(
                    row.line, style = MekaType.itemMeta,
                    color = if (row.urgent) Meka.colors.critical else Meka.colors.textSecondary,
                )
                AnimatedContent(
                    targetState = row.chip to row.blocked,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "call-chip",
                ) { (chip, isBlocked) ->
                    Text(chip, style = MekaType.caption, color = if (isBlocked) Meka.colors.critical else Meka.colors.textTertiary)
                }
            }
        }
        AnimatedVisibility(
            expanded,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            CallDetailView(row.id, row.detail, fetch, onCallBack, onBlock, onUnblock, onNotSpam)
        }
    }
}

@Composable
private fun CallDetailView(
    id: String,
    d: CallDetail,
    fetch: suspend (String) -> ByteArray?,
    onCallBack: (String) -> Unit,
    onBlock: () -> Unit,
    onUnblock: (String) -> Unit,
    onNotSpam: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        // The small timeline: a brass dot per step, the line between them.
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            d.timeline.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Meka.colors.accent))
                    Spacer(Modifier.width(MekaSpace.xs))
                    Text(step, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        d.summary?.let { Text(it, style = MekaType.body, color = Meka.colors.textPrimary) }
        d.words?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary) }
        d.spam?.let { Text(it, style = MekaType.caption, color = Meka.colors.critical) }
        AnimatedContent(
            targetState = d.blockStatus,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "call-block-line",
        ) { line -> Text(line, style = MekaType.caption, color = Meka.colors.textTertiary) }
        if (d.listen != null) VoiceMessagePlayer(id, fetch)
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.m), verticalAlignment = Alignment.CenterVertically) {
            val number = d.number
            if (d.callBack != null && number != null) Action(d.callBack!!, Meka.colors.accent) { onCallBack(number) }
            d.block?.let { label ->
                Action(label, Meka.colors.critical, Modifier.semantics { contentDescription = "Block ${d.who}" }) { onBlock() }
            }
            val key = d.key
            if (d.unblock != null && key != null) Action(d.unblock!!, Meka.colors.accent) { onUnblock(key) }
            if (d.notSpam != null && key != null) Action(d.notSpam!!, Meka.colors.accent) { onNotSpam(key) }
        }
    }
}

@Composable
private fun Action(label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemMeta, color = color,
        modifier = modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { onClick() }
            .minTouch().padding(vertical = MekaSpace.xs),
    )
}
