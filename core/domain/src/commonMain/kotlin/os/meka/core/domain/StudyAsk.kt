package os.meka.core.domain

/**
 * Obi sees everything, slice 1 (Meka 2026-10-28 19:32: "I asked Obi about my study and he said he had no view of
 * that"): his Study plan as Ask's `study` lines and as Live's look-up answers ([ObiSees.STUDY]).
 *
 * Asked about study (a course, a lesson, a module, a session, or a course's own name, [isAbout]): every course with its
 * progress and plan, the next lessons not done, the lessons planned for each day of the coming week and today's
 * sessions. Otherwise one short summary line, so Obi knows Study exists and what's in it. Course and lesson titles are
 * his own words (typed, pasted or read from his own screenshot), never other people's; course content never goes.
 */
object StudyAskRules {
    /** At most this many courses are described in full. */
    const val ASK_COURSES = 6
    /** The next lessons not done, per course. */
    const val ASK_NEXT = 3
    /** Days of planned lessons from today (today, tomorrow, then the rest of the week). */
    const val ASK_DAYS = 7
    /** Lesson titles named on one day's line. */
    const val ASK_DAY_TITLES = 4
    /** The most `study` lines Ask sends (a head, two per course, a week of days, the day's sessions). */
    const val ASK_MAX = 1 + 2 * ASK_COURSES + ASK_DAYS + 3

    private val WORDS = Regex("""\b(stud(y|ying|ies)|course|courses|lesson|lessons|module|modules|revis(e|ion|ing)|learning|homework|coursework|syllabus|curriculum)\b""")

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /** Whether [question] is about his study: the words above, or a course's own title (or its first long word). */
    fun isAbout(question: String, courseTitles: List<String>): Boolean {
        val q = question.lowercase()
        if (WORDS.containsMatchIn(q)) return true
        return courseTitles.any { t ->
            val title = tidy(t).lowercase()
            (title.length >= 4 && title in q) ||
                title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 5 }.any { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(q) }
        }
    }

    /**
     * Ask's `study` lines. [full]: the whole picture ([isAbout] was true, or Live looked Study up); otherwise one line.
     * [lessons] are the lessons with today's suggestions ([StudyAutoRules.suggest]); [sessions] the study sessions found
     * ([StudyAutoRules.sessions]); [doneDay] turns a tick's time into its day; [clock] a minute of the day into "19:30".
     * Nothing at all with no courses.
     */
    fun askLines(
        courses: List<StudyCourse>, lessons: List<StudyLesson>, sessions: List<StudySession>, today: Long, full: Boolean,
        doneDay: (Long) -> Long, clock: (Int) -> String,
    ): List<String> {
        if (courses.isEmpty()) return emptyList()
        val views = courses.map { StudyRules.view(it, lessons, today) }
        val done = views.sumOf { it.done }
        val total = views.sumOf { it.total }
        val todayOn = StudyRules.onDay(lessons, courses, today, rollOver = true, doneDay = doneDay)
        val todayLeft = todayOn.count { !it.done }
        if (!full) {
            val each = views.take(ASK_COURSES).joinToString(" · ") { "“${tidy(it.title)}” ${it.done} of ${it.total}" }
            val planned = if (todayLeft > 0) " · ${plural(todayLeft, "lesson")} planned today" else ""
            return listOf("Study · ${plural(courses.size, "course")} · $each$planned (ask about study for the lessons and the plan)")
        }
        val out = mutableListOf<String>()
        out += "Study · ${plural(courses.size, "course")} · $done of $total lessons done" +
            if (courses.size > ASK_COURSES) " · the first $ASK_COURSES below" else ""
        views.take(ASK_COURSES).forEach { v ->
            val status = when {
                v.total == 0 -> "no lessons yet"
                v.allDone -> "all done"
                else -> v.plan.status ?: "no finish date set"
            }
            out += listOf("Course “${tidy(v.title)}”", if (v.total == 0) null else "${v.done} of ${plural(v.total, "lesson")}", if (v.total == 0) null else "${v.pct}%", status, v.plan.perWeek)
                .filterNotNull().joinToString(" · ")
            val next = lessons.filter { it.courseId == v.id && !it.done }
                .let { ls -> StudyRules.courseOrder(ls).flatten() }
                .take(ASK_NEXT)
            if (next.isNotEmpty()) {
                out += "Course “${tidy(v.title)}” next lessons: " + next.joinToString(" · ") { l ->
                    listOfNotNull("“${tidy(l.title)}”", tidy(l.module).takeIf { it.isNotEmpty() && it != StudyRules.DEFAULT_MODULE }, l.mins?.let { StudyRules.minsLabel(it) },
                        l.plannedDay?.let { d -> "planned " + StudyRules.plannedLabel(d, today).lowercase() + if (l.suggested) " (suggested)" else "" })
                        .joinToString(", ")
                }
            }
        }
        val titles = courses.associate { it.id to tidy(it.title) }
        (0 until ASK_DAYS).forEach { i ->
            val day = today + i
            val on = if (i == 0) todayOn else StudyRules.onDay(lessons, courses, day, rollOver = false, doneDay = doneDay)
            if (on.isEmpty()) return@forEach
            val left = on.filter { !it.done }
            val name = when (i) { 0 -> "today"; 1 -> "tomorrow"; else -> CivilDate.shortLabel(day) }
            val counts = when {
                left.isEmpty() -> if (on.size == 1) "done" else "all ${on.size} done"
                left.size < on.size -> "${on.size - left.size} of ${on.size} done"
                else -> plural(left.size, "lesson")
            }
            val shown = left.ifEmpty { on }
            val named = shown.take(ASK_DAY_TITLES).joinToString(", ") { l ->
                "“${tidy(l.title)}” (${titles[l.courseId].orEmpty()}${if (l.suggested) ", suggested" else ""}${if (i == 0 && !l.done && (l.plannedDay ?: today) < today) ", from ${CivilDate.shortLabel(l.plannedDay!!)}" else ""})"
            } + if (shown.size > ASK_DAY_TITLES) ", + ${shown.size - ASK_DAY_TITLES} more" else ""
            out += "Study planned $name · $counts · $named"
        }
        sessions.filter { it.day == today }.sortedBy { it.startMin ?: -1 }.forEach { s ->
            val time = s.startMin?.let { m -> (if (m >= 17 * 60) "tonight " else "today ") + clock(m) } ?: "today"
            out += listOfNotNull("Study session $time", StudyRules.minsLabel(s.mins), titles[s.courseId]?.let { "“$it”" }, s.label?.let { tidy(it) }).joinToString(" · ")
        }
        return out.take(ASK_MAX)
    }
}

