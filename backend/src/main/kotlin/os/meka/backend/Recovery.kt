package os.meka.backend

import os.meka.core.wire.RecoveryCodec
import os.meka.core.wire.WireFormatException
import java.sql.Timestamp
import javax.sql.DataSource

/** A household's recovery key as stored: only the verifier (SHA-256 of the key's secret) and who made and used it. */
data class RecoveryKeyRow(
    val householdId: String,
    val verifier: String,
    val createdAtMs: Long,
    val createdBy: String,
    val usedAtMs: Long? = null,
    val usedBy: String? = null,
)

interface RecoveryKeyStore {
    /** Saves [row] as the household's key, replacing any earlier one (made or used). */
    fun put(row: RecoveryKeyRow)
    fun get(householdId: String): RecoveryKeyRow?
    /** The unspent key with this verifier, if any. */
    fun unspent(verifier: String): RecoveryKeyRow?
    /** Spends the household's key once: false when it is already spent or was replaced meanwhile. */
    fun spend(householdId: String, verifier: String, usedBy: String, nowMs: Long): Boolean
}

/**
 * The recovery key (build plan V1 "Recovery key"; ADR-005 amendment 2026-10-12). Kept free of Ktor so its rules are
 * tested directly, like [DeviceLink].
 *
 * - [set]: a keyed phone or Mac saves a new key's verifier (the words never leave it), replacing any older key.
 * - [status]: ready · used · none, with device names.
 * - [enrol]: a new phone or Mac that holds the words connects without the enrolment code. Its request is signed with
 *   the new key it registers (proof of possession, like a watch's link), the secret's SHA-256 must be the household's
 *   unspent verifier, the device id must be new to the household, and the key is spent by the enrolment. At most
 *   [MAX_WRONG] wrong secrets an hour are tried, server-wide (256-bit secrets can't be guessed anyway; this keeps noise
 *   off the database).
 */
class Recovery(
    private val store: RecoveryKeyStore,
    private val devices: DeviceRegistry,
    private val verifier: RequestVerifier,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val wrong = ArrayDeque<Long>()

    fun set(who: DeviceIdentity, body: String): LinkReply {
        // Only the phone and the Mac make keys (a watch has no room to show 24 words; the publisher holds no device).
        if (!RecoveryCodec.isDeviceId(who.deviceId)) return LinkReply.refused(403, "device")
        val v = try { RecoveryCodec.decodeSet(body) } catch (e: WireFormatException) { return LinkReply.refused(400, "verifier") }
        val name = devices.name(who) ?: return LinkReply.refused(403, "device")
        store.put(RecoveryKeyRow(who.householdId, v, now(), name))
        return status(who)
    }

    fun status(who: DeviceIdentity): LinkReply {
        val row = store.get(who.householdId)
        val s = when {
            row == null -> RecoveryCodec.Status(RecoveryCodec.NONE)
            row.usedAtMs != null -> RecoveryCodec.Status(RecoveryCodec.USED, row.createdAtMs, row.createdBy, row.usedAtMs, row.usedBy)
            else -> RecoveryCodec.Status(RecoveryCodec.READY, row.createdAtMs, row.createdBy)
        }
        return LinkReply.ok(RecoveryCodec.encodeStatus(s))
    }

    fun enrol(path: String, body: String, time: String?, nonce: String?, signature: String?): LinkReply {
        val req = try { RecoveryCodec.decodeEnrol(body) } catch (e: WireFormatException) { return LinkReply.refused(400, "body") }
        if (!verifier.isP256(req.publicKey)) return LinkReply.refused(400, "key")
        if (!verifier.verify(req.publicKey, "POST", path, body, time, nonce, signature)) return LinkReply.refused(401, "signature")
        return synchronized(this) {
            val t = now()
            while (wrong.isNotEmpty() && wrong.first() < t - WINDOW_MS) wrong.removeFirst()
            if (wrong.size >= MAX_WRONG) return LinkReply.refused(429, RecoveryCodec.ERR_WAIT)
            val row = store.unspent(Secrets.sha256Hex(req.secret))
            if (row == null) {
                wrong.addLast(t)
                return LinkReply.refused(404, RecoveryCodec.ERR_KEY)
            }
            // A device id the household knows is refused before the key is spent, so a retry with a fresh id still works.
            if (devices.name(DeviceIdentity(row.householdId, req.deviceId)) != null) return LinkReply.refused(409, RecoveryCodec.ERR_TAKEN)
            if (!store.spend(row.householdId, row.verifier, req.name, t)) return LinkReply.refused(404, RecoveryCodec.ERR_KEY)
            when (val r = devices.enrolRecovered(row.householdId, req.deviceId, req.name, req.publicKey)) {
                is EnrolOutcome.Refused -> {
                    // Nothing was connected (a revoked id): the key goes back unspent.
                    store.put(row)
                    LinkReply.refused(409, if (r.reason == REFUSED_REVOKED) RecoveryCodec.ERR_REVOKED else RecoveryCodec.ERR_TAKEN)
                }
                is EnrolOutcome.Enrolled -> LinkReply.ok(RecoveryCodec.encodeEnrolled(RecoveryCodec.Enrolled(row.householdId, req.deviceId, r.secret)))
            }
        }
    }

    companion object {
        const val MAX_WRONG = 10
        const val WINDOW_MS = 60 * 60_000L
    }
}

