package os.meka.core.domain

/** What a triage card's first button does on this device. */
enum class TriageCardPrimary {
    /** Send the drafted reply through the message's own notification Reply action (the Fold, while it is live). */
    SEND,
    /** Open the chat (the drafted reply copied first, when there is one); Meka taps send in the app. */
    OPEN_CHAT,
    /** Copy the drafted reply (the Mac never sends). */
    COPY,
    /** An FYI read. */
    SEEN,
}

/** "Send all 3": the easy replies that can go out together, each still only on Meka's tap of this one button. */
data class TriageSendAll(val ids: List<String>, val label: String, val spoken: String)

/**
 * The messages assistant's Needs you cards (build plan V1, "Messages assistant", slice 3), non-AI and pure: which button
 * leads, what an edited reply may be, which replies are easy enough to send together, and the lines the undo bar shows.
 *
 * Personal messages stay at "ask me, then send" (autonomy Level 3, hard constraint): nothing here sends anything; a reply
 * goes out only from Meka's tap of Send (or Send all) on the Fold, through Android's own notification Reply action.
 */
object TriageReplyRules {
    const val SEND = "Send"
    const val OPEN_CHAT = "Open chat"
    const val COPY = "Copy reply"
    const val EDIT = "Edit"
    const val DONE_EDITING = "Done"
    const val NOT_NOW = "Not now"
    const val SEEN = "Seen"
    const val REPLY_HINT = "Your reply"

    /** An edited reply is Meka's own words: kept whole up to this length (WhatsApp's own limit is far above it). */
    const val MAX_REPLY = 2_000

    /** A reply this short, one paragraph, in a 1:1 chat, may go out with the others under "Send all". */
    const val MAX_EASY = 100

    /** The first button of [card]: Send while the message's Reply action is live on this phone ([live]), else Open chat. */
    fun primary(card: TriageCard, live: Boolean, canSend: Boolean = true): TriageCardPrimary = when {
        card.lane != TriageLane.NEEDS_REPLY -> TriageCardPrimary.SEEN
        !canSend -> if (card.draft != null) TriageCardPrimary.COPY else TriageCardPrimary.SEEN
        live && card.draft != null -> TriageCardPrimary.SEND
        else -> TriageCardPrimary.OPEN_CHAT
    }

    fun label(primary: TriageCardPrimary): String = when (primary) {
        TriageCardPrimary.SEND -> SEND
        TriageCardPrimary.OPEN_CHAT -> OPEN_CHAT
        TriageCardPrimary.COPY -> COPY
        TriageCardPrimary.SEEN -> SEEN
    }

    /** The second, quiet button: Not now for a reply, nothing more for an FYI (Seen already leads). */
    fun secondary(card: TriageCard): String? = if (card.lane == TriageLane.NEEDS_REPLY) NOT_NOW else null

    /** Whether Edit shows: a reply with a draft that this device can send. */
    fun editable(card: TriageCard, live: Boolean): Boolean = card.lane == TriageLane.NEEDS_REPLY && card.draft != null && live

    /**
     * Meka's edited reply as it will be sent: trimmed, line ends kept, at most [MAX_REPLY] characters; null when blank
     * (Send then does nothing). Unlike the model's draft it isn't screened for links or numbers: these are his words.
     */
    fun cleanReply(text: String?): String? {
        val t = text?.replace("\r\n", "\n")?.trim() ?: return null
        if (t.isEmpty()) return null
        return t.take(MAX_REPLY).trimEnd()
    }

    /** Whether [card]'s reply may go with "Send all": a short one-paragraph draft to one person (never into a group). */
    fun easy(card: TriageCard): Boolean {
        val d = card.draft ?: return false
        return card.lane == TriageLane.NEEDS_REPLY && !card.mentioned && d.length <= MAX_EASY && '\n' !in d
    }

    /**
     * "Send all 3" over the easy replies whose Reply action is live ([live] holds those message ids), oldest first so
     * the chats get them in the order they wrote; null with fewer than two (one card has its own Send).
     */
    fun sendAll(cards: List<TriageCard>, live: Set<String>): TriageSendAll? {
        val ready = cards.filter { easy(it) && it.id in live }.sortedBy { it.atMs }
        if (ready.size < 2) return null
        val names = ready.map { it.from.substringBefore(" · ") }.distinct()
        val who = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        return TriageSendAll(ready.map { it.id }, "Send all ${ready.size}", "Send the ${ready.size} short replies to $who")
    }

    /** Who [card] is from, for the undo bar ("Tunde", "Femi in Barça lads"). */
    fun who(card: TriageCard): String = card.from.substringBefore(" · ")

    fun sentLine(card: TriageCard): String = "Sent to ${who(card)}"
    fun sentAllLine(n: Int): String = if (n == 1) "Sent 1 reply" else "Sent $n replies"
    fun notNowLine(card: TriageCard): String = "$NOT_NOW · ${who(card)}"
    fun seenLine(card: TriageCard): String = "$SEEN · ${who(card)}"

    /** Open chat: the draft copied first so Meka only pastes and taps send. */
    fun openChatLine(card: TriageCard, app: String): String =
        if (card.draft != null) "Reply copied · paste it in $app" else "Opening $app"

    fun copiedLine(card: TriageCard): String = "Reply to ${who(card)} copied"

    /** The send failed (the notification went, or the app refused): the reply is copied and the chat opens instead. */
    const val SEND_FAILED_LINE = "Couldn't send from here · reply copied, opening the chat"

    /** The app's name as Meka knows it. */
    fun appName(app: CaptureApp?): String = when (app) {
        CaptureApp.SMS -> "Messages"
        else -> "WhatsApp"
    }
}
