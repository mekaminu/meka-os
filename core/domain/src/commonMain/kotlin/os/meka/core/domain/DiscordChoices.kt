package os.meka.core.domain

/**
 * Work mode → Discord as rows opening sheets (walk-through week 5, A17: no open chip rows or pills on a page). Each
 * channel's Follow / Following pill, each "Alert me straight away" chip with × and each "Posting lately" "+ Sam" chip is
 * one card row saying what's chosen ("#signals · Example Traders" · "Following" ›, "Sam" · "Alerts at once" ›, "“stop”"
 * · "Alerts at once" ›, and under Posting lately "Riley" · "Waits on the card" ›) opening a sheet of the two choices, the
 * one in use lit, each saying what it does; a choice writes what the chip wrote and closes the sheet.
 *
 * Add a person and Add a word stay links: their field only unfolds when asked for. Synced, so the Mac's menus match.
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure.
 */
object DiscordChoiceRules {
    const val FOLLOW = "follow"
    const val STOP = "stop"
    const val FOLLOWING_LABEL = "Following"
    const val NOT_FOLLOWED_LABEL = "Not followed"
    const val ALERT = "alert"
    const val WAIT = "wait"
    const val ALERT_LABEL = "Alerts at once"
    const val WAIT_LABEL = "Waits on the card"
    const val CHANNEL_NOTE = "Synced with the Mac. Nothing is posted to Discord or acted on."
    const val ALERT_NOTE = "Synced with the Mac. Alerts are information only; nothing is posted to Discord."
    private const val FOLLOWING_PREFIX = "Following · "

    // ---- A channel: its label · whether he follows it ----

    /** A channel's row: "#signals · Example Traders" · "Following" / "Not followed". */
    fun channelRow(row: DiscordChannelRow): NotifyChoiceRow {
        val value = if (row.watched) FOLLOWING_LABEL else NOT_FOLLOWED_LABEL
        return NotifyChoiceRow(row.label, value, "${row.label}, $value. Opens the choices")
    }

    /** The line under a channel's row, without the "Following · " the row's value already says: "last post 14:05". */
    fun channelLine(row: DiscordChannelRow): String = row.line.removePrefix(FOLLOWING_PREFIX).replaceFirstChar { it.uppercase() }

    /** A channel's sheet: Following (its posts come to Needs you) or Not followed (dropped as they arrive). */
    fun channelSheet(row: DiscordChannelRow): NotifyChoiceSheet = NotifyChoiceSheet(row.label, CHANNEL_NOTE, listOf(
        choice(FOLLOW, FOLLOWING_LABEL, "Its posts come to Needs you on the channel's card", row.watched),
        choice(STOP, NOT_FOLLOWED_LABEL, "Its posts are dropped as they arrive; nothing is kept", !row.watched),
    ))

    /** True to follow, false to stop; null when [name] isn't a choice or is already in force. */
    fun followNamed(row: DiscordChannelRow, name: String): Boolean? = onOff(name, FOLLOW, STOP)?.takeIf { it != row.watched }

    // ---- Alert me straight away: a person or a word · whether it alerts at once ----

    /** How a person or word is shown: "Sam", or a word in quotes, "“stop”". */
    fun entryLabel(text: String, person: Boolean): String = if (person) text else "“$text”"

    /** An alert row: "Sam" · "Alerts at once" (on the list), or under Posting lately "Riley" · "Waits on the card". */
    fun alertRow(text: String, person: Boolean, alerting: Boolean): NotifyChoiceRow {
        val label = entryLabel(text, person)
        val value = if (alerting) ALERT_LABEL else WAIT_LABEL
        val what = if (person) "Posts from $text" else "Posts saying $text"
        return NotifyChoiceRow(label, value, "$what, $value. Opens the choices")
    }

    /** An alert's sheet: Alerts at once, or Waits on the card (off the list), the one in force lit. */
    fun alertSheet(text: String, person: Boolean, alerting: Boolean): NotifyChoiceSheet {
        val what = if (person) "A post from $text" else "A post saying $text"
        return NotifyChoiceSheet(entryLabel(text, person), ALERT_NOTE, listOf(
            choice(ALERT, ALERT_LABEL, "$what in a channel you follow alerts on the phone straight away", alerting),
            choice(WAIT, WAIT_LABEL, "$what waits on the channel's card in Needs you, like the rest", !alerting),
        ))
    }

    /** True to add to the list, false to take off it; null when [name] isn't a choice or is already in force. */
    fun alertNamed(alerting: Boolean, name: String): Boolean? = onOff(name, ALERT, WAIT)?.takeIf { it != alerting }

    private fun onOff(name: String, on: String, off: String): Boolean? = when (name) {
        on -> true
        off -> false
        else -> null
    }

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
