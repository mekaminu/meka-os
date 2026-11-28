package os.meka.core.domain

/**
 * Goals' habit settings as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). Tapping a habit on
 * Goals unfolded up to six captioned chip rows: How often, When, How long, Book my sessions, Rotation (a booked habit)
 * and Towards (with goals). Each is now one card row ("How often" · "5 a week" ›) opening a sheet of the same choices,
 * the one in force lit, each saying what it does. A choice saves as the chip did and closes the sheet; the workout app's
 * link field, the booking caption and Delete stay under the rows.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing
 * stored.
 */
object GoalHabitChoiceRules {
    const val OFTEN_HEAD = "How often"
    const val WHEN_HEAD = "When"
    const val LONG_HEAD = "How long"
    const val BOOK_HEAD = "Book my sessions"
    const val ROTATION_HEAD = "Rotation"
    const val TOWARDS_HEAD = "Towards"
    const val ON = "On"
    const val OFF = "Off"
    const val NO_GOAL = "No goal"
    /** The choice name for "No goal" (goal ids are never blank). */
    const val NO_GOAL_NAME = ""

    const val OFTEN_NOTE = "Your pace and streak count against it. Changing it keeps the days you've ticked."
    const val WHEN_NOTE = "The part of the day Navilife plans it into, and books it into when booking is on."
    const val LONG_NOTE = "How much room Navilife looks for when it plans or books it."
    const val BOOK_NOTE = "Booking keeps its ticks and streak. Missed sessions are rebooked."
    const val ROTATION_NOTE = "Each booked session gets the next name in turn, shown on Today's card."
    const val TOWARDS_NOTE = "A goal's page counts the habits that work towards it."

    /** The parts of the day the apps offer, in their order. */
    val TIMINGS: List<HabitTiming> get() = GoalRules.TIMINGS

    private fun row(h: HabitItem, title: String, value: String) =
        NotifyChoiceRow(title, value, "${h.title}, ${title.lowercase()}: $value. Opens its choices")

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    // ---- How often ----

    fun oftenRow(h: HabitItem): NotifyChoiceRow = row(h, OFTEN_HEAD, GoalRules.targetLabel(h.targetPerWeek))

    /** What aiming for [perWeek] means. */
    fun oftenLine(perWeek: Int): String = when (perWeek) {
        7 -> "Every day · a daily streak, the week's dots fill as you tick"
        1 -> "Once Monday to Sunday · a weekly streak"
        else -> "$perWeek days Monday to Sunday, any $perWeek · a weekly streak"
    }

    fun oftenSheet(h: HabitItem): NotifyChoiceSheet = NotifyChoiceSheet("${h.title}: how often", OFTEN_NOTE,
        GoalRules.TARGET_CHOICES.map { c -> choice(c.perWeek.toString(), c.label, oftenLine(c.perWeek), c.perWeek == h.targetPerWeek) })

    /** The target a choice sets; null when it isn't one or is already in force. */
    fun oftenNamed(h: HabitItem, name: String): Int? =
        GoalRules.TARGET_CHOICES.firstOrNull { it.perWeek.toString() == name && it.perWeek != h.targetPerWeek }?.perWeek

    // ---- When ----

    fun whenRow(h: HabitItem): NotifyChoiceRow = row(h, WHEN_HEAD, GoalRules.timingLabel(h.timing))

    /** "Planned between 06:00 and 12:00", from the planner's own window ([GoalRules.timingWindow]). */
    fun whenLine(t: HabitTiming): String {
        if (t == HabitTiming.ANYTIME) return "Planned wherever your day has room"
        val w = GoalRules.timingWindow(t)
        return "Planned between ${LocalClock.formatMinute(w.first)} and ${LocalClock.formatMinute(w.last + 1)}"
    }

    fun whenSheet(h: HabitItem): NotifyChoiceSheet = NotifyChoiceSheet("${h.title}: when", WHEN_NOTE,
        TIMINGS.map { t -> choice(t.name, GoalRules.timingLabel(t), whenLine(t), t == h.timing) })

    fun whenNamed(h: HabitItem, name: String): HabitTiming? = TIMINGS.firstOrNull { it.name == name && it != h.timing }

    // ---- How long ----

    fun longValue(minutes: Int): String = "$minutes min"

