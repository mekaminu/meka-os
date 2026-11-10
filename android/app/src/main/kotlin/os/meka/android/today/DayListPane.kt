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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import os.meka.android.goals.Grip
import os.meka.android.goals.GripHandle
import os.meka.core.domain.ChecklistRules
import os.meka.core.domain.StudyRules
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import os.meka.android.ask.MicGlyph
import os.meka.android.capture.CaptureListener
import os.meka.android.capture.VoiceNoteRecorder
import os.meka.core.domain.DayListSpoken
import os.meka.core.domain.TalkProblem

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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayListPane(
    core: MekaCore, onClose: () -> Unit, closeLabel: String? = "‹ Today", forDay: Long? = null,
    /**
     * Inside the folded Week board (`BoardFolded.dc.html`): the day's line ("Mon 9 November · today") in place of the
     * big date, the total as a pill, no Times switch; the add bar's prompt ("Add to Mon · Enter for the next") and the
     * line under the rows. Null: the Day list's own screen.
     */
    inBoard: DayListInBoard? = null,
    /** The list's and the add bar's side inset: the gutter, less in a column of the open Fold's spread. */
    sidePadding: androidx.compose.ui.unit.Dp = MekaSpace.gutter,
) {
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
    // Drag to reorder (slice 1c): the held row follows the finger by its grip; the open rows it passes make room.
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(0f) }
    val gapPx = with(LocalDensity.current) { MekaSpace.xxs.toPx() }
    fun reorder(id: String, toRow: Int) {
        scope.launch {
            runCatching { core.reorderDayList(day, id, toRow, timesOn) }.getOrNull()
                ?.let { l -> haptics.light(); undo.show(l) { core.undoBoardMove() } }
        }
    }

    fun add(text: String) {
        if (text.isBlank()) return
        draft = ""
        haptics.light()
        scope.launch {
            val added = runCatching { core.addToDayList(text, day) }.getOrNull()
            said = added?.line ?: DayListRules.TYPE_FIRST
        }
    }
    val tick: (String) -> Unit = { id -> scope.launch { runCatching { core.complete(id) } } }

    // Say them: listening stays on — each stretch he says lands as tasks in the order said, then it listens for the
    // next — until he taps Stop or goes quiet. His words show under the bar as he speaks; nothing is recorded or kept.
    var saying by remember { mutableStateOf(false) }
    var heardAny by remember { mutableStateOf(false) }
    val sayDay by rememberUpdatedState(day)
    lateinit var listener: CaptureListener
    listener = remember {
        CaptureListener(
            context, scope,
            onHeard = { words, _, _ ->
                scope.launch {
                    val added = runCatching { core.sayToDayList(words, sayDay) }.getOrNull()
                    if (added != null) { heardAny = true; haptics.light(); said = added.line }
                    else if (!heardAny) said = DayListSpoken.HEARD_NOTHING
                    if (saying) listener.start()
                }
            },
            onNothing = { line ->
                saying = false
                if (line != null) said = line else if (!heardAny) said = DayListSpoken.HEARD_NOTHING
            },
        )
    }
    DisposableEffect(Unit) { onDispose { listener.release() } }
    LaunchedEffect(listener.partial) { if (listener.listening && listener.partial.isNotBlank()) said = "“${listener.partial}”" }
    val startSaying: () -> Unit = { saying = true; heardAny = false; said = DayListSpoken.LISTENING; listener.start() }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSaying() else said = TalkProblem.NO_PERMISSION.line
    }
    val sayThem: () -> Unit = {
        haptics.tick()
        when {
            saying -> { saying = false; listener.finishNow() }
            !VoiceNoteRecorder.micAllowed(context) -> askMic.launch(Manifest.permission.RECORD_AUDIO)
            else -> startSaying()
        }
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = sidePadding, vertical = MekaSpace.l),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
            ) {
                if (closeLabel != null) item(key = "close") {
                    Text(closeLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                        modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
                }
                if (v == null) {
                    item(key = "gone") { Text("This day isn't on the board any more.", style = MekaType.itemMeta, color = Meka.colors.textTertiary) }
                    return@LazyColumn
                }
                if (inBoard != null) item(key = "head") {
                    Row(Modifier.appear(rememberAppearance(0)), verticalAlignment = Alignment.CenterVertically) {
                        Text(inBoard.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                        Text(
                            v.total, style = MekaType.metaStrong, color = Meka.colors.accent,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.accent.copy(alpha = 0.14f))
                                .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                        )
                    }
                } else item(key = "head") {
                    Column(Modifier.appear(rememberAppearance(0))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.title.substringBefore(" · "), style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                            DayChip(if (timesOn) "Times on" else "Times off", selected = timesOn) { haptics.tick(); timesOn = !timesOn }
                            Text(v.total, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.padding(start = MekaSpace.xs))
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
                val openRows = v.rows.count { !it.done }
                val dragFrom = v.rows.indexOfFirst { it.id == dragId }
                val dragTo = if (dragFrom < 0) -1 else ChecklistRules.dropIndex(dragFrom, dragDy, rowPx, openRows)
                itemsIndexed(v.rows, key = { _, r -> "r-" + r.id }) { i, r ->
                    val held = dragId == r.id
                    val shift = when {
                        dragFrom < 0 || held -> 0f
                        dragFrom < dragTo && i in (dragFrom + 1)..dragTo -> -rowPx
                        dragTo < dragFrom && i in dragTo until dragFrom -> rowPx
                        else -> 0f
                    }
                    val slide = remember { Animatable(0f) }
                    LaunchedEffect(shift, dragId == null) { if (dragId == null) slide.snapTo(0f) else slide.animateTo(shift, MekaMotion.replan(reduced)) }
                    val grip = if (DayListRules.canDrag(r, v.timesOn) && openRows > 1) Grip(
                        label = StudyRules.gripLabel(r.title),
                        dy = if (held) dragDy else 0f, lifted = held,
                        onStart = { haptics.light(); sheetId = null; dragDy = 0f; dragId = r.id },
                        onDrag = { dragDy += it },
                        onEnd = {
                            val to = ChecklistRules.dropIndex(i, dragDy, rowPx, openRows)
                            if (to != i) reorder(r.id, to)
                            dragId = null; dragDy = 0f
                        },
                        onUp = DayListRules.stepTo(v, r.id, up = true)?.let { to -> { haptics.tick(); reorder(r.id, to) } },
                        onDown = DayListRules.stepTo(v, r.id, up = false)?.let { to -> { haptics.tick(); reorder(r.id, to) } },
                    ) else null
                    DayListRowView(r, v.timesOn, tick, onOpen = { haptics.tick(); sheetId = r.id },
                        modifier = Modifier.then(if (dragId != null) Modifier else Modifier.animateItem()).appear(rememberAppearance(2))
                            .graphicsLayer { translationY = if (held) 0f else slide.value },
                        grip = grip, onMeasured = { if (it > 0f) rowPx = it + gapPx })
                }
                item(key = "hint") {
                    Text(inBoard?.hint ?: DayListRules.HINT, style = MekaType.micro, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(top = MekaSpace.xs).animateItem())
                }
            }

            // The add bar stays open at the foot, over the keyboard: Enter adds and clears for the next.
            Column(
                Modifier.fillMaxWidth().background(Meka.colors.background).padding(horizontal = sidePadding, vertical = MekaSpace.xs),
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
                    Box(Modifier.weight(1f).padding(vertical = MekaSpace.m)) {
                        if (draft.isEmpty()) Text(inBoard?.placeholder ?: DayListRules.PLACEHOLDER, style = MekaType.body, color = Meka.colors.textTertiary)
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
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = inBoard?.placeholder ?: DayListRules.PLACEHOLDER },
                        )
                    }
                    Text(
                        "+", style = MekaType.itemTitle, color = Meka.colors.onAccent,
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Add") { add(draft); runCatching { focus.requestFocus() } }
                            .minTouch().clip(CircleShape).background(Meka.colors.accent).padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    SayChip(saying, listener.level, sayThem)
                    DayChip(DayListRules.PASTE) {
                        val primary = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                        val text = primary?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (text.isBlank()) said = DayListRules.COPY_FIRST else add(text)
                    }
                    if (v != null && v.openIds.isNotEmpty()) {
                        // Today fits from now (never before 9:00); another day from 9:00.
                        val from = DayListRules.fitFrom(v.isToday, java.time.LocalTime.now().let { it.hour * 60 + it.minute })
                        DayChip(DayListRules.fitLabel(from)) {
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
        EventUndoBar(undo, Modifier.align(Alignment.BottomCenter).padding(bottom = MekaSpace.xxl + MekaSpace.xxl + MekaSpace.l))

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

/** What the folded Week board puts on a day's list: its line, the add bar's prompt and the line under the rows. */
data class DayListInBoard(val title: String, val placeholder: String, val hint: String)

/** The gold bar that fills as the day's planned minutes are done, with "2 of 5 done · ends about 11:15" under it. */
@Composable
private fun DayProgressBar(progress: Float, line: String, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), MekaMotion.appear(reduced), label = "day-list-bar")
    Column(modifier.fillMaxWidth().padding(top = MekaSpace.xs, bottom = MekaSpace.xs)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
            Box(Modifier.fillMaxWidth(shown).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        }
        Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(top = MekaSpace.xxs))
    }
}

/** A slim row: tick · time · title · area dot and name · length chip. Tapping the row opens its sheet. */
@Composable
private fun DayListRowView(
    r: DayListRow, timesOn: Boolean, tick: (String) -> Unit, onOpen: () -> Unit, modifier: Modifier,
    grip: Grip? = null, onMeasured: (Float) -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    // Drag to reorder: the held row lifts onto the raised surface (scale 1.02 on the approve spring), as in Study.
    val lift by animateFloatAsState(if (grip?.lifted == true && !reduced) 1f else 0f, MekaMotion.approve(reduced), label = "day-list-lift")
    val bg by animateColorAsState(if (grip?.lifted == true) Meka.colors.surfaceRaised else Meka.colors.background, MekaMotion.appear(reduced), label = "day-list-held")
    Row(
        modifier.fillMaxWidth().zIndex(if (grip?.lifted == true) 1f else 0f).onSizeChanged { onMeasured(it.height.toFloat()) }
            .graphicsLayer { translationY = grip?.dy ?: 0f; scaleX = 1f + 0.02f * lift; scaleY = 1f + 0.02f * lift }
            .clip(RoundedCornerShape(MekaRadius.s)).background(bg).clickable(role = Role.Button, onClickLabel = "Options") { onOpen() }
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
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
            )
        }
        grip?.let { GripHandle(it, "day-list-grip-${r.id}") }
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
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/**
 * Say them: the mic and its name; while listening it reads Stop on the accent edge and the mic breathes with his voice
 * (scale on the level, none with reduced motion).
 */
@Composable
private fun SayChip(on: Boolean, level: Float, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val breathe by animateFloatAsState(if (on && !reduced) 1f + 0.25f * level.coerceIn(0f, 1f) else 1f, MekaMotion.appear(reduced), label = "day-say-breathe")
    Row(
        Modifier.testTagSay().clip(RoundedCornerShape(MekaRadius.pill))
            .clickable(role = Role.Button, onClickLabel = if (on) DayListSpoken.STOP else DayListSpoken.SAY) { onClick() }.minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .then(if (on) Modifier.border(1.dp, Meka.colors.accent, RoundedCornerShape(MekaRadius.pill)) else Modifier)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .semantics { contentDescription = if (on) "${DayListSpoken.STOP} listening" else DayListSpoken.SAY },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        MicGlyph(Meka.colors.accent, Modifier.graphicsLayer { scaleX = breathe; scaleY = breathe })
        Text(if (on) DayListSpoken.STOP else DayListSpoken.SAY, style = MekaType.caption, maxLines = 1,
            color = if (on) Meka.colors.textPrimary else Meka.colors.textSecondary)
    }
}

private fun Modifier.testTagSay(): Modifier = this.then(Modifier.testTag(DAY_LIST_SAY_TAG))

/** The Say them chip, for screen tests. */
const val DAY_LIST_SAY_TAG = "day-list-say"

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
