package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recovery key and recovered devices on real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset. */
class PostgresRecoveryKeyStoreTest {
    private val url = System.getenv("MEKA_TEST_DB_URL")

    private fun ds() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
        password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
    })

    @Test
    fun aKeyIsReplacedSpentOnceAndRecoveredDevicesAreNew() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household, recovery_key CASCADE") }
            val reg = PostgresDeviceRegistry(ds)
            reg.enrol("hh-r", "android0123456789ab", "Fold")
            assertEquals("Fold", reg.name(DeviceIdentity("hh-r", "android0123456789ab")))
            assertNull(reg.name(DeviceIdentity("hh-r", "mac00112233aabb")))
            val store = PostgresRecoveryKeyStore(ds)
            store.put(RecoveryKeyRow("hh-r", "a".repeat(64), 1_000L, "Fold"))
            store.put(RecoveryKeyRow("hh-r", "b".repeat(64), 2_000L, "Fold"))
            assertNull(store.unspent("a".repeat(64)))
            assertEquals(2_000L, store.unspent("b".repeat(64))!!.createdAtMs)
            assertTrue(store.spend("hh-r", "b".repeat(64), "Mac", 3_000L))
            assertFalse(store.spend("hh-r", "b".repeat(64), "Mac", 4_000L))
            assertNull(store.unspent("b".repeat(64)))
            assertEquals("Mac", store.get("hh-r")!!.usedBy)

            val key = TestDeviceKey().publicB64
            val r = reg.enrolRecovered("hh-r", "mac00112233aabb", "Mac", key)
            assertIs<EnrolOutcome.Enrolled>(r)
            assertEquals(key, reg.publicKey(DeviceIdentity("hh-r", "mac00112233aabb")))
            assertEquals(EnrolOutcome.Refused(REFUSED_TAKEN), reg.enrolRecovered("hh-r", "android0123456789ab", "Fold", key))
            reg.revoke("hh-r", "mac00112233aabb")
            assertEquals(EnrolOutcome.Refused(REFUSED_REVOKED), reg.enrolRecovered("hh-r", "mac00112233aabb", "Mac", key))
        }
    }
}
