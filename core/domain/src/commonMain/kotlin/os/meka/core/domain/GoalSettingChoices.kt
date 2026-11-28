package os.meka.core.domain

/**
 * A goal's own settings on Goals as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). Tapping
 * a goal unfolded open captioned chip rows: Horizon (five chips), Done by (five dated chips, cut at the cover's edge),
 * Under (a chip per bigger goal) and, on a goal counting a number, "Each tick of a habit adds 1" (a chip per habit).
 * Each is now one card row ("Horizon" · "This month" ›) opening a sheet of the same choices, the one in force lit, each
 * saying what it does. A choice saves as the chip did and closes the sheet.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing
 * stored.
 */
object GoalSettingChoiceRules {
    const val HORIZON_HEAD = "Horizon"
    const val DUE_HEAD = "Done by"
    const val UNDER_HEAD = "Under"
    const val HABIT_HEAD = "Counts a habit"
    const val NO_DATE = "No date"
    const val NOTHING = "Nothing"
    const val NO_HABIT = "No habit"
    /** The choice name for No date, Nothing and No habit (ids and days are never blank). */
    const val NONE_NAME = ""

    const val HORIZON_NOTE = "The stretch it's for. A goal sits under one as long or longer."
    const val DUE_NOTE = "A number goal's pace line runs to this day. Changing it keeps its progress."
    const val UNDER_NOTE = "A goal under a bigger one shows beneath it, year → month → week."
    const val HABIT_NOTE = "Each tick of the habit adds 1 to the number. Your own updates stay as they are."

    const val SOMEDAY_LINE = "No end · kept for when you get to it"
    const val NO_DATE_LINE = "Nothing due · no pace line"
    const val NOTHING_LINE = "Sits at the top of Goals"
    const val NO_HABIT_LINE = "Only your own updates move the number"

    private fun row(g: GoalItem, title: String, value: String) =
        NotifyChoiceRow(title, value, "${g.title}, ${title.lowercase()}: $value. Opens its choices")

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    // ---- Horizon ----

    fun horizonRow(g: GoalItem): NotifyChoiceRow = row(g, HORIZON_HEAD, GoalRules.horizonLabel(g.horizon))

    /** "Ends Sun 29 Nov", from the period's own end ([GoalRules.horizonEnd]); Someday has none. */
    fun horizonLine(h: GoalHorizon, today: Long): String =
        GoalRules.horizonEnd(h, today)?.let { "Ends ${CivilDate.shortLabel(it)}" } ?: SOMEDAY_LINE

    fun horizonSheet(g: GoalItem, today: Long): NotifyChoiceSheet = NotifyChoiceSheet("${g.title}: horizon", HORIZON_NOTE,
        GoalRules.HORIZONS.map { h -> choice(h.name, GoalRules.horizonLabel(h), horizonLine(h, today), h == g.horizon) })

    /** The horizon a choice sets; null when it isn't one or is already in force. */
    fun horizonNamed(g: GoalItem, name: String): GoalHorizon? = GoalRules.HORIZONS.firstOrNull { it.name == name && it != g.horizon }

    // ---- Done by ----

    /** "Sun 29 Nov", "Overdue · was Sat 31 Oct", or No date. */
    fun dueValue(dueDay: Long?, today: Long): String = when {
        dueDay == null -> NO_DATE
        dueDay < today -> "Overdue · was ${CivilDate.shortLabel(dueDay)}"
        else -> CivilDate.shortLabel(dueDay)
    }

    fun dueRow(g: GoalItem, today: Long): NotifyChoiceRow = row(g, DUE_HEAD, dueValue(g.dueDay, today))

    /** "Today", "Tomorrow", "In 12 days", "Was 3 days ago". */
    fun dueLine(day: Long, today: Long): String {
        val n = day - today
        return when {
            n == 0L -> "Due today"
            n == 1L -> "Due tomorrow"
            n > 1 -> "Due in $n days"
            n == -1L -> "Was due yesterday"
            else -> "Was due ${-n} days ago"
        }
    }

    /**
     * [GoalRules.dueChoices] with one choice a day: in the last quarter the quarter and the year end on the same 31 Dec,
     * and the two chips both lit once it was picked, so the year's keeps it.
     */
    fun dueChoices(today: Long): List<GoalRules.DueChoice> = GoalRules.dueChoices(today).asReversed().distinctBy { it.day }.asReversed()

