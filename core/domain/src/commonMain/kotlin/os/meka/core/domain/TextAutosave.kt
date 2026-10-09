package os.meka.core.domain

/**
 * Typing is never lost (Meka, 2026-10-08 21:48: renamed a task, there was no Save button and closing lost the edit).
 * The rules both apps follow for free-text fields (non-AI, pure):
 *  - an edit of something that exists (a task's title, its notes, a habit's workout-app link) saves itself
 *    [DELAY_MS] after typing stops, when the field loses focus, on Done/Return and when the screen closes;
 *  - an "add" field that adds one thing on its own (a task's "Add a step…") adds what was typed when the screen
 *    closes, as if Done had been pressed. Add forms with more than one field (Lists' Waiting for, Decisions…) keep
 *    Done, so a half-filled item never lands on its own.
 */
object TextAutosave {
    /** How long after the last keystroke an edit saves itself. */
    const val DELAY_MS: Long = 800L

    /**
     * The title to save for what was typed, or null when there is nothing to save: blank (the old title stays) or the
     * same as the saved one. Line breaks become spaces (a title is one line), ends are trimmed.
     */
    fun titleToSave(typed: String, saved: String): String? {
        val t = typed.replace('\n', ' ').trim()
        return if (t.isEmpty() || t == saved) null else t
    }

    /**
     * Whether the field should take on the saved title (a change from the other device, or a save landing). Not while
     * an edit here is waiting to be saved, and not when the saved title is just what's typed without its trailing
     * space: taking it then would eat the space before the next word ("Buy " saved as "Buy", then "milk" typed).
     */
    fun adoptSaved(typed: String, saved: String, dirty: Boolean): Boolean =
        !dirty && saved != typed.replace('\n', ' ').trim()

    /** What an add field adds when the screen closes with something typed, or null when it is blank. */
    fun pendingAdd(typed: String): String? = typed.trim().takeIf { it.isNotEmpty() }
}
