package os.meka.android.goals

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.ask.TripPill
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.DayPickerDialog
import os.meka.android.lists.Field
import os.meka.android.today.SectionLabel
import os.meka.core.domain.ChecklistRules
import os.meka.core.domain.StudyAdded
import os.meka.core.domain.StudyChip
import os.meka.core.domain.StudyPlanInput
import os.meka.core.domain.StudyPlanView
import os.meka.core.domain.StudyCourseView
import os.meka.core.domain.StudyDeletedRow
import os.meka.core.domain.StudyLessonRow
import os.meka.core.domain.StudyModule
import os.meka.core.domain.StudyRemoved
import os.meka.core.domain.StudyBesideRules
import os.meka.core.domain.StudyRules
import os.meka.core.domain.StudyScanRules
import os.meka.core.domain.PictureTextRules
import os.meka.android.capture.PictureRead
import os.meka.android.capture.PictureReader
import os.meka.core.domain.StudyView
import os.meka.core.domain.StudyWeekRow
import os.meka.core.domain.StudyWeekRules
import os.meka.core.domain.StudyNudgeRules
import os.meka.core.domain.StudyCheckinRules
import os.meka.core.domain.StudyWeekState
import os.meka.core.domain.TripRules
import os.meka.core.facade.MekaCore

/**
 * Goals' STUDY section (study plan, slice 1): one row per course (its ring with the percentage, the name, "5 of 24
 * lessons · about 18 h left" and the next lesson), a tap opening [StudyPane]; then "Add a course…". A course removed in
 * its pane comes back with Undo here for a few seconds.
 */