    fun longRow(h: HabitItem): NotifyChoiceRow = row(h, LONG_HEAD, longValue(h.minutes))

    fun longLine(minutes: Int): String = when {
        minutes < 60 -> "Navilife finds a $minutes-minute gap for it"
        minutes % 60 == 0 -> "Navilife finds ${if (minutes == 60) "an hour" else "${minutes / 60} hours"} for it"
        else -> "Navilife finds ${minutes / 60} h ${minutes % 60} for it"
    }

    fun longSheet(h: HabitItem): NotifyChoiceSheet = NotifyChoiceSheet("${h.title}: how long", LONG_NOTE,
        GoalRules.MINUTE_CHOICES.map { m -> choice(m.toString(), longValue(m), longLine(m), m == h.minutes) })

    fun longNamed(h: HabitItem, name: String): Int? =
        GoalRules.MINUTE_CHOICES.firstOrNull { it.toString() == name && it != h.minutes }

    // ---- Book my sessions ----

    fun bookRow(h: HabitItem): NotifyChoiceRow = row(h, BOOK_HEAD, if (h.booked) ON else OFF)

    const val BOOK_ON_LINE = "Navilife books its sessions round your calendar and work, a rest day between when there's room"
    const val BOOK_OFF_LINE = "Nothing booked · Navilife's plan for the day suggests a time"

    fun bookSheet(h: HabitItem): NotifyChoiceSheet = NotifyChoiceSheet("${h.title}: book my sessions", BOOK_NOTE, listOf(
        choice(ON, ON, BOOK_ON_LINE, h.booked),
        choice(OFF, OFF, BOOK_OFF_LINE, !h.booked),
    ))

    fun bookNamed(h: HabitItem, name: String): Boolean? = when (name) {
        ON -> if (!h.booked) true else null
        OFF -> if (h.booked) false else null
        else -> null
    }

    // ---- Rotation (a booked habit) ----

    fun rotationRow(h: HabitItem): NotifyChoiceRow = row(h, ROTATION_HEAD, h.rotationLabel)

    fun rotationLine(r: List<String>): String =
        if (r.isEmpty()) "Each session is just the habit's name" else "Sessions take turns: ${r.joinToString(", then ")}"

    fun rotationSheet(h: HabitItem): NotifyChoiceSheet = NotifyChoiceSheet("${h.title}: rotation", ROTATION_NOTE,
        SessionRules.ROTATIONS.mapIndexed { i, r -> choice(i.toString(), SessionRules.rotationLabel(r), rotationLine(r), r == h.rotation) })

    /** The preset's index in [SessionRules.ROTATIONS]; null when it isn't one or is already in force. */
    fun rotationNamed(h: HabitItem, name: String): Int? =
        SessionRules.ROTATIONS.indices.firstOrNull { it.toString() == name && SessionRules.ROTATIONS[it] != h.rotation }

    // ---- Towards (with goals) ----

    fun towardsRow(h: HabitItem, goals: List<GoalItem>): NotifyChoiceRow =
        row(h, TOWARDS_HEAD, goals.firstOrNull { it.id == h.goalId }?.title ?: NO_GOAL)

    fun towardsSheet(h: HabitItem, goals: List<GoalItem>): NotifyChoiceSheet {
        val linked = goals.any { it.id == h.goalId }
        return NotifyChoiceSheet("${h.title}: towards", TOWARDS_NOTE,
            listOf(choice(NO_GOAL_NAME, NO_GOAL, "Kept on its own, counted on no goal", !linked)) +
                goals.map { g -> choice(g.id, g.title, "Counts on ${g.title}'s page", g.id == h.goalId) })
    }

    /**
     * What a Towards choice links: [GoalLinkPick.goalId] null for No goal. Null when the name isn't a choice or is
     * already in force.
     */
    fun towardsNamed(h: HabitItem, goals: List<GoalItem>, name: String): GoalLinkPick? {
        val current = h.goalId?.takeIf { id -> goals.any { it.id == id } }
        if (name == NO_GOAL_NAME) return if (current != null) GoalLinkPick(null) else null
        val g = goals.firstOrNull { it.id == name } ?: return null
        return if (g.id != current) GoalLinkPick(g.id) else null
    }
}

/** A Towards choice: the goal to link, or null to unlink. */
data class GoalLinkPick(val goalId: String?)
