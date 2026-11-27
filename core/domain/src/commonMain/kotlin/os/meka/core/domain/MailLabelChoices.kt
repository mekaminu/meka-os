package os.meka.core.domain

/**
 * A connected mail account's label as a row opening a sheet (walk-through week 5, A17: no open chip rows on a page; each
 * account card on Mail had an open "Personal · Business · Outlook" chip row beside Disconnect). The row is "Label" ·
 * "Personal" ›; the sheet lists the three, the one in use lit, each saying what being that label does: Business is the
 * account whose mail waits during protected time ([ProtectedMailRules]). Reuses
 * Notifications' row and sheet shapes. Pure; nothing stored.
 */
object MailLabelChoiceRules {
    const val TITLE = "Label"
    const val SHEET_TITLE = "What this account is for"
    const val NOTE = "The name Navilife shows beside this account's mail on Today and Needs you. Synced, so the Mac has the same."

    /** "Label" · "Personal". */
    fun row(label: MailLabel): NotifyChoiceRow =
        NotifyChoiceRow(TITLE, label.label, "Label, ${label.label}. Opens the choices")

    /** Each label, what it does, the one in use lit. */
    fun sheet(label: MailLabel): NotifyChoiceSheet = NotifyChoiceSheet(SHEET_TITLE, NOTE, MailAccountRules.LABELS.map { l ->
        val lit = l == label
        val line = line(l)
        NotifyChoice(l.name, l.label, line, lit, "${l.label}, $line" + if (lit) ", chosen" else "")
    })

    /** What being [l] does. */
    fun line(l: MailLabel): String = when (l) {
        MailLabel.PERSONAL -> "Your own mail · shows as it comes, even in protected time"
        MailLabel.BUSINESS -> "Work mail · Protected time can hold it till morning"
        MailLabel.OUTLOOK -> "Your Outlook mail · shows as it comes, even in protected time"
    }

    /** The label a choice picks, or null when it isn't one or is the one in use. */
    fun named(label: MailLabel, name: String): MailLabel? =
        MailLabel.entries.firstOrNull { it.name == name && it != label }
}
