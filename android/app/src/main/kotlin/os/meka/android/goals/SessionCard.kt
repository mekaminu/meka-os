package os.meka.android.goals

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.SessionCard
import os.meka.core.domain.SessionStatus
import os.meka.core.facade.MekaCore

/**
 * The Gym on Today (build plan M1): one card per booked habit with something today — "Today 17:45–18:45", "Now · until
 * 18:45", then "Did you go?" with Went · Didn't go once the slot is over (Went pops a check with a spring and a light
 * haptic; a one-line note can follow), "Rebooked for Thu 17:45" after Didn't go. The card's content cross-slides as its
 * state moves on; Undo puts today's answer back. Reduced motion: cross-fades, no pop.
 */
@Composable
internal fun SessionCards(core: MekaCore, modifier: Modifier = Modifier) {
    val view by core.sessionsView.collectAsState()
    if (view.cards.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.m)) {
        view.cards.forEach { c -> SessionCardTile(core, c) }
    }
}

@Composable
private fun SessionCardTile(core: MekaCore, card: SessionCard) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val act: (suspend () -> Unit) -> Unit = { body -> scope.launch { runCatching { body() } } }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
            .padding(MekaSpace.l)
            .semantics { contentDescription = card.spoken },
    ) {
        AnimatedContent(
            targetState = card,
            contentKey = { it.status to it.line },
            transitionSpec = {
                if (reduced) fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
                else (slideInHorizontally(MekaMotion.replan(false)) { it / 8 } + fadeIn(MekaMotion.appear(false))) togetherWith
                    (slideOutHorizontally(MekaMotion.replan(false)) { -it / 8 } + fadeOut(MekaMotion.appear(false)))
            },
            label = "session",
        ) { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.status == SessionStatus.WENT) {
                    WentCheck()
                    Spacer(Modifier.width(MekaSpace.m))
                }
                Column(Modifier.weight(1f)) {
                    Text(c.heading, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    Text(
                        c.line, style = MekaType.itemMeta,
                        color = if (c.status == SessionStatus.ASK || c.status == SessionStatus.NOW) Meka.colors.accent else Meka.colors.textSecondary,
                        modifier = Modifier.padding(top = MekaSpace.xxs),
                    )
                    c.note?.let { Text("“$it”", style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
                    c.next?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xxs)) }
                }
            }
        }
        val context = LocalContext.current
        Row(Modifier.padding(top = MekaSpace.m), horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
            if (card.asks) {
                Action("Went") { haptics.light(); act { core.sessionWent(card.habitId, null) } }
                Action("Didn't go") { haptics.tick(); act { core.sessionMissed(card.habitId) } }
            }
            if (card.answered) Action("Undo") { haptics.tick(); act { core.undoSession(card.habitId) } }
            // The workout app ("Open Hevy ↗"): its link opens the app when it's installed, else the browser.
            val l = card.appLink
            val label = card.openLabel
            if (l != null && label != null) {
                Action("$label ↗") { haptics.light(); openAppLink(context, l) }
            }
        }
        AnimatedVisibility(card.status == SessionStatus.WENT && card.note == null, enter = unfold(), exit = fold()) {
            NoteField { note -> act { core.setSessionNote(card.habitId, note) } }
        }
    }
}

/** Opens the workout app's link (an app that claims the address opens; else the browser). Nothing happens if neither can. */
private fun openAppLink(context: android.content.Context, link: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Went: the check draws itself in and pops (like a habit tick, [TickRing]); Motion → Off: shown at once. */
@Composable
private fun WentCheck() {
    var shown by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    TickRing(shown, Modifier.size(28.dp))
}

/** "Add a note (optional)": one line, Done saves it. */
@Composable
private fun NoteField(save: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Box(
        Modifier.padding(top = MekaSpace.xs).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs),
    ) {
        if (text.isEmpty()) Text("Add a note · push day, 5 km… (optional)", style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(80) },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { save(text); text = "" } }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note for today's session" },
        )
    }
}
