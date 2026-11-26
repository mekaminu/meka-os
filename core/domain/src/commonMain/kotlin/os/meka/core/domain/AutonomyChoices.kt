package os.meka.core.domain

/**
 * Settings → What Navilife may do without buttons on its rows (walk-through week 5, A17: no open pills on a page). The
 * kill switch was a card with a Pause / Resume pill; it is now one card row ("Pause everything Navilife does on its
 * own" · "Off" ›) opening Off · On, each saying what it does. Each row you can change (Add what you ask for, each
 * earned-trust kind with Ask first or Let Navilife offer again, Inbox autopilot, Renewals and promises, the school's
 * dates) keeps its dot, title and line, and opens a sheet of its two choices, the one in force lit, each saying what it
 * does; choosing the other sends the row's key to the facade's `resetTrust`, exactly what its pill did. Locked rows open
 * nothing. Reuses [NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]. Pure; nothing new stored.
 */
object AutonomyChoiceRules {
    /** The lit choice's name (what's in force). */
    const val KEEP = "keep"
    /** The other choice's name: choosing it does what the row's pill did. */
    const val CHANGE = "change"

    const val PAUSE_NOTE = "Syncs with your other devices. While paused, Today says so with Resume."
    const val PAUSE_OFF_LINE = "Navilife does what you've trusted it with, each with Undo and in Activity"
    const val ROW_NOTE = "Syncs with your other devices. Everything Navilife does on its own shows with Undo and in Activity."
    const val PAUSED_NOTE = "Paused now, so everything asks first until you resume; this choice is kept for then."

    const val DOES_IT = "Does it for you"
    const val KEEP_ASKING = "Keep asking"
    const val DOES_IT_LINE = "Navilife does it on its own, each with Undo; undoing one puts it back to asking"
    const val ASK_FIRST_LINE = "Back to a card you tap · Navilife offers again after ${TrustRules.STREAK} yeses in a row"
    const val KEEP_ASKING_LINE = "A card you tap each time · Navilife won't offer to do it on its own"
    const val OFFER_AGAIN_LINE = "Still a card you tap · Navilife offers after ${TrustRules.STREAK} yeses in a row"
    const val DIRECT_OFF_LINE = "A card you tap for each add you ask for"

    // ---- The kill switch ----

    /** Its row: "Pause everything Navilife does on its own" · "Off" (or "On" while paused). */
    fun pauseRow(paused: Boolean): NotifyChoiceRow {
        val value = if (paused) CallChoiceRules.ON_LABEL else CallChoiceRules.OFF_LABEL
        return NotifyChoiceRow(AutonomyRules.PAUSE_TITLE, value, "${AutonomyRules.PAUSE_TITLE}, $value. Opens the choices")
    }

    /** Its sheet: Off lets trusted things run, On holds everything for a tap; the one in force lit. */
    fun pauseSheet(paused: Boolean): NotifyChoiceSheet = NotifyChoiceSheet(
        AutonomyRules.PAUSE_TITLE, PAUSE_NOTE,
        listOf(
            choice(CallChoiceRules.OFF, CallChoiceRules.OFF_LABEL, PAUSE_OFF_LINE, !paused),
            choice(CallChoiceRules.ON, CallChoiceRules.ON_LABEL, AutonomyRules.PAUSE_LINE, paused),
        ),
    )

    /** True to pause, false to resume; null when [name] isn't a choice or is already in force. */
    fun pauseNamed(paused: Boolean, name: String): Boolean? = CallChoiceRules.named(paused, name)

    // ---- A row's two choices ----

    /** True when [row] opens a sheet (it can be changed); locked rows and counting rows open nothing. */
    fun opens(row: AutonomyRow): Boolean = !row.locked && row.action != null

    /** What a row says it does to a screen reader, with that it opens its choices when it does. */
    fun spoken(row: AutonomyRow): String =
        row.title + ". " + row.line + when {
            row.locked -> ". Locked"
            opens(row) -> ". Opens the choices"
            else -> ""
        }

    /**
     * The sheet for [row]: its two choices, the more hands-off one first, the one in force lit, each saying what it
     * does; null when the row opens nothing.
     */
    fun sheet(row: AutonomyRow, paused: Boolean): NotifyChoiceSheet? {
        val action = row.action ?: return null
        if (row.locked) return null
        val note = if (paused) PAUSED_NOTE else ROW_NOTE
        val choices = when (row.key) {
            AutonomyRules.MAIL_AUTOPILOT -> onOff(action, AutonomyRules.MAIL_ON_LINE, AutonomyRules.MAIL_OFF_LINE)
            MailFindAutoRules.ENTITY_ID -> onOff(action, MailFindAutoRules.ON_LINE, MailFindAutoRules.OFF_LINE)
            SchoolAutoRules.ENTITY_ID -> onOff(action, SchoolAutoRules.ON_LINE, SchoolAutoRules.OFF_LINE)
            DirectAddRules.ENTITY_ID -> {
                val on = action == AutonomyRules.ASK_FIRST
                listOf(
                    choice(if (on) KEEP else CHANGE, DirectAddRules.JUST_ADD, DirectAddRules.ON_LINE, on),
                    choice(if (on) CHANGE else KEEP, AutonomyRules.ASK_FIRST, DIRECT_OFF_LINE, !on),
                )
            }
            else -> when (action) {
                AutonomyRules.ASK_FIRST -> listOf(
                    choice(KEEP, DOES_IT, DOES_IT_LINE, true),
                    choice(CHANGE, AutonomyRules.ASK_FIRST, ASK_FIRST_LINE, false),
                )
                AutonomyRules.OFFER_AGAIN -> listOf(
                    choice(CHANGE, AutonomyRules.OFFER_AGAIN, OFFER_AGAIN_LINE, false),
                    choice(KEEP, KEEP_ASKING, KEEP_ASKING_LINE, true),
                )
                else -> return null
            }
        }
        return NotifyChoiceSheet(row.title, note, choices)
    }

    /** True when choosing [name] should send the row's key to `resetTrust` (the other choice, not the one in force). */
    fun changes(name: String): Boolean = name == CHANGE

    /** An On · Off pair for a switch row whose pill read Turn off (on) or Turn on (off). */
    private fun onOff(action: String, onLine: String, offLine: String): List<NotifyChoice> {
        val on = action == MailAutopilotPanelRules.TURN_OFF
        return listOf(
            choice(if (on) KEEP else CHANGE, CallChoiceRules.ON_LABEL, onLine, on),
            choice(if (on) CHANGE else KEEP, CallChoiceRules.OFF_LABEL, plain(offLine), !on),
        )
    }

    /** "Off · new mail stays where it lands" → "New mail stays where it lands": the choice already says Off. */
    private fun plain(line: String): String = line.substringAfter(" · ").replaceFirstChar { it.uppercase() }

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
