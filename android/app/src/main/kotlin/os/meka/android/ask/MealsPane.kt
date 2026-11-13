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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import os.meka.core.domain.MealRules
import os.meka.core.domain.MealsPageRules
import os.meka.core.domain.MealsShopped
import os.meka.core.facade.MekaCore

/**
 * Ask → Dinners in the page shape (every page under Ask in one shape, slice 8; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ, one gold + Add (A favourite dinner, a field in the
 * sheet: "Chilli: mince, beans, rice"), three numbers (dinners planned, ingredients to add to shopping, favourites),
 * then This week (a row a day, tonight's in brass), Shopping (Add n ingredients to shopping, while a dinner is
 * planned) and Favourites. A day's row opens its sheet with the favourites to pick (or None); Shopping's opens the
 * gold button with Undo; a favourite's opens its ingredients, the week's days to plan it on and Remove.
 *
 * Motion: the title travels in from Ask's tile (shared title); the header, summary, tiles and cards appear in turn;
 * cards move with the list's item motion; the ⓘ's words unfold and the sheets rise on the expand spring
 * (LifePageParts); a picked chip's colour blends with a tick haptic and the day's sheet drops; Add to shopping gives a
 * light haptic and its line cross-fades, Undo unfolding under it; the answer line cross-fades (lit in the accent when
 * refused). Reduced motion: cross-fades.
 */
/** Dinners' list, for the by-hand tests' bounded scroll. */
const val MEALS_LIST_TAG = "meals-list"
const val MEALS_ROW_SHEET_TAG = "meals-row-sheet"
const val MEALS_ALL_SHEET_TAG = "meals-all-sheet"

