package os.meka.backend

import os.meka.core.wire.DeviceLinkCodec
import os.meka.core.wire.WireFormatException
import java.security.SecureRandom

/** What a link route answers: an HTTP status and a JSON body (`{"error": …}` when refused). */
data class LinkReply(val status: Int, val body: String) {
    companion object {
        fun ok(body: String) = LinkReply(200, body)
        fun refused(status: Int, reason: String) = LinkReply(status, DeviceLinkCodec.encodeError(reason))
    }
}

/**
 * Linking a watch without the enrolment code (build plan "Galaxy Watch", slice 1; ADR-005 amendment 2026-10-10).
 *
 * 1. The watch makes its own hardware P-256 key and asks for a code ([start], signed with that key, so possession is
 *    proved): an 8-digit code that lasts [CODE_MS], shown on the watch.
 * 2. Meka types it on a keyed device of the household ([approve]): the server enrols the watch into that household with
 *    the key that asked, so every request it makes is signed from the first one. A device gets [MAX_WRONG] wrong codes
 *    per [CODE_MS], and only keyed devices can try, so 10^8 codes can't be guessed.
 * 3. The watch picks up its device secret exactly once ([status], signed with the same key); then the link is gone.
 *
 * The links live in memory only (a secret waits here at most [CODE_MS] for its watch): one sync task, like the
 * verifier's nonce cache; a restart just means the watch asks for a new code. At most [MAX_PENDING] links wait at a
 * time, so unsigned noise can't grow it. Kept free of Ktor so its rules are tested directly.
 */
