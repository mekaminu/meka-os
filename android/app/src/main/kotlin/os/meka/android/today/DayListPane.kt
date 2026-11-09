package os.meka.android.today

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.calendar.EventUndoBar
import os.meka.android.calendar.rememberEventUndo
import os.meka.android.designsystem.LocalSharedScope
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.areaTone
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BoardAreaRules
import os.meka.core.domain.DayListRow
import os.meka.core.domain.DayListRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.TaskEstimateRules
import os.meka.core.domain.WeekBoardRules
import os.meka.core.facade.MekaCore

/**
 * The Day list (approved 2026-11-08, `docs/design/day-list/Main.dc.html`): one screen for one day where a long list of
 * tasks goes in fast. The day and date, Times on/off and the day's total; a gold bar with "2 of 5 done · ends about
 * 11:15"; slim rows (tick · time · title · area dot and name · length); an add bar that stays open — Enter adds the line
 * and clears for the next, "30m", "9:00" and "#work" are read from the text — with Paste a list and Fit times from 9:00
 * under it. A row's tap opens its sheet: length, area, move to, Edit and Done. The same tasks as the Week board and
 * Today (synced with the Mac).
 *
 * Motion: the pane springs up (MekaPane); rows stagger in, a new one arrives with the list's item motion and the rest
 * glide; the bar fills on the appear spring; the sheet springs up over a dimmed list; the line under the bar
 * cross-fades. Reduced motion: cross-fades only.
 */