@Composable
fun MealsPane(core: MekaCore, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val v by core.mealsView.collectAsState()
    val page = remember(v) { core.mealsPage(v) }
    // The line after Add: what was added, or that nothing could be read.
    var said by remember { mutableStateOf<String?>(null) }
    // What the last "Add to shopping" did, so it can be taken back while the pane is open.
    var shopped by remember { mutableStateOf<MealsShopped?>(null) }
    var infoOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var addChosen by remember { mutableStateOf<String?>(null) }
    var allCard by remember { mutableStateOf<String?>(null) }
    var rowKey by remember { mutableStateOf<String?>(null) }
    val refused = said == MealRules.NOT_READ
    val saidColor by animateColorAsState(
        if (refused) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "meals-said",
    )
    fun plan(day: Long, mealId: String?) {
        haptics.tick()
        scope.launch { runCatching { core.planMeal(day, mealId) } }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(MEALS_LIST_TAG),
            contentPadding = PaddingValues(MekaSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                LifePageHeader(
                    page.title, page.info, infoOpen,
                    titleModifier = Modifier.sharedTitleInPane(SharedMotion.paneKey(MoreItem.DINNERS)).appear(rememberAppearance(0)),
                    onInfo = { haptics.tick(); infoOpen = !infoOpen },
                    onAdd = { haptics.tick(); addChosen = MealsPageRules.ADD_CHOICES.singleOrNull()?.key; said = null; addOpen = true },
                )
            }
            item(key = "summary") {
                Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Crossfade(MealsPageRules.summary(v), animationSpec = MekaMotion.appear(reduced), label = "meals-summary") { s ->
                        Text(s, style = MekaType.caption, color = Meka.colors.textSecondary)
                    }
                    // What the last add or Add to shopping said, while no sheet is open.
                    val line = if (addOpen || rowKey != null) null else said ?: shopped?.line
                    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "meals-line") { l ->
                        l?.let { Text(it, style = MekaType.caption, color = saidColor) }
                    }
                }
            }
            item(key = "tiles") { LifeNumberTiles(page.tiles, Modifier.appear(rememberAppearance(2))) }
            page.cards.forEachIndexed { i, c ->
                item(key = "card-${c.key}") {
                    LifePageCard(
                        c, Modifier.animateItem().appear(rememberAppearance(3 + i)),
                        onMore = { haptics.tick(); allCard = c.key },
                        onRow = { r -> haptics.tick(); said = null; rowKey = r.key },
                    )
                }
            }
            item(key = "shared") {
                Text(MealRules.SHARED, style = MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = Modifier.animateItem().padding(top = MekaSpace.l))
            }
        }
        // + Add: a favourite dinner, its name, a colon, then its ingredients.
        LifeSheet(addOpen, page.addTitle, LIFE_ADD_SHEET_TAG, onClose = { addOpen = false }) {
            AddChoiceRows(page.addChoices, addChosen) { c -> haptics.tick(); said = null; addChosen = c.key }
            addChosen?.let { choice ->
                Field(MealsPageRules.hint(choice)) { typed ->
                    haptics.light()
                    scope.launch {
                        val r = runCatching { core.addMeal(typed) }.getOrNull() ?: MealRules.NOT_READ
                        said = r
                        if (r == MealRules.NOT_READ) haptics.tick() else addOpen = false
                    }
                }
                if (refused) Text(MealRules.NOT_READ, style = MekaType.caption, color = saidColor)
            }
        }
        // See all: every row of one card, a tap opens its sheet.
        val all = allCard
        LifeSheet(all != null, all ?: MealsPageRules.TITLE, MEALS_ALL_SHEET_TAG, onClose = { allCard = null }) {
            if (all != null) MealsPageRules.allRows(v, all).forEach { r ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Button) { haptics.tick(); allCard = null; rowKey = r.key }
                        .semantics(mergeDescendants = true) {}
                        .minTouch().padding(vertical = MekaSpace.xs),
                ) {
                    Text(r.name, style = MekaType.body, color = Meka.colors.textPrimary)
                    Text(listOf(r.line, r.whenText).filter { it.isNotEmpty() }.joinToString(" · "), style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
        // A row's sheet: a day's dinner to pick, the shopping button, or one favourite.
        val openKey = rowKey
        val day = openKey?.let { MealsPageRules.day(v, it) }
        val meal = openKey?.let { MealsPageRules.meal(v, it) }
        val isShop = openKey == MealsPageRules.KEY_SHOP
        val sheetTitle = day?.label ?: meal?.title ?: if (isShop) MealsPageRules.SHOPPING else MealsPageRules.TITLE
        LifeSheet(openKey != null, sheetTitle, MEALS_ROW_SHEET_TAG, onClose = { rowKey = null }) {
            when {
                day != null -> {
                    Crossfade(day.title, animationSpec = MekaMotion.appear(reduced), label = "meal-day-${day.day}") { t ->
                        Text(t ?: MealRules.NOT_PLANNED, style = MekaType.body, color = if (t == null) Meka.colors.textTertiary else Meka.colors.textPrimary)
                    }
                    if (day.title != null) Text(day.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    if (v.favourites.isEmpty()) {
                        Text(MealsPageRules.PICK_FIRST, style = MekaType.caption, color = Meka.colors.textTertiary)
                    } else {
                        ChipLine {
                            Chip(MealRules.NONE_CHOICE, day.mealId == null) { plan(day.day, null); rowKey = null }
                            v.favourites.forEach { f -> Chip(f.title, day.mealId == f.id) { plan(day.day, f.id); rowKey = null } }
                        }
                    }
                }
                isShop -> ShoppingSheet(
                    v.shoppingLabel, shopped?.line ?: v.shoppingLine,
                    canUndo = shopped?.let { it.added.isNotEmpty() || it.revived.isNotEmpty() } == true,
                    onAdd = {
                        haptics.light()
                        scope.launch { shopped = runCatching { core.mealsToShopping() }.getOrNull() }
                    },
                    onUndo = {
                        val done = shopped ?: return@ShoppingSheet
                        haptics.tick()
                        shopped = null
                        scope.launch { runCatching { core.undoMealsShopping(done) } }
                    },
                )
                meal != null -> {
                    Text(meal.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    Text(MealsPageRules.PLAN_FOR, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
                    val planned = MealsPageRules.plannedDays(v, meal.id)
                    ChipLine {
                        v.week.forEach { d ->
                            val on = d.day in planned
                            Chip(d.label, on) { plan(d.day, if (on) null else meal.id) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
                        Text(
                            "Remove", style = MekaType.caption, color = Meka.colors.textTertiary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                .clickable(role = Role.Button) {
                                    haptics.tick()
                                    rowKey = null
                                    scope.launch { runCatching { core.removeMeal(meal.id) } }
                                }
                                .semantics { contentDescription = "Remove ${meal.title}" }
                                .minTouch()
                                .padding(MekaSpace.xs),
                        )
                    }
                }
                openKey != null -> Text(MealsPageRules.GONE, style = MekaType.caption, color = Meka.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun ChipLine(chips: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) { chips() }
}

/** Shopping's sheet: the gold "Add n ingredients to shopping" (when there's anything to add), its line and Undo. */
@Composable
private fun ShoppingSheet(label: String?, line: String, canUndo: Boolean, onAdd: () -> Unit, onUndo: () -> Unit) {
    val reduced = Meka.reducedMotion
    label?.let {
        Text(
            it, style = MekaType.itemMeta, color = Meka.colors.onAccent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onAdd)
                .minTouch().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        )
    }
    Crossfade(line, animationSpec = MekaMotion.appear(reduced), label = "meals-shopping") { l ->
        Text(l, style = MekaType.caption, color = Meka.colors.textSecondary)
    }
    AnimatedVisibility(canUndo, enter = unfold(), exit = fold()) {
        Text(
            "Undo", style = MekaType.caption, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onUndo).minTouch(),
        )
    }
}
