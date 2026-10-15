package os.meka.core.domain

/**
 * What finishing a chat's card does in the messaging app (build plan V1, "Messages grouped per chat", slice 2), non-AI
 * and pure. The Fold carries it out through Android's official APIs only: the chat notification's own "Mark as read"
 * action (Notification.Action with SEMANTIC_ACTION_MARK_AS_READ) and NotificationListenerService.cancelNotification.
 * No WhatsApp library, and nothing is ever sent.
 *
 * Mark read happens only on Meka's tap: the chat card's "Mark read in WhatsApp", or Done with the off-by-default
 * "Mark read when I finish a card" (off because WhatsApp shows blue ticks when read receipts are on). Clearing the
 * chat's notifications from the shade only hides them: it never marks read on its own and never deletes anything.
 */
data class ChatFinish(
    /** Fire the notification's own Mark as read first (its action disappears with the notification). */
    val markRead: Boolean,
    /** Then clear the chat's notifications from the shade. */
    val clear: Boolean,
    /** Mark read was asked for but the notification is gone or offers no such action: open the chat instead. */
    val openChat: Boolean,
)

/** How Meka finished a chat's card. */
enum class ChatFinishTap {
    /** Done on the chat's card. */
    DONE,
    /** "Mark read in WhatsApp" on the chat's card. */
    MARK_READ,
    /** "Open WhatsApp to mark read" (no Mark as read on offer). */
    OPEN_TO_MARK,
}

/** How Meka finished a one-message card (slice 2b). */
enum class CardFinish { SENT, SEEN, NOT_NOW, OPENED }

object ChatShadeRules {
    const val SETTING_TITLE = "Mark read when I finish a card"
    const val SETTING_ON = "Done also marks the chat read in WhatsApp or Messages (people see blue ticks if read receipts are on)"
    const val SETTING_OFF = "Off · Done only clears the chat's notifications; WhatsApp still shows it unread"

    /** "Mark read in WhatsApp", "Mark read in Messages". */
    fun markReadLabel(app: CaptureApp?): String = "Mark read in ${TriageReplyRules.appName(app)}"

    /**
     * A one-message card's Mark read pill (slice 2b): only while its notification offers Mark as read (the card's own
     * Open chat already covers the rest), else null.
     */
    fun singleMarkButton(app: CaptureApp?, markable: Boolean): String? = if (markable) markReadLabel(app) else null

    /**
     * Whether finishing a one-message card the way [finish] says also clears its notification from the shade (slice 2b):
     * Send (after the reply went through the notification's own Reply) and Seen do; Not now keeps it there for later,
     * and Open chat leaves it to WhatsApp, which clears it once the chat is read.
     */
    fun clearsAfter(finish: CardFinish): Boolean = finish == CardFinish.SENT || finish == CardFinish.SEEN

    // --- Read to the end (slice 2b) ---

    /** How long the last message under Show all stays on screen before the chat counts as read to the end. */
    const val READ_DWELL_MS = 1_500L

    /**
     * Reading a chat to its last message under Show all clears its notifications from the shade (hidden only: it never
     * marks read and the card stays until Done). Not while a reply is waiting: clearing would take away the
     * notification's Reply that Send answers through.
     */
    fun readToEndClears(chat: TriageChat): Boolean = !chat.single && chat.replies == 0

    // --- Clear from the shade as soon as MEKA has it (slice 2b) ---

    const val ARRIVAL_ON = "Clear this chat from the shade as it comes"
    const val ARRIVAL_OFF = "Stop clearing this chat from the shade"
    const val ARRIVAL_SETTING_TITLE = "Cleared from the shade as they come"
    const val ARRIVAL_SETTING_LINE =
        "Navilife keeps every message and its card; WhatsApp still shows them unread. A message waiting for your reply stays in the shade until you send or finish it."

    /** The chat key of a captured message, the same as [TriageChatRules.key] gives its card. */
    fun key(item: CapturedItem): String {
        val who = ChatTitleRules.cleanOrNull(item.conversation)?.lowercase()?.let { "g:$it" } ?: "p:${People.key(item.personName)}"
        return "${item.app.name}|$who"
    }

    /**
     * Whether [chat] may have "Clear from the shade as soon as MEKA has it": never a one-to-one chat with someone on
     * the family or always-notify list (his wife's messages always stay in the shade).
     */
    fun canClearOnArrival(chat: TriageChat, lists: PeopleLists): Boolean =
        chat.isGroup || chat.cards.none { lists.isFamily(it.personKey) || lists.isAlwaysNotify(it.personKey) } &&
            !lists.isFamily(chat.title) && !lists.isAlwaysNotify(chat.title)

