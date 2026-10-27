package os.meka.android.goals

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import os.meka.android.lists.Field
import os.meka.android.today.SectionLabel
import os.meka.core.domain.StudyAdded
import os.meka.core.domain.StudyChip
import os.meka.core.domain.StudyPlanInput
import os.meka.core.domain.StudyPlanView
import os.meka.core.domain.StudyCourseView
import os.meka.core.domain.StudyDeletedRow
import os.meka.core.domain.StudyLessonRow
import os.meka.core.domain.StudyModule
import os.meka.core.domain.StudyRemoved
import os.meka.core.domain.StudyRules
import os.meka.core.domain.StudyView
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
internal fun StudyPane(core: MekaCore, courseId: String, onClose: () -> Unit, onRemoved: (StudyRemoved) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val all by core.studyView.collectAsState()
    val c = all.course(courseId)
    var said by remember { mutableStateOf<StudyAdded?>(null) }
    var removed by remember { mutableStateOf<StudyRemoved?>(null) }
    var outline by rememberSaveable(courseId) { mutableStateOf("") }
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
    BackHandler(onBack = { if (sheet != null || moduleMenu != null) { sheet = null; moduleMenu = null } else onClose() })
    LaunchedEffect(c) {
        val shown = c?.modules.orEmpty().flatMap { it.lessons }.associate { it.id to it.done }
        pending.keys.toList().forEach { id -> if (shown[id] == null || shown[id] == pending[id]) pending.remove(id) }
        val days = c?.modules.orEmpty().flatMap { it.lessons }.associate { it.id to it.plannedDay }
        plannedTap.keys.toList().forEach { id -> if (id !in days || days[id] == plannedTap[id]) plannedTap.remove(id) }
        c?.planInput?.let { planTap = planTap.settled(it) }
    }
    LaunchedEffect(removed) { if (removed != null) { delay(TripRules.UNDO_MS); removed = null } }
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
        }
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
    val saidColor by animateColorAsState(
        if (said?.refused == true) Meka.colors.accent else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "study-said",
    )

    // Unfolded, the course keeps a phone-like column (the design is drawn at phone width) centred on the page.
    Box(Modifier.fillMaxSize().background(Meka.colors.background), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        Modifier.widthIn(max = 600.dp).fillMaxHeight().testTag("study-pane"),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
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
                plan, Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(1)),
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
        item(key = "lessons-label") { SectionLabel("Lessons", Modifier.padding(top = MekaSpace.l).appear(rememberAppearance(1))) }
        item(key = "field") {
            Column(Modifier.appear(rememberAppearance(1)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                OutlineField(outline, { outline = it }, add)
                Crossfade(said?.line ?: StudyRules.ADD_LESSONS_NOTE, animationSpec = MekaMotion.appear(reduced), label = "study-added") { line ->
                    Text(line, style = MekaType.caption, color = if (said != null) saidColor else Meka.colors.textTertiary)
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
        val firstOpen = c.modules.firstOrNull { m -> m.lessons.any { !(pending[it.id] ?: it.done) } }?.key
        val moduleNames = c.modules.map { it.name }
        for (m in c.modules) {
            val open = opened[m.key] ?: (m.key == firstOpen)
            item(key = "module-${m.key}") {
                ModuleHead(
                    m, open, pending, Modifier.animateItem(),
                    onToggle = { haptics.tick(); opened[m.key] = !open },
                    onHold = { haptics.tick(); sheet = null; moduleMenu = if (moduleMenu == m.key) null else m.key },
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
                for (row in m.lessons) {
                    item(key = "lesson-${row.id}") {
                        LessonRow(
                            row, pending[row.id] ?: row.done,
                            if (plannedTap.containsKey(row.id)) plannedTap[row.id]?.let { StudyRules.plannedLabel(it, today) } else row.planned,
                            row.late && !plannedTap.containsKey(row.id), Modifier.animateItem(), onTick = { tick(row, it) },
                            onHold = { haptics.tick(); moduleMenu = null; sheet = if (sheet == row.id) null else row.id },
                        )
                    }
                    if (sheet == row.id) item(key = "lesson-sheet-${row.id}") {
                        LessonSheet(
                            row, m.name, moduleNames, pending[row.id] ?: row.done, StudyRules.planChips(today, if (plannedTap.containsKey(row.id)) plannedTap[row.id] else row.plannedDay), Modifier.animateItem(),
                            onPlan = { chip ->
                                haptics.light()
                                val day = chip.key.toLongOrNull()
                                plannedTap[row.id] = day
                                scope.launch {
                                    val line = runCatching { core.planStudyLesson(row.id, day) }
                                    line.getOrNull()?.let { said = StudyAdded(it, 0, refused = false) }
                                    if (line.isFailure) plannedTap.remove(row.id)
                                }
                            },
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
            AddModule(Modifier.animateItem().padding(top = MekaSpace.xs)) { name, text ->
                scope.launch {
                    val r = runCatching { core.addStudyModule(courseId, name, text) }.getOrNull()
                    added(r)
                    r?.refused == false
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
    plan: StudyPlanView, modifier: Modifier,
    onFinish: (StudyChip) -> Unit, onSession: (StudyChip) -> Unit, onSlot: (StudyChip) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth().testTag("study-plan"), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surface).padding(MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            SectionLabel(StudyRules.PLAN)
            ChipGroup(StudyRules.FINISH_LABEL, plan.ends, "study-end", onFinish)
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
private fun ModuleHead(m: StudyModule, open: Boolean, pending: Map<String, Boolean>, modifier: Modifier, onToggle: () -> Unit, onHold: () -> Unit) {
    val reduced = Meka.reducedMotion
    val done = m.lessons.count { pending[it.id] ?: it.done }
    val count = StudyRules.moduleCount(done, m.lessons.size)
    val allDone = done == m.lessons.size
    val turn by animateFloatAsState(if (open) 180f else 0f, MekaMotion.expand(reduced), label = "study-module-chev")
    Row(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .combinedClickable(role = Role.Button, onClick = onToggle, onLongClick = onHold, onLongClickLabel = "Rename or delete the module")
            .semantics(mergeDescendants = true) { contentDescription = "${m.name}, $count, " + if (open) "tap to fold" else "tap to open" }
            .minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("study-module-${m.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        Text(m.name, style = MekaType.taskTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(count, style = MekaType.caption, color = if (allDone) Meka.colors.success else Meka.colors.textTertiary)
        Text("⌄", style = MekaType.caption, color = Meka.colors.textTertiary, modifier = Modifier.rotate(turn))
    }
}

/** One lesson: the ring (22 dp in a 48 dp target), its name (faded once ticked) and how long it takes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LessonRow(row: StudyLessonRow, done: Boolean, planned: String?, late: Boolean, modifier: Modifier, onTick: (Boolean) -> Unit, onHold: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fade by animateFloatAsState(if (done) 0.5f else 1f, MekaMotion.appear(reduced), label = "study-lesson-fade")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .combinedClickable(role = Role.Checkbox, onClick = { onTick(!done) }, onLongClick = onHold, onLongClickLabel = "Rename, move or delete")
            .semantics(mergeDescendants = true) {
                contentDescription = row.spoken.removeSuffix(", done")
                stateDescription = if (done) "Done" else "Not done"
            }
            .minTouch().padding(start = MekaSpace.m).testTag("study-lesson-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        TickRing(done, Modifier.size(22.dp))
        Text(row.title, style = MekaType.taskTitle, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(fade))
        // Plan a lesson for a day: its date chip ("Today", "Tomorrow", "Thu 29 Oct"; in the accent once it rolled over).
        AnimatedVisibility(planned != null && !done, enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced))) {
            Text(planned.orEmpty(), style = MekaType.caption, color = if (late) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs).testTag("study-lesson-planned"))
        }
        if (row.mins.isNotEmpty()) Text(row.mins, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.alpha(fade).padding(end = MekaSpace.xs))
    }
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
    onPlan: (StudyChip) -> Unit, onName: (String) -> Unit, onModule: (String) -> Unit, onTick: (Boolean) -> Unit, onDelete: () -> Unit, onDone: () -> Unit,
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
