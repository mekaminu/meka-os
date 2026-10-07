package os.meka.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.sql.DataSource

data class DeviceIdentity(val householdId: String, val deviceId: String)

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

    fun enrol(householdId: String, deviceId: String): String = enrol(householdId, deviceId, deviceId)

    override fun enrol(householdId: String, deviceId: String, name: String): String {
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
}

const val REFUSED_REVOKED = "revoked"
const val REFUSED_HOUSEHOLD = "household"
