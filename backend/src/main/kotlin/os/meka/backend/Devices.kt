package os.meka.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.sql.DataSource

data class DeviceIdentity(val householdId: String, val deviceId: String)

/** A linked watch as Settings → Watch lists it (Galaxy Watch, slice 1): never its secret or key. */
data class LinkedDevice(val id: String, val name: String, val linkedAtMs: Long)

/** A linked device's id: only watches join this way (ADR-005 amendment 2026-10-10). */
fun isLinkedDeviceId(id: String): Boolean = os.meka.core.wire.DeviceLinkCodec.isWatchId(id)

/** What the enrolment code may do (ADR-005 amendment 2026-10-07). */
sealed interface EnrolOutcome {
    data class Enrolled(val secret: String) : EnrolOutcome
    /** Refused; [reason] is a stable code the apps turn into words: `revoked` or `household`. */
    data class Refused(val reason: String) : EnrolOutcome
}

/** Resolves a bearer secret to a device (ADR-005, M0 scheme). Only SHA-256 hashes are stored. */
interface DeviceRegistry {
    fun authenticate(bearerSecret: String): DeviceIdentity?
    /** Enrols (or re-enrols, rotating the secret and clearing revocation) a device; returns its secret exactly once. */
    fun enrol(householdId: String, deviceId: String, name: String): String

    /**
     * Enrolment with the shared enrolment code (`POST /v1/enrol`). Narrower than [enrol], which is the owner's own
     * server-side path: the code never brings back a revoked device, and once a household exists it can't start
     * another one. A device that isn't revoked can still re-enrol (that is "Reconnect" after its secret is lost).
     */
    fun enrolWithCode(householdId: String, deviceId: String, name: String): EnrolOutcome

    /** The device's registered signing key, or null while it has none (then bearer-only is still accepted). */
    fun publicKey(device: DeviceIdentity): String?

    /** Stores the device's first signing key. Returns false if a different key is already registered. */
    fun registerKey(device: DeviceIdentity, publicKeyB64: String): Boolean

    /**
     * The one household this server holds, or null when it holds none or more than one. The release-only publisher
     * (GitHub build) publishes for it; enrolment with the code never starts a second household.
     */
    fun soleHousehold(): String?

    /**
     * How many Macs are connected to [householdId] (not revoked; a Mac's id starts with "mac"), for Setup's "Mac" step.
     * Null when unknown. Only the count leaves the server, never a name or id.
     */
    fun macs(householdId: String): Int? = null

    /**
     * Enrols a watch Meka approved from a keyed device ([DeviceLink]), registering the key the watch asked with, so its
     * requests must be signed from the first one. Like the enrolment code, it never brings back a revoked device id.
     */
    fun enrolLinked(householdId: String, deviceId: String, name: String, publicKeyB64: String): EnrolOutcome

    /** The household's linked watches that aren't revoked, newest first. */
    fun linkedDevices(householdId: String): List<LinkedDevice>

    /** Revokes one of the household's linked watches at once. False when it isn't one (or is already unlinked). */
    fun unlink(householdId: String, deviceId: String): Boolean
}

object Secrets {
    private val rng = SecureRandom()

