package os.meka.core.domain

/**
 * How a triaged message reads as a Needs you card on the Mac (Mac presentation pass, item 6; Meka, 2026-10-15 13:48),
 * non-AI and pure: one strong line (who · what they need), the rest muted, the sender's number written the way people
 * write it, and a likely-scam text kept out of Needs you (shown greyed under LIKELY SCAMS with Delete and Block).
 *
 * Only ever lowers a message's place (ADR-006: untrusted content never raises its own standing): a text whose gist,
 * written by Navilife's AI, calls it a scam, or a stranger's number whose gist uses a scam phrase ([MailJunkRules]),
 * is set aside; a group message, one someone Meka watches for asked, or a named sender's message without the AI's
 * verdict stays a normal card.
 */
object NeedsYouCardRules {
    const val SCAM_CHIP = "⚠ Likely scam"
    const val SCAM_HEADING = "LIKELY SCAMS"
    const val DELETE = "Delete"
    const val BLOCK = "Block number"
    const val SCAM_HINT = "Kept out of Needs you. Don't tap links in it; Delete clears it on both devices, Block stops calls from the number."

    /** Words the AI's gist uses when it judges a message a scam (lower case). */
    private val VERDICT_WORDS = listOf("scam", "phishing", "fraudulent", "fraud attempt", "smishing", "spoofed", "impersonat")

    /** The sender as written: a number spaced the UK way ("07480 538349"), a name as it came. */
    fun who(person: String): String {
        val t = person.trim()
        return if (BlockedCallerRules.keyOf(t) != null) BlockedCallerRules.display(t) else t
    }

    /** The sender part of [TriageCard.from] ("Tunde", "07480 538349"); for a group message the person before " in ". */
    fun sender(card: TriageCard): String {
        val head = card.from.substringBeforeLast(" · ")
        return if (card.group != null) head.substringBefore(" in ") else head
    }

    /** The number to block for [card]'s sender, or null when the sender is a name or a short code. */
    fun number(card: TriageCard): String? = sender(card).takeIf { BlockedCallerRules.keyOf(it) != null }

    /** Whether [card] reads as a scam, so Needs you leaves it out. */
    fun likelyScam(card: TriageCard): Boolean {
        if (card.group != null || card.askedBy != null || card.mentioned) return false
        val gist = card.gist.lowercase()
        if (card.gist == TriageCard.LOCAL_LINE || card.gist == TriageCard.NO_GIST_LINE) return false
        if (VERDICT_WORDS.any { it in gist } && "not a scam" !in gist && "isn't a scam" !in gist) return true
        // A stranger's number using a scam phrase ("parcel held", "verify your account").
        return number(card) != null && MailJunkRules.scamSignal(null, null, card.gist) != null
    }

    /** The cards Needs you shows: every card that isn't a likely scam, in their order. */
    fun shown(cards: List<TriageCard>): List<TriageCard> = cards.filter { !likelyScam(it) }

    /** The likely scams, newest first, shown greyed after the other cards. */
    fun scams(cards: List<TriageCard>): List<TriageCard> = cards.filter { likelyScam(it) }.sortedByDescending { it.atMs }

    /** The card's one strong line: "Tunde · Asks if you're coming Saturday" (who · what they need). */
    fun headline(card: TriageCard): String {
        val who = sender(card).ifBlank { card.from }
        val what = card.gist.trim().trimEnd('.')
        return if (what.isEmpty()) who else "$who · $what"
    }

    /** The muted line under it: "14:02 · Needs a reply · WhatsApp"; a group's name first ("in Barça lads · 14:02 · FYI"). */
    fun meta(card: TriageCard): String = buildList {
        card.group?.let { add("in $it") }
        add(card.from.substringAfterLast(" · "))
        add(card.lane.label)
        card.app?.let { add(it.label) }
    }.joinToString(" · ")

    /** "LIKELY SCAMS · 2". */
    fun scamHeading(count: Int): String = "$SCAM_HEADING · $count"

    /** The undo bar after Delete: "Deleted the likely scam from 07480 538349". */
    fun deletedLine(card: TriageCard): String = "Deleted the likely scam from ${sender(card)}"
}