@Composable
internal fun StudySection(
    view: StudyView,
    modifier: Modifier,
    removed: StudyRemoved?,
    onUndo: (StudyRemoved) -> Unit,
    onAdd: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(modifier.testTag("study-section"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        SectionLabel(StudyRules.SECTION)
        for (c in view.courses) {
            CourseRow(c) { haptics.light(); onOpen(c.id) }
        }
        AnimatedVisibility(
            visible = removed != null,
            enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            removed?.let { r -> UndoLine(r) { haptics.light(); onUndo(r) } }
        }
        Field(StudyRules.ADD_COURSE_HINT, Modifier.testTag("study-add-course")) { text -> onAdd(text) }
        if (view.courses.isEmpty()) Text(StudyRules.ADD_COURSE_NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** A course on Goals: its ring, name, progress line and next lesson, and a chevron. */
@Composable
private fun CourseRow(c: StudyCourseView, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "Open ${c.spoken}" }
            .minTouch().padding(vertical = MekaSpace.xs).testTag("study-course-${c.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        CourseRing(c.fraction, "${c.pct}", 40.dp, 4.dp, null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(c.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
            c.next?.let { Text(it, style = MekaType.caption, color = if (c.allDone) Meka.colors.success else Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Text("›", style = MekaType.body, color = Meka.colors.accent)
    }
}

/** A progress ring sweeping to [fraction] from nothing as it appears (glides on later changes), the number inside. */
@Composable
private fun CourseRing(fraction: Float, label: String, size: Dp, width: Dp, sub: String?) {
    val reduced = Meka.reducedMotion
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val fill by animateFloatAsState(if (shown) fraction.coerceIn(0f, 1f) else 0f, MekaMotion.appear(reduced), label = "study-ring")
    val track = Meka.colors.surfaceRaised
    val accent = Meka.colors.accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = width.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arc = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
            if (fill > 0f) drawArc(accent, -90f, fill * 360f, false, Offset(inset, inset), arc, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = if (sub == null) MekaType.caption else MekaType.nowTitle, color = if (sub == null) Meka.colors.textSecondary else Meka.colors.textPrimary)
            sub?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        }
    }
}

@Composable
private fun UndoLine(r: StudyRemoved, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(r.line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(
            "Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onUndo)
                .semantics { contentDescription = "Undo: ${r.line}" }.minTouch().padding(MekaSpace.xs),
        )
    }
}

/**
 * Goals → Study → a course (study plan, slice 1; mockup `docs/design/study/study-plan.dc.html`): the big ring with the
 * share done, the course's name, "5 of 24 lessons · about 18 h left" and the next lesson; LESSONS with the outline field
 * (paste the course outline: one lesson a line, "Module 2: Core skills" starts a module, "45 min" its length) and each
 * module folding on a tap with its count ("2 of 6", "Done" in green), its lessons in course order. A tick shows in the
 * same frame and moves the ring (the core's write runs after). Study fixes (Meka 2026-10-26 23:21: a long press had
 * deleted a lesson): a long press opens the lesson's sheet under it (rename, move to module, mark done, Delete after a
 * confirm, with Undo) or a module's menu (rename, delete after a confirm); "+ Add lesson" at the foot of each open module
 * and "+ Add module" (its name and first lessons) after the modules; Recently deleted (folded, Restore) under them.
 * Remove course asks first (Undo on Goals). Synced with the Mac.
 *
 * Motion: the header, ring and lessons stagger in; the ring sweeps round from the top on appear and glides on a tick
 * (appear spring); modules fold on the expand spring with the chevron turning; the ring of a lesson draws its check
 * (TickRing) and the title fades; the line under the field cross-fades (refused lit in the accent). Haptics: light on a
 * tick and an add, a tick on a fold or a remove; sheets and confirms unfold on the expand spring. Reduced motion:
 * cross-fades only, the ring set at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StudyPane(
    core: MekaCore, courseId: String, onClose: () -> Unit, onRemoved: (StudyRemoved) -> Unit,
    /** Slice 3: how "From a screenshot" gets its words; null opens the photo picker and reads on the phone (tests hand words in). */
    readScreenshot: (((PictureRead) -> Unit) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val all by core.studyView.collectAsState()
    val c = all.course(courseId)
    var said by remember { mutableStateOf<StudyAdded?>(null) }
    var removed by remember { mutableStateOf<StudyRemoved?>(null) }
    var outline by rememberSaveable(courseId) { mutableStateOf("") }
    // Slice 3: the line a screenshot's read left under the field ("Reading the picture…", "Read 14 lessons…"), until Add.
    var scanned by remember(courseId) { mutableStateOf<String?>(null) }
    // Study beside its mockup: the dashed "+ Add lessons" row at the foot of LESSONS was tapped (the field opens there).
    var addTapped by rememberSaveable(courseId) { mutableStateOf(false) }
    // The tap's result in the next frame: what this screen ticked, until the view says the same.
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    // Plan a lesson for a day: the day picked here (null = not planned), shown at once until the view says the same.
    val plannedTap = remember { mutableStateMapOf<String, Long?>() }
    val today = remember(all) { core.todayEpochDay() }
    // Modules folded or opened by hand; untouched, the first module not yet done is open and the rest folded.
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    // Slice 2a: the plan's chips tapped here, worked out at once until the view says the same.
    var planTap by remember(courseId) { mutableStateOf(PlanTap()) }
    // Study fixes: the lesson whose sheet is open, the module whose menu is open, Recently deleted unfolded, and the
    // Remove course confirm.
    var sheet by remember(courseId) { mutableStateOf<String?>(null) }
    var moduleMenu by remember(courseId) { mutableStateOf<String?>(null) }
    var showDeleted by remember(courseId) { mutableStateOf(false) }
    var confirmCourse by remember(courseId) { mutableStateOf(false) }
    // Study fixes (3) slice 3: the lessons picked under "Select several" (null when not selecting), and the lessons a
    // "Pick a day…" date picker is open for.
    var selecting by remember(courseId) { mutableStateOf<Set<String>?>(null) }
    var pickFor by remember(courseId) { mutableStateOf<List<String>?>(null) }
    // Study fixes (2) rest, drag to reorder (2026-10-28): the lesson or module held by its grip (≡), how far it has
    // moved, a lesson row's height, where each module head on screen starts (root y) and where the held one began;
    // the new order is shown at once (per module, and the modules') until the view says the same.
    var dragLesson by remember(courseId) { mutableStateOf<String?>(null) }
    var dragModule by remember(courseId) { mutableStateOf<String?>(null) }
    var dragDy by remember(courseId) { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(0f) }
    val headTops = remember(courseId) { mutableStateMapOf<String, Float>() }
    var dragTop by remember(courseId) { mutableFloatStateOf(0f) }
    val lessonOrder = remember(courseId) { mutableStateMapOf<String, List<String>>() }
    var moduleOrder by remember(courseId) { mutableStateOf<List<String>?>(null) }
    val gapPx = with(LocalDensity.current) { MekaSpace.xs.toPx() }
    // Slice 2b part 2, THIS WEEK: a row's Start or Done tapped here, shown at once (with the lessons it had then) until
    // the view says the same or the row's lessons change; Done's bar line with Undo.
    val weekTap = remember(courseId) { mutableStateMapOf<String, Pair<StudyWeekState, List<String>>>() }
    var sessionDone by remember(courseId) { mutableStateOf<StudyRemoved?>(null) }
    // Slice 2b rest: "Nudge me 10 minutes before" tapped here, shown at once until the view says the same.
    var nudgeTap by remember(courseId) { mutableStateOf<Boolean?>(null) }
    // Slice 2b rest: "Sunday check-in" tapped here, the same way.
    var checkinTap by remember(courseId) { mutableStateOf<Boolean?>(null) }
    BackHandler(onBack = {
        when {
            selecting != null -> selecting = null
            sheet != null || moduleMenu != null -> { sheet = null; moduleMenu = null }
            else -> onClose()
        }
    })
    LaunchedEffect(c) {
        val shown = c?.modules.orEmpty().flatMap { it.lessons }.associate { it.id to it.done }
        pending.keys.toList().forEach { id -> if (shown[id] == null || shown[id] == pending[id]) pending.remove(id) }
        val days = c?.modules.orEmpty().flatMap { it.lessons }.associate { it.id to it.plannedDay }
        plannedTap.keys.toList().forEach { id -> if (id !in days || days[id] == plannedTap[id]) plannedTap.remove(id) }
        c?.planInput?.let { planTap = planTap.settled(it) }
        if (nudgeTap == c?.nudge) nudgeTap = null
        if (checkinTap == c?.checkin) checkinTap = null
        val rows = c?.week.orEmpty().associateBy { it.key }
        weekTap.keys.toList().forEach { k ->
            val r = rows[k]; val t = weekTap[k]
            if (r == null || t == null || r.state == t.first || r.lessonIds != t.second) weekTap.remove(k)
        }
        val viewModules = c?.modules.orEmpty()
        if (moduleOrder == viewModules.map { it.key } || moduleOrder?.toSet() != viewModules.map { it.key }.toSet()) moduleOrder = null
        lessonOrder.keys.toList().forEach { k ->
            val ids = viewModules.firstOrNull { it.key == k }?.lessons?.map { it.id }
            if (ids == null || ids == lessonOrder[k] || ids.toSet() != lessonOrder[k]?.toSet()) lessonOrder.remove(k)
        }
    }
    // The modules as shown: the order dragged here first, until the view catches up.
    val shownModules = c?.modules.orEmpty().let { ms -> StudyOrder.apply(ms, moduleOrder, lessonOrder) }
    val dragFrom = dragLesson?.let { id -> shownModules.firstOrNull { m -> m.lessons.any { it.id == id } } }
    val lessonFrom = dragFrom?.lessons?.indexOfFirst { it.id == dragLesson } ?: -1
    val lessonTo = if (dragFrom == null) -1 else ChecklistRules.dropIndex(lessonFrom, dragDy, rowPx, dragFrom.lessons.size)
    val moduleFrom = dragModule?.let { k -> shownModules.indexOfFirst { it.key == k } } ?: -1
    val moduleTo = if (moduleFrom < 0) -1 else StudyOrder.moduleSlot(shownModules.map { it.key }, dragModule!!, headTops, dragTop + dragDy)
    var lastSlot by remember { mutableIntStateOf(-1) }
    val slot = if (lessonTo >= 0) lessonTo else moduleTo
    LaunchedEffect(slot) { if (slot >= 0 && lastSlot >= 0 && slot != lastSlot) haptics.tick(); lastSlot = slot }
    val placeLesson: (StudyModule, String, Int) -> Unit = { m, id, to ->
        val from = m.lessons.indexOfFirst { it.id == id }
        if (from >= 0 && to in m.lessons.indices && to != from) {
            lessonOrder[m.key] = m.lessons.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
            scope.launch { if (runCatching { core.placeStudyLesson(id, to) }.getOrNull() != true) lessonOrder.remove(m.key) }
        }
    }
    val placeModule: (StudyModule, Int) -> Unit = { m, to ->
        val keys = shownModules.map { it.key }
        val from = keys.indexOf(m.key)
        if (from >= 0 && to in keys.indices && to != from) {
            moduleOrder = keys.toMutableList().apply { add(to, removeAt(from)) }
            scope.launch { if (runCatching { core.placeStudyModule(courseId, m.name, to) }.getOrNull() != true) moduleOrder = null }
        }
    }
    LaunchedEffect(removed) { if (removed != null) { delay(TripRules.UNDO_MS); removed = null } }
    LaunchedEffect(sessionDone) { if (sessionDone != null) { delay(TripRules.UNDO_MS); sessionDone = null } }
    // Start marks the session on; Done ticks its lessons in the same frame, the core's line and Undo after.
    val onWeek: (StudyWeekRow, StudyWeekState) -> Unit = { row, state ->
        if (state == StudyWeekState.START) {
            haptics.tick()
            weekTap[row.key] = StudyWeekState.ON to row.lessonIds
            scope.launch { if (runCatching { core.startStudySession(row.key) }.isFailure) weekTap.remove(row.key) }
        } else if (state == StudyWeekState.ON) {
            haptics.light()
            weekTap[row.key] = StudyWeekState.DONE to row.lessonIds
            row.lessonIds.forEach { pending[it] = true }
            scope.launch {
                val r = runCatching { core.finishStudySession(row.key) }
                r.getOrNull()?.let { sessionDone = it.takeIf { d -> d.token.isNotEmpty() } }
                if (r.isFailure) { weekTap.remove(row.key); row.lessonIds.forEach { pending.remove(it) } }
            }
        }
    }
    val tick: (StudyLessonRow, Boolean) -> Unit = { row, done ->
        haptics.light()
        pending[row.id] = done
        scope.launch { if (runCatching { core.tickStudyLesson(row.id, done) }.isFailure) pending.remove(row.id) }
    }
    val add: () -> Unit = {
        val text = outline
        if (text.isNotBlank()) scope.launch {
            val r = runCatching { core.addStudyLessons(courseId, text) }.getOrNull()
            if (r?.refused == false) { haptics.light(); outline = "" } else haptics.reject()
            said = r
            scanned = null
        }
    }
    val context = LocalContext.current
    val onRead: (PictureRead) -> Unit = { r ->
        said = null
        when (r) {
            is PictureRead.Words -> {
                val scan = StudyScanRules.scan(r.text)
                if (scan.found) { haptics.light(); outline = StudyScanRules.merge(outline, scan) } else haptics.reject()
                scanned = scan.line
            }
            is PictureRead.NoWords -> { haptics.reject(); scanned = r.line }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) scanned = null
        else scope.launch { onRead(runCatching { PictureReader.read(context, uri) }.getOrElse { PictureRead.NoWords(PictureTextRules.COULD_NOT_OPEN_LINE) }) }
    }
    val fromScreenshot: () -> Unit = {
        haptics.light(); said = null; scanned = PictureTextRules.READING_LINE
        val pick = readScreenshot
        if (pick != null) pick(onRead)
        else runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { scanned = PictureTextRules.COULD_NOT_OPEN_LINE }
    }
    val added: (StudyAdded?) -> Unit = { r ->
        if (r?.refused == false) haptics.light() else haptics.reject()
        said = r
    }
    val choose: (PlanTap, suspend () -> Boolean) -> Unit = { next, write ->
        haptics.light()
        val before = planTap
        planTap = next
        scope.launch { if (runCatching { write() }.isFailure) planTap = before }
    }
    // Plan a lesson (or several) for a day: shown at once, the core's line on the bar after; a failed write puts it back.
    val planDay: (List<String>, Long?) -> Unit = { ids, day ->
        if (day != null && !StudyRules.plannable(day, today)) {
            haptics.reject()
            said = StudyAdded(StudyRules.PICK_REFUSED, 0, refused = true)
        } else if (ids.isNotEmpty()) {
            haptics.light()
            ids.forEach { plannedTap[it] = day }
            scope.launch {
                val line = runCatching { if (ids.size == 1) core.planStudyLesson(ids.single(), day) else core.planStudyLessons(ids, day) }
                line.getOrNull()?.let { said = StudyAdded(it, 0, refused = false) }
                if (line.isFailure) ids.forEach { plannedTap.remove(it) }
            }
        }
    }
    val fieldOpen = StudyBesideRules.fieldOpen(c?.total ?: 0, addTapped, outline, scanned != null)
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "study-said",
    )

    // Unfolded, the course keeps a phone-like column (the design is drawn at phone width) centred on the page.
    Box(Modifier.fillMaxSize().background(Meka.colors.background), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        Modifier.widthIn(max = 600.dp).fillMaxHeight().testTag("study-pane"),
        contentPadding = PaddingValues(start = MekaSpace.gutter, end = MekaSpace.gutter, top = MekaSpace.l, bottom = if (selecting != null) 220.dp else MekaSpace.l),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "back") {
            Text("‹ Goals and habits", style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); onClose() }.semantics { contentDescription = "Back to Goals and habits" }.minTouch())
        }
        if (c == null) {
            item(key = "gone") { Text(StudyRules.GONE, style = MekaType.body, color = Meka.colors.textSecondary) }
            return@LazyColumn
        }
        val lessons = c.modules.flatMap { it.lessons }
        val done = lessons.count { pending[it.id] ?: it.done }
        val plan = if (planTap.isEmpty) c.plan else StudyRules.plan(planTap.applyTo(c.planInput))
        val total = lessons.size
        val pct = if (total == 0) 0 else (done * 100 + total / 2) / total
        item(key = "head") {
            Row(
                Modifier.fillMaxWidth().appear(rememberAppearance(0))
                    .semantics(mergeDescendants = true) { contentDescription = "${c.title}, $pct percent done, ${c.line}" + (c.next?.let { ", $it" } ?: "") },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.l),
            ) {
                CourseRing(if (total == 0) 0f else done.toFloat() / total, "$pct%", 96.dp, 8.dp, "done")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    Text(c.title, style = MekaType.nowTitle, color = Meka.colors.textPrimary)
                    Text(c.line, style = MekaType.caption, color = Meka.colors.textSecondary)
                    c.next?.let { Text(it, style = MekaType.captionStrong, color = if (c.allDone) Meka.colors.success else Meka.colors.accent) }
                    Crossfade(plan.status, animationSpec = MekaMotion.appear(reduced), label = "study-status") { st ->
                        st?.let { Text(it, style = MekaType.caption, color = if (plan.behind) Meka.colors.accent else Meka.colors.success, modifier = Modifier.testTag("study-status")) }
                    }
                }
            }
        }
        if (total > 0 && !c.allDone) item(key = "plan") {
            PlanCard(
                plan, c.suggested, Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(1)),
                onFinish = { chip ->
                    val day = chip.key.toLong()
                    if (!chip.on) choose(planTap.copy(finish = day)) { core.setStudyFinish(courseId, day) }
                },
                onSession = { chip ->
                    val mins = chip.key.toInt()
                    if (!chip.on) choose(planTap.copy(session = mins)) { core.setStudySession(courseId, mins) }
                },
                onSlot = { chip ->
                    val slots = (planTap.slots ?: c.planInput.slots).let { if (chip.on) it - chip.key else it + chip.key }
                    choose(planTap.copy(slots = slots)) { core.setStudySlot(courseId, chip.key, !chip.on) }
                },
            )
        }
        if (c.week.isNotEmpty()) item(key = "week") {
            WeekCard(
                c.week, weekTap, sessionDone, Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(1)), onWeek,
                nudge = nudgeTap ?: c.nudge,
                onNudge = {
                    val on = !(nudgeTap ?: c.nudge)
                    haptics.tick(); nudgeTap = on
                    scope.launch { if (runCatching { core.setStudyNudge(courseId, on) }.getOrNull() == null) nudgeTap = null }
                },
                checkin = checkinTap ?: c.checkin,
                onCheckin = {
                    val on = !(checkinTap ?: c.checkin)
                    haptics.tick(); checkinTap = on
                    scope.launch { if (runCatching { core.setStudyCheckin(courseId, on) }.getOrNull() == null) checkinTap = null }
                },
                onUndo = { r ->
                    haptics.light(); sessionDone = null
                    scope.launch { runCatching { core.undoStudySessionDone(r.token) } }
                },
            )
        }
        item(key = "lessons-label") { SectionLabel("Lessons", Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(1))) }
        // Study beside its mockup: the course's lines ("Added …", "Planned …") and Undo stay under LESSONS; the outline field
        // moved to the foot of the list behind the dashed "+ Add lessons" row (StudyBesideRules.fieldOpen).
        item(key = "field") {
            Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                AnimatedVisibility(said != null && !fieldOpen, enter = unfold(), exit = fold()) {
                    Crossfade(said?.line.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "study-said-top") { line ->
                        Text(line, style = MekaType.caption, color = saidColor, modifier = Modifier.testTag("study-said"))
                    }
                }
                AnimatedVisibility(
                    visible = removed != null,
                    enter = if (reduced) fadeIn() else fadeIn() + expandVertically(),
                    exit = if (reduced) fadeOut() else fadeOut() + shrinkVertically(),
                ) {
                    removed?.let { r ->
                        UndoLine(r) {
                            haptics.light(); removed = null
                            scope.launch { runCatching { core.undoStudyRemoval(r.token) } }
                        }
                    }
                }
            }
        }
        if (c.modules.isEmpty()) {
            item(key = "empty") {
                Text(StudyRules.EMPTY_COURSE, style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.animateItem().padding(top = MekaSpace.xs))
            }
        }
        val firstOpen = shownModules.firstOrNull { m -> m.lessons.any { !(pending[it.id] ?: it.done) } }?.key
        val moduleNames = shownModules.map { it.name }
        val canDrag = selecting == null
        shownModules.forEachIndexed { mi, m ->
            // A held module folds while it's dragged (its lessons travel with it once let go).
            val open = (opened[m.key] ?: (m.key == firstOpen)) && dragModule != m.key
            item(key = "module-${m.key}") {
                val held = dragModule == m.key
                DisposableEffect(m.key) { onDispose { headTops.remove(m.key) } }
                ModuleHead(
                    m, open, pending,
                    Modifier.then(if (held) Modifier else Modifier.animateItem())
                        .onGloballyPositioned { if (dragModule != m.key) headTops[m.key] = it.positionInRoot().y },
                    onToggle = { haptics.tick(); opened[m.key] = !open },
                    onHold = { haptics.tick(); sheet = null; moduleMenu = if (moduleMenu == m.key) null else m.key },
                    grip = if (canDrag && shownModules.size > 1) Grip(
                        label = StudyRules.gripLabel(m.name),
                        dy = if (held) dragDy else 0f, lifted = held,
                        onStart = { haptics.light(); sheet = null; moduleMenu = null; dragTop = headTops[m.key] ?: 0f; dragDy = 0f; dragModule = m.key; lastSlot = mi },
                        onDrag = { dragDy += it },
                        onEnd = {
                            if (moduleTo >= 0 && moduleTo != mi) placeModule(m, moduleTo)
                            dragModule = null; dragDy = 0f
                        },
                        onUp = if (mi > 0) ({ haptics.tick(); placeModule(m, mi - 1) }) else null,
                        onDown = if (mi < shownModules.size - 1) ({ haptics.tick(); placeModule(m, mi + 1) }) else null,
                    ) else null,
                    dropAbove = dragModule != null && !held && StudyOrder.dropsAbove(shownModules.map { it.key }, dragModule!!, moduleTo, m.key),
                )
            }
            if (moduleMenu == m.key) item(key = "module-menu-${m.key}") {
                ModuleMenu(
                    m, Modifier.animateItem(),
                    onRename = { name -> scope.launch { if (runCatching { core.renameStudyModule(courseId, m.name, name) }.getOrNull() == true) haptics.light() else haptics.reject() }; moduleMenu = null },
                    onDelete = {
                        haptics.tick(); moduleMenu = null
                        scope.launch { runCatching { core.removeStudyModule(courseId, m.name) }.getOrNull()?.let { removed = it } }
                    },
                    onDone = { moduleMenu = null },
                )
            }
            if (open) {
                m.lessons.forEachIndexed { li, row ->
                    item(key = "lesson-${row.id}") {
                        val held = dragLesson == row.id
                        // The rows a held lesson passes make room on the replan spring; let go, they sit at once.
                        val shift = when {
                            dragFrom?.key != m.key || held || lessonFrom < 0 -> 0f
                            lessonFrom < lessonTo && li in (lessonFrom + 1)..lessonTo -> -rowPx
                            lessonTo < lessonFrom && li in lessonTo until lessonFrom -> rowPx
                            else -> 0f
                        }
                        val slide = remember { Animatable(0f) }
                        LaunchedEffect(shift, dragLesson == null) { if (dragLesson == null) slide.snapTo(0f) else slide.animateTo(shift, MekaMotion.replan(reduced)) }
                        LessonRow(
                            row, pending[row.id] ?: row.done,
                            if (plannedTap.containsKey(row.id)) plannedTap[row.id]?.let { StudyRules.plannedLabel(it, today) } else row.planned,
                            row.late && !plannedTap.containsKey(row.id),
                            Modifier.then(if (dragLesson != null) Modifier else Modifier.animateItem())
                                .graphicsLayer { translationY = if (held) 0f else slide.value },
                            grip = if (canDrag && m.lessons.size > 1) Grip(
                                label = StudyRules.gripLabel(row.title),
                                dy = if (held) dragDy else 0f, lifted = held,
                                onStart = { haptics.light(); sheet = null; moduleMenu = null; dragDy = 0f; dragLesson = row.id; lastSlot = li },
                                onDrag = { dragDy += it },
                                onEnd = {
                                    val to = ChecklistRules.dropIndex(li, dragDy, rowPx, m.lessons.size)
                                    placeLesson(m, row.id, to)
                                    dragLesson = null; dragDy = 0f
                                },
                                onUp = if (li > 0) ({ haptics.tick(); placeLesson(m, row.id, li - 1) }) else null,
                                onDown = if (li < m.lessons.size - 1) ({ haptics.tick(); placeLesson(m, row.id, li + 1) }) else null,
                            ) else null,
                            onMeasured = { if (it > 0f) rowPx = it + gapPx },
                            onTick = { tick(row, it) },
                            onHold = { haptics.tick(); moduleMenu = null; sheet = if (sheet == row.id) null else row.id },
                            selected = selecting?.let { row.id in it },
                            onSelect = { haptics.tick(); selecting = selecting?.let { if (row.id in it) it - row.id else it + row.id } },
                        )
                    }
                    if (sheet == row.id) item(key = "lesson-sheet-${row.id}") {
                        LessonSheet(
                            row, m.name, moduleNames, pending[row.id] ?: row.done, StudyRules.planChips(today, if (plannedTap.containsKey(row.id)) plannedTap[row.id] else row.plannedDay), Modifier.animateItem(),
                            onPlan = { chip ->
                                if (chip.key == StudyRules.PICK_KEY) { haptics.tick(); pickFor = listOf(row.id) }
                                else planDay(listOf(row.id), chip.key.toLongOrNull())
                            },
                            onSelect = { haptics.tick(); sheet = null; moduleMenu = null; selecting = setOf(row.id) },
                            onName = { name -> scope.launch { if (runCatching { core.renameStudyLesson(row.id, name) }.getOrNull() == true) haptics.light() else haptics.reject() } },
                            onModule = { to -> haptics.light(); sheet = null; opened[StudyRules.moduleKey(courseId, to)] = true; scope.launch { runCatching { core.moveStudyLesson(row.id, to) } } },
                            onTick = { tick(row, it) },
                            onDelete = {
                                haptics.tick(); sheet = null
                                scope.launch { runCatching { core.removeStudyLesson(row.id) }.getOrNull()?.let { removed = it } }
                            },
                            onDone = { sheet = null },
                        )
                    }
                }
                item(key = "add-into-${m.key}") {
                    AddInto(m, Modifier.animateItem()) { text ->
                        scope.launch { added(runCatching { core.addStudyLessonsTo(courseId, m.name, text) }.getOrNull()) }
                    }
                }
            }
        }
        item(key = "add-module") {
            // A held module let go here lands last: the gold line shows above "+ Add module".
            val last = moduleFrom >= 0 && moduleTo == shownModules.size - 1 && moduleTo != moduleFrom
            val line by animateFloatAsState(if (last) 1f else 0f, MekaMotion.appear(reduced), label = "study-module-drop-last")
            val accent = Meka.colors.accent
            AddModule(Modifier.animateItem().padding(top = MekaSpace.xs).drawBehind {
                if (line > 0f) drawLine(accent.copy(alpha = line), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }) { name, text ->
                scope.launch {
                    val r = runCatching { core.addStudyModule(courseId, name, text) }.getOrNull()
                    added(r)
                    r?.refused == false
                }
            }
        }
        item(key = "add-lessons") {
            Column(Modifier.animateItem().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                AnimatedVisibility(!fieldOpen, enter = unfold(), exit = fold()) {
                    AddLessonsRow { haptics.tick(); addTapped = true }
                }
                AnimatedVisibility(fieldOpen, enter = unfold(), exit = fold()) {
                    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        OutlineField(outline, { outline = it }, add)
                        ScreenshotAction(fromScreenshot)
                        Crossfade(scanned ?: said?.line ?: StudyRules.ADD_LESSONS_NOTE, animationSpec = MekaMotion.appear(reduced), label = "study-added") { line ->
                            Text(line, style = MekaType.caption, color = if (said != null || scanned != null) saidColor else Meka.colors.textTertiary)
                        }
                    }
                }
            }
        }
        if (c.deleted.isNotEmpty()) {
            item(key = "deleted-head") {
                DeletedHead(c.deleted.size, showDeleted, Modifier.animateItem().padding(top = MekaSpace.l)) { haptics.tick(); showDeleted = !showDeleted }
            }
            if (showDeleted) items(c.deleted, key = { "deleted-${it.id}" }) { d ->
                DeletedRow(d, Modifier.animateItem()) {
                    haptics.light()
                    scope.launch { runCatching { core.restoreStudyLesson(d.id) } }
                }
            }
        }
        item(key = "note") {
            Column(Modifier.animateItem().padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(StudyRules.NOTE + " " + StudyRules.HOLD_NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
                Text(
                    "Remove course", style = MekaType.itemMeta, color = Meka.colors.critical,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                        haptics.tick(); confirmCourse = !confirmCourse
                    }.semantics { contentDescription = "Remove ${c.title} and its lessons" }.minTouch().testTag("study-remove-course"),
                )
                AnimatedVisibility(confirmCourse, enter = unfold(), exit = fold()) {
                    ConfirmRow(StudyRules.confirmRemoveCourse(c.title, c.total), "Remove", "study-remove-course-confirm", onYes = {
                        haptics.tick(); confirmCourse = false
                        scope.launch { runCatching { core.removeStudyCourse(courseId) }.getOrNull()?.let { onRemoved(it); onClose() } }
                    }, onNo = { confirmCourse = false })
                }
            }
        }
    }
    // Study fixes (3) slice 3: the selection bar slides up from the foot while lessons are being picked.
    AnimatedVisibility(
        visible = selecting != null && c != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduced) fadeIn(MekaMotion.appear(true)) else fadeIn() + slideInVertically { it },
        exit = if (reduced) fadeOut(MekaMotion.appear(true)) else fadeOut() + slideOutVertically { it },
    ) {
        val picked = selecting.orEmpty()
        SelectBar(
            picked.size, StudyRules.selectChips(today),
            onChip = { chip ->
                when {
                    picked.isEmpty() -> haptics.reject()
                    chip.key == StudyRules.PICK_KEY -> { haptics.tick(); pickFor = picked.toList() }
                    else -> { planDay(picked.toList(), chip.key.toLongOrNull()); selecting = null }
                }
            },
            onCancel = { haptics.tick(); selecting = null },
        )
    }
    }
    pickFor?.let { ids ->
        DayPickerDialog(
            initialDay = today + StudyRules.PLAN_DAYS,
            onPick = { day -> planDay(ids, day); if (StudyRules.plannable(day, today)) selecting = null },
            onDismiss = { pickFor = null },
        )
    }
}