    fun newDeviceSecret(): String {
        val b = ByteArray(32).also(rng::nextBytes)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Constant-time comparison for secrets/hashes. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

class PostgresDeviceRegistry(private val ds: DataSource) : DeviceRegistry {
    override fun authenticate(bearerSecret: String): DeviceIdentity? {
        if (bearerSecret.length != 64) return null
        val hash = Secrets.sha256Hex(bearerSecret)
        return ds.connection.use { c ->
            c.prepareStatement("SELECT household_id, id FROM device WHERE secret_sha256 = ? AND revoked_at IS NULL").use { st ->
                st.setString(1, hash)
                st.executeQuery().use { rs -> if (rs.next()) DeviceIdentity(rs.getString(1), rs.getString(2)) else null }
            }
        }
    }

    /** Enrols a device and returns its secret exactly once. Development path until passkey enrolment (ADR-005). */
    override fun enrol(householdId: String, deviceId: String, name: String): String = ds.connection.use { c ->
        c.autoCommit = false
        val secret = Secrets.newDeviceSecret()
        c.prepareStatement("INSERT INTO household(id) VALUES (?) ON CONFLICT DO NOTHING").use { it.setString(1, householdId); it.executeUpdate() }
        c.prepareStatement(
            """INSERT INTO device(id, household_id, name, secret_sha256) VALUES (?,?,?,?)
               ON CONFLICT (household_id, id) DO UPDATE SET name = EXCLUDED.name, secret_sha256 = EXCLUDED.secret_sha256, revoked_at = NULL,
                 public_key = NULL""",
        ).use {
            it.setString(1, deviceId); it.setString(2, householdId); it.setString(3, name); it.setString(4, Secrets.sha256Hex(secret))
            it.executeUpdate()
        }
        c.commit()
        secret
    }

    override fun enrolWithCode(householdId: String, deviceId: String, name: String): EnrolOutcome {
        ds.connection.use { c ->
            val revoked = c.prepareStatement("SELECT revoked_at IS NOT NULL FROM device WHERE household_id = ? AND id = ?").use { st ->
                st.setString(1, householdId); st.setString(2, deviceId)
                st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
            if (revoked) return EnrolOutcome.Refused(REFUSED_REVOKED)
            val (known, any) = c.prepareStatement("SELECT bool_or(id = ?), count(*) > 0 FROM household").use { st ->
                st.setString(1, householdId)
                st.executeQuery().use { rs -> rs.next(); (rs.getBoolean(1)) to rs.getBoolean(2) }
            }
            if (any && !known) return EnrolOutcome.Refused(REFUSED_HOUSEHOLD)
        }
        return EnrolOutcome.Enrolled(enrol(householdId, deviceId, name))
    }

    override fun publicKey(device: DeviceIdentity): String? = ds.connection.use { c ->
        c.prepareStatement("SELECT public_key FROM device WHERE household_id = ? AND id = ?").use { st ->
            st.setString(1, device.householdId); st.setString(2, device.deviceId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    override fun registerKey(device: DeviceIdentity, publicKeyB64: String): Boolean = ds.connection.use { c ->
        c.prepareStatement("UPDATE device SET public_key = ? WHERE household_id = ? AND id = ? AND public_key IS NULL").use {
            it.setString(1, publicKeyB64); it.setString(2, device.householdId); it.setString(3, device.deviceId); it.executeUpdate()
        }
        publicKey(device) == publicKeyB64
    }

    override fun soleHousehold(): String? = ds.connection.use { c ->
        c.prepareStatement("SELECT id FROM household LIMIT 2").use { st ->
            st.executeQuery().use { rs ->
                val ids = buildList { while (rs.next()) add(rs.getString(1)) }
                ids.singleOrNull()
            }
        }
    }

    override fun macs(householdId: String): Int? = ds.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM device WHERE household_id = ? AND revoked_at IS NULL AND id LIKE 'mac%'").use { st ->
            st.setString(1, householdId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    }

    override fun enrolLinked(householdId: String, deviceId: String, name: String, publicKeyB64: String): EnrolOutcome {
        if (!isLinkedDeviceId(deviceId)) return EnrolOutcome.Refused(REFUSED_REVOKED)
        val revoked = ds.connection.use { c ->
            c.prepareStatement("SELECT revoked_at IS NOT NULL FROM device WHERE household_id = ? AND id = ?").use { st ->
                st.setString(1, householdId); st.setString(2, deviceId)
                st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }
        if (revoked) return EnrolOutcome.Refused(REFUSED_REVOKED)
        val secret = enrol(householdId, deviceId, name)
        if (!registerKey(DeviceIdentity(householdId, deviceId), publicKeyB64)) return EnrolOutcome.Refused(REFUSED_REVOKED)
        return EnrolOutcome.Enrolled(secret)
    }

    override fun linkedDevices(householdId: String): List<LinkedDevice> = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT id, name, created_at FROM device WHERE household_id = ? AND revoked_at IS NULL AND id LIKE 'watch%' ORDER BY created_at DESC LIMIT 20",
        ).use { st ->
            st.setString(1, householdId)
            st.executeQuery().use { rs ->
                // The LIKE only narrows; the watch-id rule decides (the same one the in-memory registry uses).
                buildList { while (rs.next()) add(LinkedDevice(rs.getString(1), rs.getString(2), rs.getTimestamp(3).time)) }
                    .filter { isLinkedDeviceId(it.id) }
            }
        }
    }

    override fun unlink(householdId: String, deviceId: String): Boolean {
        if (!isLinkedDeviceId(deviceId)) return false
        return ds.connection.use { c ->
            c.prepareStatement("UPDATE device SET revoked_at = now() WHERE household_id = ? AND id = ? AND revoked_at IS NULL").use {
                it.setString(1, householdId); it.setString(2, deviceId); it.executeUpdate() == 1
            }
        }
    }

    fun revoke(householdId: String, deviceId: String) = ds.connection.use { c: Connection ->
        c.prepareStatement("UPDATE device SET revoked_at = now() WHERE household_id = ? AND id = ?").use {
            it.setString(1, householdId); it.setString(2, deviceId); it.executeUpdate()
        }
    }
}

class InMemoryDeviceRegistry : DeviceRegistry {
    private val byHash = HashMap<String, DeviceIdentity>()
    private val keys = HashMap<DeviceIdentity, String>()
    private val households = HashSet<String>()
    private val revoked = HashSet<DeviceIdentity>()
    private val names = HashMap<DeviceIdentity, Pair<String, Long>>()

    fun enrol(householdId: String, deviceId: String): String = enrol(householdId, deviceId, deviceId)

    override fun enrol(householdId: String, deviceId: String, name: String): String {
        names.getOrPut(DeviceIdentity(householdId, deviceId)) { name to System.currentTimeMillis() }
        byHash.entries.removeAll { it.value == DeviceIdentity(householdId, deviceId) } // re-enrol rotates
        keys.remove(DeviceIdentity(householdId, deviceId))
        revoked.remove(DeviceIdentity(householdId, deviceId))
        households += householdId
        val secret = Secrets.newDeviceSecret()
        byHash[Secrets.sha256Hex(secret)] = DeviceIdentity(householdId, deviceId)
        return secret
    }

    fun revoke(deviceId: String) {
        byHash.values.filter { it.deviceId == deviceId }.forEach { revoked += it }
        byHash.entries.removeAll { it.value.deviceId == deviceId }
    }

    override fun enrolWithCode(householdId: String, deviceId: String, name: String): EnrolOutcome = when {
        DeviceIdentity(householdId, deviceId) in revoked -> EnrolOutcome.Refused(REFUSED_REVOKED)
        households.isNotEmpty() && householdId !in households -> EnrolOutcome.Refused(REFUSED_HOUSEHOLD)
        else -> EnrolOutcome.Enrolled(enrol(householdId, deviceId, name))
    }

    override fun authenticate(bearerSecret: String) = byHash[Secrets.sha256Hex(bearerSecret)]
    override fun publicKey(device: DeviceIdentity) = keys[device]
    override fun registerKey(device: DeviceIdentity, publicKeyB64: String): Boolean = keys.getOrPut(device) { publicKeyB64 } == publicKeyB64
    override fun soleHousehold(): String? = households.singleOrNull()
    override fun macs(householdId: String): Int =
        byHash.values.filter { it.householdId == householdId && it.deviceId.startsWith("mac") && it !in revoked }.distinct().size

    override fun enrolLinked(householdId: String, deviceId: String, name: String, publicKeyB64: String): EnrolOutcome {
        val who = DeviceIdentity(householdId, deviceId)
        if (!isLinkedDeviceId(deviceId) || who in revoked) return EnrolOutcome.Refused(REFUSED_REVOKED)
        val secret = enrol(householdId, deviceId, name)
        keys[who] = publicKeyB64
        return EnrolOutcome.Enrolled(secret)
    }

    override fun linkedDevices(householdId: String): List<LinkedDevice> =
        byHash.values.filter { it.householdId == householdId && isLinkedDeviceId(it.deviceId) && it !in revoked }.distinct()
            .map { w -> names[w].let { LinkedDevice(w.deviceId, it?.first ?: w.deviceId, it?.second ?: 0L) } }
            .sortedByDescending { it.linkedAtMs }

    override fun unlink(householdId: String, deviceId: String): Boolean {
        val who = DeviceIdentity(householdId, deviceId)
        if (!isLinkedDeviceId(deviceId) || who in revoked || byHash.values.none { it == who }) return false
        revoked += who
        byHash.entries.removeAll { it.value == who }
        return true
    }
}

const val REFUSED_REVOKED = "revoked"
const val REFUSED_HOUSEHOLD = "household"
