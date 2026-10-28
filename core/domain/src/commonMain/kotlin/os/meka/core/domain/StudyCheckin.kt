package os.meka.core.domain

/**
 * Study plan, slice 2b rest (the board: "a Sunday check-in"; mockup's toggle "Sunday check-in · How the week went, and
 * next week's sessions"): from [StudyCheckinRules.FROM_MIN] on Sunday until [StudyCheckinRules.UNTIL_MIN] on Monday,
 * Today shows one card for the courses with the check-in on: per course, what he ticked in the week (Monday to that
 * Sunday), whether he's on track, and next week's sessions with the lessons in them. Looks good puts it away on both
 * devices (each course keeps the Sunday it was seen for); Change plan opens the course. Never spoken, never a sound.
 */
data class StudyCheckinRow(
    val courseId: String,
    val title: String,
    /** "This week · 4 lessons · about 3 h", "This week · nothing ticked". */
    val week: String,
    /** "On track for Fri 11 Dec", "2 lessons behind · catch up Saturday", or null with no finish date. */
    val status: String?,
    val behind: Boolean,
    /** "Next week · 3 sessions · Mon, Wed, Sat · 5 lessons", "Next week · no sessions yet · pick when you can study", "All 24 lessons done". */
    val next: String,
    val spoken: String,
)

data class StudyCheckin(
    /** The Sunday it's for ([StudyCheckinRules.key]): what Looks good takes. */
    val key: String,
    val headline: String,
    /** "Mon 26 Oct – Sun 1 Nov". */
    val sub: String,
    val rows: List<StudyCheckinRow>,
    /** Obi's one line: what to change, or that the week ahead is set. */
    val obi: String,
    val spoken: String,
)

object StudyCheckinRules {
    /** Sunday from 17:00 ... */
    const val FROM_MIN = 17 * 60
    /** ... to Monday 12:00. */
    const val UNTIL_MIN = 12 * 60
    const val TOGGLE = "Sunday check-in"
    const val TOGGLE_SUB = "How the week went, and next week’s sessions"
    const val HEADLINE = "Your study week"
    const val LOOKS_GOOD = "Looks good"
    const val CHANGE = "Change plan"
    const val NO_SESSIONS = "no sessions yet · pick when you can study"
    const val NOTHING_TICKED = "nothing ticked"

    fun key(sunday: Long): String = "checkin:$sunday"

    /** The Sunday a [key] names, or null. */
    fun dayOf(key: String): Long? = key.removePrefix("checkin:").takeIf { key.startsWith("checkin:") }?.toLongOrNull()

    /** The Sunday whose check-in shows at [nowMin] on [today], or null outside the window. */
    fun sundayFor(today: Long, nowMin: Int): Long? = when (CivilDate.isoDayOfWeek(today)) {
        7 -> today.takeIf { nowMin >= FROM_MIN }
        1 -> (today - 1).takeIf { nowMin < UNTIL_MIN }
        else -> null
    }

    /**
     * The check-in, or null: for each course with it on that hasn't been seen for this Sunday, with lessons in it and
     * either lessons left or one ticked this week. [lessons] carry the suggestions ([StudyAutoRules.suggest]),
     * [sessions] are from today ([StudyAutoRules.sessions]), [plans] each course's plan card ([StudyRules.plan]).
     */
    fun checkin(
        courses: List<StudyCourse>, lessons: List<StudyLesson>, sessions: List<StudySession>, plans: Map<String, StudyPlanView>,
        today: Long, nowMin: Int, doneDay: (Long) -> Long,
    ): StudyCheckin? {
        val sunday = sundayFor(today, nowMin) ?: return null
        val weekDays = (sunday - 6)..sunday
        val nextDays = (sunday + 1)..(sunday + 7)
        val rows = courses.filter { it.checkin && it.checkinSeen != sunday }.mapNotNull { c ->
            val mine = lessons.filter { it.courseId == c.id }
            if (mine.isEmpty()) return@mapNotNull null
            val ticked = mine.filter { it.done && it.doneAtMs?.let(doneDay) in weekDays }
            val left = mine.count { !it.done }
            if (left == 0 && ticked.isEmpty()) return@mapNotNull null
            val tickedMins = ticked.mapNotNull { it.mins }.sum()
            val week = "This week · " + when {
                ticked.isEmpty() -> NOTHING_TICKED
                tickedMins > 0 -> "${lessonsWord(ticked.size)} · ${StudyRules.aboutLabel(tickedMins)}"
                else -> lessonsWord(ticked.size)
            }
            val plan = plans[c.id]
            val ahead = sessions.filter { it.courseId == c.id && it.day in nextDays }.map { it.day }.distinct().sorted()
            val planned = mine.count { !it.done && it.plannedDay in nextDays }
            val next = when {
                left == 0 -> if (mine.size == 1) "The lesson is done" else "All ${mine.size} lessons done"
                ahead.isEmpty() -> "Next week · $NO_SESSIONS"
                else -> listOfNotNull(
                    "Next week · " + (if (ahead.size == 1) "1 session" else "${ahead.size} sessions"),
                    ahead.joinToString(", ") { LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(it) - 1] },
                    planned.takeIf { it > 0 }?.let(::lessonsWord),
                ).joinToString(" · ")
            }
            val status = plan?.status.takeIf { left > 0 }
            val behind = plan?.behind == true && left > 0
            StudyCheckinRow(
                c.id, c.title, week, status, behind, next,
                spoken = listOfNotNull(c.title, week, status, next).joinToString(", ").replace(" · ", ", "),
            )
        }
        if (rows.isEmpty()) return null
        val obi = when {
            rows.any { it.next.endsWith(NO_SESSIONS) } -> "Pick when you can study and Obi will plan next week’s sessions."
            rows.any { it.behind } -> "A bit behind: an extra session next week, or a later finish date, puts you back on track."
            rows.all { it.next.startsWith("All ") || it.next.startsWith("The lesson") } -> "Course done. Well played."
            else -> "Next week is planned. Change anything in the course."
        }
        val headline = HEADLINE
        val sub = "${CivilDate.shortLabel(sunday - 6)} – ${CivilDate.shortLabel(sunday)}"
        return StudyCheckin(
            key(sunday), headline, sub, rows, obi,
            spoken = (listOf(headline) + rows.map { it.spoken } + obi).joinToString(". "),
        )
    }

    private fun lessonsWord(n: Int) = if (n == 1) "1 lesson" else "$n lessons"
}
