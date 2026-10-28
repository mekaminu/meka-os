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
 * [StudyRules.plan]).
 *
 * Study fixes (Meka 2026-10-26 23:21: "when I hold an item it deletes automatically, I just lost an item"; "how do I add
 * an item in a module"): a long press opens a lesson's sheet (rename, move to another module, mark done, delete after a
 * confirm) instead of deleting; a module can be renamed or deleted (after a confirm, with Undo), lessons are added into
 * a chosen module, and a new module is added with its first lessons; every lesson deleted in the last
 * [StudyRules.RECENT_DAYS] days (and any deleted before deletion times were kept) shows under Recently deleted with Restore. Slice 2b part 1 ([StudyAutoRules]): lessons he hasn't planned are suggested for his next study sessions.
 * Slice 2b part 2 ([StudyWeekRules]): THIS WEEK with Start and Done on a session.
 * Slice 2b rest ([StudyNudgeRules]): the 10-minute nudge card on Today, with Start and Done.
 * Slice 2b rest ([StudyCheckinRules]): the Sunday check-in card on Today.
 * Still to come (the board): slice 3, the outline from a screenshot.
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
    /** Slice 2b rest (LWW Bool, absent = on): "Nudge me 10 minutes before" ([StudyNudgeRules]). */
    const val NUDGE = "nudge"
    /** Slice 2b rest (LWW Bool, absent = on): "Sunday check-in" ([StudyCheckinRules]). */
    const val CHECKIN = "checkin"
    /** Slice 2b rest (LWW Int64): the Sunday (epoch day) whose check-in he put away with Looks good. */
    const val CHECKIN_SEEN = "checkinSeen"
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
    /** Study fixes (2026-10-26): when it was deleted, for Recently deleted (LWW, absent on lessons deleted before). */
    const val DELETED_AT = "deletedAtMs"
    /**
     * Plan a lesson for a day (Meka 2026-10-26 23:21: "how do you plan an item for today, tomorrow…"): the epoch day he
     * planned it for (LWW; Null when not planned). An un-done lesson planned for a day gone by rolls to today.
     */
    const val PLANNED_DAY = "plannedDay"
}

data class StudyCourse(
    val id: String, val title: String, val addedAtMs: Long,
    val finishByDay: Long? = null, val sessionMins: Int? = null, val slots: Set<String> = emptySet(),
    val planFromDay: Long? = null, val planLeft: Int? = null,
    /** Slice 2b rest: the 10-minute nudge card on Today ([StudyNudgeRules]); on until he turns it off. */
    val nudge: Boolean = true,
    /** Slice 2b rest: the Sunday check-in card on Today ([StudyCheckinRules]); on until he turns it off. */
    val checkin: Boolean = true,
    /** The Sunday whose check-in he put away, or null. */
    val checkinSeen: Long? = null,
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
    /** The day he planned it for ([StudyLessonFields.PLANNED_DAY]), or null. */
    val plannedDay: Long? = null,
    /**
     * Slice 2b: [plannedDay] is a suggestion ([StudyAutoRules.suggest]) for his next study session, not a day he set;
     * never stored.
     */
    val suggested: Boolean = false,
)

/** One line of an outline ([StudyRules.readOutline]). */
data class StudyEntry(val module: String, val title: String, val mins: Int?)

/** A deleted lesson still on the replica (its removal is TrueWins; Restore puts a copy back). */
data class StudyDeletedLesson(val id: String, val courseId: String, val title: String, val module: String, val deletedAtMs: Long?)

/** One row of Recently deleted: its title, "From 1 · Foundations", and the id Restore takes. */
data class StudyDeletedRow(val id: String, val title: String, val line: String, val spoken: String)

/**
 * One lesson's row: "Lesson 3" · "45 min", [done] drawn faded with its ring ticked. [planned] is its date chip ("Today",
 * "Tomorrow", "Thu 29 Oct", "Today · from Mon" when it rolled over; [late] then), null when not planned or done.
 */
data class StudyLessonRow(
    val id: String, val title: String, val mins: String, val done: Boolean, val spoken: String,
    val planned: String? = null, val plannedDay: Long? = null, val late: Boolean = false,
    /** Slice 2b: [planned] is a suggestion ("Tomorrow · suggested"); [plannedDay] is then null (he hasn't set one). */
    val suggested: Boolean = false,
)

/**
 * The lessons planned for a day, for Today ("Study today · 3 lessons · about 1 h 30 · next: Pivot tables") and the
 * Tomorrow sheet ("Study · 2 lessons · about 1 h · Pivot tables, Dashboards"). [left] lessons not yet done, [done] of
 * them ticked that day; [lit] when one rolled over from an earlier day.
 */
