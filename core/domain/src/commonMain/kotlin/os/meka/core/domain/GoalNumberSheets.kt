package os.meka.core.domain

/**
 * A number goal's two boxes as rows opening sheets (walk-through week 5, A17: no open input boxes on a page). An open
 * goal on Goals unfolded "A number · £2,000 or 12 books (optional)" and, once counting, "Add to it · +200, -1 or =1200",
 * both always open. Each is now one card row:
 *  - "Number to reach" over where it stands ("£800 of £2,000 · On pace"), or what to type while it doesn't count one
 *    (the title's own number offered: "Not set · try “£2,000” from the name"), opening a sheet with the number to change
 *    (the title's number already in it), what the number does, Close · Save. A typed line that isn't a number keeps
 *    the sheet open and says so under it; a blank box stops counting.
 *  - "+ Add to it" over where it is now and how to type an update, opening a sheet whose Add stays dark until something
 *    is typed; a line that isn't an update keeps the sheet open and says so under it.
 *
 * Reuses [GoalAddRow] and [GoalAddSheet]. Pure; nothing new stored (the goal's number and its updates as before).
 */
object GoalNumberRules {
    const val NUMBER_LABEL = "Number to reach"
    const val NUMBER_EMPTY = "Not set · £2,000 or 12 books"
    const val ADD_LABEL = "+ Add to it"
    const val SAVE = "Save"
    const val ADD = "Add"
    const val MAX_TYPED = 40
    const val NOT_A_NUMBER = "That isn't a number · try £2,000 or 12 books"
    const val NOT_AN_UPDATE = "Type a number, like +200 or =1200"
    const val NO_CHANGE = "No change"

    /** "Number to reach" over where it stands, or what to type (the title's number when it has one). */
    fun numberRow(g: GoalItem): GoalAddRow {
        val m = g.metric
        if (m != null) return GoalAddRow(NUMBER_LABEL, m.line + (m.paceLine?.let { " · $it" } ?: ""))
        val s = GoalMetricRules.suggestFromTitle(g.title)
        return GoalAddRow(NUMBER_LABEL, if (s != null) "Not set · try “$s” from the name" else NUMBER_EMPTY)
    }

    /** What the number's sheet starts with: the number kept, else the title's number, else nothing. */
    fun numberInitial(g: GoalItem): String = g.metric?.targetText ?: GoalMetricRules.suggestFromTitle(g.title) ?: ""

    fun numberSheet(g: GoalItem): GoalAddSheet = GoalAddSheet(
        title = NUMBER_LABEL,
        hint = "£2,000 or 12 books",
        spokenHint = "The number this goal counts up to",
        note = if (g.metric != null) "The goal's progress follows this number. Left blank, the goal stops counting a number; its updates are kept."
        else "The goal's progress follows this number, and a small chart shows how it grows. Updates go in Add to it.",
        addLabel = SAVE,
    )

    /**
     * Save lights once the typed number would change what's counted: a different number, or blank while one is kept
     * (which stops counting). Blank with nothing kept, or the same number again, stays dark.
     */
    fun canSaveNumber(typed: String, g: GoalItem): Boolean {
        val t = typed.trim().take(MAX_TYPED).trim()
        val m = g.metric ?: return t.isNotEmpty()
        if (t.isEmpty()) return true
        val parsed = GoalMetricRules.parseTarget(t) ?: return true // said under the field once tried
        return parsed.first != m.targetX100 || parsed.second != m.unit
    }

    /** Whether the typed number is one the goal can count (blank stops counting, so it's fine too). */
    fun numberOk(typed: String): Boolean = typed.isBlank() || GoalMetricRules.parseTarget(typed.trim()) != null

    /** "+ Add to it" over where it is now and how to type an update. */
    fun addRow(m: GoalMetric): GoalAddRow =
        GoalAddRow(ADD_LABEL, "Now ${GoalMetricRules.format(m.currentX100, m.unit)} · +200, -1 or =1200")

    fun addSheet(m: GoalMetric): GoalAddSheet = GoalAddSheet(
        title = "Add to it",
        hint = "+200, -1 or =1200",
        spokenHint = "An update to the number",
        note = "${m.line} now. +200 adds, -1 takes away and =1200 sets the number; each update shows under the chart with Remove.",
        addLabel = ADD,
    )

    /** Add lights once something is typed. */
    fun canAdd(typed: String): Boolean = typed.isNotBlank()

    /** What's said on the page once an update is kept: "Added +200", or "No change" when it didn't move the number. */
    fun addedLine(typed: String, changed: Boolean): String = if (changed) "Added ${typed.trim()}" else NO_CHANGE
}