class DeviceLink(
    private val devices: DeviceRegistry,
    private val verifier: RequestVerifier,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Pending(
        val linkId: String,
        val code: String,
        val deviceId: String,
        val name: String,
        val publicKey: String,
        val expiresAtMs: Long,
        val householdId: String? = null,
        val secret: String? = null,
    )

    private val rng = SecureRandom()
    private val pending = LinkedHashMap<String, Pending>()
    /** Wrong codes per approving device (household/device → times). */
    private val wrong = HashMap<String, ArrayDeque<Long>>()

    // ---- the watch ----

    /** `{"deviceId","name","publicKey"}` signed with that key over [path] → `{"linkId","code","expiresAtMs"}`. */
    fun start(path: String, body: String, time: String?, nonce: String?, signature: String?): LinkReply {
        val req = try { DeviceLinkCodec.decodeStart(body) } catch (e: WireFormatException) { return LinkReply.refused(400, "body") }
        if (!verifier.isP256(req.publicKey)) return LinkReply.refused(400, "key")
        if (!verifier.verify(req.publicKey, "POST", path, body, time, nonce, signature)) return LinkReply.refused(401, "signature")
        val started = synchronized(this) {
            sweep()
            // Asking again (a new code after one ran out, or the app reopened) replaces this watch's last one.
            pending.values.removeAll { it.publicKey == req.publicKey || it.deviceId == req.deviceId }
            if (pending.size >= MAX_PENDING) return LinkReply.refused(429, DeviceLinkCodec.ERR_BUSY)
            var code: String
            do { code = (0 until DeviceLinkCodec.CODE_DIGITS).joinToString("") { rng.nextInt(10).toString() } } while (pending.values.any { it.code == code })
            val p = Pending("lnk" + hex(12), code, req.deviceId, req.name, req.publicKey, now() + CODE_MS)
            pending[p.linkId] = p
            DeviceLinkCodec.Started(p.linkId, p.code, p.expiresAtMs)
        }
        return LinkReply.ok(DeviceLinkCodec.encodeStarted(started))
    }

    /**
     * `{"linkId"}` signed with the watch's key → waiting · expired · linked (with the household, device id and secret,
     * handed over once). An unknown link reads as expired, so the watch asks for a new code.
     */
    fun status(path: String, body: String, time: String?, nonce: String?, signature: String?): LinkReply {
        val linkId = try { DeviceLinkCodec.decodeStatusRequest(body) } catch (e: WireFormatException) { return LinkReply.refused(400, "body") }
        val p = synchronized(this) { sweep(); pending[linkId] }
            ?: return LinkReply.ok(DeviceLinkCodec.encodeStatus(DeviceLinkCodec.Status(DeviceLinkCodec.EXPIRED)))
        if (!verifier.verify(p.publicKey, "POST", path, body, time, nonce, signature)) return LinkReply.refused(401, "signature")
        if (p.secret == null || p.householdId == null) {
            return LinkReply.ok(DeviceLinkCodec.encodeStatus(DeviceLinkCodec.Status(DeviceLinkCodec.WAITING)))
        }
        synchronized(this) { pending.remove(linkId) }
        return LinkReply.ok(
            DeviceLinkCodec.encodeStatus(DeviceLinkCodec.Status(DeviceLinkCodec.LINKED, p.householdId, p.deviceId, p.secret)),
        )
    }

    // ---- Meka's side (a keyed device of the household) ----

    /** `{"code"}` from a keyed device → `{"deviceId","name"}`: the watch is now a device of [who]'s household. */
    fun approve(who: DeviceIdentity, body: String): LinkReply {
        val code = try { DeviceLinkCodec.decodeApprove(body) } catch (e: WireFormatException) { return LinkReply.refused(400, DeviceLinkCodec.ERR_CODE) }
        val tries = "${who.householdId}/${who.deviceId}"
        val p = synchronized(this) {
            sweep()
            val recent = wrong.getOrPut(tries) { ArrayDeque() }.apply { while (isNotEmpty() && first() < now() - CODE_MS) removeFirst() }
            if (recent.size >= MAX_WRONG) return LinkReply.refused(429, DeviceLinkCodec.ERR_WAIT)
            val match = pending.values.firstOrNull { it.secret == null && Secrets.constantTimeEquals(it.code, code) }
            if (match == null) {
                recent.addLast(now())
                return LinkReply.refused(404, DeviceLinkCodec.ERR_CODE)
            }
            // Taken off the waiting list at once, so a code can be used only once.
            pending.remove(match.linkId)
            match
        }
        return when (val r = devices.enrolLinked(who.householdId, p.deviceId, p.name, p.publicKey)) {
            is EnrolOutcome.Refused -> LinkReply.refused(409, DeviceLinkCodec.ERR_REVOKED)
            is EnrolOutcome.Enrolled -> {
                synchronized(this) {
                    // The secret waits for its watch's next status check, no longer than a code lasts.
                    pending[p.linkId] = p.copy(householdId = who.householdId, secret = r.secret, expiresAtMs = now() + CODE_MS)
                }
                LinkReply.ok(DeviceLinkCodec.encodeLinked(DeviceLinkCodec.Linked(p.deviceId, p.name)))
            }
        }
    }

    /** The household's linked watches (never a secret or key). */
    fun watches(who: DeviceIdentity): LinkReply = LinkReply.ok(
        DeviceLinkCodec.encodeWatches(devices.linkedDevices(who.householdId).map { DeviceLinkCodec.Watch(it.id, it.name, it.linkedAtMs) }),
    )

    /** `{"id"}`: revokes that watch at once; its next request is refused. */
    fun unlink(who: DeviceIdentity, body: String): LinkReply {
        val id = try { DeviceLinkCodec.decodeUnlink(body) } catch (e: WireFormatException) { return LinkReply.refused(400, "id") }
        if (!devices.unlink(who.householdId, id)) return LinkReply.refused(404, DeviceLinkCodec.ERR_UNKNOWN)
        return LinkReply.ok(DeviceLinkCodec.encodeUnlink(id))
    }

    /** How many links are waiting (tests). */
    @Synchronized fun waiting(): Int { sweep(); return pending.size }

    /** On the lock: drops links past their time (a code that ran out, or a secret nobody picked up). */
    private fun sweep() {
        val t = now()
        pending.values.removeAll { it.expiresAtMs <= t }
        if (wrong.size > 1_000) wrong.entries.removeAll { e -> e.value.all { it < t - CODE_MS } }
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val CODE_MS = 10 * 60_000L
        const val MAX_PENDING = 10
        const val MAX_WRONG = 5
    }
}
