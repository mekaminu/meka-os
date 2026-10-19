package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.core.domain.DeviceJoin
import os.meka.core.domain.DeviceJoinRules
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import os.meka.core.wire.DeviceJoinCodec
import os.meka.core.wire.WireCodec
import os.meka.core.wire.WireFormatException
import java.security.SecureRandom

/**
 * Approving a new phone or Mac from one already connected (build plan M1 "Clean-ups"; ADR-005 amendment 2026-10-19).
 *
 * Once the household has a phone or Mac that holds its signing key and has sent something in the last
 * [DeviceJoinCodec.APPROVER_SEEN_DAYS] days ([DeviceRegistry.hasApprover]), the enrolment code alone no longer adds a
 * device id the household doesn't know: [ask] holds the request, answers a request id (only the asker gets it) and a
 * six-digit code both screens show, and writes a `device_join` entity so Needs you on the Fold and the Mac shows it
 * (an urgent wake, so it arrives within seconds). [answer] from a keyed phone or Mac enrols it or refuses it; the asker
 * picks up its secret once with [status]. Re-enrolling a device id the household already has ("Reconnect"), a revoked
 * one (refused as before) and a household with no approver in use (both devices lost) keep the code's old path.
 *
 * The asks live in memory only (a secret waits here at most [DeviceJoinCodec.ASK_MS] for its device), like
 * [DeviceLink]'s; a restart just means the new device connects again. At most [MAX_PENDING] wait at a time, and at most
 * one per device id (asking again replaces it). Kept free of Ktor so its rules are tested directly.
 */
