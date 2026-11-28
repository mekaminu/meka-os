package os.meka.core.domain

/**
 * A goal's words as rows opening sheets (walk-through week 5, A17: no open input boxes on a page). An open goal on Goals
 * unfolded two always-open boxes, "Say what done looks like (optional)" and "Say why, in a line (optional)". Each is now
 * one card row, "What done looks like" and "Why it matters", whose line is the words kept (or what to say while there are
 * none), opening a sheet with the words to change, what Navilife does with them, Close · Save. Save lights only once the
 * typed words would change what's kept; a blank box clears them.
 *
 * Reuses [GoalAddRow] and [GoalAddSheet]. Pure; nothing new stored (the goal's target and why fields as before).
 */
object GoalTextRules {
    const val TARGET_LABEL = "What done looks like"
    const val WHY_LABEL = "Why it matters"
    const val TARGET_EMPTY = "Not said yet · how you'll know it's finished"
    const val WHY_EMPTY = "Not said yet · the reason, in a line"
    const val SAVE = "Save"
    /** The longest preview on the row; the sheet holds all of it. */
    const val PREVIEW_MAX = 90
    const val MAX_TARGET = Goals.MAX_TITLE
    const val MAX_WHY = GoalRules.MAX_WHY

    /** "What done looks like" over the words kept, or what to say. */
    fun targetRow(g: GoalItem): GoalAddRow = GoalAddRow(TARGET_LABEL, preview(g.target) ?: TARGET_EMPTY)

    /** "Why it matters" over the words kept, or what to say. */
    fun whyRow(g: GoalItem): GoalAddRow = GoalAddRow(WHY_LABEL, preview(g.why) ?: WHY_EMPTY)

    val targetSheet: GoalAddSheet = GoalAddSheet(
        title = TARGET_LABEL,
        hint = "Run 10k in under 55 minutes",
        spokenHint = "What done looks like for this goal",
        note = "Shown under the goal's name on Goals, so you know when it's finished. Left blank, it's cleared.",
        addLabel = SAVE,
    )

    val whySheet: GoalAddSheet = GoalAddSheet(
        title = WHY_LABEL,
        hint = "To have more energy in the evenings",
        spokenHint = "Why this goal matters to you",
        note = "Kept with the goal and shown here when you open it. Left blank, it's cleared.",
        addLabel = SAVE,
    )

    /** The words as they're kept: trimmed, at most [max]; null clears them. */
    fun cleaned(typed: String, max: Int): String? = typed.trim().take(max).trim().ifEmpty { null }

    /** Save lights once the typed words would change what's kept (blank clears words that are there). */
    fun canSave(typed: String, saved: String?, max: Int): Boolean = cleaned(typed, max) != saved?.let { cleaned(it, max) }

    /** One line for the row: the first line of the words, cut at a word with "…" past [PREVIEW_MAX]; null when none. */
    fun preview(words: String?): String? {
        val all = words?.trim()?.ifEmpty { null } ?: return null
        val one = all.lineSequence().first().trim()
        val more = one != all
        if (one.length <= PREVIEW_MAX) return if (more) "$one…" else one
        val cut = one.take(PREVIEW_MAX).substringBeforeLast(' ').ifBlank { one.take(PREVIEW_MAX) }.trimEnd(',', '.', ';', ':', ' ')
        return "$cut…"
    }
}