/**
 * Study fixes (3) slice 3: the bar under the lessons while several are picked: "3 lessons selected" and Cancel, then
 * PLAN FOR… as chips (the week, Pick a day…, Not planned). 48 dp targets; a chip with nothing picked is refused.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectBar(n: Int, chips: List<StudyChip>, onChip: (StudyChip) -> Unit, onCancel: () -> Unit) {
    val reduced = Meka.reducedMotion
    Column(
        Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(MekaSpace.m).clip(RoundedCornerShape(MekaRadius.l))
            .background(Meka.colors.surfaceRaised).padding(MekaSpace.m).testTag("study-select-bar"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Crossfade(StudyRules.selectedLine(n), animationSpec = MekaMotion.appear(reduced), label = "study-selected", modifier = Modifier.weight(1f)) { line ->
                Text(line, style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.testTag("study-selected"))
            }
            Text(StudyRules.CANCEL, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onCancel)
                    .semantics { contentDescription = "Stop selecting" }.minTouch().padding(MekaSpace.xs).testTag("study-select-cancel"))
        }
        Text(if (n == 0) StudyRules.SELECT_HINT else StudyRules.PLAN_SELECTED, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (chip in chips) TripPill(chip.label, on = false, tag = "study-select-plan-${chip.label}", description = "${chip.spoken}, $n lessons") { onChip(chip) }
        }
    }
}

/**
 * THIS WEEK (slice 2b part 2, mockup): the course's study sessions from today for a week, one row a day: the day and
 * time, the lessons ("Pivot tables + 1 more · 1 h 15"), where ("Train morning"), and Start → Done → a quiet green Done.
 * A tap shows in the same frame ([taps]); the row's fill and the button's colour glide, the lines cross-fade.
 */
