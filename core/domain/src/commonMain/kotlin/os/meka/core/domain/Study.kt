package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Study plan (Fix first, Meka 2026-10-26: two courses to finish), slice 1: a course under Goals, with its modules and
 * lessons to tick and a progress ring (mockup `docs/design/study/study-plan.dc.html`). A course's outline is pasted or
 * typed: one lesson a line, "Module 1: Foundations" (or Section, Week, Part, Unit, Chapter, or a line ending in ":")
 * starts a module, and a time at the end ("45 min", "1 h 30", "(1h)") is the lesson's length. Plain rules on the device:
 * course content is Meka's data and never leaves his synced data. Two synced entity types (ADR-008 addendum
 * 2026-10-26): `study_course` and `study_lesson`.
 *
 * Slice 2a: the plan (finish by, session length, when he studies → sessions a week, whether it fits, on track or behind;
 * [StudyRules.plan]). Still to come (the board): slice 2b, sessions on the Week board, Start/Done, the 10-minute nudge and
 * the Sunday check-in; slice 3, the outline from a screenshot.
 */
object StudyCourseFields {
    /** "Data analytics certificate" (at most [StudyRules.MAX_COURSE]). */
    const val TITLE = "title"
    const val ADDED_AT = "addedAtMs"
    /** LWW: a removed course and its lessons are not shown; Undo sets it back to false. */
    const val REMOVED = "removed"
    // Slice 2a, the plan (all LWW, absent until chosen):
    /** The epoch day he means to finish by. */
    const val FINISH_BY = "finishByDay"
    /** Minutes in a session (30, 45, 60; [StudyRules.DEFAULT_SESSION] until chosen). */
    const val SESSION_MINS = "sessionMins"
    /** When he can study: [StudyRules.SLOTS] keys joined by ",", in that order ("train,sat"). */
    const val SLOTS = "slots"
    /** The day the finish date was set, and how many lessons were left then: what "on track" is measured against. */
    const val PLAN_FROM = "planFromDay"
    const val PLAN_LEFT = "planLeft"
}

object StudyLessonFields {
    const val COURSE = "courseId"
    /** The module it sits in ("1 · Foundations"); lessons with the same module name are one module. */
    const val MODULE = "module"
    /** "Lesson 3: Pivot tables" (at most [StudyRules.MAX_LESSON]). */
    const val TITLE = "title"
    /** Minutes it takes; absent when the outline didn't say. */
    const val MINS = "mins"
    /** Its place in the course (lessons show in this order; a module's place is its first lesson's). */
    const val POS = "pos"
    const val DONE = "done"
    const val DONE_AT = "doneAtMs"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
    /** Set on a removed lesson once Undo has put a copy back, so a second Undo adds nothing. */
    const val RESTORED = "restored"
}

data class StudyCourse(
    val id: String, val title: String, val addedAtMs: Long,
    val finishByDay: Long? = null, val sessionMins: Int? = null, val slots: Set<String> = emptySet(),
    val planFromDay: Long? = null, val planLeft: Int? = null,
)

/** A plan choice (a finish date, a session length, a time he can study): its label, whether it's picked, its key. */
data class StudyChip(val key: String, val label: String, val on: Boolean, val spoken: String)

/** When he can study ([StudyRules.SLOTS]): "Train mornings" are three sessions a week. */
data class StudySlot(val key: String, val name: String, val perWeek: Int)

/**
 * What the plan is worked out from (slice 2a): the course's choices and what's left. The apps redo [StudyRules.plan] with
 * a tapped choice so the card answers in the same frame, before the core has written it.
 */
data class StudyPlanInput(
    val today: Long,
    val finishByDay: Long?,
    val sessionMins: Int,
    val slots: Set<String>,
    val leftLessons: Int,
    /** Minutes of the lessons left that say how long they take. */
    val leftKnownMins: Int,
    /** Lessons left that don't say; each counts as the course's average length (or a session). */
    val leftUnknown: Int,
    val avgKnownMins: Int?,
    val planFromDay: Long?,
    val planLeft: Int?,
) {
    fun withFinish(day: Long) = copy(finishByDay = day, planFromDay = today, planLeft = leftLessons)
    fun withSession(mins: Int) = copy(sessionMins = mins)
    fun withSlot(key: String, on: Boolean) = copy(slots = if (on) slots + key else slots - key)
}

/**
 * The course's "Your plan" card (slice 2a, mockup): FINISH BY, EACH SESSION and WHEN YOU CAN STUDY as chips, then "3
 * sessions a week" · "about 2 h 15 · 19 lessons left", whether it fits the slots picked, and Obi's line; [status] ("On
 * track for Mon 7 Dec", "1 lesson behind · catch up Saturday") goes under the course's line.
 */
