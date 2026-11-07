package os.meka.backend

import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Obi on calls, slice 3c: **one booking call's sound for his phone**, both sides mixed. The business's frames arrive in
 * real time (20 ms each) and set the pace; Obi's words, which Google sends faster than they're spoken, wait in a queue
 * and one frame's worth is mixed into each of the business's frames, so he hears them as the business does. Google
 * cutting Obi off ([clear]) empties the queue, as the phone service's own buffer is emptied. Memory only, nothing kept.
 */
class ListenMix(private val maxQueued: Int = 8_000 * 30) {
    private var queue = ByteArray(4_096)
    private var head = 0
    private var size = 0

    /** Obi's next μ-law bytes (8 kHz), as sent to the business. */
    @Synchronized fun obi(mu: ByteArray) {
        if (mu.isEmpty()) return
        val keep = (size + mu.size - maxQueued).coerceAtLeast(0)
        if (keep > 0) drop(keep.coerceAtMost(size))
        if (size + mu.size > queue.size) grow(size + mu.size)
        for (b in mu) { queue[(head + size) % queue.size] = b; size++ }
    }

    /** Google cut Obi off: what he hadn't said yet is gone. */
    @Synchronized fun clear() { head = 0; size = 0 }

    /** The business's frame [mu] with as much of Obi's queue mixed in (sample by sample, clipped). */
    @Synchronized fun business(mu: ByteArray): ByteArray {
        if (size == 0 || mu.isEmpty()) return mu
        val them = CallAudio.muLawToPcm(mu)
        val n = minOf(size, mu.size)
        val obi = ByteArray(n) { queue[(head + it) % queue.size] }
        drop(n)
        val his = CallAudio.muLawToPcm(obi)
        val out = ShortArray(them.size) { i ->
            val v = them[i].toInt() + (if (i < his.size) his[i].toInt() else 0)
            v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return CallAudio.pcmToMuLaw(out)
    }

    @Synchronized fun queued(): Int = size

    private fun drop(n: Int) { head = (head + n) % queue.size; size -= n; if (size == 0) head = 0 }

    private fun grow(min: Int) {
        val next = ByteArray(maxOf(min, queue.size * 2))
        for (i in 0 until size) next[i] = queue[(head + i) % queue.size]
        queue = next
        head = 0
    }
}

/**
 * Obi on calls, slice 3c: **the booking calls he can listen in on**. A booking call's session [open]s its call id while
 * Obi is on it and [end]s it when Obi's part is over; his listening leg's stream [claim]s a one-use ticket made when his
 * mobile was rung, then [attach]es: from then on each of the business's frames, mixed with Obi's words, goes to his
 * phone. He is muted: nothing from his leg ever reaches the call. Memory only; nothing heard is kept or logged.
 */
class BookingListen(private val now: () -> Long = System::currentTimeMillis) {
    data class Ticket(val token: String, val householdId: String, val bookingId: String, val callId: String, val mintedAtMs: Long)

    private class Ear(val mix: ListenMix, val send: (ByteArray) -> Unit, val close: () -> Unit)

    private val rng = SecureRandom()
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val ears = ConcurrentHashMap<String, Ear>()
    /** Obi's words while nobody listens yet are queued too, so the first frames he hears aren't cut mid-word. */
    private val waiting = ConcurrentHashMap<String, ListenMix>()

    fun open(callId: String) { active += callId }

    fun active(callId: String): Boolean = callId in active

    /** Obi's part of call [callId] is over: his leg's stream closes (his mobile hangs up), its tickets go. */
    fun end(callId: String) {
        active -= callId
        waiting.remove(callId)
        tickets.values.removeIf { it.callId == callId }
        ears.remove(callId)?.let { runCatching { it.close() } }
    }

