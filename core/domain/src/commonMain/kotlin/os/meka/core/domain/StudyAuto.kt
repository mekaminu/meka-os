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
data class StudySession(
    val day: Long, val mins: Int, val courseId: String,
    /** Slice 2b part 2: when it starts (minute of the day; null for an all-day event) and what it is ("Train morning", the event's title). */
    val startMin: Int? = null,
    val label: String? = null,
)

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
    /** Slice 2b part 2: when each picked time starts (the mockup's times) and what THIS WEEK calls it. */
    private val SLOT_START = mapOf("train" to 7 * 60 + 45, "thu" to 16 * 60, "sat" to 9 * 60, "eve" to 20 * 60 + 30, "night" to 22 * 60)
    private val SLOT_LABEL = mapOf(
        "train" to "Train morning", "thu" to "After work", "sat" to "Saturday morning", "eve" to "Evening", "night" to "Night",
    )

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
        dayOf: (Long) -> Long, office: (Long) -> Boolean, minuteOf: (Long) -> Int? = { null },
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
                StudySession(day, mins, course.id, if (e.allDay) null else minuteOf(e.startAtMs), e.title.trim().take(60).ifEmpty { null })
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
                    if (n < TRAIN_PER_WEEK) { trainsInWeek[week] = n + 1; out += slotSession(day, mins, c.id, "train") }
                }
                for ((key, days) in SLOT_DAYS) if (key in c.slots && dow in days) out += slotSession(day, mins, c.id, key)
            }
        }
        return out.sortedWith(compareBy<StudySession> { it.day }.thenBy { it.startMin ?: 0 }) to false
    }

    private fun slotSession(day: Long, mins: Int, courseId: String, key: String) =
        StudySession(day, mins, courseId, SLOT_START[key], SLOT_LABEL[key])

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

/** Slice 2b part 2: where a THIS WEEK row is. */
enum class StudyWeekState { START, ON, DONE }

/**
 * One row of a course's THIS WEEK card (mockup): "Today" · "07:45", "Pivot tables + 1 more · 1 h 15", "Train morning",
 * and its button: Start, then Done once started (ticks its lessons), and a quiet Done once they're all ticked. [key]
 * names the day's sessions of that course ([StudyWeekRules.key]); [lessonIds] are what Done ticks. A day with nothing
 * left to put in it is a catch-up ([catchUp], no button).
 */
data class StudyWeekRow(
    val key: String,
    val day: Long,
    val dayLabel: String,
    val time: String?,
    val lessons: String,
    /** Where it is: "Train morning", the event's title, or [StudyWeekRules.CATCH_UP_LINE]. */
    val place: String,
    val lessonIds: List<String>,
    val state: StudyWeekState,
    val button: String?,
    /** "Started 14:05" while it's on. */
    val note: String?,
    val catchUp: Boolean,
    val spoken: String,
)

/**
 * Study plan, slice 2b part 2 (the board: "Start/Done on a session ticking its lessons"; mockup's THIS WEEK): a course's
 * study sessions from today for a week, each day's with the lessons planned or suggested for it. Start marks it on
 * (kept on that device only: a session lasts under an hour); Done ticks its lessons not yet done, and one done ahead of
 * its day is planned for today, where it was done, so the later session takes the next lessons. Nothing else is stored.
 */
object StudyWeekRules {
    const val LABEL = "This week"
    const val START = "Start"
    const val DONE = "Done"
    const val CATCH_UP = "Catch-up"
    const val CATCH_UP_LINE = "Nothing left to put in it"
    const val DAYS = 7

    fun key(courseId: String, day: Long): String = "$courseId:$day"

    /** The day a [key] names, or null. */
    fun dayOf(key: String): Long? = key.substringAfterLast(':', "").toLongOrNull()

    /** The course a [key] names, or null. */
    fun courseOf(key: String): String? = key.substringBeforeLast(':', "").ifEmpty { null }

    /**
     * The rows for [courseId]: its [sessions] from [today] for [DAYS] days, one row a day (the earliest start, the
     * minutes together), with the lessons ([lessons] as [StudyAutoRules.suggest] gave them) planned for that day; done
     * ones only when ticked that day ([doneDay]). [started] holds when each key was started ([timeOf] says it).
     */
    fun rows(
        courseId: String, lessons: List<StudyLesson>, sessions: List<StudySession>, today: Long,
        started: Map<String, Long>, doneDay: (Long) -> Long, timeOf: (Long) -> String,
    ): List<StudyWeekRow> {
        val mine = sessions.filter { it.courseId == courseId && it.day in today until today + DAYS }
        if (mine.isEmpty()) return emptyList()
        val course = lessons.filter { it.courseId == courseId }
        val known = course.mapNotNull { it.mins }
        val avg = if (known.isEmpty()) null else (known.sum() + known.size / 2) / known.size
        val ordered = StudyRules.courseOrder(course).flatten()
        return mine.groupBy { it.day }.toSortedMap().map { (day, s) ->
            val on = ordered.filter { l -> l.plannedDay == day && (!l.done || l.doneAtMs?.let(doneDay) == day) }
            val k = key(courseId, day)
            val allDone = on.isNotEmpty() && on.all { it.done }
            val state = when {
                allDone -> StudyWeekState.DONE
                k in started -> StudyWeekState.ON
                else -> StudyWeekState.START
            }
            val dayLabel = if (day == today) StudyRules.TODAY else LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]
            val time = s.mapNotNull { it.startMin }.minOrNull()?.let { LocalClock.formatMinute(it) }
            val sessionMins = s.sumOf { it.mins }
            val mins = if (on.isEmpty()) sessionMins else on.sumOf { it.mins ?: avg ?: sessionMins }
            val what = when (on.size) {
                0 -> CATCH_UP
                1 -> on.first().title
                else -> "${on.first().title} + ${on.size - 1} more"
            }
            val lessonsLine = "$what · ${StudyRules.minsLabel(mins)}"
            val place = if (on.isEmpty()) CATCH_UP_LINE else s.mapNotNull { it.label }.distinct().joinToString(" · ").ifEmpty { "Study" }
            val note = started[k]?.takeIf { state == StudyWeekState.ON }?.let { "Started ${timeOf(it)}" }
            val button = when {
                on.isEmpty() -> null
                state == StudyWeekState.START -> START
                else -> DONE
            }
            val spoken = listOfNotNull(
                CivilDate.longLabel(day).takeIf { day != today } ?: "Today", time, lessonsLine.replace(" · ", ", "), place.takeIf { on.isNotEmpty() },
                note, if (state == StudyWeekState.DONE) "done" else null,
            ).joinToString(", ")
            StudyWeekRow(
                k, day, dayLabel, time, lessonsLine, place, on.filter { !it.done }.map { it.id }, state, button, note,
                catchUp = on.isEmpty(), spoken = spoken,
            )
        }
    }

    /** What Done says: "Ticked “Pivot tables”", "Ticked 3 lessons"; with the row's [day] ahead, "· done ahead of Thu". */
    fun doneSaid(titles: List<String>, day: Long, today: Long): String {
        val what = if (titles.size == 1) "Ticked “${titles.first()}”" else "Ticked ${titles.size} lessons"
        return if (day > today) "$what · done ahead of ${LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]}" else what
    }

    const val NOTHING_TO_TICK = "Nothing left to tick in that session"
}