data class StudyPlanView(
    val ends: List<StudyChip>,
    val lens: List<StudyChip>,
    val slots: List<StudyChip>,
    val perWeek: String?,
    val perWeekSub: String?,
    val fit: String?,
    val fits: Boolean,
    val status: String?,
    val behind: Boolean,
    val obi: String?,
    val spoken: String,
)

data class StudyLesson(
    val id: String, val courseId: String, val module: String, val title: String, val mins: Int?, val pos: Long,
    val done: Boolean, val doneAtMs: Long?,
)

/** One line of an outline ([StudyRules.readOutline]). */
data class StudyEntry(val module: String, val title: String, val mins: Int?)

/** One lesson's row: "Lesson 3" · "45 min", [done] drawn faded with its ring ticked. */
data class StudyLessonRow(val id: String, val title: String, val mins: String, val done: Boolean, val spoken: String)

/** One module: "1 · Foundations" · "2 of 6" (or "Done", [allDone]), its lessons in course order. */
data class StudyModule(val key: String, val name: String, val count: String, val allDone: Boolean, val lessons: List<StudyLessonRow>)

/** A course: the ring, its lines and its modules. */
data class StudyCourseView(
    val id: String,
    val title: String,
    /** 0..100, the ring's number ("21%"). */
    val pct: Int,
    /** 0..1, the ring's sweep. */
    val fraction: Float,
    val done: Int,
    val total: Int,
    /** "5 of 24 lessons · about 18 h in all", or [StudyRules.NO_LESSONS]. */
    val line: String,
    /** "Next: Lesson 6 · 2 · Core skills", "All 24 lessons done", or null with no lessons. */
    val next: String?,
    val allDone: Boolean,
    val modules: List<StudyModule>,
    /** The module new lessons go in when the outline doesn't name one (the last one), or null for none yet. */
    val lastModule: String?,
    val spoken: String,
    /** Slice 2a: the plan card and what it's worked out from. */
    val plan: StudyPlanView,
    val planInput: StudyPlanInput,
)

data class StudyView(val courses: List<StudyCourseView>) {
    fun course(id: String): StudyCourseView? = courses.firstOrNull { it.id == id }

    companion object { val EMPTY = StudyView(emptyList()) }
}

/** What adding did: the line under the field, how many lessons, and whether nothing was written. */
data class StudyAdded(val line: String, val count: Int, val refused: Boolean, val courseId: String? = null)

/** What removing did: "Removed Lesson 3" and the token Undo takes. */
data class StudyRemoved(val line: String, val token: String)

object StudyRules {
    const val MAX_COURSE = 60
    const val MAX_MODULE = 60
    const val MAX_LESSON = 80
    const val MAX_MINS = 600
    /** At most this many lessons from one paste (a whole course outline fits). */
    const val MAX_PASTE = 200
    const val DEFAULT_MODULE = "Lessons"
    const val SECTION = "Study"
    const val ADD_COURSE_HINT = "Add a course…"
    const val ADD_COURSE_NOTE = "A course you're working through: its modules and lessons to tick, and how far you are."
    const val ADD_LESSONS_HINT = "Add lessons · paste the course outline"
    const val ADD_LESSONS_DESCRIPTION =
        "Add lessons. Paste the course outline, one lesson a line; a line like Module 1: Foundations starts a module, and a time at the end like 45 min is the lesson's length"
    const val ADD_LESSONS_NOTE = "One lesson a line. \"Module 2: Core skills\" starts a module; \"45 min\" at the end says how long."
    const val NO_LESSONS = "No lessons yet"
    const val EMPTY_COURSE = "Paste the course outline above: its modules and lessons, and how long each takes if it says."
    const val NOT_READ = "Paste the course outline, one lesson a line."
    const val COURSE_NOT_READ = "Type the course's name."
    const val GONE = "That course has gone."
    const val NOTE = "Ticks show on the Fold and the Mac."
    const val OPEN = "Open"

