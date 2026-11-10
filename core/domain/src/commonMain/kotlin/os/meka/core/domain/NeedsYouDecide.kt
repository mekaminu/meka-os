package os.meka.core.domain

/**
 * The approved Needs you look's TO DECIDE cards (Meka 2026-11-09 22:33, `docs/design/needs-you/needs-you.dc.html`),
 * non-AI and pure: a request from someone Meka watches as one compact card: an icon for its kind, what it is
 * ("Work from home · Wed 16 Dec", "Task: Check the algorithm · Today"), who it's from with their words on one line
 * ("Your wife · “the Christmas show is that day”"), then Add · Change · No. The taps are the request card's own
 * (Add, Change, Not a task); nothing here sends or adds anything.
 */
enum class NeedsYouDecideIcon(
    /** A 24-unit stroke path, drawn the same on the Fold and the Mac. */
    val path: String,
) {
    HOME("M3 11l9-7 9 7v9H3zM9 20v-6h6v6"),
    TASK("M5 12l5 5 9-10"),
    EVENT("M4 6h16v14H4zM4 10h16M8 3v4M16 3v4"),
    REMINDER("M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21h4"),
    RENEWAL("M4 12a8 8 0 0 1 14-5l2 2M20 12a8 8 0 0 1-14 5l-2-2M20 4v5h-5M4 20v-5h5"),
    TRIP("M2 16l20-7-3-2-7 2-5-4-2 1 3 5-4 1-2-1-1 1z"),
    IDEA("M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10c1 1 1 2 1 3h6c0-1 0-2 1-3a6 6 0 0 0-4-10"),
    REPLY("M4 5h16v11H8l-4 4z"),
    /** A school day off on an office day. */
    SCHOOL("M2 9l10-5 10 5-10 5zM6 11v5c3 2.5 9 2.5 12 0v-5"),
    /** Date night. */
    NIGHT("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"),
    /** An appointment that has come due (dentist, eye test, haircut). */
    APPOINTMENT("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 7v5l3 2"),
    /** An approval to trade (slice 5). */
    TRADE("M3 17l6-6 4 4 8-8M15 7h6v6"),
    /** An approval to pay. */
    PAYMENT("M3 6h18v12H3zM3 10h18M7 15h4"),
    /** An approval to share a document. */
    SHARE("M6 3h8l4 4v14H6zM14 3v4h4M9 13h6M9 17h6"),
}

/** One TO DECIDE card in the new look. */
data class NeedsYouDecideCard(
    val key: String,
    val icon: NeedsYouDecideIcon,
    /** "Work from home · Wed 16 Dec", "Task: Check the algorithm · Today". */
    val what: String,
    /** "Your wife · “the Christmas show is that day”": who, then their words; one line, cut at the end. */
    val from: String,
    /** "Add". */
    val yesLabel: String,
    /** "Change", or null when there's nothing to edit (a work-from-home day, a renewal, a booking, an idea). */
    val changeLabel: String?,
    /** "No": the card's own decline (Not a task, Not needed) said in one word; [spoken] names it in full. */
    val noLabel: String,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
    /** A second, quieter row of taps under the three (an appointment's Call to book · Copy email); usually none. */
    val more: List<String> = emptyList(),
    /** Slice 5: an approval's detail lines (what would happen), shown whole under the one line: never cut away. */
    val details: List<String> = emptyList(),
    /** The one line is lit in the critical colour (an approval in its last 15 minutes). */
    val urgent: Boolean = false,
    /** A quiet note under the taps ("Approving needs your fingerprint"); null for none. */
    val note: String? = null,
)

object NeedsYouDecideRules {
    const val NO = "No"

    /** Whether a request shows as the compact card: a reply card keeps its whole draft, so it keeps the full card. */
    fun compact(card: RequestCard): Boolean = card.draft == null && card.proposal.kind != RequestKind.REPLY

    fun icon(kind: RequestKind): NeedsYouDecideIcon = when (kind) {
        RequestKind.TASK -> NeedsYouDecideIcon.TASK
        RequestKind.WORK_FROM_HOME -> NeedsYouDecideIcon.HOME
        RequestKind.EVENT -> NeedsYouDecideIcon.EVENT
        RequestKind.REMINDER -> NeedsYouDecideIcon.REMINDER
        RequestKind.RENEWAL -> NeedsYouDecideIcon.RENEWAL
        RequestKind.REPLY -> NeedsYouDecideIcon.REPLY
        RequestKind.BOOKING -> NeedsYouDecideIcon.TRIP
        RequestKind.IDEA -> NeedsYouDecideIcon.IDEA
    }

    /**
     * What the card decides, as a noun rather than a verb ("Add task: X" → "Task: X", "Add event: X" → "Event: X",
     * "Remind me: X" → "Reminder: X"); work from home, renewals, bookings and ideas read as they did.
     */
    fun what(card: RequestCard): String {
        val a = card.action
        val swaps = listOf("Add task: " to "Task: ", "Add event: " to "Event: ", "Remind me: " to "Reminder: ", "Add to Ideas: " to "Idea: ")
        swaps.forEach { (from, to) -> if (a.startsWith(from)) return to + a.removePrefix(from) }
        return a
    }

