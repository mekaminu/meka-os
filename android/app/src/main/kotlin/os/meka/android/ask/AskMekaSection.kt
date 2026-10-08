package os.meka.android.ask

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.calendar.EventUndo
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.SkeletonRows
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.AiStatusView
import os.meka.core.domain.AskCard
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.AskRules
import os.meka.core.facade.MekaCore

/** What Ask is showing under its field: nothing yet, MEKA thinking, an answer, or why there is none. */
private sealed interface AskShown {
    data object Idle : AskShown
    data class Thinking(val question: String) : AskShown
    data class Answer(val question: String, val outcome: AskOutcome, val n: Int) : AskShown
}

/**
 * Ask MEKA on the Fold (build plan V1, AI layer slice 3b). The field asks MEKA in your own words; Search stays one tap
 * away beside it (and is the field itself while asking can't work: AI off, the month's budget used up, not connected).
 * Under the field, MEKA's AI and the month's spend ("On · $1.20 of $20 this month", lit when it needs a look). Asking
 * shows the question, a thinking shimmer, then the answer's lines fading in one after another and up to three cards
 * rising under them; a card does nothing until tapped (light haptic), then leaves and the undo bar rises with what it
 * did and Undo. Motion per the catalogue's Assistant row; reduced motion cross-fades.
 */
@Composable
fun AskMekaSection(core: MekaCore, undo: EventUndo, openSearch: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    var status by remember { mutableStateOf<AiStatusView?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var shown by remember { mutableStateOf<AskShown>(AskShown.Idle) }
    var done by remember { mutableStateOf(setOf<Int>()) }
    var asks by remember { mutableStateOf(0) }
    LaunchedEffect(asks) { status = core.aiStatus() }
    val canAsk = status?.canAsk != false
    val reduced = Meka.reducedMotion

    fun ask() {
        val q = AskRules.question(text) ?: return
        keyboard?.hide()
        haptics.light()
        shown = AskShown.Thinking(q)
        done = emptySet()
        scope.launch {
            val out = core.askMeka(q)
            shown = AskShown.Answer(q, out, asks + 1)
            asks += 1
            if (out is AskOutcome.Answered) text = ""
        }
    }

    fun tap(i: Int, card: AskCard) {
        haptics.light()
        done = done + i
        scope.launch {
            try {
                val d = core.doAsk(card)
                val back = d.undo
                undo.show(d.line, back?.let { u -> suspend { core.undoAsk(u); done = done - i } })
            } catch (e: Exception) {
                done = done - i
                undo.show(e.message ?: "That can't be done now", null)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(MekaRadius.pill))
                    .background(Meka.colors.surfaceRaised)
                    .then(if (canAsk) Modifier else Modifier.clickable(role = Role.Button, onClick = openSearch))
                    .padding(horizontal = MekaSpace.l),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (canAsk) {
                    if (text.isEmpty()) Text("Ask MEKA…", style = MekaType.body, color = Meka.colors.textTertiary)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.take(AskRules.MAX_QUESTION) },
                        singleLine = true,
                        textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                        cursorBrush = SolidColor(Meka.colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Ask MEKA" },
                    )
                } else {
                    Text("Search everything", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                        modifier = Modifier.semantics { contentDescription = "Search everything" })
                }
            }
            if (canAsk) {
                val sendable = AskRules.question(text) != null && shown !is AskShown.Thinking
                val bg by animateColorAsState(if (sendable) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.themeBlend(reduced), label = "ask-send")
                Text("Ask", style = MekaType.itemMeta, color = if (sendable) Meka.colors.onAccent else Meka.colors.textTertiary,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
                        .clickable(enabled = sendable, role = Role.Button) { ask() }
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s))
                Text("Search", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .clickable(role = Role.Button, onClick = openSearch)
                        .semantics { contentDescription = "Search everything" }
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s))
            }
        }
        // MEKA's AI and the month's spend; cross-fades as it changes.
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "ai-status", modifier = Modifier.padding(top = MekaSpace.xs, start = MekaSpace.xxs),
        ) { s ->
            Text(
                "MEKA's AI · " + (s?.line ?: "Checking…"),
                style = MekaType.caption, color = if (s?.lit == true) Meka.colors.accent else Meka.colors.textTertiary,
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(top = MekaSpace.m, bottom = MekaSpace.l)
                .animateContentSize(MekaMotion.expand(reduced))
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            when (val s = shown) {
                AskShown.Idle -> Text(
                    if (canAsk) "Ask about your day, or ask MEKA to add, move or tick off a task, start a fast or set a timer. Nothing happens until you tap."
                    else "Tasks, events, lists, goals and habits.",
                    style = MekaType.caption, color = Meka.colors.textTertiary,
                )
                is AskShown.Thinking -> {
                    QuestionLine(s.question)
                    SkeletonRows(count = 2, rowHeight = 18.dp)
                }
                is AskShown.Answer -> key(s.n) {
                    QuestionLine(s.question)
                    when (val o = s.outcome) {
                        is AskOutcome.Unavailable -> Text(o.line, style = MekaType.body, color = Meka.colors.textSecondary,
                            modifier = Modifier.appear(rememberAppearance(0)))
                        is AskOutcome.Answered -> {
                            val lines = AskRules.answerLines(o.answer.text)
                            lines.forEachIndexed { i, line ->
                                Text(line, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.appear(rememberAppearance(i)))
                            }
                            o.answer.cards.forEachIndexed { j, card ->
                                AnimatedVisibility(
                                    visible = j !in done,
                                    enter = if (reduced) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
                                    exit = if (reduced) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
                                ) {
                                    AskCardRow(card, Modifier.appear(rememberAppearance(lines.size + j))) { tap(j, card) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionLine(q: String) {
    Text("“$q”", style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 3)
}

/** A proposal: its line and one pill; nothing happens until it is tapped. */
@Composable
private fun AskCardRow(card: AskCard, modifier: Modifier, onTap: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(top = MekaSpace.xxs)
            .clip(RoundedCornerShape(MekaRadius.m))
            .background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(card.line, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 3)
        Text(card.button, style = MekaType.itemMeta, color = Meka.colors.onAccent,
            modifier = Modifier.padding(start = MekaSpace.s).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = "${card.button}: ${card.line}" }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
    }
}
