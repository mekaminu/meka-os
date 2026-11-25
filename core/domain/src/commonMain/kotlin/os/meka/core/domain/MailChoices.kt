package os.meka.core.domain

/**
 * Mail's three one-choice settings as rows opening a sheet (walk-through week 5, A17: no open chip rows on a page; the
 * walk-through's mail finding 8 named Meeting prep's "Off 5 10 15 30 min" row). Ten-minute inbox (Morning · Evening ·
 * Off), Meeting prep (Off · 5 · 10 · 15 · 30 min) and Warranty reminders (Off · £50 · £100 · £250 · £500) were each a
 * title, a line and an open chip row. Each is now one card row ("Meeting prep" · "15 min") opening Notifications' choice
 * sheet: the choices with a line on what each does, the one in use lit; a choice saves and closes it. Pure; nothing new
 * stored.
 */
object MailChoiceRules {
    const val NOTE = "Syncs with your other devices."

    // ---- Ten-minute inbox ----

    fun momentRow(t: MailMomentTime): NotifyChoiceRow = row(MailMomentRules.TITLE, t.label)

    fun momentLine(t: MailMomentTime): String = when (t) {
        MailMomentTime.OFF -> "No ten minutes offered; From your mail still lists what needs you"
        else -> "From ${LocalClock.formatMinute(t.minute!!)}, Needs you offers a few minutes to clear what needs you"
    }

    fun momentSheet(t: MailMomentTime): NotifyChoiceSheet = NotifyChoiceSheet(
        MailMomentRules.TITLE, NOTE,
        MailMomentTime.entries.map { choice(it.name, it.label, momentLine(it), it == t) },
    )

    fun moment(name: String): MailMomentTime? = MailMomentTime.entries.firstOrNull { it.name == name }

    // ---- Meeting prep ----

    fun prepRow(l: MeetingPrepLead): NotifyChoiceRow = row(MeetingPrepRules.TITLE, l.label)

    fun prepLine(l: MeetingPrepLead): String =
        if (l == MeetingPrepLead.OFF) "No prep on Today and no heads-up before meetings"
        else "${l.label} before a meeting with other people: who's coming, when you last met, your last emails and what's open"

    fun prepSheet(l: MeetingPrepLead): NotifyChoiceSheet = NotifyChoiceSheet(
        MeetingPrepRules.TITLE, NOTE,
        MeetingPrepLead.entries.map { choice(it.name, it.label, prepLine(it), it == l) },
    )

    fun prep(name: String): MeetingPrepLead? = MeetingPrepLead.entries.firstOrNull { it.name == name }

    // ---- Warranty reminders ----

    fun warrantyRow(w: MailWarrantyLevel): NotifyChoiceRow =
        row(MailWarrantyRules.TITLE, if (w == MailWarrantyLevel.OFF) w.label else "${w.label} or more")

    fun warrantyLine(w: MailWarrantyLevel): String =
        if (w == MailWarrantyLevel.OFF) "Receipts don't put warranty ends on Renewals; reminders already there stay"
        else "Orders of ${w.label} or more: Renewals reminds you a month before the warranty ends"

    fun warrantySheet(w: MailWarrantyLevel): NotifyChoiceSheet = NotifyChoiceSheet(
        MailWarrantyRules.TITLE, NOTE,
        MailWarrantyLevel.entries.map { choice(it.name, if (it == MailWarrantyLevel.OFF) it.label else "${it.label} or more", warrantyLine(it), it == w) },
    )

    fun warranty(name: String): MailWarrantyLevel? = MailWarrantyLevel.entries.firstOrNull { it.name == name }

    private fun row(title: String, value: String) = NotifyChoiceRow(title, value, "$title, $value. Opens the choices")

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
