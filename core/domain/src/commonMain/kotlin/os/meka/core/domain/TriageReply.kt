package os.meka.core.domain

/** What a triage card's first button does on this device. */
enum class TriageCardPrimary {
    /** Send the drafted reply through the message's own notification Reply action (the Fold, while it is live). */
    SEND,
    /**
     * On the Mac, while the Fold still holds the message's notification (WhatsApp replies, slice 2): ask the phone to
     * send the reply through that notification's own Reply. Only on Meka's tap; the phone answers Sent or why not.
     */
    SEND_VIA_PHONE,
    /** Open the chat (the drafted reply copied first, when there is one); Meka taps send in the app. */
    OPEN_CHAT,
    /**
     * Open WhatsApp's chat with the reply already typed (its official click-to-chat link); Meka taps send there. When
     * Send can't go from here: the notification has gone, or on the Mac. One-to-one chats with a known number only.
     */
    REPLY_IN_APP,
    /** Copy the drafted reply (the Mac never sends). */
    COPY,
    /** An FYI read. */
    SEEN,
}

/**
 * One phone number of a contact on the Fold (WhatsApp Open chat, 2026-10-29), read on the phone when Meka taps and never
 * stored, synced or sent: [contactId] tells two people of the same name apart, [mobile] the contact's mobile.
 */
