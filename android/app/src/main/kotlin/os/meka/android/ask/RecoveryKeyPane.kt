package os.meka.android.ask

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.sharedTitleInPane
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.core.domain.RecoveryKeyDraft
import os.meka.core.domain.RecoveryKeyRules
import os.meka.core.facade.MekaCore

/**
 * Ask → Settings → Recovery key (Recovery key, slice 2; ADR-005 amendment 2026-10-12): whether the household has a key
 * ("Made today on the Fold · …"), and Make: the 24 words in six rows of four, "I've written them down", then three of
 * them typed back from the paper before the key's verifier (never the words) is saved and the old key stops working.
 * While the words or the confirmation are on screen the window is FLAG_SECURE (no screenshots, blank in Recents). The
 * words live only in this composition: never saved state, never stored or synced.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; a shimmer until the server answers; the
 * status line cross-fades and blends to the accent while there's no usable key; Make gives a light haptic and the
 * status cross-fades into the words, which fade in row by row (the stagger, 40 ms apart); "I've written them down"
 * cross-fades to the three fields; Save gives a light haptic once saved and the words cross-fade back to the status
 * ("Saved · keep the paper somewhere safe"); words that don't match or a problem unfold in the accent (expand spring)
 * with a tick haptic. Reduced motion: cross-fades, no stagger.
 */
@Composable
fun RecoveryKeyPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val view by core.recoveryKeyView.collectAsState()
    var draft by remember { mutableStateOf<RecoveryKeyDraft?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val answers = remember { mutableStateListOf("", "", "") }
    LaunchedEffect(Unit) { runCatching { core.refreshRecoveryKey() } }
    SecureWhile(draft != null)
    val enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))
    val exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

    fun close() { draft = null; confirming = false; answers.indices.forEach { answers[it] = "" }; onClose() }

    fun save() {
        val d = draft ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (core.saveRecoveryKey(d, answers.toList())) {
                    haptics.light(); saved = true; draft = null; confirming = false
                    answers.indices.forEach { answers[it] = "" }
                } else haptics.tick()
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clickable(role = Role.Button) { close() }.minTouch())
        Text(MoreItem.RECOVERY.label, style = MekaType.greeting, color = Meka.colors.textPrimary,
            modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.RECOVERY)).appear(rememberAppearance(0)))
        val stage = when { draft == null -> 0; !confirming -> 1; else -> 2 }
        AnimatedContent(
            stage, label = "recovery-stage",
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
        ) { s ->
            val d = draft
            when {
                s == 0 || d == null -> StatusSection(view, saved, busy) {
                    haptics.light(); saved = false; confirming = false
                    answers.indices.forEach { answers[it] = "" }
                    draft = core.makeRecoveryKey()
                }
                s == 1 -> WordsSection(d, onWritten = { haptics.light(); confirming = true }, onCancel = { haptics.tick(); draft = null })
                else -> ConfirmSection(d, answers, busy, onSave = { save() }, onBack = { haptics.tick(); confirming = false })
            }
        }
        val problem = view?.problem
        AnimatedVisibility(problem != null, enter = enter, exit = exit) {
            Text(problem.orEmpty(), style = MekaType.caption, color = Meka.colors.accent)
        }
    }
}

@Composable
private fun StatusSection(view: os.meka.core.domain.RecoveryKeyView?, saved: Boolean, busy: Boolean, onMake: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        if (view == null) {
            SkeletonRows(1, Modifier.padding(top = MekaSpace.xs))
            return@Column
        }
        val line = if (saved) RecoveryKeyRules.SAVED else view.line
        val color = androidx.compose.animation.animateColorAsState(
            if (view.lit && !saved) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "recovery-lit",
        ).value
        Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "recovery-line") { l ->
            Text(l, style = MekaType.body, color = color, modifier = Modifier.appear(rememberAppearance(1)))
        }
        Text(RecoveryKeyRules.HOW, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.appear(rememberAppearance(1)))
        view.warning?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(2))) }
        Pill(view.action, enabled = !busy, modifier = Modifier.appear(rememberAppearance(2)), onClick = onMake)
    }
}

@Composable
private fun WordsSection(draft: RecoveryKeyDraft, onWritten: () -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("Your 24 words", style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(RecoveryKeyRules.HOW, style = MekaType.caption, color = Meka.colors.textTertiary)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            draft.rows.forEachIndexed { i, row ->
                // Each row fades in after the last (the stagger); reduced motion: all at once.
                Row(Modifier.fillMaxWidth().appear(rememberAppearance(i + 1)), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    row.forEach { w ->
                        Text(w, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l), verticalAlignment = Alignment.CenterVertically) {
            Pill(RecoveryKeyRules.WRITE_DOWN, enabled = true, modifier = Modifier.appear(rememberAppearance(draft.rows.size + 1)), onClick = onWritten)
            Text("Not now", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clickable(role = Role.Button) { onCancel() }.minTouch())
        }
    }
}

@Composable
private fun ConfirmSection(
    draft: RecoveryKeyDraft, answers: MutableList<String>, busy: Boolean, onSave: () -> Unit, onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(RecoveryKeyRules.CONFIRM, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        draft.confirmLabels.forEachIndexed { i, label ->
            Column(Modifier.appear(rememberAppearance(i + 1))) {
                Text(label, style = MekaType.caption, color = Meka.colors.textTertiary)
                BasicTextField(
                    value = answers[i],
                    onValueChange = { answers[i] = it.take(12) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                    cursorBrush = SolidColor(Meka.colors.accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password, capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, imeAction = if (i == answers.lastIndex) ImeAction.Done else ImeAction.Next,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSave() }),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised)
                        .padding(MekaSpace.m).semantics { contentDescription = label },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (busy) "Saving…" else "Save", enabled = !busy && answers.all { it.isNotBlank() }, onClick = onSave)
            Text("Show the words again", style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clickable(role = Role.Button, enabled = !busy) { onBack() }.minTouch())
        }
    }
}

@Composable
private fun Pill(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    ) {
        Text(label, style = MekaType.itemMeta, color = if (enabled) Meka.colors.accent else Meka.colors.textTertiary)
    }
}

/** No screenshots or Recents preview while [secure] (the words are on screen). */
@Composable
internal fun SecureWhile(secure: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, secure) {
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
