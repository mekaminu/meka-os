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