    // After the number: nothing, a separator ("Module 1: Foundations") or a capital ("Week 2 Pivot tables"); "Part 2 of
    // the setup" stays a lesson.
    private val HEADER_NUMBERED = Regex("^(?:[Mm]odule|MODULE|[Ss]ection|SECTION|[Ww]eek|WEEK|[Pp]art|PART|[Uu]nit|UNIT|[Cc]hapter|CHAPTER|[Tt]opic|TOPIC|[Bb]lock|BLOCK)\\s*(\\d{1,3})(?:\\s*$|\\s*[:.\\-–—·|]\\s*(.*)$|\\s+([A-Z].*)$)")
    private val HEADER_NAMED = Regex("(?i)^(module|section|week|part|unit|chapter|topic|block)\\s*[:\\-–—·|]\\s*(.+)$")
    private val BULLET = Regex("^[-•*·–—>]+\\s*")
    private val NUMBERING = Regex("^\\d{1,3}(\\.\\d{1,3})*[.)]?\\s+(?=\\S)")
    private val HOURS = Regex("(?i)[\\s(\\[·,|\\-–—]*(\\d{1,2}(?:[.,]\\d)?)\\s*(?:h|hr|hrs|hour|hours)\\b\\s*(?:(\\d{1,2})\\s*(?:m|min|mins|minute|minutes)?)?\\s*[)\\]]?$")
    private val MINUTES = Regex("(?i)[\\s(\\[·,|\\-–—]*(\\d{1,3})\\s*(?:m|min|mins|minute|minutes)\\b\\.?\\s*[)\\]]?$")

    private fun clean(s: String, max: Int): String =
        s.trim().trim(' ', ',', ';', '-', '–', '—', '·', '|', ':').replace(Regex("\\s+"), " ").take(max).trim()
            .replaceFirstChar { it.uppercaseChar() }

    /** "Module 1: Foundations" → "1 · Foundations", "Week 3" → "Week 3", "Basics:" → "Basics"; null for a lesson line. */
    fun moduleOf(line: String): String? {
        val t = line.trim()
        HEADER_NUMBERED.find(t)?.let { m ->
            val n = m.groupValues[1].trimStart('0').ifEmpty { "0" }
            val name = clean(m.groupValues[2].ifEmpty { m.groupValues[3] }, MAX_MODULE)
            val word = t.takeWhile { it.isLetter() }.lowercase().replaceFirstChar { it.uppercaseChar() }
            return if (name.isEmpty() || name.none { it.isLetter() }) "$word $n" else "$n · $name".take(MAX_MODULE)
        }
        HEADER_NAMED.find(t)?.let { m -> return clean(m.groupValues[2], MAX_MODULE).takeIf { it.isNotEmpty() } }
        if (t.endsWith(":") && t.length > 1 && t.dropLast(1).any { it.isLetter() }) return clean(t.dropLast(1), MAX_MODULE)
        return null
    }

    /** The minutes a time at the end says ("45 min", "1 h 30", "(1.5h)") and the line without it. */
    fun lengthOf(line: String): Pair<String, Int?> {
        HOURS.find(line)?.let { m ->
            val h = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val extra = m.groupValues[2].toIntOrNull() ?: 0
            val mins = (h * 60).toInt() + extra
            val rest = line.substring(0, m.range.first)
            if (mins in 1..MAX_MINS && rest.any { it.isLetterOrDigit() }) return rest to mins
        }
        MINUTES.find(line)?.let { m ->
            val mins = m.groupValues[1].toIntOrNull() ?: return@let
            val rest = line.substring(0, m.range.first)
            if (mins in 1..MAX_MINS && rest.any { it.isLetterOrDigit() }) return rest to mins
        }
        return line to null
    }

    /**
     * Reads a pasted or typed outline: one lesson a line (or split by ";"), a module header starts a module, a time at
     * the end is the lesson's length; bullets and numbering ("1.", "2.3", "-") are dropped. Lessons before any header go
     * in [module] (the course's last module), else [DEFAULT_MODULE]. Empty when there's no lesson to add.
     */
    fun readOutline(text: String, module: String?): List<StudyEntry> {
        var current = module?.takeIf { it.isNotBlank() } ?: DEFAULT_MODULE
        val out = mutableListOf<StudyEntry>()
        for (raw in text.split('\n', '\r', ';')) {
            var t = raw.trim().replace(BULLET, "").trim()
            if (t.isEmpty() || t.none { it.isLetterOrDigit() }) continue
            val header = moduleOf(t)
            if (header != null) { current = header; continue }
            t = t.replace(NUMBERING, "")
            val (rest, mins) = lengthOf(t)
            val title = clean(rest, MAX_LESSON)
            if (title.isEmpty() || title.none { it.isLetterOrDigit() }) continue
            out += StudyEntry(current, title, mins)
            if (out.size >= MAX_PASTE) break
        }
        return out
    }

    /** A course's typed name, tidied; null when there's nothing to name it. */
    fun courseTitle(text: String): String? =
        clean(text.replace(Regex("(?i)^(add\\s+)?(a\\s+)?course\\s*[:\\-–]?\\s*"), ""), MAX_COURSE).takeIf { t -> t.any { it.isLetterOrDigit() } }