/**
 * Obi sees everything, slice 2: Ask's (and the orb's) Study cards. `study_done` ticks one lesson off ("I've done Pivot
 * tables"), `plan_lesson` plans a lesson, or a module's lessons not done, for a day ("plan module 4 for tomorrow").
 * The title is a lesson's (or a module's) as on the study lines, optionally after its course and a colon ("Excel:
 * Pivot tables"). Only one thing may fit: two lessons of the same name in different courses, with no course named,
 * make no card rather than a guess. His own data; he taps to confirm, and Undo puts it back.
 */
object StudyAskCards {
    /** A module's lessons planned by one card at most (the rest stay as they are). */
    const val MAX_PLAN = 20
    const val TICK_BUTTON = "Done"
    const val PLAN_BUTTON = "Plan"
    const val GONE = "That lesson isn't in Study any more"
    const val ALREADY_DONE = "That lesson is already ticked"
    const val ALREADY_PLANNED = "Those lessons are already planned for that day"

    /** "module 4", "Module 4: Reporting", "4 · Reporting": a module asked for by its number (and maybe its name). */
    private val NUMBERED = Regex("""^(?:(?:module|section|week|unit|part|chapter)\s*)?0*(\d{1,3})\b\s*[:·\-–]?\s*(.*)$""")
    private val SHOWN_NUMBER = Regex("""^(\d{1,3}) · (.+)$""")

    /** Lowercase, quotes and stray punctuation gone, spaces collapsed: how titles are compared. */
    fun key(s: String): String =
        s.lowercase().replace(Regex("""[“”‘’"'`.,!?()\[\]]"""), " ").replace(Regex("""\s+"""), " ").trim()

    private class Scope(val courses: List<StudyCourse>, val query: String)

    /** The courses [title] may mean and what's left to match: "Excel: Pivot tables" → Excel's lessons, "pivot tables". */
    private fun scopes(title: String, courses: List<StudyCourse>): List<Scope> {
        val all = Scope(courses, key(title))
        val colon = title.indexOf(':')
        if (colon <= 0) return listOf(all)
        val head = key(title.substring(0, colon))
        val rest = key(title.substring(colon + 1))
        if (head.isEmpty() || rest.isEmpty()) return listOf(all)
        val named = courses.filter { key(it.title) == head }.ifEmpty { courses.filter { head.length >= 3 && head in key(it.title) } }
        // A course named first narrows it; the whole title is still tried (a lesson's own name can hold a colon).
        return if (named.isEmpty()) listOf(all) else listOf(Scope(named, rest), all)
    }

    private fun <T> one(xs: List<T>): T? = xs.singleOrNull()

    /** The one lesson [title] names among [pool]: the same name, else the one whose name holds it (or is held by it). */
    private fun lessonIn(pool: List<StudyLesson>, q: String, loose: Boolean = true): StudyLesson? {
        if (q.isEmpty()) return null
        val exact = pool.filter { key(it.title) == q }
        if (exact.isNotEmpty()) return one(exact)
        if (!loose || q.length < 3) return null
        return one(pool.filter { val k = key(it.title); q in k || (k.length >= 4 && k in q) })
    }

