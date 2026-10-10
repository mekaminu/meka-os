package os.meka.android.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.LocalSharedScope
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.shell.OpenItem
import os.meka.android.shell.SearchNav
import os.meka.android.today.DetailPane
import os.meka.android.today.SectionLabel
import os.meka.android.today.todayActions
import os.meka.core.domain.SearchHit
import os.meka.core.domain.SearchKind
import os.meka.core.domain.SearchTarget
import os.meka.core.facade.MekaCore

/** Typing settles for this long before the core searches (it is cheap; this just keeps rows from flickering). */
private const val SETTLE_MS = 120L

/**
 * Search everything (build plan M1): tasks, calendar, Waiting for, decisions, renewals, Someday, goals, habits and
 * done tasks, searched on this phone over what is already synced. An open task opens its detail over the results; a
 * done one unfolds "Reopen" in place; a list item, goal or habit opens its tab with its row unfolded; calendar events
 * are shown, not opened.
 *
 * Motion: the field is focused as the pane springs up; groups stagger in 40 ms apart and rows glide (animateItem) as
 * results narrow while typing; the task detail springs up over the results. Reduced motion: cross-fades only.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchPane(
    core: MekaCore, onClose: () -> Unit, openItem: (OpenItem) -> Unit,
    /** What was typed in Ask's field ("See all 12 matches", a task match). */
    initialQuery: String = "",
    /** A task match tapped in Ask: its detail opens over the results. */
    initialTask: String? = null,
) {
    val v by core.searchView.collectAsState()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var selectedId by rememberSaveable { mutableStateOf(initialTask) }
    var unfolded by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val conflicts by core.conflicts.collectAsState()

    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(SETTLE_MS)
        core.search(query)
    }
    // From a task match the detail is what's wanted, not the keyboard.
    LaunchedEffect(Unit) { if (initialTask == null) runCatching { focus.requestFocus() } }

    val close = { scope.launch { core.search("") }; onClose() }
    val tap: (SearchHit) -> Unit = { hit ->
        when {
            hit.kind == SearchKind.DONE -> { haptics.tick(); unfolded = if (unfolded == hit.id) null else hit.id }
            hit.target == SearchTarget.TASK -> { keyboard?.hide(); selectedId = hit.id }
            SearchNav.destination(hit.target) != null -> {
                keyboard?.hide()
                scope.launch { core.search("") }
                openItem(OpenItem(hit.target, hit.id))
            }
        }
        Unit
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text("Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { close() }.minTouch())
            }
            item(key = "title") {
                Text("Search", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.padding(bottom = MekaSpace.m).appear(rememberAppearance(0)))
            }
            item(key = "field") {
                SearchField(query, { query = it; unfolded = null }, focus, Modifier.appear(rememberAppearance(0))) { keyboard?.hide() }
            }
            item(key = "summary") {
                Text(
                    if (v.active || query.isBlank()) v.summary.ifEmpty { HINT } else "",
                    style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                    modifier = Modifier.padding(top = MekaSpace.xs, bottom = MekaSpace.xs).animateItem().appear(rememberAppearance(1)),
                )
            }
            v.groups.forEachIndexed { i, g ->
                item(key = "h-${g.kind.name}") {
                    SectionLabel(g.label, Modifier.padding(top = MekaSpace.m).animateItem().appear(rememberAppearance(i + 2)))
                }
                items(g.hits, key = { "r-${g.kind.name}-${it.id}" }) { hit ->
                    ResultRow(hit, unfolded == hit.id, Modifier.animateItem().appear(rememberAppearance(i + 2)), { tap(hit) }) {
                        haptics.light(); unfolded = null
                        scope.launch { runCatching { core.reopen(hit.id) } }
                    }
                }
                g.moreLine?.let { line ->
                    item(key = "m-${g.kind.name}") {
                        Text(line, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
                    }
                }
            }
        }

        // An open task's detail springs up over the results; it follows edits (and closes when the task leaves).
        val actions = todayActions(core, scope, { selectedId }, { selectedId = it })
        val selected = v.hits.firstOrNull { it.id == selectedId && it.target == SearchTarget.TASK }?.task
        var shown by remember { mutableStateOf(selected) }
        if (selected != null) shown = selected
        // Titles don't travel from here: the same task may also be on Today underneath.
        CompositionLocalProvider(LocalSharedScope provides null) {
            MekaPane(visible = selected != null) {
                shown?.let { t ->
                    DetailPane(t, conflicts.filter { it.taskId == t.id }, actions, Modifier.fillMaxSize(), onClose = { selectedId = null })
                }
            }
        }
    }
}

private const val HINT = "Tasks, calendar, lists, renewals, goals and habits. Searched on this phone; nothing leaves it."

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit, focus: FocusRequester, modifier: Modifier, onDone: () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        if (value.isEmpty()) Text("Search everything…", style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search everything" },
        )
    }
}

@Composable
private fun ResultRow(hit: SearchHit, unfolded: Boolean, modifier: Modifier, onTap: () -> Unit, onReopen: () -> Unit) {
    val tappable = hit.kind == SearchKind.DONE || SearchNav.destination(hit.target) != null || hit.target == SearchTarget.TASK
    val hint = if (hit.kind == SearchKind.DONE) "Shows Reopen" else SearchNav.hint(hit.target)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .then(if (tappable) Modifier.clickable(onClickLabel = hint, role = Role.Button) { onTap() } else Modifier)
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .animateContentSize(MekaMotion.expand(Meka.reducedMotion)),
    ) {
        Text(hit.title, style = MekaType.itemTitle,
            color = if (hit.kind == SearchKind.DONE) Meka.colors.textSecondary else Meka.colors.textPrimary, maxLines = 2)
        hit.detail?.let { Text(it, style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 2) }
        hit.snippet?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2, modifier = Modifier.padding(top = MekaSpace.xxs)) }
        AnimatedVisibility(
            unfolded,
            enter = if (Meka.reducedMotion) fadeIn(MekaMotion.appear(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
            exit = if (Meka.reducedMotion) fadeOut(MekaMotion.appear(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
        ) {
            Text("Reopen", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onReopen() }.padding(vertical = MekaSpace.xxs))
        }
    }
}