    /** "45 min", "1 h", "1 h 30"; "" for none. */
    fun minsLabel(mins: Int?): String = when {
        mins == null || mins <= 0 -> ""
        mins < 60 -> "$mins min"
        mins % 60 == 0 -> "${mins / 60} h"
        else -> "${mins / 60} h ${mins % 60}"
    }

    /** "about 18 h", "about 40 min", rounding to the half hour from an hour up. */
    fun aboutLabel(mins: Int): String = when {
        mins < 60 -> "about $mins min"
        else -> {
            val halves = (mins + 15) / 30
            if (halves % 2 == 0) "about ${halves / 2} h" else "about ${halves / 2}½ h"
        }
    }

    fun addedLine(entries: List<StudyEntry>): String {
        val modules = entries.map { it.module }.distinct()
        return when {
            entries.size == 1 -> "Added ${entries[0].title} to ${entries[0].module}"
            modules.size == 1 -> "Added ${entries.size} lessons to ${modules[0]}"
            else -> "Added ${entries.size} lessons in ${modules.size} modules"
        }
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    fun courses(entities: Map<String, Map<String, FieldValue>>): List<StudyCourse> = entities.mapNotNull { (id, s) ->
        if (s.f(ActionableFields.DELETED).boolOrNull == true || s.f(StudyCourseFields.REMOVED).boolOrNull == true) return@mapNotNull null
        val title = s.f(StudyCourseFields.TITLE).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        StudyCourse(
            id, title, s.f(StudyCourseFields.ADDED_AT).longOrNull ?: 0L,
            finishByDay = s.f(StudyCourseFields.FINISH_BY).longOrNull,
            sessionMins = s.f(StudyCourseFields.SESSION_MINS).longOrNull?.toInt()?.takeIf { it in MIN_SESSION..MAX_SESSION },
            slots = readSlots(s.f(StudyCourseFields.SLOTS).textOrNull),
            planFromDay = s.f(StudyCourseFields.PLAN_FROM).longOrNull,
            planLeft = s.f(StudyCourseFields.PLAN_LEFT).longOrNull?.toInt()?.takeIf { it >= 0 },
        )
    }.sortedWith(compareBy<StudyCourse> { it.addedAtMs }.thenBy { it.id })

    fun lessons(entities: Map<String, Map<String, FieldValue>>): List<StudyLesson> = entities.mapNotNull { (id, s) ->
        if (s.f(StudyLessonFields.DELETED).boolOrNull == true) return@mapNotNull null
        val course = s.f(StudyLessonFields.COURSE).textOrNull ?: return@mapNotNull null
        val title = s.f(StudyLessonFields.TITLE).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        StudyLesson(
            id, course,
            s.f(StudyLessonFields.MODULE).textOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_MODULE,
            title,
            s.f(StudyLessonFields.MINS).longOrNull?.toInt()?.takeIf { it in 1..MAX_MINS },
            s.f(StudyLessonFields.POS).longOrNull ?: (s.f(StudyLessonFields.ADDED_AT).longOrNull ?: 0L),
            s.f(StudyLessonFields.DONE).boolOrNull == true,
            s.f(StudyLessonFields.DONE_AT).longOrNull,
        )
    }.sortedWith(compareBy<StudyLesson> { it.pos }.thenBy { it.id })

    fun lessonFields(courseId: String, e: StudyEntry, pos: Long, nowMs: Long): Map<String, FieldValue> = buildMap {
        put(StudyLessonFields.COURSE, courseId.fv())
        put(StudyLessonFields.MODULE, e.module.fv())
        put(StudyLessonFields.TITLE, e.title.fv())
        e.mins?.let { put(StudyLessonFields.MINS, it.toLong().fv()) }
        put(StudyLessonFields.POS, pos.fv())
        put(StudyLessonFields.DONE, false.fv())
        put(StudyLessonFields.ADDED_AT, nowMs.fv())
    }

    fun tickFields(done: Boolean, nowMs: Long): Map<String, FieldValue> = mapOf(
        StudyLessonFields.DONE to done.fv(),
        StudyLessonFields.DONE_AT to (if (done) nowMs.fv() else FieldValue.Null),
    )

    /** The module key the apps fold by. */
    fun moduleKey(courseId: String, module: String): String = "$courseId:${module.lowercase()}"

    private fun row(l: StudyLesson): StudyLessonRow {
        val mins = minsLabel(l.mins)
        val spoken = listOfNotNull(l.title, mins.takeIf { it.isNotEmpty() }?.replace(" min", " minutes")?.replace(" h", " hours"), if (l.done) "done" else null)
            .joinToString(", ")
        return StudyLessonRow(l.id, l.title, mins, l.done, spoken)
    }

    /** "2 of 6", or "Done" once every lesson is ticked. */
    fun moduleCount(done: Int, total: Int): String = if (total > 0 && done == total) "Done" else "$done of $total"

    fun view(course: StudyCourse, all: List<StudyLesson>, today: Long): StudyCourseView {
        val lessons = all.filter { it.courseId == course.id }
        val done = lessons.count { it.done }
        val total = lessons.size
        val pct = if (total == 0) 0 else (done * 100 + total / 2) / total
        val modules = lessons.groupBy { it.module.lowercase() }.values
            .sortedBy { ls -> ls.minOf { it.pos } }
            .map { ls ->
                val d = ls.count { it.done }
                StudyModule(moduleKey(course.id, ls.first().module), ls.first().module, moduleCount(d, ls.size), d == ls.size, ls.map(::row))
            }
        val allMins = lessons.mapNotNull { it.mins }.sum()
        val leftMins = lessons.filter { !it.done }.mapNotNull { it.mins }.sum()
        val lessonsWord = if (total == 1) "lesson" else "lessons"
        val line = when {
            total == 0 -> NO_LESSONS
            allMins == 0 -> "$done of $total $lessonsWord"
            done == 0 -> "$done of $total $lessonsWord · ${aboutLabel(allMins)} in all"
            done == total -> "$done of $total $lessonsWord · ${aboutLabel(allMins)} in all"
            leftMins == 0 -> "$done of $total $lessonsWord"
            else -> "$done of $total $lessonsWord · ${aboutLabel(leftMins)} left"
        }
        val nextLesson = lessons.firstOrNull { !it.done }
        val next = when {
            total == 0 -> null
            nextLesson == null -> if (total == 1) "The lesson is done" else "All $total lessons done"
            modules.size > 1 || nextLesson.module != DEFAULT_MODULE -> "Next: ${nextLesson.title} · ${nextLesson.module}"
            else -> "Next: ${nextLesson.title}"
        }
        val lastModule = lessons.maxByOrNull { it.pos }?.module
        val known = lessons.mapNotNull { it.mins }
        val left = lessons.filter { !it.done }
        val input = StudyPlanInput(
            today = today, finishByDay = course.finishByDay, sessionMins = course.sessionMins ?: DEFAULT_SESSION,
            slots = course.slots, leftLessons = left.size, leftKnownMins = left.mapNotNull { it.mins }.sum(),
            leftUnknown = left.count { it.mins == null }, avgKnownMins = if (known.isEmpty()) null else (known.sum() + known.size / 2) / known.size,
            planFromDay = course.planFromDay, planLeft = course.planLeft,
        )
        return StudyCourseView(
            id = course.id, title = course.title, pct = pct, fraction = if (total == 0) 0f else done.toFloat() / total,
            done = done, total = total, line = line, next = next, allDone = total > 0 && done == total,
            modules = modules, lastModule = lastModule,
            spoken = "${course.title}, $pct percent, $line" + (next?.let { ", $it" } ?: ""),
            plan = plan(input), planInput = input,
        )
    }

    fun view(courses: List<StudyCourse>, lessons: List<StudyLesson>, today: Long): StudyView =
        StudyView(courses.map { view(it, lessons, today) })

    // ---- The plan (slice 2a) ----

    const val DEFAULT_SESSION = 45
    const val MIN_SESSION = 10
    const val MAX_SESSION = 240
    val SESSION_CHOICES = listOf(30, 45, 60)
    /** Finish-by choices, in weeks from today. */
    val WEEK_CHOICES = listOf(4, 6, 8)
    val SLOTS = listOf(
        StudySlot("train", "Train mornings", 3),
        StudySlot("thu", "Thu after work", 1),
        StudySlot("sat", "Sat morning", 1),
        StudySlot("eve", "Evenings", 2),
        StudySlot("night", "Nights", 2),
    )
    const val PLAN = "Your plan"
    const val FINISH_LABEL = "Finish by"
    const val SESSION_LABEL = "Each session"
    const val SLOTS_LABEL = "When you can study"
    const val PICK_DATE = "Pick a finish date to see how many sessions a week it takes."
    const val PICK_SLOTS = "Pick when you can study to see if it fits."
    const val PASSED = "The finish date has passed · pick a new one"

    fun readSlots(text: String?): Set<String> =
        text.orEmpty().split(',').map { it.trim() }.filter { k -> SLOTS.any { it.key == k } }.toSet()

    fun slotsText(keys: Set<String>): String = SLOTS.filter { it.key in keys }.joinToString(",") { it.key }

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    private fun ceilDiv(a: Long, b: Long): Long = if (b <= 0) 0 else (a + b - 1) / b

    /** Minutes of lessons left, counting a lesson that doesn't say as the course's average (or a session). */
    fun leftMins(i: StudyPlanInput): Int = i.leftKnownMins + i.leftUnknown * (i.avgKnownMins ?: i.sessionMins)

    /** Sessions a week to finish by [finish] (at least one), from [i]'s minutes left and session length. */
    fun sessionsPerWeek(i: StudyPlanInput, finish: Long, sessionMins: Int = i.sessionMins): Int {
        val days = (finish - i.today).coerceAtLeast(1)
        val perWeekMin = ceilDiv(leftMins(i).toLong() * 7, days)
        return ceilDiv(perWeekMin, sessionMins.toLong().coerceAtLeast(1)).toInt().coerceAtLeast(1)
    }

    /** Minutes a week to finish by [finish]. */
    fun minsPerWeek(i: StudyPlanInput, finish: Long): Int =
        ceilDiv(leftMins(i).toLong() * 7, (finish - i.today).coerceAtLeast(1)).toInt()

    /** Sessions a week the picked slots give. */
    fun available(slots: Set<String>): Int = SLOTS.filter { it.key in slots }.sumOf { it.perWeek }

    /** "Mon 7 Dec · 6 wks" */
    fun endLabel(day: Long, today: Long): String {
        val days = day - today
        val tail = when {
            days <= 0 -> "passed"
            days < 7 -> plural(days.toInt(), "day")
            else -> "${(days + 3) / 7} wks".let { if (it == "1 wks") "1 wk" else it }
        }
        return "${CivilDate.shortLabel(day)} · $tail"
    }

    /**
     * How far behind the plan he is, in whole lessons: lessons left when the date was set are spread evenly from that
     * day to the finish, and anything under a whole lesson counts as on track.
     */
    fun behindBy(i: StudyPlanInput): Int {
        val finish = i.finishByDay ?: return 0
        val from = i.planFromDay ?: i.today
        val startLeft = i.planLeft ?: i.leftLessons
        val span = finish - from
        if (span <= 0) return 0
        val elapsed = (i.today - from).coerceIn(0, span)
        val expected = startLeft.toDouble() * elapsed / span
        val actual = (startLeft - i.leftLessons).coerceAtLeast(0)
        return kotlin.math.floor(expected - actual + 1e-9).toInt().coerceAtLeast(0)
    }

    /** The plan card for [i] ([StudyPlanView]). */
    fun plan(i: StudyPlanInput): StudyPlanView {
        val finish = i.finishByDay
        val options = WEEK_CHOICES.map { i.today + it * 7L }
        val ends = buildList {
            if (finish != null && finish !in options) add(StudyChip(finish.toString(), endLabel(finish, i.today), true, "Finish by ${CivilDate.longLabel(finish)}"))
            options.forEach { d -> add(StudyChip(d.toString(), endLabel(d, i.today), d == finish, "Finish by ${CivilDate.longLabel(d)}")) }
        }
        val lens = buildList {
            if (i.sessionMins !in SESSION_CHOICES) add(StudyChip(i.sessionMins.toString(), minsLabel(i.sessionMins), true, "${minsLabel(i.sessionMins)} sessions"))
            SESSION_CHOICES.forEach { m -> add(StudyChip(m.toString(), minsLabel(m), m == i.sessionMins, "$m minute sessions")) }
        }
        val slots = SLOTS.map { StudyChip(it.key, it.name, it.key in i.slots, "${it.name}, ${plural(it.perWeek, "session")} a week") }
        val avail = available(i.slots)
        var perWeek: String? = null
        var perWeekSub: String? = null
        var fit: String? = null
        var fits = false
        var status: String? = null
        var behind = false
        var obi: String? = null
        when {
            i.leftLessons == 0 -> Unit
            finish == null -> fit = PICK_DATE
            finish <= i.today -> { status = PASSED; behind = true }
            else -> {
                val sessions = sessionsPerWeek(i, finish)
                perWeek = "${plural(sessions, "session")} a week"
                perWeekSub = "about ${minsLabel(minsPerWeek(i, finish).coerceAtLeast(1))} · ${plural(i.leftLessons, "lesson")} left"
                fits = avail > 0 && sessions <= avail
                fit = when {
                    avail == 0 -> PICK_SLOTS
                    fits -> "Fits: you picked ${plural(avail, "slot")} a week."
                    else -> "Doesn’t fit: you need ${plural(sessions, "slot")} a week but picked $avail. Add a slot, make sessions longer or move the date."
                }
                val by = behindBy(i)
                behind = by > 0
                status = if (by > 0) {
                    "${plural(by, "lesson")} behind · " + if ("sat" in i.slots) "catch up Saturday" else "an extra session catches you up"
                } else "On track for ${CivilDate.shortLabel(finish)}"
                obi = when {
                    avail == 0 -> null
                    fits -> (avail - sessions).let { spare ->
                        if (spare > 0) "That leaves ${plural(spare, "spare slot")} a week to catch up if one slips."
                        else "Every slot you picked is needed: one more gives you room to catch up."
                    }
                    else -> {
                        val longer = SESSION_CHOICES.firstOrNull { it > i.sessionMins && sessionsPerWeek(i, finish, it) <= avail }
                        val later = (1..52).map { i.today + it * 7L }.firstOrNull { it > finish && sessionsPerWeek(i, it) <= avail }
                        when {
                            longer != null -> "With $longer-minute sessions it fits the slots you picked."
                            later != null -> "Move the date to ${CivilDate.shortLabel(later)} and it fits the slots you picked."
                            else -> null
                        }
                    }
                }
            }
        }
        val spoken = listOfNotNull(status, perWeek, perWeekSub, fit, obi).joinToString(". ")
        return StudyPlanView(ends, lens, slots, perWeek, perWeekSub, fit, fits, status, behind, obi, spoken)
    }
}

/** Courses and their lessons on a replica. */
class Study(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
) {
    fun courses(): List<StudyCourse> =
        StudyRules.courses(replica.entities(EntityTypes.STUDY_COURSE).associate { it.ref.entityId to it.fields })

    fun lessons(): List<StudyLesson> =
        StudyRules.lessons(replica.entities(EntityTypes.STUDY_LESSON).associate { it.ref.entityId to it.fields })

    fun view(today: Long): StudyView = StudyRules.view(courses(), lessons(), today)

    private fun exists(courseId: String): Boolean = courses().any { it.id == courseId }

    /** Adds a course named [text]; refused when there's no name. Any lines after the first are its outline. */
    fun addCourse(text: String): StudyAdded {
        val lines = text.trim().lines()
        val title = StudyRules.courseTitle(lines.firstOrNull().orEmpty()) ?: return StudyAdded(StudyRules.COURSE_NOT_READ, 0, refused = true)
        val id = newId()
        replica.commitLocal(EntityTypes.STUDY_COURSE, id, mapOf(
            StudyCourseFields.TITLE to title.fv(),
            StudyCourseFields.ADDED_AT to nowMs().fv(),
            StudyCourseFields.REMOVED to false.fv(),
        ))
        val rest = lines.drop(1).joinToString("\n")
        val added = if (rest.isBlank()) 0 else addLessons(id, rest).let { if (it.refused) 0 else it.count }
        return StudyAdded(if (added > 0) "Added $title with $added lessons" else "Added $title", added, refused = false, courseId = id)
    }

    /** Adds the lessons [text] lists to the course ([StudyRules.readOutline]), after its last lesson. */
    fun addLessons(courseId: String, text: String): StudyAdded {
        if (!exists(courseId)) return StudyAdded(StudyRules.GONE, 0, refused = true)
        val mine = lessons().filter { it.courseId == courseId }
        val last = mine.maxByOrNull { it.pos }
        val entries = StudyRules.readOutline(text, last?.module)
        if (entries.isEmpty()) return StudyAdded(StudyRules.NOT_READ, 0, refused = true)
        var pos = (last?.pos ?: 0L)
        val now = nowMs()
        // A lesson under a module the course already has goes at that module's end (the rest shift along by one).
        val placed = mine.map { it.module.lowercase() to it.pos }.toMutableList()
        for (e in entries) {
            val inModule = placed.filter { it.first == e.module.lowercase() }.maxOfOrNull { it.second }
            val at = if (inModule != null && inModule < pos) {
                // Room after the module's last lesson: halve the gap to the next one (positions are spaced by 1024).
                val nextPos = placed.map { it.second }.filter { it > inModule }.minOrNull() ?: (inModule + GAP * 2)
                if (nextPos - inModule >= 2) inModule + (nextPos - inModule) / 2 else { pos += GAP; pos }
            } else { pos += GAP; pos }
            placed += e.module.lowercase() to at
            replica.commitLocal(EntityTypes.STUDY_LESSON, newId(), StudyRules.lessonFields(courseId, e, at, now))
        }
        return StudyAdded(StudyRules.addedLine(entries), entries.size, refused = false, courseId = courseId)
    }

    /** Ticks or unticks one lesson; false when it isn't there or is already so. */
    fun tick(id: String, done: Boolean): Boolean {
        val f = replica.entity(EntityTypes.STUDY_LESSON, id)?.fields ?: return false
        if (f[StudyLessonFields.DELETED]?.boolOrNull == true) return false
        if ((f[StudyLessonFields.DONE]?.boolOrNull == true) == done) return false
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, StudyRules.tickFields(done, nowMs()))
        return true
    }

