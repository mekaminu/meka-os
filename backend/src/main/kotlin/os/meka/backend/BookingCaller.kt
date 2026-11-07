package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.BookingCall
import os.meka.core.domain.BookingCallFields
import os.meka.core.domain.BookingCallRules
import os.meka.core.domain.BookingCallState
import os.meka.core.domain.BookingDialRules
import os.meka.core.domain.BookingLiveRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LiveCallRules
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.PutThroughRules
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
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
    /** Slice 3c: the booking calls he can listen in on, his listening leg's markup (the whisper) and where its status goes. */
    private val listen: BookingListen? = null,
    private val listenTwiml: () -> String = { "" },
    private val listenStatusUrl: String = "",
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
        // Slice 3: a call's words are kept 7 days, then blanked.
        for (c in cards.filter { BookingLiveRules.shouldForget(it, t) }) {
            if (write(hh, EntityTypes.BOOKING_CALL, c.id, mapOf(
                    BookingCallFields.TRANSCRIPT to FieldValue.Text(""), BookingCallFields.WORDS to FieldValue.Text(""),
                ), heads[c.id])) wrote = true
        }
        // Slice 4: with "Booking calls" off (absent = on) approved cards wait; nothing rings.
        val bookingOn = read(hh, EntityTypes.CONTEXT_MODE)[WorkMode.ENTITY_ID]?.get(WorkFields.CALL_BOOKING)?.value != FieldValue.Bool(false)
        for (c in cards.filter { bookingOn && it.open && it.state == BookingCallState.APPROVED }.sortedBy { it.approvedAtMs ?: it.askedAtMs }) {
            when (val due = BookingDialRules.due(c, personal, assistant, t, calendar, line.spentPence(), line.capPence(hh))) {
                null, BookingDialRules.Due.Wait -> Unit
                is BookingDialRules.Due.Close -> if (close(hh, c.id, due.state, due.line)) wrote = true
                is BookingDialRules.Due.Ring -> {
                    if (onCall) continue
                    val d = dialer
                    if (d == null || !runCatching { d.dialConfigured() }.getOrDefault(false) || !line.ready()) { report = "waiting: not set up"; continue }
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
        // The stream is over: Obi said goodbye himself (or handed over: slice 3b rings his mobile), or he never started
        // talking and the business hears why.
        is VoiceEvent.BookingEnded -> when {
            !line.wasLive(event.callId) -> VoiceReply.Goodbye(BookingDialRules.SORRY)
            line.outcome(event.callId)?.takenOver == true -> handOver(event.callId)
            else -> VoiceReply.HangUp
        }
        // Slice 3b: his mobile answered: the whisper, only for a call he asked to take over.
        is VoiceEvent.BookingWhisper -> if (handingOver(event.parent) != null) VoiceReply.Whisper(BookingLiveRules.TAKE_OVER_WHISPER, VoiceStep.BOOKING_WHISPER_ANSWERED)
            else VoiceReply.HangUp
        is VoiceEvent.BookingWhisperAnswered -> {
            val p = handingOver(event.parent)
            if (p == null || !PutThroughRules.takes(event.digits)) VoiceReply.HangUp
            else {
                if (write(p.first, EntityTypes.BOOKING_CALL, p.second, mapOf(BookingCallFields.TAKEN_AT to FieldValue.Int64(now())),
                        read(p.first, EntityTypes.BOOKING_CALL)[p.second])) runCatching { onWritten(p.first) }
                VoiceReply.Connect
            }
        }
        is VoiceEvent.BookingTakenDialed -> takenDialed(event)
        is VoiceEvent.BookingListenAnswered -> listenAnswered(event)
        is VoiceEvent.BookingListenStatus -> listenEnded(event)
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

    /** Slice 3b: the household and card of booking call [callId] while it's being handed to him, else null. */
    private fun handingOver(callId: String?): Pair<String, String>? = callId?.takeIf { it.isNotBlank() }?.let { handing[it] }

    /** Slice 3b: calls being handed to him (his mobile rung), by call id: the household and card. Memory only. */
    private val handing = ConcurrentHashMap<String, Pair<String, String>>()

    /** Slice 3b: ring his mobile from the assistant's number; without one the business hears he'll call back. */
    private fun handOver(callId: String): VoiceReply {
        val p = pending[callId] ?: return VoiceReply.Goodbye(BookingLiveRules.TAKE_OVER_SORRY)
        val profile = read(p.first, EntityTypes.HOUSEHOLD_PROFILE)[AboutYouRules.ENTITY_ID].orEmpty()
        fun text(f: String) = (profile[f]?.value as? FieldValue.Text)?.value
        val mobile = PutThroughRules.dialable(text(AboutYouFields.MOBILE))
        val from = PutThroughRules.dialable(text(AboutYouFields.ASSISTANT_NUMBER))
        if (mobile == null || from == null) {
            missed(p.first, p.second)
            return VoiceReply.Goodbye(BookingLiveRules.TAKE_OVER_SORRY)
        }
        handing[callId] = p
        System.err.println("booking call: putting the call through to the owner") // no names, no numbers
        return VoiceReply.TakeOver(mobile, from)
    }

    /** Slice 3b: his mobile's leg is over: taken (how long, on the meter) or missed (the business was told he'll call back). */
    private fun takenDialed(e: VoiceEvent.BookingTakenDialed): VoiceReply {
        val (hh, id) = handing.remove(e.callId) ?: return VoiceReply.HangUp
        val head = read(hh, EntityTypes.BOOKING_CALL)[id] ?: return VoiceReply.HangUp
        val c = BookingCallRules.read(id, head.mapValues { it.value.value }) ?: return VoiceReply.HangUp
        if (c.takenAtMs == null) {
            missed(hh, id)
            return VoiceReply.Goodbye(BookingLiveRules.TAKE_OVER_SORRY)
        }
        val secs = e.seconds.coerceAtLeast(0)
        var wrote = write(hh, EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.YOUR_SECONDS to FieldValue.Int64(secs),
            BookingCallFields.RESULT to FieldValue.Text(BookingLiveRules.takenLine(true, secs, c.result).take(BookingDialRules.MAX_LINE)),
        ), head)
        val e164 = (BookingCallRules.numberCheck(c.number, emptyList()) as? os.meka.core.domain.NumberCheck.Ok)?.e164
        val meter = line.recordHandOver(hh, e.callId, e164, secs)
        val heads = read(hh, EntityTypes.CONTEXT_MODE)[LiveCallRules.METER_ID]
        if (write(hh, EntityTypes.CONTEXT_MODE, LiveCallRules.METER_ID, mapOf(
                LiveCallRules.METER_MONTH to FieldValue.Text(meter.month), LiveCallRules.METER_PENCE to FieldValue.Int64(meter.spentPence),
                LiveCallRules.METER_CALLS to FieldValue.Int64(meter.calls),
            ), heads)) wrote = true
        if (wrote) runCatching { onWritten(hh) }
        return VoiceReply.HangUp
    }

    /** Slice 3b: he didn't take it: Not booked, they were told he'll call back (from taken, or still on the call). */
    private fun missed(hh: String, id: String) {
        val closed = close(hh, id, BookingCallState.NOT_BOOKED, BookingLiveRules.TAKE_OVER_MISSED, from = BookingCallState.TAKEN) ||
            close(hh, id, BookingCallState.NOT_BOOKED, BookingLiveRules.TAKE_OVER_MISSED, from = BookingCallState.CALLING)
        if (closed) runCatching { onWritten(hh) }
    }

    /** Slice 3b: whether he tapped Take over on this booking call's card (and his mobile is in About you to ring). */
    fun takeOverAsked(t: LiveCallTicket): Boolean {
        val id = t.bookingId ?: return false
        val hh = t.householdId
        if (read(hh, EntityTypes.BOOKING_CALL)[id]?.get(BookingCallFields.TAKE_OVER)?.value != FieldValue.Bool(true)) return false
        val mobile = (read(hh, EntityTypes.HOUSEHOLD_PROFILE)[AboutYouRules.ENTITY_ID]?.get(AboutYouFields.MOBILE)?.value as? FieldValue.Text)?.value
        return PutThroughRules.dialable(mobile) != null
    }

    /** Slice 3c: booking calls whose listening leg was rung (by the business's call id): once a call. Memory only. */
    private val listenRung = ConcurrentHashMap.newKeySet<String>()

    /** Slice 3c: his listening legs being rung or listening, by the leg's call id: the ticket. Memory only. */
    private val listenLegs = ConcurrentHashMap<String, Leg>()
    private data class Leg(val token: String, val householdId: String, val bookingId: String)

    /** Slice 3c: whether he tapped Listen on this booking call's card and his leg isn't rung yet. */
    fun listenAsked(t: LiveCallTicket): Boolean {
        val id = t.bookingId ?: return false
        if (listen == null || t.callId in listenRung) return false
        val f = read(t.householdId, EntityTypes.BOOKING_CALL)[id] ?: return false
        fun yes(k: String) = f[k]?.value == FieldValue.Bool(true)
        return yes(BookingCallFields.LISTEN) && !yes(BookingCallFields.HANG_UP) && !yes(BookingCallFields.TAKE_OVER)
    }

    /**
     * Slice 3c: rings his mobile from the assistant's number for booking call [t]: the whisper, press 1, then he hears
     * both sides, muted. Without his mobile, the assistant's number, the month's room or a live call, it says so on the card.
     */
    fun ringToListen(t: LiveCallTicket) {
        val id = t.bookingId ?: return
        val hub = listen ?: return
        if (!listenRung.add(t.callId)) return
        val hh = t.householdId
        val profile = read(hh, EntityTypes.HOUSEHOLD_PROFILE)[AboutYouRules.ENTITY_ID].orEmpty()
        fun text(f: String) = (profile[f]?.value as? FieldValue.Text)?.value
        val mobile = PutThroughRules.dialable(text(AboutYouFields.MOBILE))
        val from = PutThroughRules.dialable(text(AboutYouFields.ASSISTANT_NUMBER))
        val d = dialer
        val token = if (mobile == null || from == null || d == null || line.spentPence() >= line.capPence(hh)) null else hub.ticket(hh, id, t.callId)
        val sid = token?.let { tk ->
            runCatching { d!!.dial(mobile!!, from!!, listenTwiml(), listenStatusUrl, BookingLiveRules.LISTEN_RING_SECONDS, BookingDialRules.LIMIT_SECONDS) }.getOrNull()
                .also { if (it == null) hub.drop(tk) }
        }
        if (sid == null || token == null) return listenOver(hh, id)
        listenLegs[sid] = Leg(token, hh, id)
        System.err.println("booking call: ringing the owner to listen in") // no names, no numbers
    }

    /** Slice 3c: his key on the listening leg: 1 (and the call still live) starts the sound; anything else hangs up. */
    private fun listenAnswered(e: VoiceEvent.BookingListenAnswered): VoiceReply {
        val token = listenLegs[e.callId]?.token ?: return VoiceReply.HangUp
        val t = listen?.peek(token)
        if (t == null || !PutThroughRules.takes(e.digits)) return VoiceReply.HangUp
        val head = read(t.householdId, EntityTypes.BOOKING_CALL)[t.bookingId]
        if (write(t.householdId, EntityTypes.BOOKING_CALL, t.bookingId, mapOf(BookingCallFields.LISTENING_AT to FieldValue.Int64(now())), head)) {
            runCatching { onWritten(t.householdId) }
        }
        return VoiceReply.ListenStream(token)
    }

    /** Slice 3c: his listening leg is over: its minutes on the meter (not a call), and the card stops saying he listens. */
    private fun listenEnded(e: VoiceEvent.BookingListenStatus): VoiceReply {
        val leg = listenLegs.remove(e.callId) ?: return VoiceReply.Done
        listen?.drop(leg.token)
        val hh = leg.householdId
        val meter = line.recordListen(hh, e.callId, e.seconds)
        var wrote = false
        if (e.seconds > 0) {
            val heads = read(hh, EntityTypes.CONTEXT_MODE)[LiveCallRules.METER_ID]
            wrote = write(hh, EntityTypes.CONTEXT_MODE, LiveCallRules.METER_ID, mapOf(
                LiveCallRules.METER_MONTH to FieldValue.Text(meter.month), LiveCallRules.METER_PENCE to FieldValue.Int64(meter.spentPence),
                LiveCallRules.METER_CALLS to FieldValue.Int64(meter.calls),
            ), heads)
        }
        if (wrote) runCatching { onWritten(hh) }
        // While the call is still on, the card goes back to Obi is on the call; a closed card needs nothing.
        listenOver(hh, leg.bookingId)
        return VoiceReply.Done
    }

    /** Slice 3c: the card no longer says he's listening (or being rung to), while the call is still on. */
    private fun listenOver(hh: String, id: String) {
        val head = read(hh, EntityTypes.BOOKING_CALL)[id] ?: return
        val c = BookingCallRules.read(id, head.mapValues { it.value.value }) ?: return
        if (c.state != BookingCallState.CALLING) return
        if (write(hh, EntityTypes.BOOKING_CALL, id, mapOf(BookingCallFields.LISTENING_AT to FieldValue.Int64(-1)), head)) runCatching { onWritten(hh) }
    }

    /** Slice 3: whether he tapped Hang up on the card this booking call is for. */
    fun hangUpAsked(t: LiveCallTicket): Boolean {
        val id = t.bookingId ?: return false
        return read(t.householdId, EntityTypes.BOOKING_CALL)[id]?.get(BookingCallFields.HANG_UP)?.value == FieldValue.Bool(true)
    }

    /**
     * Slice 3: the live view's last lines ([words], [os.meka.core.domain.BookingWords] encoded; the business's words
     * display only) on the card while it's on the call, and when Obi started talking the first time.
     */
    fun liveWords(t: LiveCallTicket, words: String) {
        val hh = t.householdId
        val id = t.bookingId ?: return
        val head = read(hh, EntityTypes.BOOKING_CALL)[id] ?: return
        val c = BookingCallRules.read(id, head.mapValues { it.value.value }) ?: return
        if (c.state != BookingCallState.CALLING) return
        val fields = buildMap<String, FieldValue> {
            put(BookingCallFields.WORDS, FieldValue.Text(words.take(BookingLiveRules.MAX_LIVE + BookingLiveRules.LIVE_LINES * 3)))
            if (c.answeredAtMs == null) put(BookingCallFields.ANSWERED_AT, FieldValue.Int64(now()))
        }
        if (write(hh, EntityTypes.BOOKING_CALL, id, fields, head)) runCatching { onWritten(hh) }
    }

    /** A booking call's session ended: the card's result, and the month's spend for both apps' meter. */
    fun ended(t: LiveCallTicket, end: LiveCallEnd, meter: LiveCallRules.Meter) {
        val hh = t.householdId
        val id = t.bookingId ?: return
        val card = read(hh, EntityTypes.BOOKING_CALL)[id]?.let { f -> BookingCallRules.read(id, f.mapValues { it.value.value }) }
        val (state, text) = BookingDialRules.result(end.live, end.booking, card, end.hungUp, end.takenOver)
        // Slice 3: the result card's length, the time and name agreed (the time only inside the card's window) and the words.
        val extra = buildMap<String, FieldValue> {
            if (end.live) put(BookingCallFields.SECONDS, FieldValue.Int64(end.seconds))
            put(BookingCallFields.WORDS, FieldValue.Text(""))
            if (end.live && end.words.isNotBlank()) put(BookingCallFields.TRANSCRIPT, FieldValue.Text(end.words.take(BookingLiveRules.MAX_TRANSCRIPT + BookingLiveRules.MAX_LINES * 3)))
            if (state == BookingCallState.BOOKED) {
                BookingLiveRules.agreedMinute(end.booking?.time, card?.fromMin, card?.toMin)?.let { put(BookingCallFields.BOOKED_MIN, FieldValue.Int64(it.toLong())) }
                end.booking?.name?.let { put(BookingCallFields.BOOKED_NAME, FieldValue.Text(it)) }
            }
        }
        var wrote = close(hh, id, state, text, from = BookingCallState.CALLING, extra = extra)
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
    @Synchronized private fun close(
        hh: String, id: String, state: BookingCallState, text: String, from: BookingCallState = BookingCallState.APPROVED,
        extra: Map<String, FieldValue> = emptyMap(),
    ): Boolean {
        val head = read(hh, EntityTypes.BOOKING_CALL)[id] ?: return false
        val c = BookingCallRules.read(id, head.mapValues { it.value.value }) ?: return false
        if (c.state != from || (from == BookingCallState.APPROVED && c.cancelled)) return false
        return write(hh, EntityTypes.BOOKING_CALL, id, mapOf(
            BookingCallFields.STATE to FieldValue.Text(state.code),
            BookingCallFields.RESULT to FieldValue.Text(text.take(BookingDialRules.MAX_LINE)),
            BookingCallFields.ENDED_AT to FieldValue.Int64(now()),
        ) + extra, head)
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
