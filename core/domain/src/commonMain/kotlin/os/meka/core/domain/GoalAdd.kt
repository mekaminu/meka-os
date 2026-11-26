package os.meka.core.domain

/**
 * What Goals' "+ New habit" and "+ New goal" sheets say (walk-through week 5, A17: no open input boxes or chip rows on
 * a page). Each is one card row on the page that opens its sheet: the name, a field with a short example, the choices
 * (how often and when; what done looks like and the horizon), what Navilife does with it, and Close · Add.
 */
data class GoalAddSheet(
    val title: String,
    /** The field's example, shown while it's empty. */
    val hint: String,
    /** What a screen reader says for the field. */
    val spokenHint: String,
    val note: String,
    val addLabel: String,
)

/** The card row on the page that opens a [GoalAddSheet]. */
data class GoalAddRow(val label: String, val line: String)

object GoalAddRules {
    const val NEW_HABIT = "+ New habit"
    const val NEW_GOAL = "+ New goal"
    const val ADD = "Add"
    const val CLOSE = "Close"
    /** The longest name kept from the sheet. */
    const val MAX_TITLE = 120
    const val HOW_OFTEN = "How often"
    const val WHEN = "When"
    const val DONE_LOOKS_LIKE = "What does done look like? (optional)"
    const val HORIZON = "By when"

    const val DEFAULT_PER_WEEK = 7
    val DEFAULT_TIMING = HabitTiming.ANYTIME
    val DEFAULT_HORIZON = GoalHorizon.MONTH

    val habitRow = GoalAddRow(NEW_HABIT, "Something to do, or stop doing, most weeks")
    val goalRow = GoalAddRow(NEW_GOAL, "Something you're working towards")

    val habitSheet = GoalAddSheet(
        title = "New habit",
        hint = "Read 20 pages",
        spokenHint = "The habit's name",
        note = "Navilife keeps count and makes room in your plan when it falls behind. Change how often or when from the habit at any time.",
        addLabel = ADD,
    )

    val goalSheet = GoalAddSheet(
        title = "New goal",
        hint = "Run a 10k",
        spokenHint = "The goal's name",
        note = "Link habits and tasks to it from the goal and its progress counts itself.",
        addLabel = ADD,
    )

    /** Goals' STUDY "+ Add course" (walk-through week 5, A17): one row opening a sheet, not the old open box. */
    val courseRow = GoalAddRow("+ Add course", "A course you're working through, its lessons to tick")

    val courseSheet = GoalAddSheet(
        title = "New course",
        hint = "Data analytics",
        spokenHint = "The course's name",
        note = "The course opens next: paste its outline there, one lesson a line, or add it from a screenshot. Nothing leaves your data.",
        addLabel = ADD,
    )

    /** Add on the course sheet lights only for a name the course would keep ("Course:" alone, or "…", isn't one). */
    fun canAddCourse(title: String): Boolean = courseName(title) != null

    /** The course's name as it's added: its first line, read the way the Study plan reads a typed course. */
    fun courseName(title: String): String? = StudyRules.courseTitle(title.trim().lines().firstOrNull().orEmpty())

    /** The name as it's kept: trimmed, at most [MAX_TITLE]; null when there's nothing to add (Add stays dark). */
    fun cleaned(title: String): String? = title.trim().take(MAX_TITLE).trim().ifEmpty { null }

    fun canAdd(title: String): Boolean = cleaned(title) != null

    /** The optional "what done looks like": blank is none. */
    fun cleanedTarget(target: String): String? = target.trim().take(MAX_TITLE).trim().ifEmpty { null }

    /** What a screen reader says for a row: its label and what it opens. */
    fun rowSpoken(row: GoalAddRow): String = "${row.label.removePrefix("+ ")}, ${row.line}"
}