    /** Ticks [title]'s lesson off: only one not done yet. */
    fun tick(courses: List<StudyCourse>, lessons: List<StudyLesson>, title: String?): AskProposal.StudyTick? {
        if (title.isNullOrBlank() || title.length > AskRules.MAX_TITLE * 2) return null
        for (s in scopes(title, courses)) {
            val ids = s.courses.map { it.id }.toSet()
            val pool = lessons.filter { it.courseId in ids && !it.done }
            val l = lessonIn(pool, s.query) ?: continue
            val course = courses.first { it.id == l.courseId }
            return AskProposal.StudyTick(l.id, l.title.replace(Regex("""\s+"""), " ").trim(), course.title.trim())
        }
        return null
    }

    /**
     * Plans [title] for [day]: one lesson not done (by its name), else one module's lessons not done (by the module's
     * name, or "module 4" for the module numbered 4). Null when nothing fits, the day can't be planned
     * ([StudyRules.plannable]) or every one of them is already planned for that day.
     */
    fun plan(courses: List<StudyCourse>, lessons: List<StudyLesson>, title: String?, day: Long?, today: Long): AskProposal.StudyPlan? {
        if (title.isNullOrBlank() || title.length > AskRules.MAX_TITLE * 2 || day == null || !StudyRules.plannable(day, today)) return null
        for (s in scopes(title, courses)) {
            val ids = s.courses.map { it.id }.toSet()
            val pool = lessons.filter { it.courseId in ids && !it.done }
            val course = { id: String -> courses.first { it.id == id }.title.trim() }
            fun lessonCard(l: StudyLesson): AskProposal.StudyPlan? =
                if (l.plannedDay == day) null
                else AskProposal.StudyPlan(listOf(l.id), "\u201C${l.title.replace(Regex("""\s+"""), " ").trim()}\u201D", course(l.courseId), day)
            fun moduleCard(m: List<StudyLesson>): AskProposal.StudyPlan? {
                val inOrder = StudyRules.courseOrder(m).flatten()
                val toPlan = inOrder.filter { it.plannedDay != day }.take(MAX_PLAN)
                if (toPlan.isEmpty()) return null
                val name = moduleLabel(inOrder.first().module)
                val what = name + " · " + if (toPlan.size == 1) "1 lesson" else "${toPlan.size} lessons"
                return AskProposal.StudyPlan(toPlan.map { it.id }, what, course(inOrder.first().courseId), day)
            }
            val modules = pool.groupBy { it.courseId to it.module.lowercase() }
            // A lesson's own name first, then a module by its name or number, then the looser matches.
            lessonIn(pool, s.query, loose = false)?.let { return lessonCard(it) }
            moduleIn(modules, s.query, loose = false)?.let { return moduleCard(it) }
            lessonIn(pool, s.query, loose = true)?.let { return lessonCard(it) }
            moduleIn(modules, s.query, loose = true)?.let { return moduleCard(it) }
        }
        return null
    }

    /** The one module [q] names: the same name, "module 4" for the one numbered 4, else the one whose name holds it. */
    private fun moduleIn(modules: Map<Pair<String, String>, List<StudyLesson>>, q: String, loose: Boolean): List<StudyLesson>? {
        if (q.isEmpty()) return null
        val named = modules.entries.map { key(it.value.first().module) to it.value }
        named.filter { it.first == q }.let { if (it.isNotEmpty()) return one(it)?.second }
        NUMBERED.matchEntire(q)?.let { m ->
            val n = m.groupValues[1].toInt()
            val rest = m.groupValues[2].trim()
            val hits = named.filter { (k, _) -> NUMBERED.matchEntire(k)?.groupValues?.get(1)?.toIntOrNull() == n }
                .let { h -> if (h.size > 1 && rest.isNotEmpty()) h.filter { rest in it.first } else h }
            if (hits.isNotEmpty()) return one(hits)?.second
        }
        if (!loose || q.length < 3) return null
        return one(named.filter { q in it.first })?.second
    }

    /** How a module is named on a card: "4 · Reporting" (as Study reads "Module 4: Reporting") → "Module 4, Reporting". */
    fun moduleLabel(module: String): String {
        val m = module.replace(Regex("""\s+"""), " ").trim()
        return SHOWN_NUMBER.matchEntire(m)?.let { "Module ${it.groupValues[1]}, ${it.groupValues[2]}" } ?: m
    }

    fun tickCard(p: AskProposal.StudyTick): AskCard = AskCard(p, "Tick off “${p.title}” · ${p.course}", TICK_BUTTON)

    fun planCard(p: AskProposal.StudyPlan, today: Long): AskCard =
        AskCard(p, "Plan ${p.what} for ${dayWord(p.day, today)} · ${p.course}", PLAN_BUTTON)

    fun tickDone(p: AskProposal.StudyTick): String = "Ticked off “${p.title}” in Study"

    fun planDone(p: AskProposal.StudyPlan, today: Long): String = "Planned ${p.what} for ${dayWord(p.day, today)}"

    /** "today", "tomorrow", "Thu 29 Oct". */
    fun dayWord(day: Long, today: Long): String =
        StudyRules.plannedLabel(day, today).let { if (it == StudyRules.TODAY || it == StudyRules.TOMORROW) it.lowercase() else it }
}
