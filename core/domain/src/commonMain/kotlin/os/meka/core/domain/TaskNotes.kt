package os.meka.core.domain

/**
 * A task's notes in its sheet (walk-through 2026-11-22 A17: "the task sheet's 'Add notes…' box … open inputs"). No
 * box sits open under the rows: with no notes the sheet shows one row, [ADD]; with notes it shows them whole in a card
 * that opens the field on a tap. The field has [DONE] at its foot, which saves and closes it; a field emptied and
 * closed clears the notes and the row comes back. Plain rules, read by the Fold and the Mac alike.
 */
object TaskNotesRules {
    const val LABEL = "Notes"
    const val ADD = "+ Add notes"
    const val HINT = "Anything to remember for this task"
    const val DONE = "Done"
    const val EDIT_DESCRIPTION = "Notes, tap to edit"

    /** The notes as the card shows them, or null when there are none (the [ADD] row shows instead). */
    fun shown(notes: String?): String? = notes?.trim()?.takeIf { it.isNotEmpty() }

    /** What a closed field saves: null clears the notes. */
    fun saved(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
}
