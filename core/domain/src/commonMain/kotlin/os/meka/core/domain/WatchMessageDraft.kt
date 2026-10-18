package os.meka.core.domain

/**
 * **Galaxy Watch: approve message drafts from the wrist**, slice 1. Navilife's drafted reply to a WhatsApp or text
 * message (a Needs a reply card with a draft, [TriageCard]) on the watch, with **Send**. Pure, no AI; nothing is stored.
 *
 * The watch keeps its own synced copy of the cards, so it reads the same drafts as the Fold's and the Mac's Needs you.
 * A message reply can only go out through the message's own notification Reply on the Fold (official RemoteInput; no
 * WhatsApp library), so Send on the wrist is the Mac's **Send (via your phone)** ([TriageCards.requestPhoneSend]): his
 * tap writes the request on the synced card and the Fold sends the words as the wrist showed them, within five minutes
 * or never. **Nothing is sent without his tap** (Level 3, hard rule) and no gesture sends. Send is offered only while
 * the Fold still holds the message's notification and the draft fits the wrist whole ([MAX_CHARS]); otherwise the draft
 * is kept and the wrist says "Needs your phone". Edit and Not now stay on the phone and the Mac (Not now can't be
 * taken back, so it is never one small tap away on the wrist).
 */
data class WatchMessageDraft(
    val messageId: String,
    /** "MESSAGE REPLY" · "MESSAGE REPLY · 1 OF 3". */
    val label: String,
    /** "Alex · 14:02 · WhatsApp", "Alex in Football parents · 14:02 · WhatsApp". */
    val from: String,
    /** "Asks if you're coming Saturday"; null when the card has no gist. */
    val wants: String?,
    /** The draft as the wrist shows it: whole, or cut with "…" when it's too long to send from here. */
    val draft: String,
    /** Send is offered: the phone holds the message, the draft is whole and no request is waiting. */
    val canSend: Boolean,
    /** "Sends from your phone · only when you tap Send" · "Sending from your phone…" · "Needs your phone · …". */
    val note: String,
    /** The note is about a problem (couldn't send / didn't pick it up): shown in the warning colour. */
    val warn: Boolean,
    /** "+2 more on your phone"; null with one. */
    val more: String?,
    val spoken: String,
)

object WatchMessageDraftRules {
    const val LABEL = "MESSAGE REPLY"
    const val SEND = "Send"
    const val NOTE = "Sends from your phone · only when you tap Send"
    const val SENDING = "Sending from your phone…"
    const val NEEDS_PHONE = "Needs your phone · the message has gone from it · your draft is kept"
    const val TOO_LONG = "Long draft · send it from your phone"
    const val GONE = "Couldn't send · the message was opened on your phone · your draft is kept there"
    const val LATE = "Your phone didn't pick it up · nothing sent · your draft is kept there"
    const val ASKED = "Sending from your phone · it leaves here once it's gone"
    const val NOT_NOW = "That message has gone · nothing sent"
    const val EDIT_HINT = "Edit or Not now on your phone"

    /** The longest draft the wrist shows whole (the same as an email draft's, [WatchDraftRules.MAX_CHARS]). */
    const val MAX_CHARS = WatchDraftRules.MAX_CHARS

    /** How long the line after Send stays (the row follows the card after that). */
    const val LINE_MS = 6_000L

    /** The message drafts waiting on him, newest first: open Needs a reply cards with Navilife's draft. */
    fun waiting(cards: List<TriageCard>): List<TriageCard> =
        cards.filter { it.lane == TriageLane.NEEDS_REPLY && !it.draft.isNullOrBlank() }.sortedByDescending { it.atMs }

    /** The newest message draft for the wrist, or null when none waits. */
    fun view(cards: List<TriageCard>): WatchMessageDraft? {
        val drafts = waiting(cards)
        val c = drafts.firstOrNull() ?: return null
        val text = c.draft.orEmpty().trim()
        val fits = text.length <= MAX_CHARS
        val waitingOnPhone = c.phoneSend == PhoneSendState.WAITING
        val canSend = fits && c.phoneLive && !waitingOnPhone && c.phoneSend != PhoneSendState.LATE
        val (note, warn) = when {
            waitingOnPhone -> SENDING to false
            c.phoneSend == PhoneSendState.GONE && !c.phoneLive -> GONE to true
            c.phoneSend == PhoneSendState.LATE -> LATE to true
            !c.phoneLive -> NEEDS_PHONE to false
            !fits -> TOO_LONG to false
            else -> NOTE to false
        }
        val n = drafts.size
        val wants = c.gist.trim().takeIf { it.isNotEmpty() && it != TriageCard.LOCAL_LINE && it != TriageCard.NO_GIST_LINE }
        val from = c.from.trim().ifEmpty { "Someone" } + (c.app?.let { " · ${it.label}" } ?: "")
        val shown = if (fits) text else cut(text)
        val who = c.from.substringBefore(" · ").trim().ifEmpty { "someone" }
        val spoken = buildString {
            append("Message reply to $who")
            wants?.let { append(". $it") }
            append(". Navilife's draft: $shown. ")
            append(if (canSend) "It goes from your phone only when you tap Send." else note.replace(" · ", ", ") + ".")
            if (n > 1) append(" ${n - 1} more on your phone.")
        }
        return WatchMessageDraft(
            messageId = c.id,
            label = if (n > 1) "$LABEL · 1 OF $n" else LABEL,
            from = from,
            wants = wants,
            draft = shown,
            canSend = canSend,
            note = note,
            warn = warn,
            more = if (n > 1) "+${n - 1} more on your phone" else null,
            spoken = spoken,
        )
    }

    /**
     * The words the watch may ask the phone to send for [messageId] now: the card's whole draft, exactly as the wrist
     * showed it, while Send is offered; else null (nothing is written).
     */
    fun sendable(cards: List<TriageCard>, messageId: String): String? {
        val c = waiting(cards).firstOrNull { it.id == messageId } ?: return null
        val text = c.draft.orEmpty().trim()
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        if (!c.phoneLive || c.phoneSend == PhoneSendState.WAITING || c.phoneSend == PhoneSendState.LATE) return null
        return text
    }

    private fun cut(text: String): String {
        val head = text.take(MAX_CHARS - 1)
        val space = head.lastIndexOf(' ')
        val t = if (space > MAX_CHARS / 2) head.substring(0, space) else head
        return t.trimEnd(',', '.', ';', ':', '-', ' ') + "…"
    }
}
