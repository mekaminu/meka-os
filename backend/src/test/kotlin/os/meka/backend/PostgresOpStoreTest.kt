package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs against the Postgres given by MEKA_TEST_DB_URL (CI service container). Skipped when unset, so a laptop
 * without Postgres still runs the rest of the suite.
 */
class PostgresOpStoreTest {
    private val url = System.getenv("MEKA_TEST_DB_URL")

    private fun ds() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
        password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 8
    })

    private fun op(id: String, dev: String) = Op(id, "hh-pg", "task", "t-$id", "title", FieldValue.Text("v$id"), Hlc(1, 0, dev), emptyList(), dev)

    @Test
    fun concurrentPushesGetGapFreeSequencesAndNoDuplicates() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household CASCADE") }
            val reg = PostgresDeviceRegistry(ds)
            reg.enrol("hh-pg", "a", "a"); reg.enrol("hh-pg", "b", "b")
            val sync = SyncService(PostgresOpStore(ds))
            val pool = Executors.newFixedThreadPool(8)
            repeat(200) { i ->
                val dev = if (i % 2 == 0) "a" else "b"
                // Each op pushed twice concurrently: exactly one copy must be stored.
                repeat(2) { pool.submit { runCatching { sync.push(PushRequest("hh-pg", dev, listOf(op("op$i", dev)))) } } }
            }
            pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
            // Retry anything that lost a unique-constraint race, as a real client would.
            repeat(200) { i -> val dev = if (i % 2 == 0) "a" else "b"; sync.push(PushRequest("hh-pg", dev, listOf(op("op$i", dev)))) }

            val all = sync.pull(PullRequest("hh-pg", "a", 0, 1000)).ops
            assertEquals(200, all.size)
            assertEquals((1L..200L).toList(), all.map { it.seq }, "gap-free, strictly increasing")
            assertEquals(200, all.map { it.op.opId }.toSet().size)
        }
    }

    @Test
    fun theEnrolmentCodeNeverBringsBackARevokedDeviceOrStartsASecondHousehold() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household CASCADE") }
            val reg = PostgresDeviceRegistry(ds)
            // The very first enrolment may create the household.
            val first = reg.enrolWithCode("home", "fold", "Fold")
            check(first is EnrolOutcome.Enrolled)
            // Reconnect: a device that isn't revoked re-enrols and gets a new secret.
            val again = reg.enrolWithCode("home", "fold", "Fold")
            check(again is EnrolOutcome.Enrolled)
            assertEquals(null, reg.authenticate(first.secret))
            assertEquals(DeviceIdentity("home", "fold"), reg.authenticate(again.secret))
            assertEquals(EnrolOutcome.Refused(REFUSED_HOUSEHOLD), reg.enrolWithCode("elsewhere", "x", "x"))
            check(reg.enrolWithCode("home", "mac", "Mac") is EnrolOutcome.Enrolled)
            reg.revoke("home", "mac")
            assertEquals(EnrolOutcome.Refused(REFUSED_REVOKED), reg.enrolWithCode("home", "mac", "Mac"))
            // The owner's own server-side path still can.
            reg.enrol("home", "mac", "Mac")
            check(reg.enrolWithCode("home", "mac", "Mac") is EnrolOutcome.Enrolled)
        }
    }
}
