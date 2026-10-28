package os.meka.core.domain

/**
 * Study plan, slice 2b part 1 (the board: "the Study plan's auto-schedule fills the days he hasn't planned, never
 * moving ones he set by hand"): lessons he hasn't planned are suggested for his next study sessions, in course order,
 * as many as each session's time holds.
 *
 * Nothing is written: the suggestion is worked out on each device from the same synced lessons, courses and calendar,
 * so the Fold and the Mac agree, a lesson not done simply moves on to the next session, and a lesson he plans by hand
 * (Plan for…) is his and never moves. A suggested lesson shows on the course ("Tomorrow · suggested"), on Today, in the
 * Tomorrow sheet and on the Week board like a planned one.
 *
 * Sessions come from his calendar's "Study…" events in the next [HORIZON] days (matched to a course by the words of its
 * name; an event that names none goes to the first course with lessons left), each holding its own length. With no
 * such event, from the times he picked in YOUR PLAN: Train mornings on office days (three a week at most), Thursday
 * after work, Saturday morning, Evenings on Monday and Wednesday, Nights on Tuesday and Sunday, each a session long.
 */
data class StudySession(val day: Long, val mins: Int, val courseId: String)

object StudyAutoRules {
    /** Sessions are looked for from today to this many days ahead. */
    const val HORIZON = 14
    const val SUGGESTED = "suggested"
    const val FROM_CALENDAR = "from your calendar"
    const val FROM_SLOTS = "from the times you picked"

    /** The weekdays (ISO, Monday = 1) each picked time falls on when the calendar has no study sessions. */
    private val SLOT_DAYS = mapOf(
        "thu" to setOf(4), "sat" to setOf(6), "eve" to setOf(1, 3), "night" to setOf(2, 7),
    )
    private const val TRAIN_PER_WEEK = 3

    /** Whether an event is a study session: its title starts with "Study" ("Study · Data analytics", "Study: SQL"). */
    fun isStudy(title: String): Boolean {
        val t = title.trim()
        if (!t.startsWith("study", ignoreCase = true)) return false
        return t.length == 5 || !t[5].isLetter()
    }

