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
 * same frame and moves the ring (the core's write runs after); a long press removes a lesson with Undo. Remove course
 * at the foot (Undo on Goals). Synced with the Mac.
 *
 * Motion: the header, ring and lessons stagger in; the ring sweeps round from the top on appear and glides on a tick
 * (appear spring); modules fold on the expand spring with the chevron turning; the ring of a lesson draws its check
 * (TickRing) and the title fades; the line under the field cross-fades (refused lit in the accent). Haptics: light on a
 * tick and an add, a tick on a fold or a remove. Reduced motion: cross-fades only, the ring set at once.
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
    // Modules folded or opened by hand; untouched, the first module not yet done is open and the rest folded.
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    // Slice 2a: the plan's chips tapped here, worked out at once until the view says the same.
    var planTap by remember(courseId) { mutableStateOf(PlanTap()) }
    BackHandler(onBack = onClose)
    LaunchedEffect(c) {
        val shown = c?.modules.orEmpty().flatMap { it.lessons }.associate { it.id to it.done }
        pending.keys.toList().forEach { id -> if (shown[id] == null || shown[id] == pending[id]) pending.remove(id) }
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
        for (m in c.modules) {
            val open = opened[m.key] ?: (m.key == firstOpen)
            item(key = "module-${m.key}") {
                ModuleHead(m, open, pending, Modifier.animateItem()) { haptics.tick(); opened[m.key] = !open }
            }
            if (open) {
                items(m.lessons, key = { "lesson-${it.id}" }) { row ->
                    LessonRow(
                        row, pending[row.id] ?: row.done, Modifier.animateItem(), onTick = { tick(row, it) },
                        onRemove = {
                            haptics.tick()
                            scope.launch { runCatching { core.removeStudyLesson(row.id) }.getOrNull()?.let { removed = it } }
                        },
                    )
                }
            }
        }
        item(key = "note") {
            Column(Modifier.animateItem().padding(top = MekaSpace.l), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Text(StudyRules.NOTE, style = MekaType.caption, color = Meka.colors.textTertiary)
                Text(
                    "Remove course", style = MekaType.itemMeta, color = Meka.colors.critical,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m)).clickable(role = Role.Button) {
                        haptics.tick()
                        scope.launch { runCatching { core.removeStudyCourse(courseId) }.getOrNull()?.let { onRemoved(it); onClose() } }
                    }.semantics { contentDescription = "Remove ${c.title} and its lessons" }.minTouch().testTag("study-remove-course"),
                )
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
private fun OutlineField(value: String, onValue: (String) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.l)).background(Meka.colors.surfaceRaised)
                .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
        ) {
            if (value.isEmpty()) Text(StudyRules.ADD_LESSONS_HINT, style = MekaType.body, color = Meka.colors.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                cursorBrush = SolidColor(Meka.colors.accent),
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)
                    .semantics { contentDescription = StudyRules.ADD_LESSONS_DESCRIPTION }.testTag("study-outline"),
            )
        }
        AnimatedVisibility(value.isNotBlank(), enter = unfold(), exit = fold()) {
            Action("Add lessons") { onAdd() }
        }
    }
}

/** A module's head: its name, its count ("2 of 6", "Done" in green) and a chevron; a tap folds it. */
@Composable
private fun ModuleHead(m: StudyModule, open: Boolean, pending: Map<String, Boolean>, modifier: Modifier, onToggle: () -> Unit) {
    val reduced = Meka.reducedMotion
    val done = m.lessons.count { pending[it.id] ?: it.done }
    val count = StudyRules.moduleCount(done, m.lessons.size)
    val allDone = done == m.lessons.size
    val turn by animateFloatAsState(if (open) 180f else 0f, MekaMotion.expand(reduced), label = "study-module-chev")
    Row(
        modifier.fillMaxWidth().padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .clickable(role = Role.Button, onClick = onToggle)
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
private fun LessonRow(row: StudyLessonRow, done: Boolean, modifier: Modifier, onTick: (Boolean) -> Unit, onRemove: () -> Unit) {
    val reduced = Meka.reducedMotion
    val fade by animateFloatAsState(if (done) 0.5f else 1f, MekaMotion.appear(reduced), label = "study-lesson-fade")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m))
            .combinedClickable(role = Role.Checkbox, onClick = { onTick(!done) }, onLongClick = onRemove, onLongClickLabel = "Remove")
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
        if (row.mins.isNotEmpty()) Text(row.mins, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.alpha(fade).padding(end = MekaSpace.xs))
    }
}
