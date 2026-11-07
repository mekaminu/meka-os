package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.BankHolidayFields
import os.meka.core.domain.BankHolidayStore
import os.meka.core.domain.BookingDialRules
import os.meka.core.domain.BookingLiveRules
import os.meka.core.domain.BookingWords
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.FreeSlot
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.LiveCallRules
import os.meka.core.domain.LiveModeRules
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.wire.LiveProtocol
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/*
 * Obi on calls, part A slice 1 (Meka approved 2026-11-07): a caller talks to Obi live.
 *
 * The phone service streams the call's audio to [LiveCallRoutes.PATH] (Twilio Media Streams: 8 kHz μ-law, base64, in
 * JSON frames); the server opens a Gemini Live session with the key it already holds (nothing is handed to any device)
 * and carries audio both ways ([CallAudio]), Obi's two tools ([LiveCallRules.TAKE_MESSAGE], [LiveCallRules.END_CALL])
 * being the only things he can do. When the stream ends the phone service asks [VoiceStep.LIVE_ENDED]: a call that
 * never went live (Google slow or refusing) falls back to the scripted turns, so a caller is always answered.
 * Nothing of what is said is logged; the message lands in the after-work summary like any other.
 */

/** The phone service's audio and Gemini Live's, converted (PCM16 little-endian; μ-law per ITU G.711). */
object CallAudio {
    fun muLawToPcm(b: ByteArray): ShortArray = ShortArray(b.size) { decode(b[it].toInt() and 0xFF) }

    fun pcmToMuLaw(s: ShortArray): ByteArray = ByteArray(s.size) { encode(s[it].toInt()) }

    private fun decode(u: Int): Short {
        val x = u.inv() and 0xFF
        val exp = (x shr 4) and 0x07
        val mant = x and 0x0F
        val mag = (((mant shl 3) + 0x84) shl exp) - 0x84
        return (if (x and 0x80 != 0) -mag else mag).toShort()
    }

    private fun encode(pcm: Int): Byte {
        var s = pcm
        val sign = if (s < 0) { s = -s; 0x80 } else 0
        if (s > 32_635) s = 32_635
        s += 0x84
        var exp = 7
        var mask = 0x4000
        while (s and mask == 0 && exp > 0) { exp--; mask = mask shr 1 }
        val mant = (s shr (exp + 3)) and 0x0F
        return ((sign or (exp shl 4) or mant).inv() and 0xFF).toByte()
    }

    /** 8 kHz → 16 kHz, linear between neighbours (the last sample held). */
    fun up8to16(s: ShortArray): ShortArray {
        val out = ShortArray(s.size * 2)
        for (i in s.indices) {
            val a = s[i].toInt()
            val b = if (i + 1 < s.size) s[i + 1].toInt() else a
            out[2 * i] = a.toShort()
            out[2 * i + 1] = ((a + b) / 2).toShort()
        }
        return out
    }

