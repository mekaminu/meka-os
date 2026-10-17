package os.meka.core.domain

/**
 * "The mic should just add it", slice 2 (Meka, 2026-10-17): what the capture sheet's 🎙 did with what he said. Either it
 * went straight in ([line] with Undo and Edit for [SpokenCaptureRules.SHOW_MS]), or, with **Ask first** on (or Navilife
 * paused, or nothing left to add once the day words are read), [fill] puts [words] in the field for his ↑ Add as before.
 */
data class SpokenCaptured(
    /** "Added “Buy milk”" · "Added “Call the garage” · Tomorrow" · "Added milk and eggs to shopping" · "Timer set · …". */
    val line: String,
    /** What he said, cleaned: Edit puts it back in the field. */
    val words: String,
    /** The Activity entry Undo puts back (a task or shopping), or null. */
    val activityId: String? = null,
    /** The quick alarm or timer Undo cancels, or null. */
    val alarmId: String? = null,
    /** Nothing was added: the words wait in the field for ↑ Add. */
    val fill: Boolean = false,
)

/** Pure rules for the capture sheet's spoken capture; unit-tested without a replica. */
object SpokenCaptureRules {
    /** The capture sheet's hint under an empty field. */
    const val HINT = "Tap the mic and what you say is added · hold it for a voice note"
    /** The mic's label for a screen reader. */
    const val MIC_LABEL = "Speak: what you say is added straight away. Hold for a voice note"
    const val UNDO = "Undo"
    const val EDIT = "Edit"
    const val UNDONE = "Taken back · say it again or type it"
    /** How long "Added …" stays with Undo and Edit before the sheet drops away. */
    const val SHOW_MS = 4_000L
    /** Longest text kept from one spoken capture (a line, not a note: a ramble is a voice note). */
    const val MAX_CHARS = 200

    /** What was heard, ready to add (spaces collapsed, a lone recogniser full stop dropped, first letter up), or null. */
    fun clean(heard: String?): String? {
        var t = heard?.replace(Regex("""\s+"""), " ")?.trim() ?: return null
        if (t.endsWith('.') && !t.endsWith("..")) t = t.dropLast(1).trimEnd()
        if (t.isEmpty()) return null
        if (t.length > MAX_CHARS) {
            val cut = t.take(MAX_CHARS)
            val space = cut.lastIndexOf(' ')
            t = (if (space >= MAX_CHARS / 2) cut.take(space) else cut).trimEnd()
        }
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * What [words] said into the capture sheet come to: a plain "add …" / "put … on the shopping" reads as Ask's direct
     * add does ([DirectAddRules.request]); anything else is a task in his own words, its day and time read from them
     * ("buy milk tomorrow" → Buy milk, tomorrow). Null when nothing would be left to add.
     */
    fun proposal(words: String, today: Long, nowMinute: Int): AskProposal? {
        val add = DirectAddRules.request(words) ?: DirectAdd(shopping = false, words = words)
        return DirectAddRules.proposal(add, today, nowMinute)
    }

    /** What a screen reader says once it's added: the line, then that Undo and Edit are there for a few seconds. */
    fun spoken(c: SpokenCaptured): String = "${c.line}. $UNDO or $EDIT for ${SHOW_MS / 1000} seconds."
}
