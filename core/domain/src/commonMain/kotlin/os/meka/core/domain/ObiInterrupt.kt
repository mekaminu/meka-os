package os.meka.core.domain

// ---- Obi slice 4: the inbox + interrupt governor (non-AI, pure) ----

/**
 * What a card from a background job asks of Meka (Obi slice 4, Meka 2026-10-22/23: "every background job and agent
 * posts a card (notify · ask · approve) instead of talking"). Every [Notice] is one of these; the label is shown on the
 * notification beside Navilife's name on the Fold and as its subtitle on the Mac.
 */
enum class ObiCardKind(val label: String) {
    /** Something to know; nothing to answer. */
    NOTIFY("To know"),
    /** A question with its answers on the card (Went · Didn't go, Wake earlier, Later tonight). */
    ASK("Asks you"),
    /** Nothing happens without his yes: an approval (fingerprint in Needs you) or words sent only on his tap. */
    APPROVE("Needs your yes"),
}

/**
 * The interrupt governor (Obi slice 4). Background work never talks: it posts a card ([kindOf]) through the
 * notification governor, and Obi says something out loud only when [SpeakUpRules.checkMoment] lets him, which now holds
 * for focus too ([inFocus], or the phone's Do Not Disturb). Money and family that can't wait ([breaksThrough]) are said
 * even in focus, quiet hours or a meeting, and don't count against the day's cap; nothing is ever said out of the
 * phone's speaker, on a call, over a conversation or while he's muted Obi.
 */
object ObiInterruptRules {
    /** The moments that break through focus, quiet hours, meetings and the cap. */
    val BREAKS_THROUGH: Set<SpeakMoment> = setOf(SpeakMoment.URGENT, SpeakMoment.MONEY)

    /** Under those moments' switches in Speaking up. */
    const val BREAKS_THROUGH_LINE = "Said even in focus, quiet hours or a meeting"

    /** A calendar block counts as focus when its title is one of these, or starts with one ("Focus: report"). */
    val FOCUS_TITLES: List<String> = listOf("focus", "focus time", "deep work", "heads down", "do not disturb", "dnd", "no meetings")

    fun breaksThrough(m: SpeakMoment): Boolean = m in BREAKS_THROUGH

    /** A focus block is under way: a timed event now whose title says focus (Google Calendar's Focus time is one). */
    fun inFocus(events: List<CalendarEvent>, nowMs: Long): Boolean = events.any {
        !it.allDay && it.startAtMs <= nowMs && nowMs < it.endAtMs && isFocusTitle(it.title)
    }

    fun isFocusTitle(title: String): Boolean {
        val t = title.trim().lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex(" +"), " ").trim()
        if (t.isEmpty()) return false
        return FOCUS_TITLES.any { f -> t == f || t.startsWith("$f ") }
    }

    /** What [n] asks of him: an approval or a send-on-tap is Needs your yes; a card with answers Asks you; the rest To know. */
    fun kindOf(n: Notice): ObiCardKind = when {
        n.source == NoticeSource.APPROVAL || NoticeAction.SEND_MESSAGE in n.actions -> ObiCardKind.APPROVE
        n.tier == NoticeTier.ACTION || n.actions.any { it !in INFO_ACTIONS } -> ObiCardKind.ASK
        else -> ObiCardKind.NOTIFY
    }

    /** The notification's small line beside Navilife's name (Fold) or its subtitle (Mac). */
    fun cardLine(n: Notice): String = kindOf(n).label

    /** Buttons that only open or copy something: they don't make a card a question. */
    private val INFO_ACTIONS = setOf(NoticeAction.LISTEN, NoticeAction.PAY, NoticeAction.COPY_MESSAGE, NoticeAction.EDIT_MESSAGE)

    // ---- The money moments' words (said after his name; never an amount) ----

    /** "a payment approval from Kestrel runs out at 22:30. It's in Needs you." (payments and trades only). */
    fun approvalWords(kind: ApprovalKind, source: String, at: String): String? = when (kind) {
        ApprovalKind.PAYMENT, ApprovalKind.TRADE ->
            "a ${kind.label.lowercase()} approval from ${source.trim().take(40)} runs out at $at. It's in Needs you."
        else -> null
    }

    /** "your BTC long is very near liquidation. The figures are in Money." */
    fun liquidationWords(coin: String, long: Boolean, very: Boolean): String =
        "your ${coin.trim()} ${if (long) "long" else "short"} is ${if (very) "very " else ""}near liquidation. The figures are in Money."
}
