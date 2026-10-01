package os.meka.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import javax.sql.DataSource

data class DeviceIdentity(val householdId: String, val deviceId: String)

/** Resolves a bearer secret to a device (ADR-005, M0 scheme). Only SHA-256 hashes are stored. */
interface DeviceRegistry {
    fun authenticate(bearerSecret: String): DeviceIdentity?
    /** Enrols (or re-enrols, rotating the secret and clearing revocation) a device; returns its secret exactly once. */
    fun enrol(householdId: String, deviceId: String, name: String): String
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
               ON CONFLICT (household_id, id) DO UPDATE SET name = EXCLUDED.name, secret_sha256 = EXCLUDED.secret_sha256, revoked_at = NULL""",
        ).use {
            it.setString(1, deviceId); it.setString(2, householdId); it.setString(3, name); it.setString(4, Secrets.sha256Hex(secret))
            it.executeUpdate()
        }
        c.commit()
        secret
    }

    fun revoke(householdId: String, deviceId: String) = ds.connection.use { c: Connection ->
        c.prepareStatement("UPDATE device SET revoked_at = now() WHERE household_id = ? AND id = ?").use {
            it.setString(1, householdId); it.setString(2, deviceId); it.executeUpdate()
        }
    }
}

class InMemoryDeviceRegistry : DeviceRegistry {
    private val byHash = HashMap<String, DeviceIdentity>()

    fun enrol(householdId: String, deviceId: String): String = enrol(householdId, deviceId, deviceId)

    override fun enrol(householdId: String, deviceId: String, name: String): String {
        byHash.entries.removeAll { it.value == DeviceIdentity(householdId, deviceId) } // re-enrol rotates
        val secret = Secrets.newDeviceSecret()
        byHash[Secrets.sha256Hex(secret)] = DeviceIdentity(householdId, deviceId)
        return secret
    }

    fun revoke(deviceId: String) { byHash.entries.removeAll { it.value.deviceId == deviceId } }

    override fun authenticate(bearerSecret: String) = byHash[Secrets.sha256Hex(bearerSecret)]
}
