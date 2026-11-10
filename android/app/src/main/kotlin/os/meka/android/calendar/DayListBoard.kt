package os.meka.android.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.DayListInBoard
import os.meka.android.today.DayListPane
import os.meka.core.domain.DayListBoardRules
import os.meka.core.domain.DayListPill
import os.meka.core.domain.FoldedMenuItem
import os.meka.core.domain.FoldedMenuKind
import os.meka.core.facade.MekaCore

/**
 * The Week board on the folded phone as Day lists (approved 2026-11-08, `docs/design/day-list/BoardFolded.dc.html`;
 * Meka 2026-11-09 22:27: the old folded board "looks awful"): "November · Week 46 · 6h 15m planned" with ‹ › ⋯, the
 * week's seven days as 64 dp pills with each day's planned length, and under them the chosen day's Day list (its line
 * and total pill, the done bar, slim rows, its own add bar: Enter for the next). The same tasks as Today, the open Fold
 * and the Mac. ⋯ holds Agenda view, New event and Plan your week.
 *
 * Motion: the header and strip appear on the stagger; the chosen pill's fill and ink cross-fade (appear token); the
 * day's list cross-fades to the next day (the Day list's own row motion inside). Reduced motion: cross-fades only.
 */
@Composable
fun DayListBoard(
    core: MekaCore, modifier: Modifier = Modifier,
    onAgenda: (() -> Unit)? = null, onAddEvent: (() -> Unit)? = null, onPlanWeek: (() -> Unit)? = null,
) {
    val board by core.weekBoard.collectAsState()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    // The day in view, kept across a fold or unfold and a trip to another tab.
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    val day = DayListBoardRules.shownDay(board, chosen) ?: return
    val week = DayListBoardRules.weekOf(board, day) ?: return
    val head = remember(week) { DayListBoardRules.head(week) }
    val strip = remember(week) { DayListBoardRules.strip(week) }
    val menu = remember(onAgenda, onAddEvent, onPlanWeek) {
        buildList {
            if (onAgenda != null) add(FoldedMenuItem(FoldedMenuKind.AGENDA, "Agenda view", ""))
            if (onAddEvent != null) add(FoldedMenuItem(FoldedMenuKind.NEW_EVENT, "New event", ""))
            if (onPlanWeek != null) add(FoldedMenuItem(FoldedMenuKind.PLAN_WEEK, os.meka.core.domain.PlanWeekRules.OPEN, "Obi helps"))
        }
    }

    Column(modifier.fillMaxSize().background(Meka.colors.background).testTag(DAY_LIST_BOARD_TAG)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MekaSpace.gutter).padding(top = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            FoldedHeader(
                month = head.month, week = head.weekLine, spoken = head.spoken,
                canBack = DayListBoardRules.canStep(board, day, -1), canForward = DayListBoardRules.canStep(board, day, 1),
                onBack = { DayListBoardRules.stepWeek(board, day, -1)?.let { chosen = it } },
                onForward = { DayListBoardRules.stepWeek(board, day, 1)?.let { chosen = it } },
                menu = menu,
                onMenu = { item ->
                    when (item.kind) {
                        FoldedMenuKind.AGENDA -> onAgenda?.invoke()
                        FoldedMenuKind.NEW_EVENT -> onAddEvent?.invoke()
                        FoldedMenuKind.PLAN_WEEK -> onPlanWeek?.invoke()
                        else -> Unit
                    }
                },
                modifier = Modifier.appear(rememberAppearance(0)),
            )
            DayPills(strip, day, Modifier.appear(rememberAppearance(1))) { e -> haptics.tick(); chosen = e }
        }
        AnimatedContent(
            targetState = day,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "day-list-board-day",
        ) { d ->
            val bd = board.day(d)
            key(d) {
                DayListPane(
                    core, onClose = {}, closeLabel = null, forDay = d,
                    inBoard = bd?.let { DayListInBoard(DayListBoardRules.dayTitle(it), DayListBoardRules.placeholder(it), DayListBoardRules.HINT) },
                )
            }
        }
    }
}

/**
 * The strip: seven pills (letter, number, the day's planned length or "·"). The day in view is filled gold with dark ink,
 * today's number is gold otherwise, past days are quieter. Tap one to bring its list into view.
 */
@Composable
private fun DayPills(pills: List<DayListPill>, inView: Long, modifier: Modifier, onTap: (Long) -> Unit) {
    val reduced = Meka.reducedMotion
    Row(modifier.fillMaxWidth().testTag(DAY_LIST_STRIP_TAG), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        pills.forEach { p ->
            key(p.epochDay) {
                val lit = p.epochDay == inView
                val fill by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "day-pill-fill")
                val ink by animateColorAsState(
                    when {
                        lit -> Meka.colors.onAccent
                        p.isToday -> Meka.colors.accent
                        p.isPast -> Meka.colors.textTertiary
                        else -> Meka.colors.textPrimary
                    },
                    MekaMotion.appear(reduced), label = "day-pill-ink",
                )
                Column(
                    Modifier.weight(1f).height(DayListBoardRules.PILL_HEIGHT.dp)
                        .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
                        .clickable(role = Role.Tab) { onTap(p.epochDay) }
                        .clearAndSetSemantics { contentDescription = p.spoken; selected = lit },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(p.letter, style = MekaType.micro, color = ink.copy(alpha = 0.75f), maxLines = 1)
                    Text(p.number, style = MekaType.upNextCardTitle, color = ink, maxLines = 1)
                    Text(p.length, style = MekaType.micro, color = ink.copy(alpha = 0.8f), maxLines = 1)
                }
            }
        }
    }
}

/** The folded Week board as Day lists, and its strip (screen tests). */
const val DAY_LIST_BOARD_TAG = "day-list-board"
const val DAY_LIST_STRIP_TAG = "day-list-strip"