    /**
     * Plan (slice 2a): the finish date. "On track" is then measured from [today] with the lessons left now; false when
     * the course isn't there or the date is already so.
     */
    fun setFinish(courseId: String, day: Long, today: Long): Boolean {
        val c = courses().firstOrNull { it.id == courseId } ?: return false
        if (c.finishByDay == day) return false
        val left = lessons().count { it.courseId == courseId && !it.done }
        replica.commitLocal(EntityTypes.STUDY_COURSE, courseId, mapOf(
            StudyCourseFields.FINISH_BY to day.fv(),
            StudyCourseFields.PLAN_FROM to today.fv(),
            StudyCourseFields.PLAN_LEFT to left.toLong().fv(),
        ))
        return true
    }

    /** Plan: minutes in a session ([StudyRules.MIN_SESSION]..[StudyRules.MAX_SESSION]). */
    fun setSession(courseId: String, mins: Int): Boolean {
        val c = courses().firstOrNull { it.id == courseId } ?: return false
        if (mins !in StudyRules.MIN_SESSION..StudyRules.MAX_SESSION || c.sessionMins == mins) return false
        replica.commitLocal(EntityTypes.STUDY_COURSE, courseId, mapOf(StudyCourseFields.SESSION_MINS to mins.toLong().fv()))
        return true
    }

