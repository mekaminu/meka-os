package os.meka.core.domain

/**
 * Study's plan choices as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). The course's "Your
 * plan" card had three open chip rows: FINISH BY's dates, EACH SESSION's lengths and WHEN YOU CAN STUDY's times. Each is
 * now one card row saying what's chosen ("Finish by" · "Mon 7 Dec") that opens a sheet of the choices, each with a line
 * on what it means for him (how many sessions a week that date or length takes), the chosen one lit. When you can study
 * is the one sheet that picks several: a tap lights or dims a time and the sheet stays open until Done.
 *
 * They reuse Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]) so every page
 * draws the same thing; a choice's name is its plan chip's key, so the apps write it back exactly as the chips did. Pure;
 * nothing new stored. The apps pass the plan's input with any choice tapped on screen already applied.
 */
object StudyPlanChoiceRules {
    const val PICK_DATE = "Pick a date"
    const val NONE_YET = "None yet"
    const val DATE_NOTE = "Your sessions a week are worked out from the lessons left. Change it any time."
    const val SESSION_NOTE = "Lessons longer than a session run over two."
    const val SLOTS_NOTE = "Pick every time you could study. Each one you pick lights; Done when that's all of them."

    private fun row(title: String, value: String) = NotifyChoiceRow(title, value, "$title, $value. Opens the choices")
    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    private fun plural(n: Int, one: String) = "$n ${if (n == 1) one else one + "s"}"

    /** "Finish by" · "Mon 7 Dec", or · "Pick a date". */
    fun finishRow(i: StudyPlanInput): NotifyChoiceRow =
        row(StudyRules.FINISH_LABEL, i.finishByDay?.let { CivilDate.shortLabel(it) } ?: PICK_DATE)

    /** What finishing by [day] takes: "3 sessions a week of 45 min", or that it has passed. */
    fun finishLine(i: StudyPlanInput, day: Long): String =
        if (day <= i.today) "This date has passed"
        else "${plural(StudyRules.sessionsPerWeek(i, day), "session")} a week of ${StudyRules.minsLabel(i.sessionMins)}"

    /** The plan's finish dates (a date he set that isn't one of the choices first), the one in use lit. */
    fun finishSheet(i: StudyPlanInput): NotifyChoiceSheet = NotifyChoiceSheet(
        StudyRules.FINISH_LABEL, DATE_NOTE,
        StudyRules.plan(i).ends.map { choice(it.key, it.label, finishLine(i, it.key.toLong()), it.on) },
    )

    /** A finish choice back to its day; null if it isn't one. */
    fun finishNamed(name: String): Long? = name.toLongOrNull()

    /** "Each session" · "45 min". */
    fun sessionRow(i: StudyPlanInput): NotifyChoiceRow = row(StudyRules.SESSION_LABEL, StudyRules.minsLabel(i.sessionMins))

    /** What a [mins] session means: "4 sessions a week to finish by Mon 7 Dec", or "Sessions of 30 minutes" with no date. */
    fun sessionLine(i: StudyPlanInput, mins: Int): String {
        val finish = i.finishByDay
        return if (finish == null || finish <= i.today) "Sessions of $mins minutes"
        else "${plural(StudyRules.sessionsPerWeek(i, finish, mins), "session")} a week to finish by ${CivilDate.shortLabel(finish)}"
    }

    fun sessionSheet(i: StudyPlanInput): NotifyChoiceSheet = NotifyChoiceSheet(
        StudyRules.SESSION_LABEL, SESSION_NOTE,
        StudyRules.plan(i).lens.map { choice(it.key, it.label, sessionLine(i, it.key.toInt()), it.on) },
    )

    /** A session choice back to its minutes; null if it isn't one. */
    fun sessionNamed(name: String): Int? = name.toIntOrNull()?.takeIf { it in StudyRules.MIN_SESSION..StudyRules.MAX_SESSION }

    /** "When you can study" · "None yet", the one time's name, or "3 times · 5 sessions". */
    fun slotsRow(i: StudyPlanInput): NotifyChoiceRow {
        val picked = StudyRules.SLOTS.filter { it.key in i.slots }
        val value = when (picked.size) {
            0 -> NONE_YET
            1 -> picked.single().name
            else -> "${picked.size} times · ${plural(StudyRules.available(i.slots), "session")}"
        }
        return row(StudyRules.SLOTS_LABEL, value)
    }

    /** Every time he could study with the sessions a week it gives, the ones picked lit (several may be). */
    fun slotsSheet(i: StudyPlanInput): NotifyChoiceSheet = NotifyChoiceSheet(
        StudyRules.SLOTS_LABEL, SLOTS_NOTE,
        StudyRules.SLOTS.map { choice(it.key, it.name, "${plural(it.perWeek, "session")} a week", it.key in i.slots) },
    )

    /** A tap on a time back to the slot and whether it is now picked; null if it isn't one. */
    fun slotTapped(i: StudyPlanInput, name: String): Pair<String, Boolean>? =
        StudyRules.SLOTS.firstOrNull { it.key == name }?.let { it.key to (it.key !in i.slots) }
}