    fun pcmBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            val v = s[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun pcmShorts(b: ByteArray): ShortArray = ShortArray(b.size / 2) { i ->
        ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort()
    }

    /** One phone-service media payload (base64 μ-law, 8 kHz) as Gemini's input (base64 PCM16, 16 kHz). */
    fun callerToModel(payload: String): String? {
        val raw = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Base64.getEncoder().encodeToString(pcmBytes(up8to16(muLawToPcm(raw))))
    }

    /**
     * Gemini's 24 kHz PCM16 down to the phone's 8 kHz μ-law: each three samples averaged (a cheap low-pass), with any
     * odd byte or one or two samples left at a chunk's end carried to the next.
     */
    class Down24to8 {
        private var carryByte: Int = -1
        private val carry = ArrayList<Int>(3)

        fun push(chunk: ByteArray): ByteArray {
            val bytes = if (carryByte >= 0) byteArrayOf(carryByte.toByte()) + chunk else chunk
            carryByte = if (bytes.size % 2 == 1) bytes.last().toInt() and 0xFF else -1
            val samples = pcmShorts(if (carryByte >= 0) bytes.copyOf(bytes.size - 1) else bytes)
            val out = ArrayList<Short>(samples.size / 3 + 1)
            for (v in samples) {
                carry += v.toInt()
                if (carry.size == 3) {
                    out += (carry.sum() / 3).toShort()
                    carry.clear()
                }
            }
            return pcmToMuLaw(out.toShortArray())
        }

        fun reset() { carryByte = -1; carry.clear() }
    }
}

/** One live call as the meter keeps it. */
data class LiveCallRow(val id: String, val householdId: String, val month: String, val startedAtMs: Long, val seconds: Long, val microUsd: Long)

interface LiveCallStore {
    /** Adds the call once (a second add of the same id is ignored). */
    fun add(row: LiveCallRow)
    fun monthMicroUsd(month: String): Long
    fun monthCalls(month: String): Long
}

class InMemoryLiveCallStore : LiveCallStore {
    private val rows = LinkedHashMap<String, LiveCallRow>()
    @Synchronized override fun add(row: LiveCallRow) { rows.putIfAbsent(row.id, row) }
    @Synchronized override fun monthMicroUsd(month: String) = rows.values.filter { it.month == month }.sumOf { it.microUsd }
    @Synchronized override fun monthCalls(month: String) = rows.values.count { it.month == month }.toLong()
}

class PostgresLiveCallStore(private val ds: DataSource) : LiveCallStore {
    override fun add(row: LiveCallRow) = ds.connection.use { c ->
        c.prepareStatement(
            "INSERT INTO live_call(id, household_id, month, started_at_ms, seconds, micro_usd) VALUES (?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
        ).use {
            it.setString(1, row.id); it.setString(2, row.householdId); it.setString(3, row.month); it.setLong(4, row.startedAtMs)
            it.setLong(5, row.seconds); it.setLong(6, row.microUsd)
            it.executeUpdate()
        }
        Unit
    }

    override fun monthMicroUsd(month: String): Long = one("SELECT COALESCE(SUM(micro_usd), 0) FROM live_call WHERE month = ?", month)
    override fun monthCalls(month: String): Long = one("SELECT COUNT(*) FROM live_call WHERE month = ?", month)

