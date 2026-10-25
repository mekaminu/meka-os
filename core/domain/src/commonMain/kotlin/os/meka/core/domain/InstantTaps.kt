package os.meka.core.domain

/**
 * Taps feel instant (Meka 2026-10-25 01:45: "When I click on Done it takes at least 4 seconds, it should be
 * instantaneous"). A rule for every screen: a tap shows its result in the next frame and the work runs after. The screen
 * keeps a small set of the ids it has decided and hides them at once; the core's writes follow. Once the view no longer
 * holds an id (the write landed, here or on the other device), it is forgotten; if the work fails, the card comes back
 * with a line saying why.
 *
 * Also (4b, Meka 01:50: "clicked Approve and everything keeps coming back for me to approve again"): a decided card
 * never comes back while the view still holds it from before the write.
 */
object InstantTapRules {
    /** The bar's line when the work behind a tap failed and its card came back. */
    fun failedLine(what: String, reason: String?): String {
        val why = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_REASON)
        return if (why == null) "$what didn't go through · it's back" else "$what didn't go through · it's back: $why"
    }

    /** The ids decided on this screen, plus [ids]. */
    fun hide(hidden: Set<String>, ids: Collection<String>): Set<String> = hidden + ids

    /** The work failed: [ids] show again. */
    fun restore(hidden: Set<String>, ids: Collection<String>): Set<String> = hidden - ids.toSet()

    /**
     * The view changed: the decided ids it no longer holds are forgotten (their write landed), so the set never grows
     * and an id that comes back later (say a fresh card for the same plan) is shown. Ids the view still holds stay
     * hidden until the write reaches it.
     */
    fun settle(hidden: Set<String>, present: Collection<String>): Set<String> {
        if (hidden.isEmpty()) return hidden
        val still = present.toSet()
        return hidden.filterTo(LinkedHashSet()) { it in still }
    }

    /** The messages to show: those not decided here yet (the chats are grouped from what is left). */
    fun triage(cards: List<TriageCard>, hidden: Set<String>): List<TriageCard> =
        if (hidden.isEmpty()) cards else cards.filter { it.id !in hidden }

    /** The decision stack's cards to show (Approve on a clean-up, Done on a task…): those not decided here yet. */
    fun stack(cards: List<DecisionCard>, hidden: Set<String>): List<DecisionCard> =
        if (hidden.isEmpty()) cards else cards.filter { it.id !in hidden }

    /** Whether a chat's card is decided here (every one of its messages is hidden). */
    fun chatHidden(chat: TriageChat, hidden: Set<String>): Boolean = chat.ids.isNotEmpty() && chat.ids.all { it in hidden }

    const val MAX_REASON = 80
}