data class StudyDayLine(
    val text: String, val spoken: String, val left: Int, val done: Int, val lit: Boolean,
    /** The course a tap opens (the Week board's line): the next lesson's, else the first one's; null elsewhere. */
    val courseId: String? = null,
)

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
    /** Study fixes: Recently deleted, newest first. */
    val deleted: List<StudyDeletedRow> = emptyList(),
    /**
     * Slice 2b: "5 lessons suggested for your next 3 sessions · Thu, Sat, Mon · from your calendar" under the plan card
     * ([StudyAutoRules.line]); null when nothing is suggested.
     */
    val suggested: String? = null,
    /** Slice 2b part 2: THIS WEEK, the course's sessions from today for a week with Start and Done ([StudyWeekRules.rows]). */
    val week: List<StudyWeekRow> = emptyList(),
    /** Slice 2b rest: "Nudge me 10 minutes before" is on ([StudyNudgeRules]). */
    val nudge: Boolean = true,
    /** Slice 2b rest: "Sunday check-in" is on ([StudyCheckinRules]). */
    val checkin: Boolean = true,
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

    // Study fixes (2026-10-26): the lesson and module sheets, adding into a module, and Recently deleted.
    const val RECENT_DAYS = 30
    const val RECENT_LABEL = "Recently deleted"
    const val RESTORE = "Restore"
    const val RENAME = "Rename"
    const val MOVE_LABEL = "Move to module"
    const val MARK_DONE = "Mark done"
    const val MARK_UNDONE = "Mark not done"
    const val DELETE = "Delete"
    const val CANCEL = "Cancel"
    const val DONE = "Done"
    const val DELETE_MODULE = "Delete module"
    const val ADD_LESSON = "+ Add lesson"
    const val ADD_LESSON_HINT = "Lesson name, or paste several lines"
    const val ADD_MODULE = "+ Add module"
    const val ADD_MODULE_HINT = "Module name"
    const val ADD_MODULE_LESSONS_HINT = "Its first lessons, one a line"
    const val MODULE_NOT_READ = "Type the module's name."
    const val MODULE_NEEDS_LESSON = "Type the module's first lesson too."
    const val NAME_NOT_READ = "Type a name."
    const val HOLD_NOTE = "Press and hold a lesson or a module to rename, move or delete it; drag ≡ to change the order."
    /** Drag to reorder (2026-10-28): the grip's spoken actions (the same moves without a drag). */
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    /** What the grip says to a screen reader: "Drag to reorder Pivot tables". */
    fun gripLabel(name: String): String = "Drag to reorder $name"

    fun confirmDelete(title: String): String = "Delete \u201C$title\u201D?"
    fun confirmDeleteModule(name: String, n: Int): String = "Delete \u201C$name\u201D and its ${if (n == 1) "lesson" else "$n lessons"}?"
    fun confirmRemoveCourse(title: String, n: Int): String =
        "Remove \u201C$title\u201D" + (if (n == 0) "?" else " and its ${if (n == 1) "lesson" else "$n lessons"}?")

    /** A module's typed name, tidied ("Module 3: Dashboards" reads as "3 · Dashboards"); null with nothing to name it. */
    fun moduleName(text: String): String? {
        val t = text.trim()
        if (t.none { it.isLetterOrDigit() }) return null
        return moduleOf(t) ?: clean(t, MAX_MODULE).takeIf { n -> n.any { it.isLetterOrDigit() } }
    }

    /** A lesson's typed name, tidied (a time at the end is dropped here; the sheet only renames). */
    fun lessonName(text: String): String? = clean(text, MAX_LESSON).takeIf { n -> n.any { it.isLetterOrDigit() } }

    fun deletedLessons(entities: Map<String, Map<String, FieldValue>>): List<StudyDeletedLesson> = entities.mapNotNull { (id, s) ->
        if (s.f(StudyLessonFields.DELETED).boolOrNull != true || s.f(StudyLessonFields.RESTORED).boolOrNull == true) return@mapNotNull null
        val course = s.f(StudyLessonFields.COURSE).textOrNull ?: return@mapNotNull null
        val title = s.f(StudyLessonFields.TITLE).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        StudyDeletedLesson(id, course, title, s.f(StudyLessonFields.MODULE).textOrNull?.takeIf { it.isNotBlank() } ?: DEFAULT_MODULE,
            s.f(StudyLessonFields.DELETED_AT).longOrNull)
    }

    /** Recently deleted for [courseId]: the last [RECENT_DAYS] days (and any without a time), newest first. */
    fun deletedRows(courseId: String, deleted: List<StudyDeletedLesson>, nowMs: Long): List<StudyDeletedRow> =
        deleted.filter { it.courseId == courseId && (it.deletedAtMs == null || nowMs - it.deletedAtMs <= RECENT_DAYS * 86_400_000L) }
            .sortedWith(compareByDescending<StudyDeletedLesson> { it.deletedAtMs ?: 0L }.thenBy { it.title })
            .map { StudyDeletedRow(it.id, it.title, "From ${it.module}", "${it.title}, deleted, from ${it.module}") }

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
            nudge = s.f(StudyCourseFields.NUDGE).boolOrNull ?: true,
            checkin = s.f(StudyCourseFields.CHECKIN).boolOrNull ?: true,
            checkinSeen = s.f(StudyCourseFields.CHECKIN_SEEN).longOrNull,
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
            s.f(StudyLessonFields.PLANNED_DAY).longOrNull,
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

    /**
     * A course's lessons as they show: in modules (a module's place is its first lesson's), each module's lessons in
     * order. [lessons] are one course's, in [lessons]' order (by place).
     */
    fun courseOrder(lessons: List<StudyLesson>): List<List<StudyLesson>> =
        lessons.sortedWith(compareBy<StudyLesson> { it.pos }.thenBy { it.id })
            .groupBy { it.module.lowercase() }.values.sortedBy { ls -> ls.minOf { it.pos } }

    /**
     * Drag to reorder: places for a list where the nulls are the lessons that moved and the rest keep theirs; each run
     * of nulls is spread evenly between its neighbours (or [Study.GAP] apart past either end). Null when the kept
     * places aren't in order or there's no room, so the caller numbers everything again.
     */
    fun placeBetween(slots: List<Long?>): List<Long>? {
        val kept = slots.filterNotNull()
        if (kept.zipWithNext().any { (a, b) -> a >= b }) return null
        val out = ArrayList<Long>(slots.size)
        var i = 0
        while (i < slots.size) {
            val v = slots[i]
            if (v != null) { out += v; i++; continue }
            var j = i
            while (j < slots.size && slots[j] == null) j++
            val k = j - i
            val prev = out.lastOrNull()
            val next = slots.getOrNull(j)
            for (n in 1..k) out += when {
                prev == null && next == null -> n * Study.GAP
                next == null -> prev!! + n * Study.GAP
                prev == null -> next - (k - n + 1) * Study.GAP
                else -> {
                    val step = (next - prev) / (k + 1)
                    if (step < 1) return null
                    prev + n * step
                }
            }
            i = j
        }
        return out
    }

    private fun row(l: StudyLesson, today: Long): StudyLessonRow {
        val mins = minsLabel(l.mins)
        if (l.suggested && !l.done && l.plannedDay != null) {
            val chip = StudyAutoRules.chip(l.plannedDay, today)
            val spoken = listOfNotNull(
                l.title, mins.takeIf { it.isNotEmpty() }?.replace(" min", " minutes")?.replace(" h", " hours"),
                "suggested for ${plannedLabel(l.plannedDay, today)}",
            ).joinToString(", ")
            return StudyLessonRow(l.id, l.title, mins, false, spoken, chip, plannedDay = null, late = false, suggested = true)
        }
        val planned = if (l.done) null else l.plannedDay?.let { plannedLabel(it, today) }
        val spoken = listOfNotNull(
            l.title, mins.takeIf { it.isNotEmpty() }?.replace(" min", " minutes")?.replace(" h", " hours"),
            planned?.let { "planned for ${it.replace(" · ", ", ")}" }, if (l.done) "done" else null,
        ).joinToString(", ")
        return StudyLessonRow(l.id, l.title, mins, l.done, spoken, planned, l.plannedDay, late = !l.done && (l.plannedDay ?: today) < today)
    }

    /** "2 of 6", or "Done" once every lesson is ticked. */
    fun moduleCount(done: Int, total: Int): String = if (total > 0 && done == total) "Done" else "$done of $total"

    fun view(
        course: StudyCourse, all: List<StudyLesson>, today: Long,
        deleted: List<StudyDeletedLesson> = emptyList(), nowMs: Long = 0L, fromCalendar: Boolean = false,
        week: List<StudyWeekRow> = emptyList(),
    ): StudyCourseView {
        val lessons = all.filter { it.courseId == course.id }
        val done = lessons.count { it.done }
        val total = lessons.size
        val pct = if (total == 0) 0 else (done * 100 + total / 2) / total
        val modules = courseOrder(lessons)
            .map { ls ->
                val d = ls.count { it.done }
                StudyModule(moduleKey(course.id, ls.first().module), ls.first().module, moduleCount(d, ls.size), d == ls.size, ls.map { row(it, today) })
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
            deleted = deletedRows(course.id, deleted, nowMs),
            suggested = StudyAutoRules.line(course.id, lessons, fromCalendar),
            week = week,
            nudge = course.nudge,
            checkin = course.checkin,
        )
    }

    fun view(
        courses: List<StudyCourse>, lessons: List<StudyLesson>, today: Long,
        deleted: List<StudyDeletedLesson> = emptyList(), nowMs: Long = 0L, fromCalendar: Boolean = false,
        week: Map<String, List<StudyWeekRow>> = emptyMap(),
    ): StudyView = StudyView(courses.map { view(it, lessons, today, deleted, nowMs, fromCalendar, week[it.id].orEmpty()) })

    // ---- Plan a lesson for a day (Meka 2026-10-26 23:21) ----

    const val PLAN_FOR = "Plan for"
    const val NOT_PLANNED = "Not planned"
    const val TODAY = "Today"
    const val TOMORROW = "Tomorrow"
    /** The sheet offers today and the next [PLAN_DAYS] − 1 days. */
    const val PLAN_DAYS = 7

    /** A planned lesson's date chip: "Today", "Tomorrow", "Thu 29 Oct", or "Today · from Mon" once its day has gone by. */
    fun plannedLabel(day: Long, today: Long): String = when {
        day == today -> TODAY
        day == today + 1 -> TOMORROW
        day < today -> "$TODAY · from ${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]}"
        else -> CivilDate.shortLabel(day)
    }

    /**
     * The lesson sheet's PLAN FOR chips: Today, Tomorrow and the next five days ("Thu 29 Oct"), the one it's planned
     * for picked (a day gone by counts as today, where it rolled to), and "Not planned" (key [NOT_PLANNED_KEY]) once it is.
     */
    fun planChips(today: Long, plannedDay: Long?): List<StudyChip> {
        val on = plannedDay?.let { maxOf(it, today) }
        val days = (0 until PLAN_DAYS).map { today + it }
        val extra = on?.takeIf { it !in days }
        return buildList {
            days.forEach { d -> add(StudyChip(d.toString(), plannedLabel(d, today), d == on, "Plan for ${CivilDate.longLabel(d)}")) }
            extra?.let { add(StudyChip(it.toString(), plannedLabel(it, today), true, "Planned for ${CivilDate.longLabel(it)}")) }
            add(StudyChip(PICK_KEY, PICK_DAY, false, "Pick a day from the calendar"))
            if (plannedDay != null) add(StudyChip(NOT_PLANNED_KEY, NOT_PLANNED, false, "Not planned for a day"))
        }
    }

    const val NOT_PLANNED_KEY = "none"

    // ---- Study fixes (3) slice 3: pick any day, and plan several lessons at once ----

    /** The chip that opens a date picker for a day past the week (key [PICK_KEY]); the apps never plan with its key. */
    const val PICK_DAY = "Pick a day\u2026"
    const val PICK_KEY = "pick"
    /** How far ahead a lesson can be planned: a year from today. */
    const val PICK_MAX_DAYS = 365
    const val SELECT = "Select several"
    const val PLAN_SELECTED = "Plan for\u2026"
    const val SELECT_HINT = "Tap lessons to pick them, then choose a day."
    const val PICK_REFUSED = "Pick a day from today to a year ahead"

    /** Whether [day] can be planned: today up to [PICK_MAX_DAYS] ahead (a day gone by can't be). */
    fun plannable(day: Long, today: Long): Boolean = day in today..(today + PICK_MAX_DAYS)

    /** The chips for several lessons at once: the week, Pick a day… and Not planned (some of them may be planned). */
    fun selectChips(today: Long): List<StudyChip> =
        planChips(today, null) + StudyChip(NOT_PLANNED_KEY, NOT_PLANNED, false, "Not planned for a day")

    /** "1 lesson selected", "3 lessons selected"; "Pick lessons" when none is. */
    fun selectedLine(n: Int): String = when (n) {
        0 -> "Pick lessons"
        1 -> "1 lesson selected"
        else -> "$n lessons selected"
    }

    /** What planning several says: "Planned 3 lessons for tomorrow", "Planned 2 lessons for Thu 12 Nov", "3 lessons are no longer planned". */
    fun plannedManySaid(n: Int, day: Long?, today: Long): String {
        val what = if (n == 1) "1 lesson" else "$n lessons"
        return if (day == null) "$what ${if (n == 1) "is" else "are"} no longer planned"
        else "Planned $what for " + plannedLabel(day, today).let { if (it == TODAY || it == TOMORROW) it.lowercase() else it }
    }

    /** What planning says on the bar: "Planned “Pivot tables” for Tomorrow", "“Pivot tables” is no longer planned". */
    fun plannedSaid(title: String, day: Long?, today: Long): String =
        if (day == null) "\u201C$title\u201D is no longer planned"
        else "Planned \u201C$title\u201D for " + plannedLabel(day, today).let { if (it == TODAY || it == TOMORROW) it.lowercase() else it }

    /**
     * The lessons on [day]: planned for it, plus (for today, [rollOver]) un-done lessons planned for a day gone by. Done
     * ones count only when ticked that day ([doneDay] of their [StudyLesson.doneAtMs]). Course order.
     */
    fun onDay(lessons: List<StudyLesson>, courses: List<StudyCourse>, day: Long, rollOver: Boolean, doneDay: (Long) -> Long): List<StudyLesson> {
        val live = courses.map { it.id }.toSet()
        val order = courses.mapIndexed { i, c -> c.id to i }.toMap()
        return lessons.filter { l ->
            val p = l.plannedDay ?: return@filter false
            l.courseId in live && when {
                l.done -> p <= day && l.doneAtMs?.let(doneDay) == day && (p == day || rollOver)
                else -> p == day || (rollOver && p < day)
            }
        }.sortedWith(compareBy<StudyLesson> { order[it.courseId] ?: 0 }.thenBy { it.pos })
    }

    /**
     * Today's line: "Study today · 3 lessons · about 1 h 30 · next: Pivot tables" (lit when one rolled over: "· 1 from
     * Mon"); "Study today · 1 of 3 done · next: Pivot tables"; "Study today · all 3 done"; null with none planned.
     */
    fun todayLine(on: List<StudyLesson>, today: Long): StudyDayLine? {
        if (on.isEmpty()) return null
        val left = on.filter { !it.done }
        val done = on.size - left.size
        val late = left.filter { (it.plannedDay ?: today) < today }
        val mins = left.mapNotNull { it.mins }.sum()
        val bits = buildList {
            if (left.isEmpty()) add(if (on.size == 1) "done" else "all ${on.size} done")
            else {
                add(if (done > 0) "$done of ${on.size} done" else plural(left.size, "lesson"))
                if (mins > 0) add(aboutLabel(mins))
                add("next: ${left.first().title}")
                if (late.isNotEmpty()) {
                    val from = late.minOf { it.plannedDay ?: today }
                    add("${late.size} from ${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(from) - 1]}")
                }
            }
        }
        val text = "Study today · " + bits.joinToString(" · ")
        return StudyDayLine(text, text.replace(" · ", ", "), left.size, done, lit = late.isNotEmpty())
    }

    /** The Tomorrow sheet's line: "Study · 2 lessons · about 1 h · Pivot tables, Dashboards"; null with none planned. */
    fun tomorrowLine(on: List<StudyLesson>): StudyDayLine? {
        val left = on.filter { !it.done }
        if (left.isEmpty()) return null
        val mins = left.mapNotNull { it.mins }.sum()
        val names = left.take(2).map { it.title } + listOfNotNull((left.size - 2).takeIf { it > 0 }?.let { "+ $it more" })
        val text = listOfNotNull("Study", plural(left.size, "lesson"), aboutLabel(mins).takeIf { mins > 0 }, names.joinToString(", ")).joinToString(" · ")
        return StudyDayLine(text, text.replace(" · ", ", "), left.size, 0, lit = false)
    }

    /**
     * The Week board's line under a day's events: "Study · 2 lessons · about 1 h" ("· 1 from Mon", lit, on today when
     * one rolled over); "Study · 1 of 3 done · about 30 min"; "Study · all 3 done"; before today only what was ticked
     * that day, "Study · 2 done". [on] is [onDay] for that day (rolled over for today). Null with nothing to show.
     */
    fun boardLine(on: List<StudyLesson>, day: Long, today: Long): StudyDayLine? {
        val shown = if (day < today) on.filter { it.done } else on
        if (shown.isEmpty()) return null
        val left = shown.filter { !it.done }
        val done = shown.size - left.size
        val mins = left.mapNotNull { it.mins }.sum()
        val late = if (day == today) left.filter { (it.plannedDay ?: today) < today } else emptyList()
        val bits = buildList {
            when {
                day < today -> add("$done done")
                left.isEmpty() -> add(if (shown.size == 1) "done" else "all ${shown.size} done")
                done > 0 -> add("$done of ${shown.size} done")
                else -> add(plural(left.size, "lesson"))
            }
            if (left.isNotEmpty() && mins > 0) add(aboutLabel(mins))
            if (late.isNotEmpty()) {
                val from = late.minOf { it.plannedDay ?: today }
                add("${late.size} from ${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(from) - 1]}")
            }
        }
        val text = "Study · " + bits.joinToString(" · ")
        val names = (left.ifEmpty { shown }).take(3).joinToString(", ") { it.title }
        val spoken = text.replace(" · ", ", ") + ": " + names
        return StudyDayLine(text, spoken, left.size, done, lit = late.isNotEmpty(), courseId = (left.firstOrNull() ?: shown.first()).courseId)
    }

    /**
     * The Week board with each day's study line ([boardLine], Meka 2026-10-26 23:21: planned lessons under their day);
     * the day's screen-reader label says it too. [doneDay] turns a tick's time into its day.
     */
    fun onBoard(board: WeekBoard, lessons: List<StudyLesson>, courses: List<StudyCourse>, doneDay: (Long) -> Long): WeekBoard {
        if (lessons.none { it.plannedDay != null }) return board
        val today = board.todayEpochDay
        return board.copy(weeks = board.weeks.map { w ->
            w.copy(days = w.days.map { d ->
                val line = boardLine(onDay(lessons, courses, d.epochDay, rollOver = d.epochDay == today, doneDay = doneDay), d.epochDay, today)
                if (line == null) d else d.copy(study = line, accessibilityLabel = d.accessibilityLabel + ", " + line.spoken)
            })
        })
    }

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

    fun deleted(): List<StudyDeletedLesson> =
        StudyRules.deletedLessons(replica.entities(EntityTypes.STUDY_LESSON, includeDeleted = true).associate { it.ref.entityId to it.fields })

    fun view(today: Long): StudyView = StudyRules.view(courses(), lessons(), today, deleted(), nowMs())

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

    /**
     * Adds the lessons [text] lists to the course ([StudyRules.readOutline]), after its last lesson; with [into] (a
     * module's "+ Add lesson") they go at the end of that module unless the text names another.
     */
    fun addLessons(courseId: String, text: String, into: String? = null): StudyAdded {
        if (!exists(courseId)) return StudyAdded(StudyRules.GONE, 0, refused = true)
        val mine = lessons().filter { it.courseId == courseId }
        val last = mine.maxByOrNull { it.pos }
        val entries = StudyRules.readOutline(text, into?.takeIf { it.isNotBlank() } ?: last?.module)
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

    /** Slice 2b rest: turns "Nudge me 10 minutes before" on or off; false when it already was. */
    fun setNudge(courseId: String, on: Boolean): Boolean {
        val c = courses().firstOrNull { it.id == courseId } ?: return false
        if (c.nudge == on) return false
        replica.commitLocal(EntityTypes.STUDY_COURSE, courseId, mapOf(StudyCourseFields.NUDGE to on.fv()))
        return true
    }

    /** Slice 2b rest: turns "Sunday check-in" on or off; false when it already was. */
    fun setCheckin(courseId: String, on: Boolean): Boolean {
        val c = courses().firstOrNull { it.id == courseId } ?: return false
        if (c.checkin == on) return false
        replica.commitLocal(EntityTypes.STUDY_COURSE, courseId, mapOf(StudyCourseFields.CHECKIN to on.fv()))
        return true
    }

    /** Slice 2b rest: Looks good on the check-in for [sunday]: each of [courseIds] keeps it as seen; false when none changed. */
    fun seeCheckin(sunday: Long, courseIds: List<String>): Boolean {
        val mine = courses().filter { it.id in courseIds && it.checkinSeen != sunday }
        for (c in mine) replica.commitLocal(EntityTypes.STUDY_COURSE, c.id, mapOf(StudyCourseFields.CHECKIN_SEEN to sunday.fv()))
        return mine.isNotEmpty()
    }

    /** Plan: picks or drops a time he can study ([StudyRules.SLOTS] key). */
    fun setSlot(courseId: String, key: String, on: Boolean): Boolean {
        val c = courses().firstOrNull { it.id == courseId } ?: return false
        if (StudyRules.SLOTS.none { it.key == key } || (key in c.slots) == on) return false
        val next = if (on) c.slots + key else c.slots - key
        replica.commitLocal(EntityTypes.STUDY_COURSE, courseId, mapOf(StudyCourseFields.SLOTS to StudyRules.slotsText(next).fv()))
        return true
    }

    /**
     * Plans a lesson for [day] (an epoch day), or takes it off the plan with null; false when it isn't there or is
     * already so. A lesson ticked done keeps its day (it counts as done that day).
     */
    fun planLesson(id: String, day: Long?): Boolean {
        val l = lessons().firstOrNull { it.id == id } ?: return false
        if (l.plannedDay == day) return false
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, mapOf(StudyLessonFields.PLANNED_DAY to (day?.fv() ?: FieldValue.Null)))
        return true
    }

    /**
     * Slice 2b part 2, Done on a session: ticks [ids] not yet done; each is planned for [planFor] (today) when that's
     * given (a session done ahead of its day). The token Undo takes ([undoFinish]) holds each lesson's stored day before;
     * null when nothing was ticked.
     */
    fun finishSession(ids: List<String>, planFor: Long?): String? {
        val byId = lessons().associateBy { it.id }
        val parts = mutableListOf<String>()
        for (id in ids.distinct()) {
            val l = byId[id] ?: continue
            if (l.done || !tick(id, true)) continue
            if (planFor != null && l.plannedDay != planFor) planLesson(id, planFor)
            parts += "$id=${l.plannedDay ?: ""}"
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(";")
    }

    /** Undoes [finishSession]: unticks its lessons and puts back the day each was planned for; false when nothing changed. */
    fun undoFinish(token: String): Boolean {
        var changed = false
        for (part in token.split(';')) {
            val id = part.substringBefore('=')
            if (id.isEmpty()) continue
            val day = part.substringAfter('=', "").toLongOrNull()
            if (tick(id, false)) changed = true
            if (planLesson(id, day)) changed = true
        }
        return changed
    }

    /** Plans each of [ids] for [day] (null: off the plan); how many changed. Lessons not there are skipped. */
    fun planLessons(ids: List<String>, day: Long?): Int = ids.distinct().count { planLesson(it, day) }

    /** Removes one lesson, with Undo's token (its id); null when it isn't there. It stays under Recently deleted. */
    fun removeLesson(id: String): StudyRemoved? {
        val l = lessons().firstOrNull { it.id == id } ?: return null
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, mapOf(StudyLessonFields.DELETED to true.fv(), StudyLessonFields.DELETED_AT to nowMs().fv()))
        return StudyRemoved("Removed ${l.title}", LESSON + id)
    }

    /** Study fixes: renames a lesson; false when it isn't there, the name is empty or already so. */
    fun renameLesson(id: String, text: String): Boolean {
        val l = lessons().firstOrNull { it.id == id } ?: return false
        val name = StudyRules.lessonName(text) ?: return false
        if (name == l.title) return false
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, mapOf(StudyLessonFields.TITLE to name.fv()))
        return true
    }

    /** A place at the end of [module] in [mine] (between its last lesson and the next one), else after everything. */
    private fun endOf(mine: List<StudyLesson>, module: String, except: String? = null): Long {
        val others = mine.filter { it.id != except }
        val last = others.maxOfOrNull { it.pos } ?: 0L
        val inModule = others.filter { it.module.equals(module, ignoreCase = true) }.maxOfOrNull { it.pos } ?: return last + GAP
        val next = others.map { it.pos }.filter { it > inModule }.minOrNull() ?: return inModule + GAP
        return if (next - inModule >= 2) inModule + (next - inModule) / 2 else last + GAP
    }

    /** Study fixes: moves a lesson to the end of another module of its course; false when nothing changed. */
    fun moveLesson(id: String, module: String): Boolean {
        val l = lessons().firstOrNull { it.id == id } ?: return false
        val target = StudyRules.moduleName(module) ?: return false
        if (l.module.equals(target, ignoreCase = true)) return false
        val mine = lessons().filter { it.courseId == l.courseId }
        val name = mine.firstOrNull { it.module.equals(target, ignoreCase = true) }?.module ?: target
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, mapOf(
            StudyLessonFields.MODULE to name.fv(),
            StudyLessonFields.POS to endOf(mine, name, except = id).fv(),
        ))
        return true
    }

    /**
     * Study fixes (2) rest, drag to reorder (2026-10-28): puts a lesson at [index] among the other lessons of its module
     * (0 = first), the module keeping its place; false when it isn't there or is already there.
     */
    fun placeLesson(id: String, index: Int): Boolean {
        val l = lessons().firstOrNull { it.id == id } ?: return false
        val groups = StudyRules.courseOrder(lessons().filter { it.courseId == l.courseId })
        val g = groups.indexOfFirst { gr -> gr.any { it.id == id } }
        val from = groups[g].indexOfFirst { it.id == id }
        val others = groups[g].filter { it.id != id }
        val to = index.coerceIn(0, others.size)
        if (to == from) return false
        val next = groups.toMutableList().also { it[g] = others.toMutableList().apply { add(to, l) } }
        writeOrder(next.flatten(), setOf(id))
        return true
    }

    /**
     * Drag to reorder: puts the module [module] of the course at [index] among its other modules (0 = first), its
     * lessons keeping their order; false when it isn't there or is already there.
     */
    fun placeModule(courseId: String, module: String, index: Int): Boolean {
        val groups = StudyRules.courseOrder(lessons().filter { it.courseId == courseId })
        val from = groups.indexOfFirst { gr -> gr.first().module.equals(module, ignoreCase = true) }
        if (from < 0) return false
        val others = groups.filterIndexed { i, _ -> i != from }
        val to = index.coerceIn(0, others.size)
        if (to == from) return false
        val next = others.toMutableList().apply { add(to, groups[from]) }
        writeOrder(next.flatten(), groups[from].map { it.id }.toSet())
        return true
    }

    /**
     * Writes [order] (a course's lessons as they should show) as positions: the [moved] lessons alone when they fit
     * between their new neighbours, else the whole course again [GAP] apart (only lessons whose place changed).
     */
    private fun writeOrder(order: List<StudyLesson>, moved: Set<String>) {
        val pos = StudyRules.placeBetween(order.map { if (it.id in moved) null else it.pos }) ?: order.indices.map { (it + 1) * GAP }
        order.forEachIndexed { i, l ->
            if (pos[i] != l.pos) replica.commitLocal(EntityTypes.STUDY_LESSON, l.id, mapOf(StudyLessonFields.POS to pos[i].fv()))
        }
    }

    /** Study fixes: a new module named [name] with its first lessons from [text], at the course's end. */
    fun addModule(courseId: String, name: String, text: String): StudyAdded {
        if (!exists(courseId)) return StudyAdded(StudyRules.GONE, 0, refused = true)
        val module = StudyRules.moduleName(name) ?: return StudyAdded(StudyRules.MODULE_NOT_READ, 0, refused = true)
        if (StudyRules.readOutline(text, module).isEmpty()) return StudyAdded(StudyRules.MODULE_NEEDS_LESSON, 0, refused = true)
        return addLessons(courseId, text, into = module)
    }

    /** Study fixes: renames a module (every lesson in it); false when nothing changed. */
    fun renameModule(courseId: String, module: String, text: String): Boolean {
        val name = StudyRules.moduleName(text) ?: return false
        val inIt = lessons().filter { it.courseId == courseId && it.module.equals(module, ignoreCase = true) }
        if (inIt.isEmpty() || inIt.all { it.module == name }) return false
        for (l in inIt) replica.commitLocal(EntityTypes.STUDY_LESSON, l.id, mapOf(StudyLessonFields.MODULE to name.fv()))
        return true
    }

    /** Study fixes: deletes a module's lessons (after the app's confirm), with one Undo for them all. */
    fun removeModule(courseId: String, module: String): StudyRemoved? {
        val inIt = lessons().filter { it.courseId == courseId && it.module.equals(module, ignoreCase = true) }
        if (inIt.isEmpty()) return null
        val now = nowMs()
        for (l in inIt) replica.commitLocal(EntityTypes.STUDY_LESSON, l.id, mapOf(StudyLessonFields.DELETED to true.fv(), StudyLessonFields.DELETED_AT to now.fv()))
        return StudyRemoved("Removed ${inIt.first().module}", MODULE + inIt.joinToString(",") { it.id })
    }

    /** Study fixes: Recently deleted → Restore puts the lesson back where it was, ticked as it was. */
    fun restoreLesson(id: String): Boolean = undoRemove(LESSON + id)

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
        if (token.startsWith(MODULE)) {
            return token.removePrefix(MODULE).split(',').filter { it.isNotBlank() }.map { restoreCopy(it) }.any { it }
        }
        return restoreCopy(token.removePrefix(LESSON))
    }

    private fun restoreCopy(id: String): Boolean {
        val f = replica.entity(EntityTypes.STUDY_LESSON, id)?.fields ?: return false
        if (f[StudyLessonFields.DELETED]?.boolOrNull != true || f[StudyLessonFields.RESTORED]?.boolOrNull == true) return false
        replica.commitLocal(EntityTypes.STUDY_LESSON, id, mapOf(StudyLessonFields.RESTORED to true.fv()))
        replica.commitLocal(EntityTypes.STUDY_LESSON, newId(), f - StudyLessonFields.DELETED - StudyLessonFields.RESTORED - StudyLessonFields.DELETED_AT)
        return true
    }

    companion object {
        /** Lessons are placed 1024 apart, so one can go between two without moving the rest. */
        const val GAP = 1024L
        private const val COURSE = "course:"
        private const val LESSON = "lesson:"
        private const val MODULE = "module:"
    }
}