    /** "Your wife · “…”"; a card Meka shared himself says so ("You shared · “…”"). */
    fun from(card: RequestCard): String {
        val who = card.person.ifBlank { card.from.substringBefore(" · ").removePrefix("From ").trim() }
        return if (card.quote.isBlank()) who else "$who · ${card.quote}"
    }

    fun card(card: RequestCard): NeedsYouDecideCard {
        val what = what(card)
        return NeedsYouDecideCard(
            key = card.id,
            icon = icon(card.proposal.kind),
            what = what,
            from = from(card),
            yesLabel = card.addLabel,
            changeLabel = card.changeLabel,
            noLabel = NO,
            spoken = card.spoken + " " + listOfNotNull(card.addLabel, card.changeLabel, card.declineLabel).joinToString(", ") + ".",
        )
    }

    /**
     * Slice 4: the other TO DECIDE cards in the same compact form. The words are the cards' own (nothing new is said),
     * the line under "what" joins their when and their question: "INSET day · in 5 days · You're in the office that
     * day. Who's covering?". Their taps keep their own labels and do what they did.
     */
    fun school(card: SchoolCover): NeedsYouDecideCard = NeedsYouDecideCard(
        key = "school-cover-" + card.id,
        icon = NeedsYouDecideIcon.SCHOOL,
        what = card.title,
        from = joined(card.line, card.question),
        yesLabel = card.homeLabel,
        changeLabel = null,
        noLabel = card.coveredLabel,
        spoken = spoken(card.spoken, card.homeLabel, card.coveredLabel),
    )

    fun dateNight(card: DateNightNudge): NeedsYouDecideCard = NeedsYouDecideCard(
        key = "date-night-" + card.day,
        icon = NeedsYouDecideIcon.NIGHT,
        what = card.title,
        from = joined(card.line, card.question),
        yesLabel = card.bookedLabel,
        changeLabel = null,
        noLabel = card.skipLabel,
        spoken = spoken(card.spoken, card.bookedLabel, card.skipLabel),
    )

    /** "Dentist" over "Due Mon 2 Nov · every 6 months · Last went …"; Booked… · Went · Not yet, then Call to book · Copy email. */
    fun appointment(card: AppointmentNudge): NeedsYouDecideCard = NeedsYouDecideCard(
        key = "appointment-" + card.id,
        icon = NeedsYouDecideIcon.APPOINTMENT,
        what = card.title,
        from = joined(card.line, card.question),
        yesLabel = card.bookedLabel,
        changeLabel = card.wentLabel,
        noLabel = card.laterLabel,
        spoken = spoken(card.spoken, card.bookedLabel, card.wentLabel, card.laterLabel, card.callLabel, card.emailLabel),
        more = listOf(card.callLabel, card.emailLabel),
    )

    fun icon(kind: ApprovalKind): NeedsYouDecideIcon = when (kind) {
        ApprovalKind.TRADE -> NeedsYouDecideIcon.TRADE
        ApprovalKind.PAYMENT -> NeedsYouDecideIcon.PAYMENT
        ApprovalKind.SHARE -> NeedsYouDecideIcon.SHARE
    }

    /**
     * Slice 5: an approval (trade, pay, share) in the same compact form. Its title over "Trade · Kestrel · Asked 21:40 ·
     * expires in 14 min" (the label read in sentence case, lit when [ApprovalCard.urgent]); its detail lines stay whole
     * under it, as what would happen must never be cut; Approve · Decline (no Change: the one who asked sets the terms);
     * the fingerprint (or Touch ID) note under the taps. The taps are the approval's own: Approve still asks for the
     * fingerprint every time, and only that signed yes reaches the core.
     */
    fun approval(card: ApprovalCard, lock: String = ApprovalRules.LOCK_FOLD): NeedsYouDecideCard = NeedsYouDecideCard(
        key = "approval-" + card.id,
        icon = icon(card.kind),
        what = card.title,
        from = joined(sentence(card.label), card.why),
        yesLabel = card.approveLabel,
        changeLabel = null,
        noLabel = card.declineLabel,
        spoken = spoken(card.spoken, card.approveLabel + " with your fingerprint", card.declineLabel),
        details = card.lines.map { it.trim() }.filter { it.isNotEmpty() },
        urgent = card.urgent,
        note = lock,
    )

    /** "TRADE · KESTREL" → "Trade · Kestrel"; a label already in mixed case is left as it is. */
    fun sentence(label: String): String = if (label != label.uppercase()) label.trim() else
        label.split(" · ").joinToString(" · ") { part -> part.trim().lowercase().replaceFirstChar { it.uppercase() } }

    private fun joined(vararg parts: String): String = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

    private fun spoken(said: String, vararg labels: String): String {
        val s = said.trim()
        val stop = if (s.isEmpty() || s.last() in ".?!") "" else "."
        return (s + stop + " " + labels.joinToString(", ") + ".").trim()
    }
}
