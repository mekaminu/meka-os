package os.meka.core.domain

/**
 * Work mode → Messages as rows opening sheets (walk-through week 5, A17: no open chip rows or pills on a page). Each
 * person's "Keep from AI" pill, each group's Digest · Normal · Ignore chip row and its pill, and the "Mark read when I
 * finish a card" On/Off pill are each one card row saying what's chosen ("Tunde" · "Sent to AI" ›, "Football parents" ·
 * "Digest" ›, "Navilife's AI" · "Kept from it" ›, "Mark read when I finish a card" · "Off" ›) opening a sheet of the
 * choices, the one in use lit, each saying what it does; a choice writes what the chip wrote and closes the sheet.
 *
 * A chat's Stop under "Cleared from the shade as they come" stays a button: it is an action, not a choice. These lists
 * stay on the Fold (sealed, never synced) and the Mac shows the explanation only, so there is nothing to change there.
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure.
 */
object MessagesChoiceRules {
    const val SENT = "sent"
    const val KEPT = "kept"
    const val SENT_LABEL = "Sent to AI"
    const val KEPT_LABEL = "Kept from AI"
    const val AI_TITLE = "Navilife's AI"
    const val GROUP_AI_SENT = "Reads it"
    const val GROUP_AI_KEPT = "Kept from it"
    const val ON = "on"
    const val OFF = "off"
    const val ON_LABEL = "On"
    const val OFF_LABEL = "Off"
    const val ON_PHONE_NOTE = "Stays on this phone, never synced. Nothing is ever sent without your tap."
    const val MODE_NOTE = "Stays on this phone, never synced. You can also switch it on the group's digest card."
    const val MARK_READ_NOTE = "Only this phone. Nothing is ever sent without your tap."

    // ---- A person: one row, their name · whether their messages go to the AI ----

    /** A person's row: "Tunde" · "Sent to AI" / "Kept from AI". */
    fun personRow(row: MessagesSetupRow): NotifyChoiceRow {
        val value = if (row.private) KEPT_LABEL else SENT_LABEL
        return NotifyChoiceRow(row.name, value, "${row.name}, $value. Opens the choices")
    }

    /** A person's sheet: Sent to AI (a lane and a draft) or Kept from AI (FYI, nothing drafted), the one in force lit. */
    fun personSheet(row: MessagesSetupRow): NotifyChoiceSheet = NotifyChoiceSheet(row.name, ON_PHONE_NOTE, listOf(
        choice(SENT, SENT_LABEL, "Each message goes to Navilife's AI for a lane and a draft reply", !row.private),
        choice(KEPT, KEPT_LABEL, "Still shown in Needs you, as FYI with nothing drafted", row.private),
    ))

    /** True to keep from the AI, false to send to it; null when [name] isn't a choice or is already in force. */
    fun privateNamed(row: MessagesSetupRow, name: String): Boolean? = when (name) {
        KEPT -> true
        SENT -> false
        else -> null
    }?.takeIf { it != row.private }

    // ---- A group: its name · its mode, and under it Navilife's AI · Reads it / Kept from it ----

    /** A group's row: "Football parents" · "Digest". */
    fun groupRow(row: MessagesSetupRow): NotifyChoiceRow {
        val value = (row.mode ?: GroupMode.DIGEST).label
        return NotifyChoiceRow(row.name, value, "${row.name}, $value. Opens the choices")
    }

    /** A group's mode sheet: Digest · Normal · Ignore, each saying what it does, the one in force lit. */
    fun groupSheet(row: MessagesSetupRow): NotifyChoiceSheet {
        val mode = row.mode ?: GroupMode.DIGEST
        return NotifyChoiceSheet(row.name, MODE_NOTE, GroupMode.entries.map { m ->
            choice(m.name, m.label, GroupDigestRules.modeHint(m), m == mode)
        })
    }

    /** The mode a group choice picks, or null when it isn't one or is already in force. */
    fun modeNamed(row: MessagesSetupRow, name: String): GroupMode? =
        GroupMode.entries.firstOrNull { it.name == name }?.takeIf { it != (row.mode ?: GroupMode.DIGEST) }

    /** A group's AI row, under its mode row: "Navilife's AI" · "Reads it" / "Kept from it". */
    fun groupAiRow(row: MessagesSetupRow): NotifyChoiceRow {
        val value = if (row.private) GROUP_AI_KEPT else GROUP_AI_SENT
        return NotifyChoiceRow(AI_TITLE, value, "$AI_TITLE for ${row.name}, $value. Opens the choices")
    }

    /** A group's AI sheet: the digest's gist from the AI, or the digest with no gist. */
    fun groupAiSheet(row: MessagesSetupRow): NotifyChoiceSheet = NotifyChoiceSheet("$AI_TITLE · ${row.name}", ON_PHONE_NOTE, listOf(
        choice(SENT, GROUP_AI_SENT, "Its digest gets a gist from Navilife's AI, one call per digest", !row.private),
        choice(KEPT, GROUP_AI_KEPT, "Its digest still comes, with no gist; nothing goes to the AI", row.private),
    ))

    // ---- Mark read when I finish a card ----

    /** "Mark read when I finish a card" · "On" / "Off". */
    fun markReadRow(on: Boolean): NotifyChoiceRow {
        val value = if (on) ON_LABEL else OFF_LABEL
        return NotifyChoiceRow(ChatShadeRules.SETTING_TITLE, value, "${ChatShadeRules.SETTING_TITLE}, $value. Opens the choices")
    }

    /** On marks the chat read (blue ticks), Off only clears its notifications. */
    fun markReadSheet(on: Boolean): NotifyChoiceSheet = NotifyChoiceSheet(ChatShadeRules.SETTING_TITLE, MARK_READ_NOTE, listOf(
        choice(ON, ON_LABEL, ChatShadeRules.SETTING_ON, on),
        choice(OFF, OFF_LABEL, ChatShadeRules.SETTING_OFF.removePrefix("Off · ").replaceFirstChar { it.uppercase() }, !on),
    ))

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun markReadNamed(on: Boolean, name: String): Boolean? = when (name) {
        ON -> true
        OFF -> false
        else -> null
    }?.takeIf { it != on }

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
