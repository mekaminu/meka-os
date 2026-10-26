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
 * Still to come (the board): slice 2, the plan (finish by, session length, when he studies, sessions a week, on track or
 * behind, sessions on the Week board, the 10-minute nudge and the Sunday check-in); slice 3, the outline from a
 * screenshot.
 */
object StudyCourseFields {
    /** "Data analytics certificate" (at most [StudyRules.MAX_COURSE]). */
    const val TITLE = "title"
    const val ADDED_AT = "addedAtMs"
    /** LWW: a removed course and its lessons are not shown; Undo sets it back to false. */
    const val REMOVED = "removed"
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

data class StudyCourse(val id: String, val title: String, val addedAtMs: Long)

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
        StudyCourse(id, title, s.f(StudyCourseFields.ADDED_AT).longOrNull ?: 0L)
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

    fun view(course: StudyCourse, all: List<StudyLesson>): StudyCourseView {
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
        return StudyCourseView(
            id = course.id, title = course.title, pct = pct, fraction = if (total == 0) 0f else done.toFloat() / total,
            done = done, total = total, line = line, next = next, allDone = total > 0 && done == total,
            modules = modules, lastModule = lastModule,
            spoken = "${course.title}, $pct percent, $line" + (next?.let { ", $it" } ?: ""),
        )
    }

    fun view(courses: List<StudyCourse>, lessons: List<StudyLesson>): StudyView = StudyView(courses.map { view(it, lessons) })
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

    fun view(): StudyView = StudyRules.view(courses(), lessons())

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