@Composable
fun DayListPane(core: MekaCore, onClose: () -> Unit, closeLabel: String = "‹ Today", forDay: Long? = null) {
    val board by core.weekBoard.collectAsState()
    // Null: today's list (it follows the board's today past midnight).
    val day = forDay ?: board.todayEpochDay
    var timesOn by rememberSaveable { mutableStateOf(true) }
    val v = remember(board, day, timesOn) { core.dayList(board, day, timesOn) }
    val conflicts by core.conflicts.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val undo = rememberEventUndo()
    var draft by rememberSaveable { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var sheetId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current

    fun add(text: String) {
        if (text.isBlank()) return
        draft = ""
        haptics.light()
        scope.launch {
            val added = runCatching { core.addToDayList(text, day) }.getOrNull()
            said = added?.line ?: "Type a task first"
        }
    }
    val tick: (String) -> Unit = { id -> scope.launch { runCatching { core.complete(id) } } }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                item(key = "close") {
                    Text(closeLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
                }
                if (v == null) {
                    item(key = "gone") { Text("This day isn't on the board any more.", style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                    return@LazyColumn
                }
                item(key = "head") {
                    Column(Modifier.appear(rememberAppearance(0))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.title.substringBefore(" · "), style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                            DayChip(if (timesOn) "Times on" else "Times off", selected = timesOn) { haptics.tick(); timesOn = !timesOn }
                            Text(v.total, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.padding(start = MekaSpace.s))
                        }
                        if (v.isToday) Text(v.title.substringAfter(" · "), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                    }
                }
                item(key = "bar") { DayProgressBar(v.progress, listOfNotNull(v.doneLine, v.endsLine).joinToString(" · "), Modifier.appear(rememberAppearance(1))) }
                if (v.isEmpty) {
                    item(key = "empty") {
                        Text(DayListRules.EMPTY, style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                            modifier = Modifier.padding(vertical = MekaSpace.m).animateItem().appear(rememberAppearance(2)))
                    }
                }
                items(v.rows, key = { "r-" + it.id }) { r ->
                    DayListRowView(r, v.timesOn, tick, onOpen = { haptics.tick(); sheetId = r.id },
                        modifier = Modifier.animateItem().appear(rememberAppearance(2)))
                }
                item(key = "hint") {
                    Text(DayListRules.HINT, style = MekaType.micro, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.s).animateItem())
                }
            }

            // The add bar stays open at the foot, over the keyboard: Enter adds and clears for the next.
            Column(
                Modifier.fillMaxWidth().background(Meka.colors.background).padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xs),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                AnimatedContent(
                    targetState = said,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "day-list-said",
                ) { line -> if (line != null) Text(LoneWordGlue.kept(line), style = MekaType.caption, color = Meka.colors.textSecondary) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                        .padding(start = MekaSpace.l, end = MekaSpace.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = MekaSpace.s)) {
                        if (draft.isEmpty()) Text(DayListRules.PLACEHOLDER, style = MekaType.body, color = Meka.colors.textTertiary)
                        BasicTextField(
                            value = draft,
                            onValueChange = { next ->
                                // A hardware Enter or a pasted list arrives as new lines: add them at once.
                                if (next.contains('\n')) add(next) else draft = next
                            },
                            singleLine = true,
                            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                            cursorBrush = SolidColor(Meka.colors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { add(draft) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = DayListRules.PLACEHOLDER },
                        )
                    }
                    Text(
                        "+", style = MekaType.itemTitle, color = Meka.colors.onAccent,
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Add") { add(draft); runCatching { focus.requestFocus() } }
                            .minTouch().clip(CircleShape).background(Meka.colors.accent).padding(horizontal = MekaSpace.s, vertical = MekaSpace.xxs),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    DayChip(DayListRules.PASTE) {
                        val primary = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                        val text = primary?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (text.isBlank()) said = "Copy a list first, one task per line" else add(text)
                    }
                    if (v != null && v.openIds.isNotEmpty()) {
                        // Today fits from now (never before 9:00); another day from 9:00.
                        val from = if (!v.isToday) DayListRules.DAY_START
                        else maxOf(DayListRules.DAY_START, DayListRules.startOf(true, java.time.LocalTime.now().let { it.hour * 60 + it.minute }))
                        DayChip("Fit times from ${os.meka.core.domain.TaskWhenRules.timeLabel(from)}") {
                            haptics.tick()
                            scope.launch {
                                val fit = runCatching { core.fitDayList(day, from) }.getOrNull() ?: return@launch
                                undo.show(fit.line) { core.undoFitDayList(fit) }
                            }
                        }
                    }
                }
            }
        }
        EventUndoBar(undo, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))

        // A task's sheet springs up over the dimmed list.
        val open = v?.rows?.firstOrNull { it.id == sheetId }
        BackHandler(enabled = open != null && editingId == null) { sheetId = null }
        DayTaskSheet(
            row = open, board = board, day = day,
            onClose = { sheetId = null },
            onLength = { id, m -> scope.launch { runCatching { core.setEstimate(id, m) }.getOrNull()?.let { l -> undo.show(l) { core.undoEstimate() } } } },
            onArea = { id, a -> scope.launch { runCatching { core.areaOnBoard(id, a) }.getOrNull()?.let { l -> undo.show(l) { core.undoBoardArea() } } } },
            onMoveTo = { id, d -> sheetId = null; scope.launch { runCatching { core.moveOnBoard(id, d, Int.MAX_VALUE) }.getOrNull()?.let { l -> undo.show(l) { core.undoBoardMove() } } } },
            onNextWeek = { id, w -> sheetId = null; scope.launch { runCatching { core.parkOnBoard(id, w, Int.MAX_VALUE) }.getOrNull()?.let { l -> undo.show(l) { core.undoBoardMove() } } } },
            onSomeday = { id -> sheetId = null; scope.launch { runCatching { core.somedayOnBoard(id) }.getOrNull()?.let { l -> undo.show(l) { core.undoBoardMove() } } } },
            onEdit = { id -> sheetId = null; editingId = id },
            onDone = { id -> sheetId = null; haptics.light(); tick(id) },
        )

        // Edit opens the task's full detail (when, reminder, repeat, notes, steps, delete), as on Today.
        val editing = board.dayOf(editingId ?: "")?.cards?.firstOrNull { it.id == editingId }?.task
        var shown by remember { mutableStateOf(editing) }
        if (editing != null) shown = editing
        val actions = todayActions(core, scope, { editingId }, { editingId = it })
        BackHandler(enabled = editing != null) { editingId = null }
        CompositionLocalProvider(LocalSharedScope provides null) {
            MekaPane(visible = editing != null) {
                shown?.let { t -> DetailPane(t, conflicts.filter { it.taskId == t.id }, actions, Modifier.fillMaxSize(), onClose = { editingId = null }) }
            }
        }
    }
}

/** The gold bar that fills as the day's planned minutes are done, with "2 of 5 done · ends about 11:15" under it. */
@Composable
private fun DayProgressBar(progress: Float, line: String, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), MekaMotion.appear(reduced), label = "day-list-bar")
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs, bottom = MekaSpace.s)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(shown).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        }
        Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
    }
}

/** A slim row: tick · time · title · area dot and name · length chip. Tapping the row opens its sheet. */
@Composable
private fun DayListRowView(r: DayListRow, timesOn: Boolean, tick: (String) -> Unit, onOpen: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button, onClickLabel = "Options") { onOpen() }
            .padding(vertical = MekaSpace.xxs).semantics { contentDescription = r.spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.done) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(Meka.colors.accent), contentAlignment = Alignment.Center) {
                Text("✓", style = MekaType.micro, color = Meka.colors.onAccent)
            }
        } else CompleteButton(r.id, r.title, tick, size = 22.dp, settle = true, touch = 48.dp)
        if (timesOn) {
            Text(
                r.time ?: "", style = MekaType.caption, maxLines = 1,
                color = if (r.timeIsSet) Meka.colors.textSecondary else Meka.colors.textTertiary,
                modifier = Modifier.width(44.dp),
            )
        }
        Column(Modifier.weight(1f).padding(end = MekaSpace.xs)) {
            Text(
                r.title, style = MekaType.taskTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (r.done) Meka.colors.textTertiary else Meka.colors.textPrimary,
                textDecoration = if (r.done) TextDecoration.LineThrough else null,
            )
            if (!timesOn && r.time != null) Text(r.time!!, style = MekaType.micro, color = Meka.colors.textSecondary)
        }
        r.area?.let { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = MekaSpace.xs)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Meka.colors.areaTone(a)))
                Text(r.areaLabel.orEmpty(), style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1,
                    modifier = Modifier.padding(start = MekaSpace.xxs).widthIn(max = 72.dp))
            }
        }
        r.length?.let { len ->
            Text(
                len, style = MekaType.micro, maxLines = 1,
                color = if (r.lengthGuessed) Meka.colors.textTertiary else Meka.colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.xs, vertical = 2.dp),
            )
        }
    }
}