    /** Plan: picks or drops a time he can study ([StudyRules.SLOTS] key). */
    fun setSlot(courseId: String, key: String, on: Boolean): Boolean {
        val c = courses().firstOrNull { it.id == courseId } ?: return false
        if (StudyRules.SLOTS.none { it.key == key } || (key in c.slots) == on) return false
        val next = if (on) c.slots + key else c.slots - key
        replica.commitLocal(EntityTypes.STUDY_COURSE, courseId, mapOf(StudyCourseFields.SLOTS to StudyRules.slotsText(next).fv()))
        return true
    }

    /** Removes one lesson, with Undo's token (its id); null when it isn't there. */
    fun removeLesson(id: String): StudyRemoved? {
        val l = lessons().firstOrNull { it.id == id } ?: return null
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, mapOf(StudyLessonFields.DELETED to true.fv()))
        return StudyRemoved("Removed ${l.title}", LESSON + id)
    }

    /** Removes a course (its lessons go with it), with Undo's token; null when it isn't there. */
    fun removeCourse(id: String): StudyRemoved? {
        val c = courses().firstOrNull { it.id == id } ?: return null
        replica.commitLocal(EntityTypes.STUDY_COURSE, id, mapOf(StudyCourseFields.REMOVED to true.fv()))
        return StudyRemoved("Removed ${c.title}", COURSE + id)
    }

    /**
     * Undo after [removeLesson] or [removeCourse]. A course comes back as it was (`removed` is LWW); a lesson's removal
     * is for good on every device (`deleted` is TrueWins), so it comes back as a copy under a new id, ticked as it was.
     */
    fun undoRemove(token: String): Boolean {
        if (token.startsWith(COURSE)) {
            val id = token.removePrefix(COURSE)
            val f = replica.entity(EntityTypes.STUDY_COURSE, id)?.fields ?: return false
            if (f[StudyCourseFields.REMOVED]?.boolOrNull != true) return false
            replica.commitLocal(EntityTypes.STUDY_COURSE, id, mapOf(StudyCourseFields.REMOVED to false.fv()))
            return true
        }
        val id = token.removePrefix(LESSON)
        val f = replica.entity(EntityTypes.STUDY_LESSON, id)?.fields ?: return false
        if (f[StudyLessonFields.DELETED]?.boolOrNull != true || f[StudyLessonFields.RESTORED]?.boolOrNull == true) return false
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, mapOf(StudyLessonFields.RESTORED to true.fv()))
        replica.commitLocal(EntityTypes.STUDY_LESSON, newId(), f - StudyLessonFields.DELETED - StudyLessonFields.RESTORED)
        return true
    }

    companion object {
        /** Lessons are placed 1024 apart, so one can go between two without moving the rest. */
        const val GAP = 1024L
        private const val COURSE = "course:"
        private const val LESSON = "lesson:"
    }
}