data class ContactPhone(val contactId: String, val name: String, val number: String, val mobile: Boolean = false)

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
    const val REPLY_IN_WHATSAPP = "Reply in WhatsApp"
    const val SEND_VIA_PHONE = "Send (via your phone)"
    const val OPEN_WHATSAPP = "Open WhatsApp"

    /** The line under a reply card's buttons saying which way the reply will go (WhatsApp replies, slice 1, item 4). */
    const val ROUTE_SEND = "Sends from your phone"
    const val ROUTE_REPLY_IN_APP = "Opens WhatsApp with your reply typed · you tap send"
    const val ROUTE_OPEN_CHAT = "Opens the chat with your reply copied · paste and send"
    const val ROUTE_COPY = "Copies your reply · paste it in the chat"
    const val ROUTE_VIA_PHONE = "Sends from your phone · the message is still showing there"

    /** The Mac's Send (via your phone), while the phone hasn't answered, and when it couldn't (slice 2). */
    const val PHONE_WAITING_LINE = "Sending from your phone…"
    const val PHONE_GONE_LINE = "Couldn't send · the message was opened on your phone"
    const val PHONE_LATE_LINE = "Couldn't send · your phone didn't pick it up in time"

    /** An edited reply is Meka's own words: kept whole up to this length (WhatsApp's own limit is far above it). */
    const val MAX_REPLY = 2_000

    /** A reply this short, one paragraph, in a 1:1 chat, may go out with the others under "Send all". */
    const val MAX_EASY = 100

    /**
     * The first button of [card]: Send while the message's Reply action is live on this phone ([live]); otherwise, for a
     * one-to-one WhatsApp chat whose number is known, Reply in WhatsApp (the chat opens with the reply typed); else Open
     * chat (the reply copied) on the Fold and Copy reply on the Mac ([canSend] false). Never sends by itself.
     */
    fun primary(card: TriageCard, live: Boolean, canSend: Boolean = true): TriageCardPrimary = when {
        card.lane != TriageLane.NEEDS_REPLY -> TriageCardPrimary.SEEN
        canSend && live && card.draft != null -> TriageCardPrimary.SEND
        // The Mac (slice 2): the phone still holds the notification, and the last request didn't time out there.
        !canSend && card.phoneLive && card.draft != null && card.phoneSend != PhoneSendState.LATE -> TriageCardPrimary.SEND_VIA_PHONE
        card.draft != null && replyLink(card, card.draft) != null -> TriageCardPrimary.REPLY_IN_APP
        !canSend -> if (card.draft != null) TriageCardPrimary.COPY else TriageCardPrimary.SEEN
        else -> TriageCardPrimary.OPEN_CHAT
    }

    fun label(primary: TriageCardPrimary): String = when (primary) {
        TriageCardPrimary.SEND -> SEND
        TriageCardPrimary.SEND_VIA_PHONE -> SEND_VIA_PHONE
        TriageCardPrimary.REPLY_IN_APP -> REPLY_IN_WHATSAPP
        TriageCardPrimary.OPEN_CHAT -> OPEN_CHAT
        TriageCardPrimary.COPY -> COPY
        TriageCardPrimary.SEEN -> SEEN
    }

    /** Which way [primary] sends a reply card's words, shown quietly under its buttons; null for an FYI's Seen. */
    fun routeLine(card: TriageCard, primary: TriageCardPrimary): String? = when {
        card.lane != TriageLane.NEEDS_REPLY || card.draft == null -> null
        primary == TriageCardPrimary.SEND -> ROUTE_SEND
        primary == TriageCardPrimary.SEND_VIA_PHONE -> if (card.phoneSend == PhoneSendState.WAITING) null else ROUTE_VIA_PHONE
        primary == TriageCardPrimary.REPLY_IN_APP -> ROUTE_REPLY_IN_APP
        primary == TriageCardPrimary.OPEN_CHAT -> ROUTE_OPEN_CHAT.replace("the chat", appName(card.app))
        primary == TriageCardPrimary.COPY -> ROUTE_COPY
        else -> null
    }

    /**
     * WhatsApp's official click-to-chat link opening [card]'s chat with [text] typed ("https://wa.me/447700900123?text=…"):
     * only a one-to-one WhatsApp chat with a number; null otherwise (a group, a text message, no number, a blank reply).
     * Opening it sends nothing: Meka taps WhatsApp's own send.
     */
    fun replyLink(card: TriageCard, text: String?): String? {
        if (card.app != CaptureApp.WHATSAPP || card.group != null || card.mentioned) return null
        val words = cleanReply(text) ?: return null
        return LeavingRules.waLink(card.number ?: return null, words)
    }

    /**
     * The Mac's line about its last Send (via your phone) on [card] (slice 2), shown above the route: waiting, or why the
     * phone couldn't (then the card offers Reply in WhatsApp or Copy reply instead). Null when none is waiting or failed.
     */
    fun phoneSendLine(card: TriageCard): String? = when (card.phoneSend) {
        PhoneSendState.WAITING -> PHONE_WAITING_LINE
        // Once the phone holds a fresh notification for it again, Send (via your phone) is simply offered again.
        PhoneSendState.GONE -> PHONE_GONE_LINE.takeUnless { card.phoneLive }
        PhoneSendState.LATE -> PHONE_LATE_LINE
        null -> null
    }

    /** Whether the Mac's lead button waits (a request is with the phone): it can't be pressed twice. */
    fun waitingOnPhone(card: TriageCard): Boolean = card.phoneSend == PhoneSendState.WAITING

    /** The Mac's undo bar after Send (via your phone). */
    fun viaPhoneLine(card: TriageCard): String = "Sending to ${who(card)} from your phone"

    /** The undo bar after Reply in WhatsApp. */
    fun replyInAppLine(card: TriageCard): String = "Opened WhatsApp with your reply to ${who(card)} · tap send there"

    /** Send was tapped but the notification had gone: WhatsApp opened with the reply typed instead. */
    const val SEND_FAILED_TO_APP_LINE = "Couldn't send from here · opened WhatsApp with your reply typed"

    /**
     * A one-to-one chat's phone number from what the Fold's notification carried, first that reads as one: WhatsApp's
     * chat id ([shortcutId], "447700900123@s.whatsapp.net"; never a group's "@g.us" or a hidden "@lid" id), the
     * sender's link ([personUri], "tel:+447700900123"), or the sender shown as a number ([personName]). Kept as digits
     * with a leading + when international; null when none is a phone number.
     */
    fun chatNumber(personName: String?, personUri: String?, shortcutId: String?): String? {
        shortcutId?.trim()?.takeIf { it.endsWith("@s.whatsapp.net") }?.substringBefore('@')?.let { id ->
            cleanNumber("+" + id.filter { it.isDigit() })?.let { return it }
        }
        personUri?.trim()?.takeIf { it.startsWith("tel:", ignoreCase = true) }?.let { cleanNumber(it.drop(4))?.let { n -> return n } }
        val name = personName?.trim().orEmpty()
        if (name.isNotEmpty() && name.all { it.isDigit() || it in " +-()" }) return cleanNumber(name)
        return null
    }

    /**
     * Whether [card] is a one-to-one WhatsApp chat whose number the notification didn't carry (it gives a name, not a
     * number), so the Fold looks the number up on the phone ([lookupNumber]) before Open chat or Reply in WhatsApp.
     */
    fun needsLookup(card: TriageCard): Boolean =
        card.app == CaptureApp.WHATSAPP && card.group == null && !card.mentioned && card.number == null &&
            who(card).isNotBlank() && !People.key(who(card)).startsWith("tel:")

    /**
     * A one-to-one chat's number found on the phone from [name], the person as WhatsApp showed them: the Family list
     * first, then always-notify (each only when that name has one number), then the phone's [contacts] with exactly
     * that name (one contact only; its one number, else its one mobile). Never a guess: two people of that name, or a
     * contact with several numbers and no single mobile, give null. Nothing leaves the phone.
     */
    fun lookupNumber(name: String, lists: PeopleLists, contacts: List<ContactPhone>): String? {
        val key = People.key(name)
        if (key.isEmpty() || key.startsWith("tel:")) return null
        for (listed in listOf(lists.family, lists.alwaysNotify)) {
            val hits = listed.filter { People.key(it) == key }
            if (hits.isEmpty()) continue
            val nums = hits.flatMap { lists.numbers[it].orEmpty() }.mapNotNull { ContactNumbers.e164(it) }.distinctBy(People::key)
            if (nums.size == 1) cleanNumber(nums[0])?.let { return it }
        }
        val same = contacts.filter { People.key(it.name) == key }
        if (same.map { it.contactId }.distinct().size != 1) return null
        val nums = same.mapNotNull { c -> ContactNumbers.e164(c.number)?.let { it to c.mobile } }.distinctBy { People.key(it.first) }
        val pick = nums.singleOrNull() ?: nums.filter { it.second }.singleOrNull() ?: return null
        return cleanNumber(pick.first)
    }

    /** [cards] with each looked-up number ([found], by card id) filled in where the card had none. */
    fun withNumbers(cards: List<TriageCard>, found: Map<String, String>): List<TriageCard> =
        if (found.isEmpty()) cards else cards.map { c -> if (c.number == null) found[c.id]?.let { c.copy(number = it) } ?: c else c }

    /**
     * WhatsApp's click-to-chat link opening [card]'s one-to-one chat with nothing typed (for "Open WhatsApp to mark
     * read" and a card with no reply), or null when it isn't a 1:1 WhatsApp chat with a number.
     */
    fun chatLink(card: TriageCard): String? {
        if (card.app != CaptureApp.WHATSAPP || card.group != null || card.mentioned) return null
        return LeavingRules.waLink(card.number ?: return null, "")?.substringBefore("?text=")
    }

    /**
     * A group's card once its notification has gone ([live] false): WhatsApp has no official link into a group, so
     * Open chat would only open WhatsApp. The button then says so ("Open WhatsApp") and the line under it names the
     * group to find; false otherwise (the usual label and route apply).
     */
    fun groupGone(card: TriageCard, primary: TriageCardPrimary, live: Boolean): Boolean =
        primary == TriageCardPrimary.OPEN_CHAT && !live && card.app == CaptureApp.WHATSAPP && card.group != null

    fun openLabel(card: TriageCard, primary: TriageCardPrimary, live: Boolean): String =
        if (groupGone(card, primary, live)) OPEN_WHATSAPP else label(primary)

    fun openRoute(card: TriageCard, primary: TriageCardPrimary, live: Boolean): String? =
        if (groupGone(card, primary, live)) {
            "Find ${card.group} in WhatsApp" + if (card.draft != null) " · your reply is copied" else ""
        } else routeLine(card, primary)

    /** [raw] as a phone number to keep: digits (a leading + kept), 8–15 digits, never a short code; null otherwise. */
    fun cleanNumber(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val digits = t.filter { it.isDigit() }
        if (digits.length !in 8..15) return null
        return if (t.startsWith("+")) "+$digits" else digits
    }

    /** The second, quiet button: Not now for a reply, nothing more for an FYI (Seen already leads). */
    fun secondary(card: TriageCard): String? = if (card.lane == TriageLane.NEEDS_REPLY) NOT_NOW else null

    /**
     * Whether Edit shows: a reply with a draft this device can send, or one that opens WhatsApp with the words typed
     * (the edited words are what's typed there).
     */
    fun editable(card: TriageCard, live: Boolean): Boolean =
        card.lane == TriageLane.NEEDS_REPLY && card.draft != null && (live || replyLink(card, card.draft) != null)

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
