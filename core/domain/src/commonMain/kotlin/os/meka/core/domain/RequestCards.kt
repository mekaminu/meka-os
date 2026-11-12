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
    /** A renewal card's obligation kind ([ObligationKind] name; "Handle this", slice 2). Absent on other cards. */
    const val OBLIGATION = "obligation"
    /** A renewal card's repeat ([RenewalRepeat] name). */
    const val REPEAT = "repeat"
    /** A renewal card's cost in pence; absent when not known. */
    const val COST_PENCE = "costPence"
    /**
     * A reply card's drafted words ("Handle this", slice 3; display and copy only, made from untrusted text, ADR-006);
     * absent on other cards, blanked once the card is resolved like [TEXT].
     */
    const val REPLY = "reply"
    /** A booking card's [BookingKind.wire] (Travel mode, slice 2b; additive). Absent on other cards. */
    const val BOOKING_KIND = "bookingKind"
    /** A booking card's last day (a stay's check-out, a hire car's return); absent when it has none. */
    const val END_DAY = "endDay"
    /** A booking card's reference ("ABC123"); absent when none. */
    const val REF = "ref"
    /** The trip a booking card named when it was made ("Barcelona"); display only, the trip is found again on Add. */
    const val TRIP = "trip"
    /** Add, Not a task (or Change, once its task is saved) on either device: gone from Needs you on both (TrueWins). */
    const val RESOLVED = "resolved"
    /** "added" · "declined" · "changed". */
    const val RESOLUTION = "resolution"
    const val RESOLVED_AT = "resolvedAtMs"
    /**
     * "Handle her requests for me" (2026-11-11, additive): on a card resolved as [RequestResolution.HANDLED], what was
     * made — the task ids, comma-separated (the request's own first, then the steps), the home day — and the Done for you
     * row's line. [HANDLED_CLEARED] (Clear) and [HANDLED_UNDONE] (Undo) take the row away on both apps.
     */
    const val HANDLED_TASKS = "handledTasks"
    const val HANDLED_HOME_DAY = "handledHomeDay"
    const val HANDLED_LINE = "handledLine"
    const val HANDLED_CLEARED = "handledCleared"
    const val HANDLED_UNDONE = "handledUndone"
}

