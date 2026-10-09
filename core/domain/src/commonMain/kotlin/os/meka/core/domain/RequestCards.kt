package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Fields of a `request_card` (build plan V1, "Requests from my wife become tasks", slice 2; ADR-008 addendum): one per
 * proposal MEKA read from a watched person's message, synced through Meka's own server so the Mac shows the same Needs
 * you card and Add or Not a task on either device clears both. Written once by the Fold; afterwards only [RESOLVED]
 * (TrueWins), [RESOLUTION], [RESOLVED_AT] and the blanked [TEXT] change.
 */
object RequestCardFields {
    /** The captured message's id ([CapturedItem.id]); the card's id is `<messageId>#<index>`. */
    const val MESSAGE_ID = "messageId"
    const val INDEX = "index"
    /** The sender's label as the notification named them ("Wife"). */
    const val PERSON = "person"
    /** The message text, display only (untrusted, ADR-006); blanked once the card is resolved. */
    const val TEXT = "text"
    const val AT = "atMs"
    /** [RequestKind.wire]. */
    const val KIND = "kind"
    const val TITLE = "title"
    /** Local epoch day; absent when the request has no day. */
    const val DAY = "day"
    /** Local minute of the day; absent when it has no time. */
    const val MINUTE = "minute"
    /** Add, Not a task (or Change, once its task is saved) on either device: gone from Needs you on both (TrueWins). */
    const val RESOLVED = "resolved"
    /** "added" · "declined" · "changed". */
    const val RESOLUTION = "resolution"
    const val RESOLVED_AT = "resolvedAtMs"
}

/** How a request card left Needs you. */
enum class RequestResolution(val wire: String) { ADDED("added"), DECLINED("declined"), CHANGED("changed") }

/**
 * The open request cards as synced entities, non-AI. The Fold [save]s the cards for a message it read (the same
 * message read again, a re-post, or the same request from the same person while one is open makes no second card, and
 * a resolved card is never revived); both apps read [open]; [resolve] on either clears it everywhere and blanks the
 * text. Cards older than [RETENTION_MS], or whose day has gone, are not shown.
 */
class RequestCards(private val replica: Replica, private val nowMs: () -> Long, private val calendar: LocalCalendar) {
    /** Writes the cards [proposals] make from [message] that aren't already open or stored; returns the new cards. */
    fun save(message: RequestMessage, proposals: List<RequestProposal>): List<RequestCard> {
        val today = calendar.epochDayOf(nowMs())
        val made = MessageRequestRules.cards(message, proposals, open(), today, calendar)
            .filter { replica.entity(EntityTypes.REQUEST_CARD, entityId(it.id)) == null }
        made.forEach { card ->
            val p = card.proposal
            replica.commitLocal(
                EntityTypes.REQUEST_CARD, entityId(card.id),
                buildMap {
                    put(RequestCardFields.MESSAGE_ID, message.id.take(200).fv())
                    put(RequestCardFields.INDEX, card.id.substringAfterLast('#').toIntOrNull().fv())
                    put(RequestCardFields.PERSON, message.personName.trim().take(200).fv())
                    put(RequestCardFields.TEXT, message.text.take(MAX_TEXT).fv())
                    put(RequestCardFields.AT, message.atMs.fv())
                    put(RequestCardFields.KIND, p.kind.wire.fv())
                    put(RequestCardFields.TITLE, p.title.fv())
                    p.day?.let { put(RequestCardFields.DAY, it.fv()) }
                    p.minute?.let { put(RequestCardFields.MINUTE, it.fv()) }
                },
            )
        }
        return made
    }

    /** What is waiting: not resolved, from the last [RETENTION_MS], its day not gone; oldest message first. */
    fun open(): List<RequestCard> {
        val now = nowMs()
        val today = calendar.epochDayOf(now)
        return stored().filter { (_, s) -> !s.resolved && s.atMs >= now - RETENTION_MS && (s.proposal.day ?: today) >= today }
            .sortedWith(compareBy({ it.second.atMs }, { it.second.index }))
            .map { (_, s) -> MessageRequestRules.card(s.message, s.index, s.proposal, today, calendar) }
    }

    /** The open card with [cardId], or null once it is resolved, gone or never was. */
    fun find(cardId: String): RequestCard? = open().firstOrNull { it.id == cardId }

    /**
     * Takes [cardId] out of Needs you on every device and blanks its text. False when it was already resolved (the
     * other device got there first) or isn't known.
     */
    fun resolve(cardId: String, how: RequestResolution): Boolean {
        val id = entityId(cardId)
        val e = replica.entity(EntityTypes.REQUEST_CARD, id) ?: return false
        if (e[RequestCardFields.RESOLVED].boolOrNull == true) return false
        replica.commitLocal(
            EntityTypes.REQUEST_CARD, id,
            mapOf(
                RequestCardFields.RESOLVED to true.fv(),
                RequestCardFields.RESOLUTION to how.wire.fv(),
                RequestCardFields.RESOLVED_AT to nowMs().fv(),
                RequestCardFields.TEXT to FieldValue.Null,
            ),
        )
        return true
    }

    private class Stored(val message: RequestMessage, val index: Int, val proposal: RequestProposal, val atMs: Long, val resolved: Boolean)

    private fun stored(): List<Pair<String, Stored>> = replica.entities(EntityTypes.REQUEST_CARD).mapNotNull { e ->
        val messageId = e[RequestCardFields.MESSAGE_ID].textOrNull ?: return@mapNotNull null
        val index = e[RequestCardFields.INDEX].longOrNull?.toInt() ?: return@mapNotNull null
        val person = e[RequestCardFields.PERSON].textOrNull ?: return@mapNotNull null
        val at = e[RequestCardFields.AT].longOrNull ?: return@mapNotNull null
        val kind = e[RequestCardFields.KIND].textOrNull?.let(RequestKind::of) ?: return@mapNotNull null
        val title = e[RequestCardFields.TITLE].textOrNull ?: return@mapNotNull null
        val proposal = RequestProposal(kind, title, e[RequestCardFields.DAY].longOrNull, e[RequestCardFields.MINUTE].longOrNull?.toInt())
        val text = e[RequestCardFields.TEXT].textOrNull.orEmpty()
        e.ref.entityId to Stored(
            RequestMessage(messageId, person, text, at), index, proposal, at,
            resolved = e[RequestCardFields.RESOLVED].boolOrNull == true,
        )
    }

    companion object {
        const val RETENTION_MS = 7 * 24 * 60 * 60_000L
        const val MAX_TEXT = 2_000

        /** The `request_card` id for a card's id: the same on every device and install. */
        fun entityId(cardId: String): String = "r" + ActivityRules.fnv64("request:$cardId")
    }
}