@Composable
private fun WeekCard(
    rows: List<StudyWeekRow>, taps: Map<String, Pair<StudyWeekState, List<String>>>, done: StudyRemoved?, modifier: Modifier,
    onTap: (StudyWeekRow, StudyWeekState) -> Unit, onUndo: (StudyRemoved) -> Unit,
    nudge: Boolean = true, onNudge: () -> Unit = {},
    checkin: Boolean = true, onCheckin: () -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface)
            .padding(StudyBesideRules.CARD_PAD_DP.dp).testTag("study-week"),
    ) {
        SectionLabel(StudyWeekRules.LABEL)
        rows.forEach { row ->
            val state = taps[row.key]?.first ?: row.state
            val note = if (state == StudyWeekState.ON) row.note ?: "On now" else null
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("study-week-${row.day}")
                    .semantics(mergeDescendants = true) { contentDescription = row.spoken + if (state == StudyWeekState.DONE && row.state != StudyWeekState.DONE) ", done" else "" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Column(Modifier.widthIn(min = 52.dp)) {
                    Text(row.dayLabel, style = MekaType.caption, color = Meka.colors.textSecondary)
                    row.time?.let { Text(it, style = MekaType.captionStrong, color = Meka.colors.textPrimary) }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.lessons, style = MekaType.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (row.catchUp) Meka.colors.textTertiary else Meka.colors.textPrimary,
                    )
                    Crossfade(note ?: row.place, animationSpec = MekaMotion.appear(reduced), label = "study-week-where") { line ->
                        Text(line, style = MekaType.caption, color = if (note != null) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.button != null) WeekButton(state, row) { onTap(row, state) }
            }
        }
        AnimatedVisibility(done != null, enter = unfold(), exit = fold()) {
            done?.let { r -> UndoLine(r) { onUndo(r) } }
        }
        NudgeRow(nudge, onNudge)
        NudgeRow(checkin, onCheckin, StudyCheckinRules.TOGGLE, StudyCheckinRules.TOGGLE_SUB, "study-checkin")
    }
}

