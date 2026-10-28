package os.meka.core.domain

/**
 * Study plan, slice 2b rest (the board: "a 10-minute nudge card"; mockup's toggle "Nudge me 10 minutes before · A card
 * from Obi, not out loud"): from [StudyNudgeRules.LEAD_MINS] minutes before a study session with lessons in it, Today
 * shows a card ("Study in 8 min · Data analytics", "18:30 · Pivot tables + 1 more · 1 h · Train morning") with Start;
 * once started it says so with Done, until the session's end plus [StudyNudgeRules.GRACE_MINS]. Not now puts it away on
 * that device. Never spoken, never a sound.
 */
data class StudyNudge(
    /** The THIS WEEK row's key ([StudyWeekRules.key]): what Start, Done and Not now take. */
    val key: String,
    val courseId: String,
    val headline: String,
    val line: String,
    /** [StudyWeekRules.START] or [StudyWeekRules.DONE]. */
    val button: String,
    /** Started (on this device). */
    val on: Boolean,
    /** The minutes left before it starts (0 once it's time). */
    val inMins: Int,
    val spoken: String,
)

object StudyNudgeRules {
    const val LEAD_MINS = 10
    /** Not started: shown this long after the start. Started: this long after its end. */
    const val GRACE_MINS = 15
    const val TOGGLE = "Nudge me 10 minutes before"
    const val TOGGLE_SUB = "A card from Obi, not out loud"
    const val NOT_NOW = "Not now"

    /**
     * Today's nudge, or null: the earliest of today's sessions (rows from [StudyWeekRules.rows], per course in [week]) of
     * a course with the nudge on, with lessons left in it and a start time, not done or put away ([dismissed]), and
     * [nowMin] (minute of the day) inside its window.
     */
    fun nudge(
        courses: List<StudyCourse>, week: Map<String, List<StudyWeekRow>>, today: Long, nowMin: Int, dismissed: Set<String>,
    ): StudyNudge? {
        val picked = courses.filter { it.nudge }.flatMap { c ->
            week[c.id].orEmpty().filter { r ->
                val start = r.startMin
                r.day == today && !r.catchUp && start != null && r.state != StudyWeekState.DONE && r.key !in dismissed &&
                    r.lessonIds.isNotEmpty() && when (r.state) {
                        StudyWeekState.ON -> nowMin < start + r.mins + GRACE_MINS
                        else -> nowMin in (start - LEAD_MINS) until (start + GRACE_MINS)
                    }
            }.map { c to it }
        }.minByOrNull { (_, r) -> r.startMin ?: 0 } ?: return null
        val (course, row) = picked
        val start = row.startMin ?: return null
        val on = row.state == StudyWeekState.ON
        val inMins = (start - nowMin).coerceAtLeast(0)
        val headline = when {
            on -> "Studying · ${course.title}"
            inMins > 0 -> "Study in $inMins min · ${course.title}"
            else -> "Study now · ${course.title}"
        }
        val line = listOfNotNull(
            if (on) row.note ?: row.time else row.time, row.lessons, row.place.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val spoken = listOf(headline, line.replace(" · ", ", ")).joinToString(", ").replace(" · ", ", ")
        return StudyNudge(
            row.key, course.id, headline, line, if (on) StudyWeekRules.DONE else StudyWeekRules.START, on, inMins, spoken,
        )
    }
}
