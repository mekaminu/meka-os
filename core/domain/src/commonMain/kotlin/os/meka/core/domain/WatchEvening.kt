package os.meka.core.domain

/**
 * Done for the day, completeness slice 2: the evening glance on the Galaxy Watch. Once the evening has started (work's
 * end, else 20:00) and the evening card is up, the wrist shows "DONE FOR THE DAY", "6 done · 2 left for tomorrow",
 * tomorrow's first commitment, and **Shut down** (and **Later** while it can be put off), the same taps as the phone's
 * pane. Once shut down it reads "DAY SHUT DOWN" with the day's count until midnight. Nothing when the card is off, on
 * annual leave or before the evening. Read from the watch's own copy of the day ([ShutdownView]); pure, nothing stored.
 */
data class WatchEvening(
    /** "DONE FOR THE DAY" · "DAY SHUT DOWN". */
    val label: String,
    /** The card is up and waiting for him: the accent. */
    val lit: Boolean,
    /** "6 done · 2 left for tomorrow" · "6 done · nothing left" · "Nothing ticked off today · some days are like that". */
    val line: String,
    /** "Tomorrow starts at 09:00 · Standup" · "Tomorrow: nothing planned yet"; or "Day shut down at 21:04" once done. */
    val note: String?,
    /** Shut down is offered (the card is up and not yet done). */
    val canShutDown: Boolean,
    /** Later is offered (it can still come back before 23:00). */
    val canLater: Boolean,
    val spoken: String,
)

object WatchEveningRules {
    const val LABEL = "DONE FOR THE DAY"
    const val LABEL_DONE = "DAY SHUT DOWN"
    const val SHUT_DOWN = "Shut down"
    const val LATER = ShutdownRules.LATER_LABEL
    /** What the row says after Shut down on the wrist (the pane's check, in words). */
    const val SHUT = "Day shut down · see you tomorrow"

    /** "6 done · 2 left for tomorrow" (the counts the pane shows), or the kind line when nothing was ticked off. */
    fun line(done: Int, left: Int): String {
        if (done == 0 && left == 0) return ShutdownRules.NOTHING_DONE
        val first = if (done == 0) "Nothing ticked off" else "$done done"
        val second = if (left == 0) "nothing left" else "$left left for tomorrow"
        return "$first · $second"
    }

    /** The glance, or null when the wrist shows nothing for the evening. */
    fun view(v: ShutdownView): WatchEvening? {
        if (!v.evening) return null
        val left = v.left.size
        val tomorrow = v.firstLine ?: v.tomorrow.glance
        if (v.doneToday) {
            val line = if (v.doneCount == 0) ShutdownRules.NOTHING_DONE else line(v.doneCount, left)
            return WatchEvening(
                label = LABEL_DONE, lit = false, line = line, note = v.doneLine ?: tomorrow,
                canShutDown = false, canLater = false,
                spoken = listOfNotNull(v.doneLine ?: "Day shut down", line, tomorrow).joinToString(". ") + ".",
            )
        }
        if (!v.offered || v.cardOff || v.onLeave) return null
        val line = line(v.doneCount, left)
        return WatchEvening(
            label = LABEL, lit = true, line = line, note = tomorrow,
            canShutDown = true, canLater = v.laterOffered,
            spoken = "Done for the day. $line. $tomorrow.",
        )
    }
}