class InMemoryRecoveryKeyStore : RecoveryKeyStore {
    private val rows = HashMap<String, RecoveryKeyRow>()
    @Synchronized override fun put(row: RecoveryKeyRow) { rows[row.householdId] = row }
    @Synchronized override fun get(householdId: String) = rows[householdId]
    @Synchronized override fun unspent(verifier: String) = rows.values.firstOrNull { it.usedAtMs == null && Secrets.constantTimeEquals(it.verifier, verifier) }
    @Synchronized override fun spend(householdId: String, verifier: String, usedBy: String, nowMs: Long): Boolean {
        val r = rows[householdId]?.takeIf { it.usedAtMs == null && it.verifier == verifier } ?: return false
        rows[householdId] = r.copy(usedAtMs = nowMs, usedBy = usedBy)
        return true
    }
}

class PostgresRecoveryKeyStore(private val ds: DataSource) : RecoveryKeyStore {
    override fun put(row: RecoveryKeyRow) {
        ds.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO recovery_key(household_id, verifier, created_at, created_by, used_at, used_by) VALUES (?,?,?,?,NULL,NULL)
                   ON CONFLICT (household_id) DO UPDATE SET verifier = EXCLUDED.verifier, created_at = EXCLUDED.created_at,
                     created_by = EXCLUDED.created_by, used_at = NULL, used_by = NULL""",
            ).use {
                it.setString(1, row.householdId); it.setString(2, row.verifier); it.setTimestamp(3, Timestamp(row.createdAtMs)); it.setString(4, row.createdBy)
                it.executeUpdate()
            }
        }
    }

    override fun get(householdId: String): RecoveryKeyRow? = one("WHERE household_id = ?", householdId)

    override fun unspent(verifier: String): RecoveryKeyRow? = one("WHERE verifier = ? AND used_at IS NULL", verifier)

    override fun spend(householdId: String, verifier: String, usedBy: String, nowMs: Long): Boolean = ds.connection.use { c ->
        c.prepareStatement("UPDATE recovery_key SET used_at = ?, used_by = ? WHERE household_id = ? AND verifier = ? AND used_at IS NULL").use {
            it.setTimestamp(1, Timestamp(nowMs)); it.setString(2, usedBy); it.setString(3, householdId); it.setString(4, verifier)
            it.executeUpdate() == 1
        }
    }

    private fun one(where: String, arg: String): RecoveryKeyRow? = ds.connection.use { c ->
        c.prepareStatement("SELECT household_id, verifier, created_at, created_by, used_at, used_by FROM recovery_key $where").use { st ->
            st.setString(1, arg)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else RecoveryKeyRow(
                    rs.getString(1), rs.getString(2), rs.getTimestamp(3).time, rs.getString(4),
                    rs.getTimestamp(5)?.time, rs.getString(6),
                )
            }
        }
    }
}