    /**
     * No date, then the end of this week, month, quarter and year ([GoalRules.dueChoices]). A day set another way (Break
     * it down, or a period since passed) leads the list, lit, so the sheet always shows what's in force.
     */
    fun dueSheet(g: GoalItem, today: Long): NotifyChoiceSheet {
        val dues = dueChoices(today)
        val own = g.dueDay?.takeIf { d -> dues.none { it.day == d } }
        val kept = own?.let { listOf(choice(it.toString(), dueValue(it, today), dueLine(it, today), true)) } ?: emptyList()
        return NotifyChoiceSheet("${g.title}: done by", DUE_NOTE, kept + dues.map { c ->
            val d = c.day
            if (d == null) choice(NONE_NAME, NO_DATE, NO_DATE_LINE, g.dueDay == null)
            else choice(d.toString(), c.label, dueLine(d, today), d == g.dueDay)
        })
    }

    /** What a Done by choice sets ([DuePick.day] null for No date); null when it isn't one or is already in force. */
    fun dueNamed(g: GoalItem, today: Long, name: String): DuePick? {
        if (name == NONE_NAME) return if (g.dueDay != null) DuePick(null) else null
        val d = dueChoices(today).firstOrNull { it.day?.toString() == name }?.day ?: return null
        return if (d != g.dueDay) DuePick(d) else null
    }

    // ---- Under (with bigger goals to sit under, or a parent already) ----

    /** Whether the Under row shows: a goal to sit under, or one it sits under now. */
    fun showsUnder(g: GoalItem, parents: List<GoalItem>): Boolean = parents.isNotEmpty() || g.parentId != null

    fun underRow(g: GoalItem): NotifyChoiceRow = row(g, UNDER_HEAD, g.parentTitle ?: NOTHING)

    fun underLine(p: GoalItem): String = "Shown beneath ${p.title} · ${GoalRules.horizonLabel(p.horizon).lowercase()}"

    /** Nothing, then [parents] ([GoalRules.parentChoices]: bigger open goals, longest horizon first). */
    fun underSheet(g: GoalItem, parents: List<GoalItem>): NotifyChoiceSheet = NotifyChoiceSheet("${g.title}: under", UNDER_NOTE,
        listOf(choice(NONE_NAME, NOTHING, NOTHING_LINE, g.parentId == null)) +
            parents.map { p -> choice(p.id, p.title, underLine(p), p.id == g.parentId) })

    /** What an Under choice sets ([GoalLinkPick.goalId] null for Nothing); null when it isn't one or is in force. */
    fun underNamed(g: GoalItem, parents: List<GoalItem>, name: String): GoalLinkPick? {
        if (name == NONE_NAME) return if (g.parentId != null) GoalLinkPick(null) else null
        val p = parents.firstOrNull { it.id == name } ?: return null
        return if (p.id != g.parentId) GoalLinkPick(p.id) else null
    }

    // ---- Counts a habit (a goal counting a number, with habits) ----

    fun habitRow(g: GoalItem, habits: List<HabitItem>): NotifyChoiceRow {
        val id = g.metric?.habitId
        val title = id?.let { i -> habits.firstOrNull { it.id == i }?.title ?: g.metric?.habitTitle }
        return row(g, HABIT_HEAD, title ?: NO_HABIT)
    }

    fun habitLine(h: HabitItem): String = "Each tick of ${h.title} adds 1 · ${GoalRules.targetLabel(h.targetPerWeek).lowercase()}"

    fun habitSheet(g: GoalItem, habits: List<HabitItem>): NotifyChoiceSheet {
        val id = g.metric?.habitId
        return NotifyChoiceSheet("${g.title}: counts a habit", HABIT_NOTE,
            listOf(choice(NONE_NAME, NO_HABIT, NO_HABIT_LINE, id == null)) +
                habits.map { h -> choice(h.id, h.title, habitLine(h), h.id == id) })
    }

    /** What a habit choice sets ([HabitLinkPick.habitId] null for No habit); null when it isn't one or is in force. */
    fun habitNamed(g: GoalItem, habits: List<HabitItem>, name: String): HabitLinkPick? {
        val current = g.metric?.habitId
        if (name == NONE_NAME) return if (current != null) HabitLinkPick(null) else null
        val h = habits.firstOrNull { it.id == name } ?: return null
        return if (h.id != current) HabitLinkPick(h.id) else null
    }
}

/** A Done by choice: the day, or null for No date. */
data class DuePick(val day: Long?)

/** A Counts a habit choice: the habit whose ticks add 1, or null for none. */
data class HabitLinkPick(val habitId: String?)