/**
 * Slice 2b rest (mockup's toggle): "Nudge me 10 minutes before · A card from Obi, not out loud" with an On/Off pill whose
 * fill glides; synced, so the Mac's follows. Also "Sunday check-in · How the week went, and next week's sessions".
 */
@Composable
private fun NudgeRow(
    on: Boolean, onClick: () -> Unit,
    label: String = StudyNudgeRules.TOGGLE, sub: String = StudyNudgeRules.TOGGLE_SUB, tag: String = "study-nudge",
) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(reduced), label = "study-nudge-pill")
    Row(
        Modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Switch, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $sub"; stateDescription = if (on) "On" else "Off" }
            .minTouch().padding(vertical = MekaSpace.xxs).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.m),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MekaType.body, color = Meka.colors.textPrimary)
            Text(sub, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        Crossfade(if (on) "On" else "Off", animationSpec = MekaMotion.appear(reduced), label = "study-nudge-on") { l ->
            Text(l, style = MekaType.captionStrong, color = if (on) Meka.colors.onAccent else Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
        }
    }
}

/** Start (accent text on a raised pill), Done while on (filled accent), and Done once ticked (green, does nothing). */
@Composable
private fun WeekButton(state: StudyWeekState, row: StudyWeekRow, onClick: () -> Unit) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(
        when (state) {
            StudyWeekState.START -> Meka.colors.surfaceRaised
            StudyWeekState.ON -> Meka.colors.accent
            StudyWeekState.DONE -> Meka.colors.success.copy(alpha = 0.16f)
        }, MekaMotion.appear(reduced), label = "study-week-button",
    )
    val fg = when (state) {
        StudyWeekState.START -> Meka.colors.accent
        StudyWeekState.ON -> Meka.colors.onAccent
        StudyWeekState.DONE -> Meka.colors.success
    }
    val label = if (state == StudyWeekState.START) StudyWeekRules.START else StudyWeekRules.DONE
    Box(
        Modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill))
            .then(if (state == StudyWeekState.DONE) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .semantics(mergeDescendants = true) { contentDescription = if (state == StudyWeekState.START) "Start ${row.dayLabel}'s session" else if (state == StudyWeekState.ON) "Done, tick ${row.lessonIds.size} lessons" else "Session done" }
            .testTag("study-week-button-${row.day}"),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(label, animationSpec = MekaMotion.appear(reduced), label = "study-week-label") { l ->
            Text(l, style = MekaType.captionStrong, color = fg, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs))
        }
    }
}

/**
 * The plan's choices tapped on this screen (slice 2a): applied to the view's [StudyPlanInput] so the card answers in
 * the same frame, and dropped one by one as the view catches up.
 */
