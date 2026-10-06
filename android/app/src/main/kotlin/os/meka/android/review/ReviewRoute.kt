package os.meka.android.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.CountUpText
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.SectionLabel
import os.meka.core.domain.NorthStarMetric
import os.meka.core.domain.ReviewCard
import os.meka.core.domain.ReviewGoal
import os.meka.core.domain.ReviewHabit
import os.meka.core.domain.ReviewTile
import os.meka.core.domain.WeeklyReviewView
import os.meka.core.facade.MekaCore

/**
 * REVIEW (build plan M1, ADR-013): the weekly review. One Monday–Sunday week looked back on — numbers at the top,
 * what got done, habits, goals, fasts, what closed on your lists, what is still open — then the week ahead and the
 * north-star numbers. ‹ › step between weeks (back twelve). "Done reviewing" is synced to the Mac. Nothing else here
 * changes anything.
 *
 * Motion (catalogue "Weekly review"): numbers count up, tiles and sections stagger in 40 ms apart, goal bars fill on
 * appear; stepping weeks slides the week across the way you moved with a tick haptic; Done reviewing pops a check
 * (spring) with a light haptic. Reduced motion: cross-fades, numbers shown straight away.
 */
@Composable
fun ReviewRoute(core: MekaCore) {
    val v by core.reviewView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val step: (Int) -> Unit = { delta ->
        haptics.tick()
        scope.launch { runCatching { core.showReviewWeek(v.offset + delta) } }
    }

    AnimatedContent(
        targetState = v,
        contentKey = { it.weekStart },
        transitionSpec = {
            val dir = if (targetState.weekStart > initialState.weekStart) 1 else -1
            if (reduced) fadeIn(MekaMotion.replan(true)) togetherWith fadeOut(MekaMotion.replan(true))
            else (slideInHorizontally(MekaMotion.replan(false)) { dir * it / 8 } + fadeIn(MekaMotion.appear(false))) togetherWith
                (slideOutHorizontally(MekaMotion.replan(false)) { -dir * it / 8 } + fadeOut(MekaMotion.appear(false)))
        },
        label = "review-week",
        modifier = Modifier.fillMaxSize().background(Meka.colors.background),
    ) { week ->
        Week(week, step) {
            haptics.light()
            scope.launch { runCatching { core.reviewDone() } }
        }
    }
}

@Composable
private fun Week(v: WeeklyReviewView, step: (Int) -> Unit, reviewDone: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "title") {
            Column(Modifier.padding(bottom = MekaSpace.m).appear(rememberAppearance(0))) {
                Text("Review", style = MekaType.greeting, color = Meka.colors.textPrimary)
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                    StepButton("‹", "Previous week", v.canGoBack) { step(-1) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                        Text(v.rangeLabel, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    StepButton("›", "Next week", v.canGoForward) { step(1) }
                }
            }
        }

        item(key = "tiles") {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                v.tiles.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                        pair.forEachIndexed { i, t -> Tile(t, Modifier.weight(1f).appear(rememberAppearance(1 + row * 2 + i))) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                v.comparedLine?.let {
                    Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(3)))
                }
            }
        }

        // Done
        item(key = "h-done") { Section("Done", 4) }
        if (v.done.isEmpty()) item(key = "done-empty") {
            Note(if (v.isCurrent) "Nothing ticked off yet this week." else "Nothing was ticked off that week.", 4)
        }
        items(v.done, key = { "d-" + it.id }) { d -> Line(d.title, d.dayLabel, 4) }
        if (v.doneMore > 0) item(key = "done-more") { Note("and ${v.doneMore} more", 4) }

        // Habits
        if (v.habits.isNotEmpty()) {
            item(key = "h-habits") { Section("Habits", 5, v.habitsLine) }
            items(v.habits, key = { "h-" + it.id }) { h -> HabitLine(h, Modifier.appear(rememberAppearance(5))) }
        }

        // Goals
        if (v.goals.isNotEmpty()) {
            item(key = "h-goals") { Section("Goals", 6) }
            items(v.goals, key = { "g-" + it.id }) { g -> GoalLine(g, Modifier.appear(rememberAppearance(6))) }
        }

        // Fasting and lists
        val more = listOfNotNull(v.fastingLine) + v.listsLines
        if (more.isNotEmpty()) {
            item(key = "h-more") {
                Column(Modifier.appear(rememberAppearance(7))) {
                    SectionLabel("Fasting and lists", Modifier.padding(top = MekaSpace.l))
                    more.forEach { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(vertical = MekaSpace.xxs)) }
                }
            }
        }

        // Still open
        if (v.stillOpen.isNotEmpty()) {
            item(key = "h-open") { Section("Still open", 8) }
            items(v.stillOpen, key = { "o-" + it.id }) { o -> Line(o.title, o.detail, 8, lit = true) }
            if (v.stillOpenMore > 0) item(key = "open-more") { Note("and ${v.stillOpenMore} more", 8) }
        }

        // Ahead
        v.aheadTitle?.let { title ->
            item(key = "h-ahead") {
                Column(Modifier.appear(rememberAppearance(9))) {
                    SectionLabel(title, Modifier.padding(top = MekaSpace.l))
                    v.aheadLines.forEach { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(vertical = MekaSpace.xxs)) }
                }
            }
        }

        // North star (ADR-013)
        item(key = "h-north") { Section("North star", 10) }
        items(v.northStar, key = { "n-" + it.key }) { m -> NorthStarLine(m, Modifier.appear(rememberAppearance(10))) }

        item(key = "reviewed") {
            Column(
                Modifier.fillMaxWidth().padding(top = MekaSpace.xl).appear(rememberAppearance(11)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ReviewedCheck(v.reviewed)
                Spacer(Modifier.height(MekaSpace.s))
                val line = v.reviewedLine
                if (v.reviewed && line != null) {
                    Text(line, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                } else {
                    Text(
                        "Done reviewing", style = MekaType.itemTitle, color = Meka.colors.onAccent,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                            .clickable(role = Role.Button) { reviewDone() }.padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClickLabel = description) { onClick() } else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MekaType.upNextTitle, color = if (enabled) Meka.colors.accent else Meka.colors.hairline)
    }
}

@Composable
private fun Tile(t: ReviewTile, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised).padding(MekaSpace.m)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(t.label, style = MekaType.caption, color = Meka.colors.textTertiary)
        CountUpText(t.value, MekaType.greeting, Meka.colors.textPrimary)
        t.detail?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 1) }
    }
}

