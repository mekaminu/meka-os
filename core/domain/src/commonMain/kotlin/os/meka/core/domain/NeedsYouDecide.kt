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
}