internal data class PlanTap(val finish: Long? = null, val session: Int? = null, val slots: Set<String>? = null) {
    val isEmpty get() = finish == null && session == null && slots == null

    fun applyTo(i: StudyPlanInput): StudyPlanInput {
        var x = i
        if (finish != null && finish != i.finishByDay) x = x.withFinish(finish)
        if (session != null) x = x.withSession(session)
        if (slots != null) x = x.copy(slots = slots)
        return x
    }

    fun settled(i: StudyPlanInput) = PlanTap(
        finish.takeIf { it != i.finishByDay }, session.takeIf { it != i.sessionMins }, slots.takeIf { it != i.slots },
    )
}

/**
 * YOUR PLAN (slice 2a, mockup): FINISH BY, EACH SESSION and WHEN YOU CAN STUDY as chips; then "4 sessions a week" in
 * the accent with "about 2 h 24 · 19 lessons left", whether it fits (green, or amber with what to change) and Obi's
 * line under it. A chip lights in the same frame (its fill glides); the lines cross-fade.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(
    plan: StudyPlanView, suggested: String?, modifier: Modifier,
    onFinish: (StudyChip) -> Unit, onSession: (StudyChip) -> Unit, onSlot: (StudyChip) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth().testTag("study-plan"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(
            // Study beside its mockup: 16 dp inside (was 24); on the closed Fold the dates drop their weekday, two to a row.
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface).padding(StudyBesideRules.CARD_PAD_DP.dp),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            SectionLabel(StudyRules.PLAN)
            BoxWithConstraints {
                val narrow = StudyBesideRules.narrow(maxWidth.value)
                ChipGroup(StudyRules.FINISH_LABEL, plan.ends.map { it.copy(label = StudyBesideRules.endChipLabel(it.label, narrow)) }, "study-end", onFinish)
            }
            ChipGroup(StudyRules.SESSION_LABEL, plan.lens, "study-len", onSession)
            ChipGroup(StudyRules.SLOTS_LABEL, plan.slots, "study-slot", onSlot)
            Crossfade(plan.perWeek to plan.perWeekSub, animationSpec = MekaMotion.appear(reduced), label = "study-per-week") { (big, sub) ->
                if (big != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                    Text(big, style = MekaType.nowTitle, color = Meka.colors.accent, modifier = Modifier.testTag("study-per-week"))
                    sub?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.padding(bottom = MekaSpace.xxs)) }
                }
            }
            Crossfade(plan.fit, animationSpec = MekaMotion.appear(reduced), label = "study-fit") { fit ->
                fit?.let { Text(it, style = MekaType.caption, color = if (plan.fits) Meka.colors.success else if (plan.perWeek == null) Meka.colors.textTertiary else Meka.colors.accent, modifier = Modifier.testTag("study-fit")) }
            }
            // Slice 2b: lessons he hasn't planned, suggested for his next sessions ("5 lessons suggested for your next 3 sessions · …").
            AnimatedVisibility(suggested != null, enter = unfold(), exit = fold()) {
                Crossfade(suggested.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "study-suggested") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.testTag("study-suggested"))
                }
            }
        }
        AnimatedVisibility(plan.obi != null, enter = unfold(), exit = fold()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("study-obi"),
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                val accent = Meka.colors.accent
                Box(Modifier.padding(top = MekaSpace.xxs).size(16.dp).clip(RoundedCornerShape(MekaRadius.pill))
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.45f)))))
                Crossfade(plan.obi.orEmpty(), animationSpec = MekaMotion.appear(reduced), label = "study-obi") { line ->
                    Text(line, style = MekaType.caption, color = Meka.colors.textSecondary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(label: String, chips: List<StudyChip>, tag: String, onTap: (StudyChip) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(label, style = MekaType.caption, color = Meka.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            chips.forEach { chip -> PlanChip(chip, Modifier.testTag("$tag-${chip.key}")) { onTap(chip) } }
        }
    }
}

/** A plan chip: 48 dp to touch, drawn as a pill; lit in the accent when picked (the fill glides). */
@Composable
private fun PlanChip(chip: StudyChip, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (chip.on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "study-chip")
    Box(
        modifier.minTouch().clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = chip.spoken; selected = chip.on },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            chip.label, style = MekaType.caption, color = if (chip.on) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
        )
    }
}

/**
 * Slice 3: "From a screenshot" under the outline field: a quiet accent line that opens the photo picker; the picture is
 * read on the phone and its lessons land in the field to check before Add lessons.
 */
@Composable
private fun ScreenshotAction(onClick: () -> Unit) {
    Text(
        "+ " + StudyScanRules.ACTION, style = MekaType.caption, color = Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = StudyScanRules.ACTION_DESCRIPTION }.minTouch()
            .padding(vertical = MekaSpace.xxs).testTag("study-from-screenshot"),
    )
}

/** A multi-line field for an outline (a paste keeps its lines); Add appears once something is typed. */
@Composable
private fun OutlineField(
    value: String, onValue: (String) -> Unit, onAdd: () -> Unit,
    hint: String = StudyRules.ADD_LESSONS_HINT, description: String = StudyRules.ADD_LESSONS_DESCRIPTION,
    action: String = "Add lessons", tag: String = "study-outline", always: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
        ) {
            if (value.isEmpty()) Text(hint, style = MekaType.body, color = Meka.colors.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                cursorBrush = SolidColor(Meka.colors.accent),
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)
                    .semantics { contentDescription = description }.testTag(tag),
            )
        }
        AnimatedVisibility(always || value.isNotBlank(), enter = unfold(), exit = fold()) {
            Action(action) { onAdd() }
        }
    }
}