@Composable
private fun Section(label: String, index: Int, line: String? = null) {
    Column(Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(index))) {
        SectionLabel(label)
        line?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xs)) }
    }
}

@Composable
private fun Note(text: String, index: Int) {
    Text(text, style = MekaType.itemMeta, color = Meka.colors.textTertiary, modifier = Modifier.padding(vertical = MekaSpace.xxs).appear(rememberAppearance(index)))
}

@Composable
private fun Line(title: String, detail: String, index: Int, lit: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).appear(rememberAppearance(index)).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
        Text(detail, style = MekaType.caption, color = if (lit) Meka.colors.accent else Meka.colors.textTertiary, modifier = Modifier.padding(start = MekaSpace.s))
    }
}

/** A habit: its name, "5 of 7 · met", and the week's days as dots (filled where ticked). */
@Composable
private fun HabitLine(h: ReviewHabit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(h.line, style = MekaType.itemMeta, color = if (h.met) Meka.colors.accent else Meka.colors.textSecondary)
        }
        Row(Modifier.padding(top = MekaSpace.xxs).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            h.days.forEach { on ->
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) Meka.colors.accent else Meka.colors.surfaceRaised))
            }
        }
    }
}

/** A goal with its bar, which fills on appear. */
@Composable
private fun GoalLine(g: ReviewGoal, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val fill = remember(g.id) { Animatable(if (reduced) g.progressPct / 100f else 0f) }
    LaunchedEffect(g.progressPct) { fill.animateTo(g.progressPct / 100f, MekaMotion.replan(reduced)) }
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs).semantics(mergeDescendants = true) {}) {
        Text(g.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Box(Modifier.padding(vertical = MekaSpace.xs).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(fill.value.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        }
        Text(g.line, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
    }
}

@Composable
private fun NorthStarLine(m: NorthStarMetric, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.label, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(m.value ?: "—", style = MekaType.itemMeta, color = Meka.colors.textSecondary)
        }
        Text(m.line, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** The check that pops when the week is reviewed (reduced motion: fades in). */
@Composable
private fun ReviewedCheck(visible: Boolean) {
    val reduced = Meka.reducedMotion
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(MekaMotion.appear(true))
        else scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), initialScale = 0.6f) +
            fadeIn(MekaMotion.appear(false)),
        exit = fadeOut(MekaMotion.appear(reduced)),
    ) {
        Box(Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent).padding(horizontal = MekaSpace.l, vertical = MekaSpace.s)) {
            Text("✓", style = MekaType.upNextTitle, color = Meka.colors.onAccent)
        }
    }
}

/**
 * The weekly review's card in Today: "Review your week" with the week in one line. It rises in at 18:00 on Sunday
 * and stays through Monday until that week is reviewed on either device; tapping it opens Review on that week.
 */
@Composable
internal fun ReviewCardTile(card: ReviewCard, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onOpen() }.padding(MekaSpace.l),
    ) {
        Text(card.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        Text(card.line, style = MekaType.itemMeta, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
    }
}
