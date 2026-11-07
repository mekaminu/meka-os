package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.BookingCall
import os.meka.core.domain.BookingCallFields
import os.meka.core.domain.BookingCallRules
import os.meka.core.domain.BookingCallState
import os.meka.core.domain.BookingDialRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LiveCallRules
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.PutThroughRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Makes outbound calls (Obi on calls, slice 2b). Twilio first ([TwilioVoice.dial]); a fake in tests. */
interface OutboundDialer {
    fun dialConfigured(): Boolean
    /** The call's id, or null when it couldn't be made. */
    fun dial(to: String, from: String, twiml: String, statusUrl: String, ringSeconds: Int, limitSeconds: Int): String?
}

/**
 * Obi on calls, part B slice 2b: **the server rings an approved call card**. Every minute it looks at the household's
 * call cards: an approved one ([BookingCallState.APPROVED], not cancelled) is rung when [BookingDialRules.due] says so,
 * one call at a time, from the assistant's number, with Obi live on the card's brief ([BookingDialRules.instruction]).
 * The card goes to "on the call" before anything rings (so a restart never rings twice), then Booked / Not booked /
 * the call didn't go through with the line the apps show. Nothing of what is said is logged.
 */
class BookingCaller(
    private val ops: ServerOpStore,
    private val read: (householdId: String, entityType: String) -> Map<String, Map<String, Op>>,
    private val household: () -> String?,
    private val line: LiveCallLine,
    private val dialer: OutboundDialer?,
    /** The call's markup for a ticket ([TwilioVoice.bookingTwiml]). */
    private val twiml: (ticket: String) -> String,
    /** Where the phone service posts the call's final status ([VoiceStep.BOOKING_STATUS]). */
    private val statusUrl: String,
    private val provider: String = TwilioVoice.ID,
    private val now: () -> Long = System::currentTimeMillis,
    private val calendar: LocalCalendar = LondonCalendar,
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()

    /** Calls rung and not yet over, by the phone service's call id: the household and the card. Memory only. */
    private val pending = ConcurrentHashMap<String, Pair<String, String>>()

    @Volatile var lastReport: String = "not run yet"
        private set

    /** One look. Returns whether anything was written. */
    @Synchronized fun tick(): Boolean {
        val hh = household() ?: return false
        val heads = read(hh, EntityTypes.BOOKING_CALL)
        val cards = heads.mapNotNull { (id, f) -> BookingCallRules.read(id, f.mapValues { it.value.value }) }
        val profile = read(hh, EntityTypes.HOUSEHOLD_PROFILE)[AboutYouRules.ENTITY_ID].orEmpty()
        fun text(f: String) = (profile[f]?.value as? FieldValue.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val assistant = text(AboutYouFields.ASSISTANT_NUMBER)
        val personal = listOf(text(AboutYouFields.MOBILE), assistant, text(AboutYouFields.PARTNER_PHONE), text(AboutYouFields.VOICEMAIL_NUMBER))
        val t = now()
        var wrote = false
        // A card left on the call (a lost status, a restart) is closed; while one is on the call, nothing else rings.
        var onCall = false
        for (c in cards.filter { !it.cancelled && it.state == BookingCallState.CALLING }) {
            if (t - (c.calledAtMs ?: 0L) > BookingDialRules.STUCK_MS) {
                if (close(hh, c.id, BookingCallState.FAILED, BookingDialRules.STUCK, from = BookingCallState.CALLING)) wrote = true
            } else onCall = true
        }
        var report = if (onCall) "on a call" else "idle"
        for (c in cards.filter { it.open && it.state == BookingCallState.APPROVED }.sortedBy { it.approvedAtMs ?: it.askedAtMs }) {
            when (val due = BookingDialRules.due(c, personal, assistant, t, calendar, line.spentPence(), line.capPence(hh))) {
                null, BookingDialRules.Due.Wait -> Unit
                is BookingDialRules.Due.Close -> if (close(hh, c.id, due.state, due.line)) wrote = true
                is BookingDialRules.Due.Ring -> {
                    if (onCall) continue
                    if (dialer == null || !runCatching { dialer.dialConfigured() }.getOrDefault(false) || !line.ready()) { report = "waiting: not set up"; continue }
                    onCall = true
                    report = "rang a card"
                    if (ring(hh, c, due.e164, PutThroughRules.dialable(assistant)!!, text(AboutYouFields.SHOWN_NAME), heads[c.id])) wrote = true
                }
            }
        }
        lastReport = report
        if (wrote) runCatching { onWritten(hh) }
        return wrote
    }

    /** On the call first (so nothing rings twice), then the ticket and the call; a failure closes the card. */
    private fun ring(hh: String, c: BookingCall, e164: String, from: String, shownName: String?, head: Map<String, Op>?): Boolean {
        val t = now()
        write(hh, EntityTypes.BOOKING_CALL, c.id, mapOf(
            BookingCallFields.STATE to FieldValue.Text(BookingCallState.CALLING.code),
            BookingCallFields.CALLED_AT to FieldValue.Int64(t),
        ), head)
        val first = shownName?.split(' ')?.firstOrNull()?.takeIf { n -> n.length in 1..30 && n.all { it.isLetter() || it in "-'" } }
        val instruction = BookingDialRules.instruction(c, first, from, t, calendar)
        val token = line.bookingTicket(hh, provider, c.id, e164, instruction)
            ?: return close(hh, c.id, BookingCallState.FAILED, BookingDialRules.DIAL_FAILED, from = BookingCallState.CALLING).let { true }
        val sid = runCatching { dialer!!.dial(e164, from, twiml(token), statusUrl, BookingDialRules.RING_SECONDS, BookingDialRules.LIMIT_SECONDS) }.getOrNull()
        if (sid == null) {
            line.drop(token)
            close(hh, c.id, BookingCallState.FAILED, BookingDialRules.DIAL_FAILED, from = BookingCallState.CALLING)
            return true
        }
        line.bind(token, sid)
        pending[sid] = hh to c.id
        System.err.println("booking call: ringing a business for a call card") // no names, no numbers
        return true
    }

    /** The phone service's steps for a booking call. */
    fun handle(event: VoiceEvent): VoiceReply = when (event) {
        // The stream is over: Obi said goodbye himself, or he never started talking and the business hears why.
        is VoiceEvent.BookingEnded -> if (line.wasLive(event.callId)) VoiceReply.HangUp else VoiceReply.Goodbye(BookingDialRules.SORRY)
        is VoiceEvent.BookingStatus -> {
            val p = pending.remove(event.callId)
            // A call Obi talked on is closed by its session's end ([ended]); any other one by how it ended.
            if (p != null && !line.wasLive(event.callId)) {
                val (state, text) = BookingDialRules.unanswered(event.status)
                if (close(p.first, p.second, state, text, from = BookingCallState.CALLING)) runCatching { onWritten(p.first) }
            }
            VoiceReply.Done
        }
        else -> VoiceReply.Done
    }

    /** A booking call's session ended: the card's result, and the month's spend for both apps' meter. */
    fun ended(t: LiveCallTicket, end: LiveCallEnd, meter: LiveCallRules.Meter) {
        val hh = t.householdId
        val id = t.bookingId ?: return
        val card = read(hh, EntityTypes.BOOKING_CALL)[id]?.let { f -> BookingCallRules.read(id, f.mapValues { it.value.value }) }
        val (state, text) = BookingDialRules.result(end.live, end.booking, card)
        var wrote = close(hh, id, state, text, from = BookingCallState.CALLING)
        if (end.live) {
            val heads = read(hh, EntityTypes.CONTEXT_MODE)[LiveCallRules.METER_ID]
            if (write(hh, EntityTypes.CONTEXT_MODE, LiveCallRules.METER_ID, mapOf(
                    LiveCallRules.METER_MONTH to FieldValue.Text(meter.month), LiveCallRules.METER_PENCE to FieldValue.Int64(meter.spentPence),
                    LiveCallRules.METER_CALLS to FieldValue.Int64(meter.calls),
                ), heads)) wrote = true
        }
        if (wrote) runCatching { onWritten(hh) }
    }

    /** Closes card [id] with [state] and [text], only while it is still [from] (approved by default) and not cancelled. */
    @Synchronized private fun close(hh: String, id: String, state: BookingCallState, text: String, from: BookingCallState = BookingCallState.APPROVED): Boolean {
        val head = read(hh, EntityTypes.BOOKING_CALL)[id] ?: return false
        val c = BookingCallRules.read(id, head.mapValues { it.value.value }) ?: return false
        if (c.state != from || (from == BookingCallState.APPROVED && c.cancelled)) return false
        return write(hh, EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.STATE to FieldValue.Text(state.code),
            BookingCallFields.RESULT to FieldValue.Text(text.take(BookingDialRules.MAX_LINE)),
            BookingCallFields.ENDED_AT to FieldValue.Int64(now()),
        ), head)
    }

    /** Appends each field whose value differs from its head, after the head (so the server's write wins). */
    private fun write(hh: String, type: String, id: String, fields: Map<String, FieldValue>, heads: Map<String, Op>?): Boolean = ops.transaction {
        var wrote = false
        for ((field, value) in fields) {
            val last = heads?.get(field)
            if ((last?.value ?: FieldValue.Null) == value) continue
            val hlc = synchronized(clock) {
                last?.let { runCatching { clock.receive(it.hlc) } }
                clock.now()
            }
            ops.append(Op(
                opId = "srvbook" + hex(12), householdId = hh, entityType = type, entityId = id, field = field, value = value,
                hlc = hlc, baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
            ))
            wrote = true
        }
        wrote
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val LOOK_MS = 60_000L
    }
}

/** Looks a minute and a half after start, then every [BookingCaller.LOOK_MS]. Logs only what it did, never a card. */
fun startBookingCaller(caller: BookingCaller) {
    Thread.ofVirtual().name("booking-calls").start {
        Thread.sleep(90_000)
        while (true) {
            runCatching { caller.tick() }
                .onSuccess { wrote -> if (wrote) System.err.println("booking calls: ${caller.lastReport}") }
                .onFailure { System.err.println("booking calls failed: ${it::class.simpleName}") }
            Thread.sleep(BookingCaller.LOOK_MS)
        }
    }
}