    private fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it != "study" }.toSet()

    /** The course an event's title names (most words in common), else null. */
    fun courseFor(title: String, courses: List<StudyCourse>): String? {
        val w = words(title)
        if (w.isEmpty()) return null
        return courses.map { it to (words(it.title) intersect w).size }.filter { it.second > 0 }
            .maxByOrNull { it.second }?.first?.id
    }

    /**
     * The study sessions from [today] for [HORIZON] days: his calendar's "Study…" events ([dayOf] turns an event's start
     * into its day) when there are any, else each course's picked times ([office] says whether a day is an office day).
     * The second value is true when they came from the calendar.
     */
    fun sessions(
        courses: List<StudyCourse>, lessons: List<StudyLesson>, events: List<CalendarEvent>, today: Long,
        dayOf: (Long) -> Long, office: (Long) -> Boolean,
    ): Pair<List<StudySession>, Boolean> {
        val left = courses.filter { c -> lessons.any { it.courseId == c.id && !it.done } }
        if (left.isEmpty()) return emptyList<StudySession>() to false
        val last = today + HORIZON - 1
        val study = events.filter { isStudy(it.title) }
            .map { it to dayOf(it.startAtMs) }.filter { it.second in today..last }
            .sortedWith(compareBy<Pair<CalendarEvent, Long>> { it.second }.thenBy { it.first.startAtMs }.thenBy { it.first.id })
        if (study.isNotEmpty()) {
            val first = left.first()
            return study.map { (e, day) ->
                val course = courseFor(e.title, left)?.let { id -> left.first { it.id == id } } ?: first
                val mins = if (e.allDay) course.sessionMins ?: StudyRules.DEFAULT_SESSION
                else ((e.endAtMs - e.startAtMs) / 60_000L).toInt().coerceIn(StudyRules.MIN_SESSION, StudyRules.MAX_SESSION)
                StudySession(day, mins, course.id)
            } to true
        }
        val out = mutableListOf<StudySession>()
        for (c in left) {
            if (c.slots.isEmpty()) continue
            val mins = c.sessionMins ?: StudyRules.DEFAULT_SESSION
            val trainsInWeek = mutableMapOf<Long, Int>()
            for (day in today..last) {
                val dow = CivilDate.isoDayOfWeek(day)
                if ("train" in c.slots && office(day)) {
                    val week = day - (dow - 1)
                    val n = trainsInWeek[week] ?: 0
                    if (n < TRAIN_PER_WEEK) { trainsInWeek[week] = n + 1; out += StudySession(day, mins, c.id) }
                }
                for ((key, days) in SLOT_DAYS) if (key in c.slots && dow in days) out += StudySession(day, mins, c.id)
            }
        }
        return out.sortedBy { it.day } to false
    }

    /**
     * [lessons] with the unplanned ones he hasn't done given a day ([StudyLesson.suggested]): each course's in course
     * order, into its [sessions] by day, as many as a session's minutes hold (a lesson that doesn't say counts as the
     * course's average, else a session; a session always takes one). Lessons he planned for a session's day use its time
     * first. A lesson he ticked today that had no day counts on today when today has a session for its course.
     */
    fun suggest(
        courses: List<StudyCourse>, lessons: List<StudyLesson>, sessions: List<StudySession>, today: Long,
        doneDay: (Long) -> Long,
    ): List<StudyLesson> {
        if (sessions.isEmpty()) return lessons
        val given = mutableMapOf<String, Long>()
        for (c in courses) {
            val mine = lessons.filter { it.courseId == c.id }
            val mineSessions = sessions.filter { it.courseId == c.id }
            if (mineSessions.isEmpty()) continue
            val known = mine.mapNotNull { it.mins }
            val avg = if (known.isEmpty()) null else (known.sum() + known.size / 2) / known.size
            fun len(l: StudyLesson) = l.mins ?: avg ?: c.sessionMins ?: StudyRules.DEFAULT_SESSION
            // Each day's time, less what he planned by hand for it (and what he ticked today with no day).
            val room = mineSessions.groupBy { it.day }.mapValues { (_, s) -> s.sumOf { it.mins } }.toMutableMap()
            val count = mineSessions.groupBy { it.day }.mapValues { 0 }.toMutableMap()
            for (l in mine) {
                val day = l.plannedDay ?: continue
                if (day !in room || (l.done && l.doneAtMs?.let(doneDay) != day)) continue
                room[day] = room.getValue(day) - len(l); count[day] = count.getValue(day) + 1
            }
            if (today in room) for (l in mine) {
                if (l.plannedDay != null || !l.done || l.doneAtMs?.let(doneDay) != today) continue
                given[l.id] = today
                room[today] = room.getValue(today) - len(l); count[today] = count.getValue(today) + 1
            }
            val queue = ArrayDeque(mine.filter { !it.done && it.plannedDay == null })
            for (day in room.keys.sorted()) {
                while (queue.isNotEmpty()) {
                    val l = queue.first()
                    val fits = len(l) <= room.getValue(day)
                    if (!fits && count.getValue(day) > 0) break
                    queue.removeFirst()
                    given[l.id] = day
                    room[day] = room.getValue(day) - len(l); count[day] = count.getValue(day) + 1
                }
                if (queue.isEmpty()) break
            }
        }
        if (given.isEmpty()) return lessons
        return lessons.map { l -> given[l.id]?.let { l.copy(plannedDay = it, suggested = true) } ?: l }
    }

    /** A suggested lesson's chip: "Tomorrow · suggested", "Thu 29 Oct · suggested". */
    fun chip(day: Long, today: Long): String = "${StudyRules.plannedLabel(day, today)} · $SUGGESTED"

    /**
     * The plan card's line for one course: "5 lessons suggested for your next 3 sessions · Thu, Sat, Mon · from your
     * calendar"; null when nothing is suggested.
     */
    fun line(courseId: String, lessons: List<StudyLesson>, fromCalendar: Boolean): String? {
        val mine = lessons.filter { it.courseId == courseId && it.suggested && !it.done }
        if (mine.isEmpty()) return null
        val days = mine.mapNotNull { it.plannedDay }.distinct().sorted()
        val names = days.take(3).map { LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(it) - 1] } + listOfNotNull("…".takeIf { days.size > 3 })
        val n = mine.size
        val what = if (n == 1) "1 lesson suggested" else "$n lessons suggested"
        val sessions = if (days.size == 1) "your next session" else "your next ${days.size} sessions"
        return "$what for $sessions · ${names.joinToString(", ")} · ${if (fromCalendar) FROM_CALENDAR else FROM_SLOTS}"
    }
}
