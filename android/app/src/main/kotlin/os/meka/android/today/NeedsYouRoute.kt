package os.meka.android.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.BreathingRing
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.footFade
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaSharedLayout
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.work.AfterWorkCard
import os.meka.android.work.AfterWorkHost
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import os.meka.android.calendar.EventUndoBar
import os.meka.android.calendar.rememberEventUndo
import os.meka.core.domain.DecisionCard
import os.meka.core.domain.DecisionEffect
import os.meka.core.domain.DecisionMove
import os.meka.core.domain.NeedsYouStackRules
import os.meka.core.domain.Task
import os.meka.core.domain.NeedsYouStack
import os.meka.core.domain.CommandCentreRules
import os.meka.android.calendar.EventUndo
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberUpdatedState
import os.meka.core.facade.MekaCore
import androidx.compose.runtime.LaunchedEffect
import os.meka.android.MekaApplication
import androidx.compose.ui.platform.LocalContext

/**
 * NEEDS YOU: a stack of decisions (four tabs, slice 2): conflicts, overdue, due today but unscheduled, then "From your
 * lists" when chases, reviews or renewals are due. Right = yes/do, left = later, up = open, each card with its why
 * ([DecisionStackView]). Approvals join the stack in V1. Same layout rules as Today: two panes when wide, the detail
 * springs up over the stack when narrow.
 */
@Composable
fun NeedsYouRoute(core: MekaCore, openLists: () -> Unit = {}) {
    val today by core.today.collectAsState()
    val stack by core.needsYouStack.collectAsState()
    val conflicts by core.conflicts.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val actions = todayActions(core, scope, { selectedId }) { selectedId = it }
    val selected = today.needsYou.map { it.task }.firstOrNull { it.id == selectedId }
    var showAfterWork by rememberSaveable { mutableStateOf(false) }
    val undo = rememberEventUndo()
    val app = LocalContext.current.applicationContext as MekaApplication
    val openAfterWork by app.openAfterWork.collectAsState()
    LaunchedEffect(openAfterWork) {
        if (openAfterWork) { showAfterWork = true; app.openAfterWork.value = false }
    }
    val moves = rememberDecisionMoves(core, undo, openTask = { selectedId = it }, openLists = openLists)

    MekaSharedLayout(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Meka.colors.background)) {
            val twoPane = maxWidth >= 600.dp
            val list: @Composable (Modifier) -> Unit = { m ->
                NeedsYouColumn(core, stack, moves, m, openAfterWork = { showAfterWork = true })
            }
            // Opening the Fold grows the detail out beside the stack; closed, the detail springs up over it.
            TwoPaneMorph(
                twoPane,
                list = list,
                detail = { m -> DetailPane(selected, conflicts.filter { it.taskId == selected?.id }, actions, m) },
            )
            var shown by remember { mutableStateOf<Task?>(null) }
            if (selected != null) shown = selected
            MekaPane(visible = selected != null && !twoPane) {
                shown?.let { s ->
                    DetailPane(s, conflicts.filter { it.taskId == s.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
            MekaPane(visible = showAfterWork) { AfterWorkHost(onClose = { showAfterWork = false }) }
            EventUndoBar(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
}


/** The stack's moves, and the cards set aside with "Later" on this screen (nothing is written; back of the stack). */
internal class DecisionMoves(val onMove: (DecisionCard, DecisionMove) -> Unit, private val aside: MutableState<List<String>>) {
    val setAside: List<String> get() = aside.value
}

/**
 * What each move on a card does, shared by the Needs you tab and the command centre on the open Fold: Done and
 * Tomorrow go through the core with an undo; Open and Choose open the task ([openTask]); Go through opens Lists; Later
 * sets a card aside on this screen.
 */
@Composable
internal fun rememberDecisionMoves(core: MekaCore, undo: EventUndo, openTask: (String?) -> Unit, openLists: () -> Unit): DecisionMoves {
    val scope = rememberCoroutineScope()
    val aside = remember { mutableStateOf(listOf<String>()) }
    val latestOpenTask by rememberUpdatedState(openTask)
    val latestOpenLists by rememberUpdatedState(openLists)
    return remember(core, undo, scope) {
        DecisionMoves({ card, move ->
            when (val effect = card.effect(move)) {
                DecisionEffect.COMPLETE_TASK, DecisionEffect.SNOOZE_TASK -> {
                    val id = card.taskId
                    if (id != null) scope.launch {
                        val done = runCatching { core.decide(id, effect) }.getOrNull()
                        if (done != null) undo.show(NeedsYouStackRules.message(card, move)) { core.undoDecision(done) }
                    }
                }
                DecisionEffect.OPEN_TASK -> latestOpenTask(card.taskId)
                DecisionEffect.OPEN_LISTS -> latestOpenLists()
                DecisionEffect.SET_ASIDE -> {
                    aside.value = aside.value - card.id + card.id
                    undo.show(NeedsYouStackRules.message(card, move)) { aside.value = aside.value - card.id }
                }
            }
        }, aside)
    }
}

/**
 * The Needs you list: its title, the after-work card, then the stack (or "Nothing needs you" beside the breathing
 * check ring). The tab shows it with the big title; the command centre with a smaller heading that carries the count.
 */
@Composable
internal fun NeedsYouColumn(
    core: MekaCore, stack: NeedsYouStack, moves: DecisionMoves, modifier: Modifier, openAfterWork: () -> Unit,
    compact: Boolean = false, play: Boolean = true,
) {
    val cards = NeedsYouStackRules.ordered(stack, moves.setAside)
    // The foot fades into the tabs rather than cutting a card in half; the xl bottom padding clears the fade.
    LazyColumn(
        modifier.footFade(),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "title") {
            if (compact) {
                SectionLabel(CommandCentreRules.needsYouHeading(cards.size), Modifier.animateItem().appear(rememberAppearance(0, play)))
            } else {
                Text("Needs you", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(0, play)))
            }
        }
        item(key = "after-work") {
            AfterWorkCard(core, Modifier.animateItem().padding(bottom = MekaSpace.s).appear(rememberAppearance(1, play))) { openAfterWork() }
        }
        if (cards.isEmpty()) {
            item(key = "clear") {
                // Full screen: the breathing check ring beside a light line and what lands here (catalogue "Empty
                // states"; Fold review 2026-10-08, item 7); the compact column stays quiet, one line.
                if (compact) {
                    Text(NeedsYouStackRules.EMPTY_LINE, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                        modifier = Modifier.animateItem().appear(rememberAppearance(1, play)))
                } else {
                    Row(Modifier.animateItem().appear(rememberAppearance(1, play)).semantics(mergeDescendants = true) { },
                        verticalAlignment = Alignment.CenterVertically) {
                        BreathingRing(Modifier.padding(end = MekaSpace.m), check = true)
                        Column {
                            Text(NeedsYouStackRules.EMPTY_LINE, style = MekaType.body, color = Meka.colors.textPrimary)
                            Text(NeedsYouStackRules.EMPTY_CAPTION, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                        }
                    }
                }
            }
        } else {
            item(key = "stack") {
                DecisionStackView(cards, moves.onMove, Modifier.animateItem().appear(rememberAppearance(1, play)))
            }
        }
    }
}