/** A module's head: its name, its count ("2 of 6", "Done" in green) and a chevron; a tap folds it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModuleHead(
    m: StudyModule, open: Boolean, pending: Map<String, Boolean>, modifier: Modifier, onToggle: () -> Unit, onHold: () -> Unit,
    grip: Grip? = null, dropAbove: Boolean = false,
) {
    val reduced = Meka.reducedMotion
    val done = m.lessons.count { pending[it.id] ?: it.done }
    val count = StudyRules.moduleCount(done, m.lessons.size)
    val allDone = done == m.lessons.size
    val turn by animateFloatAsState(if (open) 180f else 0f, MekaMotion.expand(reduced), label = "study-module-chev")
    val lift by animateFloatAsState(if (grip?.lifted == true && !reduced) 1f else 0f, MekaMotion.approve(reduced), label = "study-module-lift")
    // Where a held module will land: a 2 dp gold line above this head (fades in and out).
    val line by animateFloatAsState(if (dropAbove) 1f else 0f, MekaMotion.appear(reduced), label = "study-module-drop")
    val accent = Meka.colors.accent
    Row(
        modifier.fillMaxWidth().zIndex(if (grip?.lifted == true) 1f else 0f).padding(top = MekaSpace.xs)
            .drawBehind {
                if (line > 0f) drawLine(accent.copy(alpha = line), Offset(0f, -MekaSpace.xs.toPx() / 2), Offset(size.width, -MekaSpace.xs.toPx() / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            .graphicsLayer { translationY = grip?.dy ?: 0f; scaleX = 1f + 0.02f * lift; scaleY = 1f + 0.02f * lift }
            .clip(RoundedCornerShape(MekaRadius.m)).background(if (grip?.lifted == true) Meka.colors.surfaceRaised else Meka.colors.surface)
            .combinedClickable(role = Role.Button, onClick = onToggle, onLongClick = onHold, onLongClickLabel = "Rename or delete the module")
            .semantics(mergeDescendants = true) { contentDescription = "${m.name}, $count, " + if (open) "tap to fold" else "tap to open" }
            .minTouch().padding(start = MekaSpace.m, end = if (grip != null) 0.dp else MekaSpace.m, top = MekaSpace.xs, bottom = MekaSpace.xs).testTag("study-module-${m.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(m.name, style = MekaType.taskTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(count, style = MekaType.caption, color = if (allDone) Meka.colors.success else Meka.colors.textTertiary)
        Text("⌄", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.rotate(turn))
        grip?.let { GripHandle(it, "study-module-grip-${m.key}") }
    }
}

/** One lesson: the ring (22 dp in a 48 dp target), its name (faded once ticked) and how long it takes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LessonRow(
    row: StudyLessonRow, done: Boolean, planned: String?, late: Boolean, modifier: Modifier,
    grip: Grip? = null, onMeasured: (Float) -> Unit = {},
    onTick: (Boolean) -> Unit, onHold: () -> Unit,
    selected: Boolean? = null, onSelect: () -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    val fade by animateFloatAsState(if (done) 0.5f else 1f, MekaMotion.appear(reduced), label = "study-lesson-fade")
    // Study fixes (3) slice 3: while selecting, a tap (or a hold) picks the lesson instead of ticking it; the row lights.
    val bg by animateColorAsState(if (selected == true || grip?.lifted == true) Meka.colors.surfaceRaised else Meka.colors.background, MekaMotion.appear(reduced), label = "study-lesson-selected")
    // Drag to reorder: the held row lifts onto the raised surface (scale 1.02 on the approve spring) and follows the finger.
    val lift by animateFloatAsState(if (grip?.lifted == true && !reduced) 1f else 0f, MekaMotion.approve(reduced), label = "study-lesson-lift")
    Row(
        modifier.fillMaxWidth().zIndex(if (grip?.lifted == true) 1f else 0f).onSizeChanged { onMeasured(it.height.toFloat()) }
            .graphicsLayer { translationY = grip?.dy ?: 0f; scaleX = 1f + 0.02f * lift; scaleY = 1f + 0.02f * lift }
            .clip(RoundedCornerShape(MekaRadius.m)).background(bg)
            .combinedClickable(
                role = Role.Checkbox,
                onClick = { if (selected != null) onSelect() else onTick(!done) },
                onLongClick = { if (selected != null) onSelect() else onHold() },
                onLongClickLabel = if (selected != null) null else "Rename, move, plan or delete",
            )
            .semantics(mergeDescendants = true) {
                contentDescription = row.spoken.removeSuffix(", done")
                stateDescription = when (selected) { null -> if (done) "Done" else "Not done"; true -> "Selected"; false -> "Not selected" }
            }
            .minTouch().padding(start = MekaSpace.m).testTag("study-lesson-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        if (selected != null) SelectDot(selected) else TickRing(done, Modifier.size(22.dp))
        Text(row.title, style = MekaType.taskTitle, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(fade))
        // Plan a lesson for a day: its date chip ("Today", "Tomorrow", "Thu 29 Oct"; in the accent once it rolled over).
        AnimatedVisibility(planned != null && !done, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
            // Slice 2b: a suggestion ("Tomorrow · suggested") is quieter: no fill, the tertiary text.
            val soft = row.suggested && planned == row.planned
            Text(planned.orEmpty(), style = MekaType.caption, color = if (late) Meka.colors.accent else if (soft) Meka.colors.textTertiary else Meka.colors.textSecondary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(if (soft) Color.Transparent else Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs).testTag("study-lesson-planned"))
        }
        if (row.mins.isNotEmpty()) Text(row.mins, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.alpha(fade).padding(end = if (grip != null) 0.dp else MekaSpace.xs))
        grip?.let { GripHandle(it, "study-lesson-grip-${row.id}") }
    }
}

/**
 * Drag to reorder (Study fixes (2) rest, 2026-10-28): a lesson's or module's grip. [dy] is how far the held one has
 * moved; [onUp] / [onDown] are the same moves without a drag (the grip's accessibility actions), null at an end.
 */
internal class Grip(
    val label: String, val dy: Float, val lifted: Boolean,
    val onStart: () -> Unit, val onDrag: (Float) -> Unit, val onEnd: () -> Unit,
    val onUp: (() -> Unit)?, val onDown: (() -> Unit)?,
)