    private fun one(sql: String, month: String): Long = ds.connection.use { c ->
        c.prepareStatement(sql).use { st -> st.setString(1, month); st.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }
    }
}

/** A call the phone service was told to stream: who it's for and how Obi starts. Memory only, one use. */
data class LiveCallTicket(
    val token: String, val householdId: String, val provider: String, val callId: String, val from: String?,
    val atWork: Boolean, val instruction: String, val mintedAtMs: Long,
    /** Slice 1b: an urgent caller may be put through (his mobile and the assistant's number usable, not his own number calling). */
    val canPutThrough: Boolean = false,
    /** Slice 1c: the free times Obi may offer for a callback ([LiveCallRules.callbackSlots]); empty = none offered. */
    val callbackSlots: List<FreeSlot> = emptyList(),
    /** Slice 2b: an outbound booking call for this call card (Obi's tools are then [BookingDialRules.BOOKING_RESULT] and end call). */
    val bookingId: String? = null,
    /** Slice 2b: the number the booking call rings (for its cost). */
    val bookingNumber: String? = null,
) {
    val booking: Boolean get() = bookingId != null
    /** How long the ticket may wait for its stream: a booking call rings first. */
    val ttlMs: Long get() = if (booking) BookingDialRules.TICKET_MS else LiveCallRules.TICKET_MS
}

/** How a live call went, for the summary and the meter. [message] and [heard] are the caller's: untrusted, display only. */
data class LiveCallEnd(
    val live: Boolean, val seconds: Long, val obiSeconds: Long, val message: LiveCallRules.Message?, val heard: String,
    /** Slice 1b: Obi told the caller he'd try to put them through ([LiveCallRules.PUT_THROUGH_ANSWER]). */
    val putThrough: Boolean = false,
    /** Slice 2b: what came of a booking call ([BookingDialRules.BOOKING_RESULT]); the business's words, display only. */
    val booking: BookingDialRules.Outcome? = null,
    /** Slice 3: a booking call's words ([BookingWords.all]) and whether he tapped Hang up. */
    val words: String = "",
    val hungUp: Boolean = false,
)

/**
 * Whether a call goes live, and its tickets: "Live conversation" on ([WorkFields.CALL_LIVE]), the Gemini key in, and
 * the month's spend under the cap ([WorkFields.CALL_LIVE_CAP]). The spend counts every call Obi talked to.
 */
class LiveCallLine(
    private val fields: FieldReader,
    private val store: LiveCallStore,
    /** Whether the Gemini key is in. */
    private val configured: () -> Boolean,
    private val gbpPerUsd: () -> Long? = { null },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /** Slice 1c: the household's synced calendar events, for the free callback times (only the times reach Obi). */
    private val events: (String) -> List<CalendarEvent> = { emptyList() },
    private val calendar: LocalCalendar = LondonCalendar,
) {
    private val tickets = ConcurrentHashMap<String, LiveCallTicket>()
    /** Calls that went live, by call id, until a few minutes after (the stream's end step asks). */
    private val wentLive = ConcurrentHashMap<String, Long>()
    /** How each call that went live ended, by call id, for the stream's end step (the put-through) and its whisper. */
    private val outcomes = ConcurrentHashMap<String, Pair<Long, LiveCallEnd>>()

    fun month(): String = LiveCallRules.month(nowMs())

    fun spentPence(month: String = month()): Long = LiveModeRules.pence(runCatching { store.monthMicroUsd(month) }.getOrDefault(0), rate())

    fun capPence(hh: String): Long =
        LiveCallRules.capGbp((fields.latest(hh, EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.CALL_LIVE_CAP) as? FieldValue.Int64)?.value) * 100L

    /** A one-use ticket for this call when it may go live, else null (the scripted turns answer). Never throws. */
    fun ticket(hh: String, provider: String, callId: String, from: String?, atWork: Boolean, canPutThrough: Boolean = false): String? = runCatching {
        val on = fields.latest(hh, EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.CALL_LIVE) == FieldValue.Bool(true)
        if (!on || !configured()) return null
        if (!LiveCallRules.allowed(on, spentPence(), capPence(hh))) return null
        val now = nowMs()
        tickets.entries.removeIf { now - it.value.mintedAtMs > it.value.ttlMs }
        wentLive.entries.removeIf { now - it.value > KEEP_MS }
        outcomes.entries.removeIf { now - it.value.first > KEEP_MS }
        if (tickets.size >= MAX_TICKETS) return null
        val knows = LiveCallRules.knows((fields.latest(hh, EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.CALL_KNOWS) as? FieldValue.Text)?.value)
        val first = (fields.latest(hh, EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, AboutYouFields.SHOWN_NAME) as? FieldValue.Text)
            ?.value?.trim()?.split(' ')?.firstOrNull()?.takeIf { n -> n.length in 1..30 && n.all { it.isLetter() || it in "-'" } }
        val slots = if (LiveCallRules.Knows.BACK in knows) callbackSlots(hh, now) else emptyList()
        val labels = slots.map { LiveCallRules.callbackLabel(it, now, calendar) }
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tickets[token] = LiveCallTicket(
            token, hh, provider, callId, from, atWork, LiveCallRules.instruction(atWork, knows, first, null, null, labels), now, canPutThrough, slots,
        )
        token
    }.getOrNull()

    /**
     * Slice 2b: a one-use ticket for an outbound booking call on card [cardId] to [e164], with Obi's [instruction]. Its
     * call id is bound once the phone service has made the call ([bind]). Null when Gemini isn't set up or too many wait.
     */
    fun bookingTicket(hh: String, provider: String, cardId: String, e164: String, instruction: String): String? = runCatching {
        if (!configured()) return null
        val now = nowMs()
        tickets.entries.removeIf { now - it.value.mintedAtMs > it.value.ttlMs }
        if (tickets.size >= MAX_TICKETS) return null
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tickets[token] = LiveCallTicket(token, hh, provider, "", null, false, instruction, now, bookingId = cardId, bookingNumber = e164)
        token
    }.getOrNull()

    /** Slice 2b: the phone service made the booking call [callId] for ticket [token]: only that call's stream may claim it. */
    fun bind(token: String, callId: String) { tickets.computeIfPresent(token) { _, t -> if (t.callId.isEmpty()) t.copy(callId = callId) else t } }

    /** Slice 2b: a booking ticket whose call was never made. */
    fun drop(token: String) { tickets.remove(token) }

    /** Whether Gemini is set up for live calls. */
    fun ready(): Boolean = runCatching { configured() }.getOrDefault(false)

    /** Free callback times from the synced work schedule, bank holidays and events; none when any of it can't be read. */
    private fun callbackSlots(hh: String, now: Long): List<FreeSlot> = runCatching {
        fun text(entity: String, field: String) = (fields.latest(hh, EntityTypes.CONTEXT_MODE, entity, field) as? FieldValue.Text)?.value
        LiveCallRules.callbackSlots(
            now, text(WorkMode.ENTITY_ID, WorkFields.SCHEDULE), text(BankHolidayStore.ENTITY_ID, BankHolidayFields.DATES), events(hh), calendar,
        )
    }.getOrDefault(emptyList())

    /** The callback a live call agreed: one of the ticket's offered times, or null. */
    fun agreedCallback(t: LiveCallTicket, m: LiveCallRules.Message?): FreeSlot? =
        LiveCallRules.pickedCallback(m?.callbackTime, t.callbackSlots, t.mintedAtMs, calendar)

    fun callbackLine(slot: FreeSlot): String = LiveCallRules.callbackLine(slot.startMs, calendar)

    /** The ticket for the stream that opened with [token] for [callId]; used once, and only within [LiveCallRules.TICKET_MS]. */
    fun claim(token: String?, callId: String?): LiveCallTicket? {
        val t = token?.takeIf { it.length == 32 }?.let { tickets.remove(it) } ?: return null
        if (t.callId.isEmpty() || t.callId != callId || nowMs() - t.mintedAtMs > t.ttlMs) return null
        return t
    }

    /** Google accepted the call's session: from now on the stream's end means the call is over. */
    fun markLive(callId: String) { wentLive[callId] = nowMs() }

    fun wasLive(callId: String): Boolean = wentLive.containsKey(callId)

    /** Kept as soon as the session ends (before the phone side is told), so the end step sees it. */
    fun ended(callId: String, end: LiveCallEnd) { if (end.live) outcomes[callId] = nowMs() to end }

    /** How the live call [callId] ended, within [KEEP_MS]; null when it never went live or is unknown. */
    fun outcome(callId: String): LiveCallEnd? = outcomes[callId]?.takeIf { nowMs() - it.first <= KEEP_MS }?.second

    /** Counts a call that went live; this month's spend and calls after it. */
    fun record(t: LiveCallTicket, end: LiveCallEnd): LiveCallRules.Meter {
        val cost = if (t.booking) BookingDialRules.costMicroUsd(t.bookingNumber, end.seconds, end.obiSeconds) else LiveCallRules.costMicroUsd(end.seconds, end.obiSeconds)
        if (end.live) runCatching {
            store.add(LiveCallRow(t.token, t.householdId, month(), t.mintedAtMs, end.seconds, cost))
        }
        val m = month()
        return LiveCallRules.Meter(m, spentPence(m), runCatching { store.monthCalls(m) }.getOrDefault(0))
    }

    private fun rate(): Long = runCatching { gbpPerUsd() }.getOrNull()?.takeIf { it > 0 } ?: LiveModeRules.FALLBACK_GBP_PER_USD_MICROS

    companion object {
        const val KEEP_MS = 15 * 60_000L
        const val MAX_TICKETS = 20
    }
}

/** The model's side of a live call: text frames to Google, closed once. */
interface ModelLink {
    fun send(text: String): Boolean
    fun close()
}

/** Opens a model link: [onText] gets each whole message from Google, [onClosed] once when it's gone. Null: couldn't open. */
fun interface ModelDial {
    fun open(onText: (String) -> Unit, onClosed: () -> Unit): ModelLink?
}

/**
 * One live call, between the phone service's stream and Gemini Live. Every entry point is synchronised: the phone
 * side's frames, Google's messages and the clock come from different threads. [toCaller] queues a frame for the phone
 * service; [hangUp] closes its stream (the phone service then asks [VoiceStep.LIVE_ENDED]).
 */
class LiveCallSession(
    val ticket: LiveCallTicket,
    private val streamSid: String,
    private val dial: ModelDial,
    private val model: String,
    private val voice: String,
    private val toCaller: (String) -> Unit,
    private val hangUp: () -> Unit,
    private val onLive: () -> Unit,
    private val onEnd: (LiveCallEnd) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = nowMs()
    private var link: ModelLink? = null
    private var live = false
    private var wrapped = false
    private var endingAt: Long? = null
    private var message: LiveCallRules.Message? = null
    private var putThrough = false
    private var outcome: BookingDialRules.Outcome? = null
    /** Slice 3: a booking call's words as said, for the live view and Read the call; Hang up and its goodbye. */
    private val words = BookingWords()
    private var hungUp = false
    private var awaitingBye = false
    private val heard = StringBuilder()
    private var callerBytes = 0L
    private var obiBytes = 0L
    private val down = CallAudio.Down24to8()

    @Volatile var done = false
        private set

    /** Opens Google's side and sends the setup; a failure ends the call at once (the scripted turns take it). */
    fun start() {
        val l = runCatching { dial.open(::fromModel, ::modelClosed) }.getOrNull()
        synchronized(this) {
            if (done) { l?.close(); return }
            val ok = l ?: return finish()
            link = ok
            if (!ok.send(setup(model, voice, ticket.instruction, ticket.booking))) finish()
        }
    }

    /** A frame from the phone service. */
    @Synchronized fun fromCaller(text: String) {
        if (done) return
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (str(o, "event")) {
            "media" -> {
                val media = o["media"] as? JsonObject ?: return
                if (str(media, "track").let { it != null && it != "inbound" }) return
                val payload = str(media, "payload") ?: return
                callerBytes += payload.length * 3L / 4
                if (!live) return
                CallAudio.callerToModel(payload)?.let { link?.send(LiveProtocol.audio(it)) }
            }
            "mark" -> if (str(o["mark"] as? JsonObject ?: return, "name") == BYE_MARK && endingAt != null) finish()
            "stop" -> finish()
        }
    }

    /** The phone side went away without a stop. */
    @Synchronized fun callerGone() { if (!done) finish() }

    /** The clock: connection timeout, the wrap-up, the longest call and the goodbye's latest end. */
    @Synchronized fun tick() {
        if (done) return
        val now = nowMs()
        val secs = (now - startedAt) / 1_000
        when {
            !live && now - startedAt > LiveCallRules.CONNECT_TIMEOUT_MS -> finish()
            secs >= LiveCallRules.MAX_SECONDS -> finish()
            endingAt?.let { now >= it } == true -> finish()
            secs >= LiveCallRules.WRAP_UP_SECONDS && !wrapped -> {
                wrapped = true
                link?.send(turn(if (ticket.booking) BookingDialRules.WRAP_UP else LiveCallRules.WRAP_UP))
            }
        }
    }

    @Synchronized private fun fromModel(text: String) {
        if (done) return
        for (e in LiveProtocol.decode(text)) when (e) {
            LiveProtocol.Event.SetupComplete -> if (!live) {
                live = true
                runCatching { onLive() }
                link?.send(turn(if (ticket.booking) BookingDialRules.KICKOFF else LiveCallRules.KICKOFF))
            }
            is LiveProtocol.Event.Audio -> for (chunk in e.chunks) {
                val pcm = runCatching { Base64.getDecoder().decode(chunk) }.getOrNull() ?: continue
                obiBytes += pcm.size
                val mu = down.push(pcm)
                if (mu.isNotEmpty()) toCaller(media(mu))
            }
            LiveProtocol.Event.Interrupted -> {
                down.reset()
                toCaller(buildJsonObject { put("event", "clear"); put("streamSid", streamSid) }.toString())
            }
            is LiveProtocol.Event.Heard -> {
                if (heard.length < MAX_HEARD) heard.append(e.text.take(MAX_HEARD - heard.length))
                if (ticket.booking) words.add(false, e.text)
            }
            is LiveProtocol.Event.Said -> if (ticket.booking) {
                words.add(true, e.text)
                awaitingBye = false
            }
            is LiveProtocol.Event.ToolCall -> when {
                // Slice 2b: a booking call has its own result tool and no message-taking.
                ticket.booking && e.name == BookingDialRules.BOOKING_RESULT -> {
                    if (outcome == null) outcome = BookingDialRules.outcome(e.args)
                    link?.send(LiveProtocol.toolAnswer(e.id, e.name, "Noted. Thank them, say goodbye in one sentence, then call ${LiveCallRules.END_CALL}."))
                }
                !ticket.booking && e.name == LiveCallRules.TAKE_MESSAGE -> {
                    val first = message == null
                    if (first) message = LiveCallRules.message(e.args)
                    // Slice 1b: a genuine urgent caller is told to hold; the end step rings his mobile.
                    if (first) putThrough = LiveCallRules.offersPutThrough(message!!, ticket.canPutThrough)
                    link?.send(LiveProtocol.toolAnswer(e.id, e.name, if (putThrough) LiveCallRules.PUT_THROUGH_ANSWER else LiveCallRules.TAKEN_ANSWER))
                }
                e.name == LiveCallRules.END_CALL -> {
                    if (endingAt == null) endingAt = nowMs() + LiveCallRules.GOODBYE_MS
                    link?.send(LiveProtocol.toolAnswer(e.id, e.name, "Ending the call after your goodbye."))
                }
                else -> link?.send(LiveProtocol.toolAnswer(e.id, e.name, null, error = "Not available on calls."))
            }
            LiveProtocol.Event.TurnComplete -> if (endingAt != null && !awaitingBye) {
                // Played out on the phone side, the mark comes back: then the line closes.
                toCaller(buildJsonObject { put("event", "mark"); put("streamSid", streamSid); putJsonObject("mark") { put("name", BYE_MARK) } }.toString())
            }
            else -> Unit
        }
    }

    @Synchronized private fun modelClosed() { if (!done) finish() }

    /** Slice 3: a booking call's last few lines when they changed (the first time once Obi is live: empty); null otherwise. */
    @Synchronized fun liveWords(): String? = if (done || !live || !ticket.booking) null else words.liveIfChanged()

    /** Slice 3: he tapped Hang up: Obi says goodbye and the line closes within [BookingLiveRules.HANG_UP_GRACE_MS]. */
    @Synchronized fun hangUpAsked() {
        if (done || hungUp || !ticket.booking) return
        hungUp = true
        if (!live) return finish()
        awaitingBye = true
        val by = nowMs() + BookingLiveRules.HANG_UP_GRACE_MS
        endingAt = endingAt?.coerceAtMost(by) ?: by
        link?.send(turn(BookingLiveRules.HUNG_UP_TURN))
    }

    private fun finish() {
        if (done) return
        done = true
        runCatching { link?.close() }
        val seconds = ((nowMs() - startedAt) / 1_000).coerceAtLeast(0)
        val obi = obiBytes / (24_000 * 2)
        // The outcome first, then the phone side: its end step (the put-through) must find it.
        runCatching {
            onEnd(LiveCallEnd(live, seconds, obi.coerceAtMost(seconds), message, heard.toString(), putThrough && live, outcome,
                words = if (ticket.booking) words.all() else "", hungUp = hungUp))
        }
        runCatching { hangUp() }
    }

    private fun media(mu: ByteArray) = buildJsonObject {
        put("event", "media")
        put("streamSid", streamSid)
        putJsonObject("media") { put("payload", Base64.getEncoder().encodeToString(mu)) }
    }.toString()

    companion object {
        const val BYE_MARK = "obi-bye"
        const val MAX_HEARD = 4_000
        private val json = Json { ignoreUnknownKeys = true }
        private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        /** One user turn of plain text (the kick-off, the wrap-up), so Obi speaks next. */
        fun turn(text: String): String = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    })
                }
                put("turnComplete", true)
            }
        }.toString()

        /** The call's setup: audio answers in [voice], Obi's instruction, his two tools, and both sides transcribed. */
        fun setup(model: String, voice: String, instruction: String, booking: Boolean = false): String = buildJsonObject {
            putJsonObject("setup") {
                put("model", if (model.startsWith("models/")) model else "models/$model")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } }
                    }
                }
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", instruction) }) } }
                putJsonArray("tools") { add(buildJsonObject { putJsonArray("functionDeclarations") { if (booking) add(bookingResultTool()) else add(takeMessageTool()); add(endCallTool()) } }) }
                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}
            }
        }.toString()

        private fun takeMessageTool() = buildJsonObject {
            put("name", LiveCallRules.TAKE_MESSAGE)
            put("description", "Record the caller's message for the person you assist. Call once, when you know who is calling, what it's about and whether it's urgent (or they won't say).")
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    fun s(name: String, d: String) = putJsonObject(name) { put("type", "STRING"); put("description", d) }
                    s("who", "Who is calling, as they said it (name and organisation if given).")
                    s("about", "What it's about, in a few words.")
                    s("message", "Their message, in their words, briefly.")
                    s("callback_number", "A number they asked to be called back on, if they gave one.")
                    s("callback_time", "The call-back time they chose, exactly as one of the times you were allowed to offer; leave out otherwise.")
                    putJsonObject("urgent") { put("type", "BOOLEAN"); put("description", "True only if they said it's urgent.") }
                    putJsonObject("scam") { put("type", "BOOLEAN"); put("description", "True if it sounds like a scam.") }
                }
                putJsonArray("required") { add("who"); add("about"); add("urgent") }
            }
        }

        private fun bookingResultTool() = buildJsonObject {
            put("name", BookingDialRules.BOOKING_RESULT)
            put("description", "Record what came of the booking call. Call once, when it's booked or clear it can't be.")
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    fun s(name: String, d: String) = putJsonObject(name) { put("type", "STRING"); put("description", d) }
                    putJsonObject("booked") { put("type", "BOOLEAN"); put("description", "True only if they confirmed the booking.") }
                    s("agreed", "What was booked, briefly: day, time, party size and the name it's under.")
                    s("reference", "A booking reference they gave, if any.")
                    s("time", "The time booked, 24-hour HH:MM (for example 19:30), if it was booked.")
                    s("name", "The name the booking is under, if it was booked.")
                    s("offered", "Other times or options they offered, if it wasn't booked.")
                    s("reason", "Why it wasn't booked, in a few words (\"voicemail\" for a voicemail or recorded menu).")
                }
                putJsonArray("required") { add("booked") }
            }
        }

        private fun endCallTool() = buildJsonObject {
            put("name", LiveCallRules.END_CALL)
            put("description", "End the call after you have said goodbye.")
        }
    }
}