    /** A ticket for his listening leg of call [callId] (only while it's live); null when it isn't. */
    fun ticket(householdId: String, bookingId: String, callId: String): String? {
        if (!active(callId)) return null
        tickets.values.removeIf { now() - it.mintedAtMs > TICKET_MS }
        if (tickets.size >= MAX_TICKETS) return null
        val token = ByteArray(18).also(rng::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        tickets[token] = Ticket(token, householdId, bookingId, callId, now())
        return token
    }

    /** The ticket [token] names, still valid and its call still live (not used up: [attach] uses it). */
    fun peek(token: String?): Ticket? {
        val t = token?.let { tickets[it] } ?: return null
        return t.takeIf { now() - it.mintedAtMs <= TICKET_MS && active(it.callId) }
    }

    fun drop(token: String) { tickets.remove(token) }

    /** His stream with ticket [token]: from now on he hears the call through [send]; [close] when it's over. One ear a call. */
    fun attach(token: String?, send: (ByteArray) -> Unit, close: () -> Unit): Ticket? {
        val t = token?.let { tickets.remove(it) } ?: return null
        if (now() - t.mintedAtMs > TICKET_MS || !active(t.callId)) return null
        val mix = waiting.remove(t.callId) ?: ListenMix()
        val old = ears.put(t.callId, Ear(mix, send, close))
        old?.let { runCatching { it.close() } }
        return t
    }

    /** His stream went away by itself. */
    fun detach(callId: String) { ears.remove(callId) }

    fun listening(callId: String): Boolean = ears.containsKey(callId)

    /** One of the business's frames (base64 μ-law), the pace of what he hears. */
    fun business(callId: String, payload: String) {
        val ear = ears[callId] ?: return
        val raw = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        runCatching { ear.send(ear.mix.business(raw)) }
    }

    /** Obi's words as sent to the business. */
    fun obi(callId: String, mu: ByteArray) {
        val ear = ears[callId]
        if (ear != null) return ear.mix.obi(mu)
        // A leg being rung: keep only the last couple of seconds, so he joins close to live.
        if (tickets.values.any { it.callId == callId }) waiting.getOrPut(callId) { ListenMix(8_000 * 2) }.obi(mu)
    }

    fun clear(callId: String) {
        ears[callId]?.mix?.clear()
        waiting[callId]?.clear()
    }

    companion object {
        /** His mobile rings up to 20 s and the whisper waits 8 s for his 1: a ticket lasts a minute. */
        const val TICKET_MS = 60_000L
        const val MAX_TICKETS = 10
        const val PATH = "/v1/voice/twilio/listen"

        fun streamUrl(publicUrl: String): String = LiveCallRoutes.streamUrl(publicUrl).removeSuffix(LiveCallRoutes.PATH) + PATH
    }
}

/**
 * Obi on calls, slice 3c: his listening leg's stream. Its start frame carries the ticket his mobile's markup gave it
 * ([VoiceReply.ListenStream]); without a valid one the stream is closed at once. The call's sound goes one way only,
 * to him; what his leg sends is never read beyond its stop.
 */
internal fun io.ktor.server.routing.Route.bookingListenStream(listen: BookingListen) {
    webSocket(BookingListen.PATH) {
        val log = call.application.environment.log
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        fun obj(t: String) = runCatching { json.parseToJsonElement(t) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        fun str(o: kotlinx.serialization.json.JsonObject?, k: String) =
            (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val start = kotlinx.coroutines.withTimeoutOrNull(10_000) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val o = obj(frame.readText()) ?: continue
                if (str(o, "event") == "start") return@withTimeoutOrNull o
            }
            null
        } ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no start"))
        val info = start["start"] as? kotlinx.serialization.json.JsonObject
        val streamSid = str(info, "streamSid") ?: str(start, "streamSid")
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no stream"))
        val params = info?.get("customParameters") as? kotlinx.serialization.json.JsonObject
        val out = kotlinx.coroutines.channels.Channel<String>(capacity = 1_024)
        val ticket = listen.attach(str(params, "ticket"), send = { mu ->
            out.trySend(buildJsonObject {
                put("event", "media")
                put("streamSid", streamSid)
                putJsonObject("media") { put("payload", Base64.getEncoder().encodeToString(mu)) }
            }.toString())
        }, close = { out.close() })
        if (ticket == null) {
            log.warn("booking listen: stream refused (no matching ticket)")
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no ticket"))
        }
        log.info("booking listen: the owner is listening in")
        val writer = launch {
            for (t in out) send(Frame.Text(t))
            runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "done")) }
        }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text && obj(frame.readText())?.let { str(it, "event") } == "stop") break
            }
        } finally {
            listen.detach(ticket.callId)
            out.close()
            writer.join()
        }
    }
}
