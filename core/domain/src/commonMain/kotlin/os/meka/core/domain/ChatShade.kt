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

object ChatShadeRules {
    const val SETTING_TITLE = "Mark read when I finish a card"
    const val SETTING_ON = "Done also marks the chat read in WhatsApp or Messages (people see blue ticks if read receipts are on)"
    const val SETTING_OFF = "Off · Done only clears the chat's notifications; WhatsApp still shows it unread"

    /** "Mark read in WhatsApp", "Mark read in Messages". */
    fun markReadLabel(app: CaptureApp?): String = "Mark read in ${TriageReplyRules.appName(app)}"

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