/** The ≡ handle (48 dp): drag it up or down; a screen reader offers Move up and Move down. */
@Composable
private fun GripHandle(grip: Grip, tag: String) {
    // The gesture outlives recompositions: always call the newest callbacks.
    val start by rememberUpdatedState(grip.onStart)
    val drag by rememberUpdatedState(grip.onDrag)
    val end by rememberUpdatedState(grip.onEnd)
    val muted = if (grip.lifted) Meka.colors.accent else Meka.colors.textTertiary
    Canvas(
        Modifier.size(48.dp)
            // Its own node (not merged into the row's), so a screen reader reaches the grip and its moves.
            .semantics(mergeDescendants = true) {
                contentDescription = grip.label
                customActions = listOfNotNull(
                    grip.onUp?.let { up -> CustomAccessibilityAction(StudyRules.MOVE_UP) { up(); true } },
                    grip.onDown?.let { down -> CustomAccessibilityAction(StudyRules.MOVE_DOWN) { down(); true } },
                )
            }
            // A tap or a hold on the grip does nothing (never ticks the lesson or opens its sheet).
            .pointerInput(tag) { detectTapGestures(onLongPress = {}) {} }
            .pointerInput(tag) {
                detectVerticalDragGestures(
                    onDragStart = { start() },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                    onVerticalDrag = { change, d -> change.consume(); drag(d) },
                )
            }
            .testTag(tag),
    ) {
        val w = 16.dp.toPx(); val x0 = (size.width - w) / 2; val gap = 5.dp.toPx()
        for (k in -1..1) {
            val y = size.height / 2 + k * gap
            drawLine(muted, Offset(x0, y), Offset(x0 + w, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** Drag to reorder: the order dragged on this screen laid over the view's, and where a held module would land. */
internal object StudyOrder {
    /** [ms] with the modules in [moduleOrder] (when given) and each module's lessons in [lessonOrder]'s order. */
    fun apply(ms: List<StudyModule>, moduleOrder: List<String>?, lessonOrder: Map<String, List<String>>): List<StudyModule> {
        val sorted = moduleOrder?.let { o -> ms.sortedBy { m -> o.indexOf(m.key).let { if (it < 0) Int.MAX_VALUE else it } } } ?: ms
        return sorted.map { m ->
            lessonOrder[m.key]?.let { o -> m.copy(lessons = m.lessons.sortedBy { r -> o.indexOf(r.id).let { if (it < 0) Int.MAX_VALUE else it } }) } ?: m
        }
    }

    /**
     * The place among the other modules for the held module [held] whose head's top is now at [top]: the number of
     * other heads that start above it ([tops], root y; a head off screen counts as above when it was above before).
     */
    fun moduleSlot(keys: List<String>, held: String, tops: Map<String, Float>, top: Float): Int {
        val from = keys.indexOf(held)
        return keys.withIndex().count { (i, k) -> k != held && (tops[k]?.let { it < top } ?: (i < from)) }
    }

    /** Whether the gold line shows above [key]'s head: it's the head the held module would land before. */
    fun dropsAbove(keys: List<String>, held: String, slot: Int, key: String): Boolean {
        val from = keys.indexOf(held)
        if (slot < 0 || slot == from) return false
        val others = keys.filter { it != held }
        return others.getOrNull(slot) == key
    }
}

/** A selection mark in place of the tick ring while selecting: an outlined circle, filled in the accent with a check once picked. */
@Composable
private fun SelectDot(on: Boolean) {
    val fill by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.background, MekaMotion.appear(Meka.reducedMotion), label = "study-select-dot")
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
            .border(1.5.dp, if (on) Meka.colors.accent else Meka.colors.textTertiary, RoundedCornerShape(MekaRadius.pill))
            .testTag(if (on) "study-select-on" else "study-select-off"),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", style = MekaType.caption, color = Meka.colors.onAccent) }
}

/** "Delete “Welcome”?" with [yes] (in the critical colour) and Cancel: nothing is deleted without it. */
@Composable
private fun ConfirmRow(question: String, yes: String, tag: String, onYes: () -> Unit, onNo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(question, style = MekaType.caption, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
        Text(yes, style = MekaType.itemMeta, color = Meka.colors.critical,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onYes)
                .semantics { contentDescription = "$yes: $question" }.minTouch().padding(MekaSpace.xs).testTag("$tag-yes"))
        Text(StudyRules.CANCEL, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onNo)
                .minTouch().padding(MekaSpace.xs).testTag("$tag-no"))
    }
}

/**
 * Study fixes: a lesson's sheet (a long press), under its row: its name (type a new one), the module it's in (another
 * chip moves it there), Mark done / not done, and Delete, which asks first (then Undo, and Recently deleted).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonSheet(
    row: StudyLessonRow, module: String, modules: List<String>, done: Boolean, planChips: List<StudyChip>, modifier: Modifier,
    onPlan: (StudyChip) -> Unit, onSelect: () -> Unit, onName: (String) -> Unit, onModule: (String) -> Unit, onTick: (Boolean) -> Unit, onDelete: () -> Unit, onDone: () -> Unit,
) {
    var confirming by remember(row.id) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(MekaSpace.m).testTag("study-lesson-sheet"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        // Plan a lesson for a day: Today, Tomorrow or a day this week; it then shows on Today and in the Tomorrow sheet.
        Text(StudyRules.PLAN_FOR, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            for (chip in planChips) TripPill(chip.label, on = chip.on, tag = "study-lesson-plan-${chip.label}", description = chip.spoken) { if (!chip.on) onPlan(chip) }
            TripPill(StudyRules.SELECT, tag = "study-lesson-select", description = "Select several lessons to plan together") { onSelect() }
        }
        Text(StudyRules.RENAME, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Field(row.title, description = "Rename ${row.title}", modifier = Modifier.testTag("study-lesson-name")) { onName(it) }
        if (modules.size > 1) {
            Text(StudyRules.MOVE_LABEL, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                for (name in modules) TripPill(name, on = name == module, tag = "study-lesson-module-$name", description = "Move to $name") { if (name != module) onModule(name) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            TripPill(if (done) StudyRules.MARK_UNDONE else StudyRules.MARK_DONE, tag = "study-lesson-mark") { onTick(!done) }
            TripPill(StudyRules.DELETE, on = confirming, tag = "study-lesson-delete", description = "Delete ${row.title}") { confirming = !confirming }
            Spacer(Modifier.weight(1f))
            Text(StudyRules.DONE, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onDone)
                    .minTouch().padding(MekaSpace.xs).testTag("study-lesson-done"))
        }
        AnimatedVisibility(confirming, enter = unfold(), exit = fold()) {
            ConfirmRow(StudyRules.confirmDelete(row.title), StudyRules.DELETE, "study-lesson-confirm", onYes = onDelete, onNo = { confirming = false })
        }
    }
}

/** Study fixes: a module's menu (a long press on its head): Rename (a field starting from its name) and Delete module, asked first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleMenu(m: StudyModule, modifier: Modifier, onRename: (String) -> Unit, onDelete: () -> Unit, onDone: () -> Unit) {
    var confirming by remember(m.key) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(MekaSpace.m).testTag("study-module-menu"),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(StudyRules.RENAME, style = MekaType.sectionLabel, color = Meka.colors.textTertiary)
        Field(m.name, description = "Rename ${m.name}", modifier = Modifier.testTag("study-module-name")) { onRename(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            TripPill(StudyRules.DELETE_MODULE, on = confirming, tag = "study-module-delete", description = "Delete ${m.name}") { confirming = !confirming }
            Spacer(Modifier.weight(1f))
            Text(StudyRules.DONE, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onDone)
                    .minTouch().padding(MekaSpace.xs).testTag("study-module-done"))
        }
        AnimatedVisibility(confirming, enter = unfold(), exit = fold()) {
            ConfirmRow(StudyRules.confirmDeleteModule(m.name, m.lessons.size), StudyRules.DELETE, "study-module-confirm", onYes = onDelete, onNo = { confirming = false })
        }
    }
}

/** Study fixes: "+ Add lesson" at the foot of an open module: a field (one lesson, or paste several lines) that adds into it. */
@Composable
private fun AddInto(m: StudyModule, modifier: Modifier, onAdd: (String) -> Unit) {
    var open by rememberSaveable(m.key) { mutableStateOf(false) }
    var text by rememberSaveable(m.key) { mutableStateOf("") }
    Column(modifier.padding(start = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(StudyRules.ADD_LESSON, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { open = !open }
                .semantics { contentDescription = "Add a lesson to ${m.name}" }.minTouch().padding(MekaSpace.xs).testTag("study-add-into-${m.key}"))
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            OutlineField(
                text, { text = it }, { if (text.isNotBlank()) { onAdd(text); text = ""; open = false } },
                hint = StudyRules.ADD_LESSON_HINT, description = "Add lessons to ${m.name}", action = "Add to ${m.name}",
                tag = "study-add-into-field-${m.key}",
            )
        }
    }
}

/**
 * Study beside its mockup: the dashed "+ Add lessons · paste the course outline" row closing LESSONS (the mockup's,
 * 1 dp dashed in the hairline colour, the app's 16 dp corners, the words in the accent, centred); a tap opens the outline field.
 */
@Composable
private fun AddLessonsRow(onClick: () -> Unit) {
    val line = Meka.colors.hairline
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = StudyBesideRules.ADD_ROW_DESCRIPTION }
            .drawBehind {
                val inset = 0.5.dp.toPx()
                drawRoundRect(
                    line, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset),
                    cornerRadius = CornerRadius(MekaRadius.m.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("study-add-lessons"),
        contentAlignment = Alignment.Center,
    ) {
        Text(StudyBesideRules.ADD_ROW, style = MekaType.caption, color = Meka.colors.accent, maxLines = 2)
    }
}

/** Study fixes: "+ Add module" after the modules: its name, then its first lessons; [onAdd] says whether it was added. */
@Composable
private fun AddModule(modifier: Modifier, onAdd: (String, String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(StudyRules.ADD_MODULE, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) { open = !open }
                .semantics { contentDescription = "Add a module" }.minTouch().padding(MekaSpace.xs).testTag("study-add-module"))
        AnimatedVisibility(open, enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field(StudyRules.ADD_MODULE_HINT, value = name, onValue = { name = it }, modifier = Modifier.testTag("study-add-module-name")) { }
                OutlineField(
                    text, { text = it }, {
                        if (name.isNotBlank() && text.isNotBlank()) { onAdd(name, text); name = ""; text = ""; open = false }
                    },
                    hint = StudyRules.ADD_MODULE_LESSONS_HINT, description = StudyRules.ADD_MODULE_LESSONS_HINT, action = "Add module",
                    tag = "study-add-module-lessons",
                )
            }
        }
    }
}

/** Study fixes: Recently deleted, folded: "Recently deleted · 2" and a chevron. */
@Composable
private fun DeletedHead(n: Int, open: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val turn by animateFloatAsState(if (open) 180f else 0f, MekaMotion.expand(reduced), label = "study-deleted-chev")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = "${StudyRules.RECENT_LABEL}, $n, " + if (open) "tap to fold" else "tap to open" }
            .minTouch().padding(horizontal = MekaSpace.xs).testTag("study-deleted-head"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text("${StudyRules.RECENT_LABEL} · $n", style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
        Text("⌄", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.rotate(turn))
    }
}

/** One deleted lesson: its name, "From 1 · Foundations" and Restore. */
@Composable
private fun DeletedRow(d: StudyDeletedRow, modifier: Modifier, onRestore: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(start = MekaSpace.m).testTag("study-deleted-${d.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MekaType.body, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(d.line, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Text(StudyRules.RESTORE, style = MekaType.itemMeta, color = Meka.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button, onClick = onRestore)
                .semantics { contentDescription = "Restore ${d.title}" }.minTouch().padding(MekaSpace.xs).testTag("study-restore-${d.id}"))
    }
}