/** How a request card left Needs you. */
enum class RequestResolution(val wire: String) {
    ADDED("added"), DECLINED("declined"), CHANGED("changed"),
    /** Acted on at once, no tap ("Handle her requests for me", [HandleForMeRules]). */
    HANDLED("handled"),
}

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
                    p.renewal?.let { r ->
                        put(RequestCardFields.OBLIGATION, r.kind.name.fv())
                        put(RequestCardFields.REPEAT, r.repeat.name.fv())
                        r.costPence?.let { put(RequestCardFields.COST_PENCE, it.fv()) }
                    }
                    p.reply?.let { put(RequestCardFields.REPLY, it.take(MAX_REPLY).fv()) }
                    p.booking?.let { b ->
                        put(RequestCardFields.BOOKING_KIND, b.kind.wire.fv())
                        b.endDay?.let { put(RequestCardFields.END_DAY, it.fv()) }
                        b.ref?.let { put(RequestCardFields.REF, it.fv()) }
                        put(RequestCardFields.TRIP, b.tripName.take(TripRules.MAX_NAME).fv())
                    }
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

    /** Whether any card (open or answered) was ever made from [messageId] ("Handle this": shared already today). */
    fun known(messageId: String): Boolean = stored().any { (_, s) -> s.message.id == messageId }

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
                RequestCardFields.REPLY to FieldValue.Null,
            ),
        )
        return true
    }

    /**
     * Marks the open [cardId] as handled for him: resolved (gone from the cards on every device, its text blanked) with
     * what was made ([taskIds], [homeDay]) and the Done for you [line]. False when it was already answered.
     */
    fun handled(cardId: String, taskIds: List<String>, homeDay: Long?, line: String): Boolean {
        if (!resolve(cardId, RequestResolution.HANDLED)) return false
        replica.commitLocal(
            EntityTypes.REQUEST_CARD, entityId(cardId),
            buildMap {
                put(RequestCardFields.HANDLED_TASKS, taskIds.joinToString(",").fv())
                homeDay?.let { put(RequestCardFields.HANDLED_HOME_DAY, it.fv()) }
                put(RequestCardFields.HANDLED_LINE, line.take(MAX_LINE).fv())
            },
        )
        return true
    }

    /** The Done for you rows: handled cards not cleared or undone, from the last [HandleForMeRules.SHOWN_MS], newest first. */
    fun doneForYou(): List<DoneForYou> {
        val rows = replica.entities(EntityTypes.REQUEST_CARD).mapNotNull { e ->
            if (e[RequestCardFields.RESOLUTION].textOrNull != RequestResolution.HANDLED.wire) return@mapNotNull null
            if (e[RequestCardFields.HANDLED_CLEARED].boolOrNull == true || e[RequestCardFields.HANDLED_UNDONE].boolOrNull == true) return@mapNotNull null
            val messageId = e[RequestCardFields.MESSAGE_ID].textOrNull ?: return@mapNotNull null
            val index = e[RequestCardFields.INDEX].longOrNull ?: return@mapNotNull null
            val person = e[RequestCardFields.PERSON].textOrNull ?: return@mapNotNull null
            val line = e[RequestCardFields.HANDLED_LINE].textOrNull ?: return@mapNotNull null
            val sent = e[RequestCardFields.AT].longOrNull ?: return@mapNotNull null
            val at = e[RequestCardFields.RESOLVED_AT].longOrNull ?: sent
            DoneForYou(
                id = "$messageId#$index",
                from = HandleForMeRules.from(person, calendar.minuteOfDay(sent)),
                line = line,
                spoken = HandleForMeRules.spoken(person, line),
                atMs = at,
                taskIds = e[RequestCardFields.HANDLED_TASKS].textOrNull.orEmpty().split(',').filter { it.isNotBlank() },
                homeDay = e[RequestCardFields.HANDLED_HOME_DAY].longOrNull,
                changeTitle = e[RequestCardFields.KIND].textOrNull?.let(RequestKind::of)?.let { k ->
                    HandleForMeRules.stepsFor(k).firstOrNull()?.takeIf { k == RequestKind.WORK_FROM_HOME }
                } ?: e[RequestCardFields.TITLE].textOrNull,
                person = person,
                drafts = e[RequestCardFields.KIND].textOrNull?.let(RequestKind::of)?.let { k ->
                    HandleForMeRules.drafts(
                        k, e[RequestCardFields.TITLE].textOrNull.orEmpty(),
                        e[RequestCardFields.HANDLED_HOME_DAY].longOrNull ?: e[RequestCardFields.DAY].longOrNull,
                        e[RequestCardFields.MINUTE].longOrNull?.toInt(), person,
                    )
                }.orEmpty(),
            )
        }
        return HandleForMeRules.shown(rows, nowMs())
    }

    /** Clear (it's seen) or Undo (everything it made is gone): the row leaves Done for you on every device. */
    fun dropHandled(cardId: String, undone: Boolean): Boolean {
        val id = entityId(cardId)
        val e = replica.entity(EntityTypes.REQUEST_CARD, id) ?: return false
        if (e[RequestCardFields.RESOLUTION].textOrNull != RequestResolution.HANDLED.wire) return false
        val field = if (undone) RequestCardFields.HANDLED_UNDONE else RequestCardFields.HANDLED_CLEARED
        if (e[field].boolOrNull == true) return false
        replica.commitLocal(EntityTypes.REQUEST_CARD, id, mapOf(field to true.fv()))
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
        val renewal = if (kind != RequestKind.RENEWAL) null else RenewalDetails(
            e[RequestCardFields.OBLIGATION].textOrNull?.let { n -> ObligationKind.entries.firstOrNull { it.name == n } } ?: ObligationKind.OTHER,
            e[RequestCardFields.REPEAT].textOrNull?.let { n -> RenewalRepeat.entries.firstOrNull { it.name == n } } ?: RenewalRepeat.NONE,
            e[RequestCardFields.COST_PENCE].longOrNull,
        )
        // A reply card whose draft is gone (blanked, or never written) has nothing to copy: it isn't shown.
        val reply = if (kind != RequestKind.REPLY) null else e[RequestCardFields.REPLY].textOrNull ?: return@mapNotNull null
        val booking = if (kind != RequestKind.BOOKING) null else BookingDetails(
            BookingKind.of(e[RequestCardFields.BOOKING_KIND].textOrNull),
            e[RequestCardFields.END_DAY].longOrNull,
            e[RequestCardFields.REF].textOrNull,
            e[RequestCardFields.TRIP].textOrNull.orEmpty(),
        )
        val proposal = RequestProposal(
            kind, title, e[RequestCardFields.DAY].longOrNull, e[RequestCardFields.MINUTE].longOrNull?.toInt(), renewal, reply, booking,
        )
        val text = e[RequestCardFields.TEXT].textOrNull.orEmpty()
        e.ref.entityId to Stored(
            RequestMessage(messageId, person, text, at), index, proposal, at,
            resolved = e[RequestCardFields.RESOLVED].boolOrNull == true,
        )
    }

    companion object {
        const val RETENTION_MS = 7 * 24 * 60 * 60_000L
        const val MAX_TEXT = 2_000
        const val MAX_REPLY = HandleThisRules.MAX_REPLY
        const val MAX_LINE = 300

        /** The `request_card` id for a card's id: the same on every device and install. */
        fun entityId(cardId: String): String = "r" + ActivityRules.fnv64("request:$cardId")
    }
}