/**
 * Gemini Live over the JDK's WebSocket, the key in a header (never in the URL, so it can't reach a log). Google's
 * messages come as text or binary frames; each whole one goes to `onText`. Sends are one at a time (the JDK allows
 * only one outstanding).
 */
class GeminiLiveDial(
    private val key: () -> String?,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build(),
) : ModelDial {
    override fun open(onText: (String) -> Unit, onClosed: () -> Unit): ModelLink? {
        val k = key() ?: return null
        val deliver = onText
        val ended = onClosed
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun gone() { if (closed.compareAndSet(false, true)) runCatching { ended() } }
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            private var bin = java.io.ByteArrayOutputStream()

            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                text.append(data)
                if (last) { val t = text.toString(); text.setLength(0); runCatching { deliver(t) } }
                ws.request(1)
                return null
            }

            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val b = ByteArray(data.remaining()).also { data.get(it) }
                if (bin.size() + b.size <= MAX_MESSAGE) bin.write(b)
                if (last) { val t = bin.toString(Charsets.UTF_8); bin = java.io.ByteArrayOutputStream(); runCatching { deliver(t) } }
                ws.request(1)
                return null
            }

            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                System.err.println("live call: Google closed the session ($statusCode)") // no words, no numbers
                gone()
                return null
            }

            override fun onError(ws: WebSocket, error: Throwable) {
                System.err.println("live call: Google's session failed (${error.javaClass.simpleName})")
                gone()
            }
        }
        val ws = try {
            client.newWebSocketBuilder().header("x-goog-api-key", k).connectTimeout(java.time.Duration.ofSeconds(5))
                .buildAsync(URI(URL), listener).get(6, TimeUnit.SECONDS)
        } catch (e: Exception) {
            System.err.println("live call: couldn't open Google's session (${e.javaClass.simpleName})")
            return null
        }
        return object : ModelLink {
            private val lock = Any()
            override fun send(text: String): Boolean {
                synchronized(lock) {
                    if (closed.get()) return false
                    return runCatching { ws.sendText(text, true).get(5, TimeUnit.SECONDS) }.isSuccess
                }
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "call ended") }
            }
        }
    }

    companion object {
        /** The Live API with a key (the constrained endpoint is for ephemeral tokens). */
        const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MAX_MESSAGE = 4 * 1024 * 1024
    }
}

/** The stream route's dependencies. */
class LiveCallRoutes(val line: LiveCallLine, val dial: ModelDial, val model: String = LiveModeRules.model(System.getenv("MEKA_LIVE_MODEL")), val voice: String = LiveModeRules.DEFAULT_VOICE) {
    companion object {
        const val PATH = "/v1/voice/twilio/stream"

        /** The stream's address: the server's public HTTPS URL as wss. */
        fun streamUrl(publicUrl: String): String = publicUrl.trimEnd('/').replaceFirst(Regex("^https://"), "wss://").replaceFirst(Regex("^http://"), "ws://") + PATH
    }
}
