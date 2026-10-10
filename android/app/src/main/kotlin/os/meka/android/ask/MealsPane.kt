package os.meka.android.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import os.meka.android.lists.Chip
import os.meka.android.lists.Field
import os.meka.android.lists.fold
import os.meka.android.lists.unfold
import os.meka.android.shell.MoreItem
import os.meka.android.shell.SharedMotion
import os.meka.android.today.SectionLabel
import os.meka.core.domain.MealDayRow
import os.meka.core.domain.MealRow
import os.meka.core.domain.MealRules
import os.meka.core.domain.MealsShopped
import os.meka.core.facade.MekaCore

/**
 * Ask → More → Dinners (meal plan → shopping, slice 1): the week's dinners a day at a time (tap a day, pick one of the
 * favourites or None), "Add 9 ingredients to shopping" putting the week's ingredients on the shared list once each
 * (with Undo while the pane is open), and the favourites typed once ("Chilli: mince, beans, rice"), each with Remove.
 *
 * Motion: the pane springs up (MekaPane) with the row's title travelling in; the summary, the day's line and the
 * shopping line cross-fade; rows stagger in 40 ms apart; a day unfolds its choices in place (expand spring) and the
 * chosen chip's colour blends with a tick haptic; Add to shopping gives a light haptic; favourites arrive and leave with
 * the list's item motion. Reduced motion: cross-fades.
 */
@Composable
fun MealsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.mealsView.collectAsState()
    var open by remember { mutableStateOf<Long?>(null) }
    // The line under the field after Add: what was added, or that nothing could be read.
    var said by remember { mutableStateOf<String?>(null) }
    // What the last "Add to shopping" did, so it can be taken back while the pane is open.
    var shopped by remember { mutableStateOf<MealsShopped?>(null) }
    val saidColor by animateColorAsState(
        if (said == MealRules.NOT_READ) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "meals-said",
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(MekaSpace.gutter),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "close") {
            Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
        }
        item(key = "title") {
            Text("Dinners", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.DINNERS)).appear(rememberAppearance(0)))
        }
        item(key = "summary") {
            Crossfade(v.summary, animationSpec = MekaMotion.appear(reduced), label = "meals-summary") { s ->
                Text(s, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(1)))
            }
        }
        if (v.week.isNotEmpty()) {
            item(key = "label-week") {
                SectionLabel("THIS WEEK", Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(2)))
            }
            itemsIndexed(v.week, key = { _, r -> "day-${r.day}" }) { i, r ->
                DayRow(
                    r, v.favourites, open == r.day, Modifier.appear(rememberAppearance(3 + i)),
                    onToggle = { haptics.tick(); open = if (open == r.day) null else r.day },
                ) { mealId ->
                    haptics.tick()
                    scope.launch { runCatching { core.planMeal(r.day, mealId) } }
                    open = null
                }
            }
            item(key = "shopping") {
                Column(Modifier.padding(top = MekaSpace.m).appear(rememberAppearance(10)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    v.shoppingLabel?.let { label ->
                        Text(
                            label, style = MekaType.itemMeta, color = Meka.colors.onAccent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                                .clickable(role = Role.Button) {
                                    haptics.light()
                                    scope.launch { shopped = runCatching { core.mealsToShopping() }.getOrNull() }
                                }
                                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
                        )
                    }
                    Crossfade(shopped?.line ?: v.shoppingLine, animationSpec = MekaMotion.appear(reduced), label = "meals-shopping") { line ->
                        Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    AnimatedVisibility(shopped?.let { it.added.isNotEmpty() || it.revived.isNotEmpty() } == true, enter = unfold(), exit = fold()) {
                        Text(
                            "Undo", style = MekaType.caption, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                                val done = shopped ?: return@clickable
                                haptics.tick()
                                shopped = null
                                scope.launch { runCatching { core.undoMealsShopping(done) } }
                            }.minTouch(),
                        )
                    }
                }
            }
        }
        item(key = "label-favourites") {
            SectionLabel("FAVOURITES", Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(11)))
        }
        item(key = "field") {
            Column(Modifier.appear(rememberAppearance(12)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(MealRules.ADD_HINT) { text ->
                    haptics.light()
                    scope.launch { said = runCatching { core.addMeal(text) }.getOrNull() ?: MealRules.NOT_READ }
                }
                Crossfade(said, animationSpec = MekaMotion.appear(reduced), label = "meals-added") { line ->
                    line?.let { Text(it, style = MekaType.caption, color = saidColor) }
                }
            }
        }
        itemsIndexed(v.favourites, key = { _, r -> "meal-${r.id}" }) { i, r ->
            MealRowView(r, Modifier.animateItem().appear(rememberAppearance(13 + i))) {
                haptics.tick()
                scope.launch { runCatching { core.removeMeal(r.id) } }
            }
        }
        item(key = "shared") {
            Text(MealRules.SHARED, style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(top = MekaSpace.l))
        }
    }
}

@Composable
private fun DayRow(
    row: MealDayRow,
    favourites: List<MealRow>,
    open: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    choose: (String?) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = row.spoken
                    stateDescription = if (open) "Choices shown" else "Tap to pick a dinner"
                }
                .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            Text(row.label, style = MekaType.caption, color = if (row.label == "Tonight") Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.weight(0.35f))
            Column(Modifier.weight(0.65f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                Crossfade(row.title, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "meal-day-${row.day}") { t ->
                    Text(t ?: MealRules.NOT_PLANNED, style = MekaType.body, color = if (t == null) Meka.colors.textTertiary else Meka.colors.textPrimary)
                }
                if (row.title != null) Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = MekaSpace.m, end = MekaSpace.m, bottom = MekaSpace.m),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                if (favourites.isEmpty()) {
                    Text("Add a favourite below first", style = MekaType.caption, color = Meka.colors.textTertiary)
                } else {
                    Chip(MealRules.NONE_CHOICE, row.mealId == null) { choose(null) }
                    favourites.forEach { f -> Chip(f.title, row.mealId == f.id) { choose(f.id) } }
                }
            }
        }
    }
}

@Composable
private fun MealRowView(row: MealRow, modifier: Modifier, onRemove: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = row.spoken },
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(row.line, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        Text(
            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove ${row.title}" }
                .padding(MekaSpace.xs),
        )
    }
}
