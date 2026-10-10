package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Linked watches on real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset. */
class PostgresDeviceLinkTest {
    private val url = System.getenv("MEKA_TEST_DB_URL")

    private fun ds() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
        password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
    })

    @Test
    fun aLinkedWatchIsEnrolledWithItsKeyListedAndUnlinkedForGood() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household CASCADE") }
            val reg = PostgresDeviceRegistry(ds)
            reg.enrol("hh-w", "android", "Fold")
            val key = TestDeviceKey().publicB64
            val watch = "watch0123456789abcdef"
            val r = reg.enrolLinked("hh-w", watch, "Galaxy Watch", key)
            assertIs<EnrolOutcome.Enrolled>(r)
            assertEquals(DeviceIdentity("hh-w", watch), reg.authenticate(r.secret))
            assertEquals(key, reg.publicKey(DeviceIdentity("hh-w", watch)))
            // Only watches are listed, never the Fold.
            assertEquals(listOf(watch to "Galaxy Watch"), reg.linkedDevices("hh-w").map { it.id to it.name })
            assertFalse(reg.unlink("hh-w", "android"))
            assertTrue(reg.unlink("hh-w", watch))
            assertFalse(reg.unlink("hh-w", watch))
            assertEquals(null, reg.authenticate(r.secret))
            assertTrue(reg.linkedDevices("hh-w").isEmpty())
            assertIs<EnrolOutcome.Refused>(reg.enrolLinked("hh-w", watch, "Galaxy Watch", key))
        }
    }
}