/** A chip: Times on, Paste a list, Fit times, and the sheet's choices; [selected] wears the accent edge. */
@Composable
private fun DayChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, maxLines = 1,
        color = if (selected) Meka.colors.textPrimary else Meka.colors.textSecondary,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) { onClick() }.minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .then(if (selected) Modifier.border(1.dp, Meka.colors.accent, RoundedCornerShape(MekaRadius.pill)) else Modifier)
            .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xs),
    )
}

/**
 * A task's sheet (the approved `Sheet.dc.html`, slice 1): LENGTH 15m · 30m · 1h · 2h, AREA, MOVE TO Tomorrow · the
 * week's days · Next week · Someday, then Edit (the full detail: time, repeat, notes, delete) and Done. Every change
 * offers Undo on the bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayTaskSheet(
    row: DayListRow?, board: os.meka.core.domain.WeekBoard, day: Long,
    onClose: () -> Unit,
    onLength: (String, Int?) -> Unit,
    onArea: (String, os.meka.core.domain.BoardArea?) -> Unit,
    onMoveTo: (String, Long) -> Unit,
    onNextWeek: (String, Long) -> Unit,
    onSomeday: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDone: (String) -> Unit,
) {
    val reduced = Meka.reducedMotion
    var shown by remember { mutableStateOf(row) }
    if (row != null) shown = row
    val dim by animateFloatAsState(if (row != null) 0.5f else 0f, MekaMotion.appear(reduced), label = "day-sheet-dim")
    if (dim > 0f) {
        Box(Modifier.fillMaxSize().background(Meka.colors.background.copy(alpha = dim))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() })
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = row != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else slideInVertically(spring(dampingRatio = 0.85f, stiffness = 500f)) { it },
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else slideOutVertically(spring(dampingRatio = 1f, stiffness = 700f)) { it },
        ) {
            val r = shown ?: return@AnimatedVisibility
            val card = r.card
            val shape = RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Meka.colors.surface).border(1.dp, Meka.colors.hairline, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(MekaSpace.xl).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline))
                Text(r.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, maxLines = 3)
                if (!r.done) {
                    SheetLabel("LENGTH")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        card.estimateChoices.forEach { c -> DayChip(TaskEstimateRules.label(c.minutes), selected = c.selected) { if (!c.selected) onLength(r.id, c.minutes) } }
                        if (card.task.estimateMinutes != null) DayChip(TaskEstimateRules.CLEAR) { onLength(r.id, null) }
                    }
                    SheetLabel(BoardAreaRules.MENU_TITLE)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        BoardAreaRules.choices(card).forEach { c ->
                            DayChip(c.label.removePrefix("✓ "), selected = c.selected) { if (!c.selected) onArea(r.id, c.area) }
                        }
                    }
                    SheetLabel("MOVE TO")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        (board.todayEpochDay until board.todayEpochDay + 7).filter { it != day }.forEach { d ->
                            val label = when (d) {
                                board.todayEpochDay -> "Today"
                                board.todayEpochDay + 1 -> "Tomorrow"
                                else -> board.day(d)?.let { "${it.weekday} ${it.number}" }
                            }
                            if (label != null) DayChip(label) { onMoveTo(r.id, d) }
                        }
                        if (!card.task.isRepeating) {
                            board.weeks.getOrNull(1)?.startEpochDay?.let { w -> DayChip("Next week") { onNextWeek(r.id, w) } }
                            DayChip(WeekBoardRules.TO_SOMEDAY) { onSomeday(r.id) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    DayChip("Edit…") { onEdit(r.id) }
                    Spacer(Modifier.weight(1f))
                    if (!r.done) DayChip("Done", selected = true) { onDone(r.id) }
                    DayChip("Close") { onClose() }
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = MekaType.sectionLabel, color = Meka.colors.textTertiary, modifier = Modifier.padding(top = MekaSpace.xs))
}