    /** Switches [chat]'s arrival clearing [on] or off. */
    fun setClearOnArrival(current: Map<String, String>, chat: TriageChat, on: Boolean): Map<String, String> =
        if (on) current + (chat.key to chat.title) else current - chat.key

    /** The undo bar's line after switching it. */
    fun arrivalLine(title: String, on: Boolean): String =
        if (on) "$title · cleared from the shade as it comes; Navilife keeps every message" else "$title · stays in the shade as usual"

    /** "Barça lads, Five-a-side": the Messages setting's line, or null when no chat has it. */
    fun arrivalSummary(settings: TriageSettings): String? =
        settings.clearOnArrival.values.sorted().takeIf { it.isNotEmpty() }?.joinToString(", ")

    /**
     * Whether a just-posted notification carrying [items] leaves the shade at once: every message is from a chat with
     * the setting on and MEKA already has each one ([had]: triaged or kept for the digest, so nothing is lost and no
     * retry needs WhatsApp's re-post); never when any message is from family or the always-notify list, or is urgent
     * (those alert). Hiding only: it never marks read and never deletes anything in WhatsApp.
     */
    fun clearsOnArrival(
        items: List<CapturedItem>, settings: TriageSettings, lists: PeopleLists, had: Set<String>,
        /**
         * The message ids with a reply card still waiting ([pendingReplies]): such a notification stays in the shade, so
         * Send keeps the notification's own Reply to answer through (WhatsApp replies, slice 1, item 3).
         */
        pending: Set<String> = emptySet(),
    ): Boolean =
        settings.clearOnArrival.isNotEmpty() && items.isNotEmpty() && items.none { it.id in pending } && items.all { item ->
            key(item) in settings.clearOnArrival && item.id in had && item.kind == CaptureKind.MESSAGE &&
                !lists.isFamily(item.personName) && !lists.isAlwaysNotify(item.personName) && Capture.breakThrough(item, lists) == null
        }

    /** The message ids whose Needs a reply card (with a drafted reply) is still open: their notifications aren't cleared. */
    fun pendingReplies(cards: List<TriageCard>): Set<String> =
        cards.filter { it.lane == TriageLane.NEEDS_REPLY && it.draft != null }.mapTo(HashSet()) { it.id }

    /** "Open WhatsApp to mark read": the notification has gone, so only the app can mark it. */
    fun openToMarkLabel(app: CaptureApp?): String = "Open ${TriageReplyRules.appName(app)} to mark read"

    /** The chat card's mark-read button: Mark read while a notification offers it, else Open … to mark read. */
    fun markButton(app: CaptureApp?, markable: Boolean): Pair<String, ChatFinishTap> =
        if (markable) markReadLabel(app) to ChatFinishTap.MARK_READ else openToMarkLabel(app) to ChatFinishTap.OPEN_TO_MARK

    /** True when any of the chat's messages still has a notification offering Mark as read. */
    fun markable(chat: TriageChat, markableIds: Set<String>): Boolean = chat.ids.any { it in markableIds }

    /**
     * What [tap] does: Done marks read only with [markReadOnDone] and only while [markable]; Mark read marks read while
     * [markable], else opens the chat; every finish clears the chat's notifications (after any Mark read) except
     * opening the chat, where WhatsApp clears them itself once read.
     */
    fun finish(tap: ChatFinishTap, markable: Boolean, markReadOnDone: Boolean): ChatFinish = when (tap) {
        ChatFinishTap.DONE -> ChatFinish(markRead = markReadOnDone && markable, clear = true, openChat = false)
        ChatFinishTap.MARK_READ ->
            if (markable) ChatFinish(markRead = true, clear = true, openChat = false)
            else ChatFinish(markRead = false, clear = false, openChat = true)
        ChatFinishTap.OPEN_TO_MARK -> ChatFinish(markRead = false, clear = false, openChat = true)
    }

    /**
     * The undo bar's line once finished: "Marked read in WhatsApp · Barça lads", "Opened WhatsApp · mark Barça lads
     * read there", else [TriageChatRules.doneLine].
     */
    fun line(chat: TriageChat, finish: ChatFinish, marked: Boolean): String = when {
        finish.markRead && marked -> "Marked read in ${TriageReplyRules.appName(chat.app)} · ${chat.title}"
        finish.openChat -> "Opened ${TriageReplyRules.appName(chat.app)} · mark ${chat.title} read there"
        else -> TriageChatRules.doneLine(chat)
    }
}