class EnrolApproval(
    private val devices: DeviceRegistry,
    private val ops: ServerOpStore?,
    private val now: () -> Long = System::currentTimeMillis,
    /** The ask was written for this household: wake its devices now (Push.urgent). */
    private val onAsked: (householdId: String) -> Unit = {},
    /** The latest op per field of one ask (Postgres reads it by index; the default scans, for tests). */
    private val heads: (householdId: String, requestId: String) -> Map<String, Op> = { hh, id ->
        val out = LinkedHashMap<String, Op>()
        ops?.after(hh, 0, Int.MAX_VALUE)?.map { it.op }?.filter { it.entityType == EntityTypes.DEVICE_JOIN && it.entityId == id }
            ?.forEach { op -> val prev = out[op.field]; if (prev == null || op.hlc > prev.hlc) out[op.field] = op }
        out
    },
) {
    private data class Pending(
        val requestId: String,
        val householdId: String,
        val deviceId: String,
        val name: String,
        val kind: String,
        val code: String,
        val askedAtMs: Long,
        val expiresAtMs: Long,
        val state: String = DeviceJoinCodec.WAITING,
        val secret: String? = null,
    )

    private val rng = SecureRandom()
    private val pending = LinkedHashMap<String, Pending>()
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)

    /** Whether this enrolment has to be allowed from another device first. */
    fun needed(req: WireCodec.EnrolRequest): Boolean =
        DeviceJoinCodec.kindOf(req.deviceId) != null &&
            devices.isNewDevice(req.householdId, req.deviceId) &&
            devices.hasApprover(req.householdId, now() - DeviceJoinCodec.APPROVER_SEEN_DAYS * DAY_MS)

    /** Holds [req] for Meka's answer; null when too many are waiting (the route says try again shortly). */
    fun ask(req: WireCodec.EnrolRequest): DeviceJoinCodec.Waiting? {
        val kind = DeviceJoinCodec.kindOf(req.deviceId) ?: return null
        val name = req.name.trim().ifEmpty { if (kind == DeviceJoinCodec.KIND_MAC) "Mac" else "Phone" }.take(80)
        val p = synchronized(this) {
            sweep()
            // Asking again (the app reopened, or a code that ran out) replaces this device's last ask.
            pending.values.filter { it.householdId == req.householdId && it.deviceId == req.deviceId && it.state == DeviceJoinCodec.WAITING }
                .forEach { pending.remove(it.requestId) }
            if (pending.size >= MAX_PENDING) return null
            var code: String
            do { code = (0 until DeviceJoinCodec.CODE_DIGITS).joinToString("") { rng.nextInt(10).toString() } } while (pending.values.any { it.code == code })
            val t = now()
            Pending("join" + hex(16), req.householdId, req.deviceId, name, kind, code, t, t + DeviceJoinCodec.ASK_MS).also { pending[it.requestId] = it }
        }
        write(p, DeviceJoinRules.WAITING, null)
        runCatching { onAsked(p.householdId) }
        return DeviceJoinCodec.Waiting(p.requestId, p.code, p.expiresAtMs)
    }

    /** `{"requestId"}` from the asking device (with the enrolment code) → waiting · refused · expired · enrolled (once). */
    fun status(body: String): LinkReply {
        val id = try { DeviceJoinCodec.decodeStatusRequest(body) } catch (e: WireFormatException) { return LinkReply.refused(400, "body") }
        val p = synchronized(this) { sweep(); pending[id] }
            ?: return LinkReply.ok(DeviceJoinCodec.encodeStatus(DeviceJoinCodec.Status(DeviceJoinCodec.EXPIRED)))
        return when {
            p.secret != null -> {
                synchronized(this) { pending.remove(id) }
                LinkReply.ok(DeviceJoinCodec.encodeStatus(DeviceJoinCodec.Status(DeviceJoinCodec.ENROLLED, p.secret)))
            }
            p.state == DeviceJoinCodec.REFUSED -> {
                synchronized(this) { pending.remove(id) }
                LinkReply.ok(DeviceJoinCodec.encodeStatus(DeviceJoinCodec.Status(DeviceJoinCodec.REFUSED)))
            }
            else -> LinkReply.ok(DeviceJoinCodec.encodeStatus(DeviceJoinCodec.Status(DeviceJoinCodec.WAITING)))
        }
    }

    /**
     * `{"requestId","allow"}` from a keyed phone or Mac of the same household → `{"state": allowed · refused · gone}`.
     * Allow enrols the device (the code's own rules: never a revoked id, never another household); the state is written
     * so the card leaves every device.
     */
    fun answer(who: DeviceIdentity, body: String): LinkReply {
        if (DeviceJoinCodec.kindOf(who.deviceId) == null) return LinkReply.refused(403, "device")
        val a = try { DeviceJoinCodec.decodeAnswer(body) } catch (e: WireFormatException) { return LinkReply.refused(400, "body") }
        val p = synchronized(this) {
            sweep()
            val p = pending[a.requestId]?.takeIf { it.householdId == who.householdId && it.state == DeviceJoinCodec.WAITING }
                ?: return LinkReply.ok(DeviceJoinCodec.encodeAnswered(DeviceJoinCodec.GONE))
            // Answered once: a second answer (from the other device) finds it gone.
            pending[p.requestId] = p.copy(state = if (a.allow) DeviceJoinCodec.ALLOWED else DeviceJoinCodec.REFUSED, expiresAtMs = now() + DeviceJoinCodec.ASK_MS)
            p
        }
        val answeredBy = devices.name(who)
        if (!a.allow) {
            write(p, DeviceJoinRules.REFUSED, answeredBy)
            return LinkReply.ok(DeviceJoinCodec.encodeAnswered(DeviceJoinCodec.REFUSED))
        }
        return when (val r = devices.enrolWithCode(p.householdId, p.deviceId, p.name)) {
            is EnrolOutcome.Refused -> {
                synchronized(this) { pending[p.requestId] = p.copy(state = DeviceJoinCodec.REFUSED) }
                write(p, DeviceJoinRules.REFUSED, answeredBy)
                LinkReply.ok(DeviceJoinCodec.encodeAnswered(DeviceJoinCodec.GONE))
            }
            is EnrolOutcome.Enrolled -> {
                synchronized(this) {
                    pending[p.requestId] = p.copy(state = DeviceJoinCodec.ALLOWED, secret = r.secret, expiresAtMs = now() + DeviceJoinCodec.ASK_MS)
                }
                write(p, DeviceJoinRules.ALLOWED, answeredBy)
                LinkReply.ok(DeviceJoinCodec.encodeAnswered(DeviceJoinCodec.ALLOWED))
            }
        }
    }

    /** How many asks are held (tests). */
    @Synchronized fun waiting(): Int { sweep(); return pending.size }

    /** The ask as an entity, every field over its current head; nothing written without an op store. */
    private fun write(p: Pending, state: String, answeredBy: String?) {
        val store = ops ?: return
        val fields = DeviceJoinRules.fields(
            DeviceJoin(p.requestId, p.name, p.kind, p.code, p.askedAtMs, p.expiresAtMs, state, answeredBy),
        ).let { if (state == DeviceJoinRules.WAITING) it else it.filterKeys { k -> k == os.meka.core.domain.DeviceJoinFields.STATE || k == os.meka.core.domain.DeviceJoinFields.ANSWERED_BY } }
        runCatching {
            val current = heads(p.householdId, p.requestId)
            store.transaction {
                for ((field, value) in fields) {
                    val last = current[field]
                    val hlc = synchronized(clock) {
                        last?.let { runCatching { clock.receive(it.hlc) } }
                        clock.now()
                    }
                    store.append(
                        Op(
                            opId = "srvjoin" + hex(12), householdId = p.householdId, entityType = EntityTypes.DEVICE_JOIN,
                            entityId = p.requestId, field = field, value = value, hlc = hlc,
                            baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
                        ),
                    )
                }
            }
        }.onFailure { System.err.println("device join: write failed: ${it::class.simpleName}") }
    }

    /** On the lock: drops asks past their time (unanswered, or a secret nobody picked up). */
    private fun sweep() {
        val t = now()
        pending.values.removeAll { it.expiresAtMs <= t }
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_PENDING = 10
        private const val DAY_MS = 24 * 60 * 60_000L
    }
}
