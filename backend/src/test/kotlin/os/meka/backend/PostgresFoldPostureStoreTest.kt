package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import os.meka.core.wire.FoldPostureCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Fold's posture report on real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset. */
class PostgresFoldPostureStoreTest {
    private val url = System.getenv("MEKA_TEST_DB_URL")

    private fun ds() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
        password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
    })

    @Test
    fun theNewestReportReplacesTheLast() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household, fold_posture_report CASCADE") }
            PostgresDeviceRegistry(ds).enrol("hh-f", "android0123456789ab", "Fold")
            val store = PostgresFoldPostureStore(ds)
            assertNull(store.latest("hh-f"))
            val first = FoldPostureCodec.Report("SM-F966B", 2_000, listOf(FoldPostureCodec.Change(1_000, "FLAT → FLAT")))
            val second = first.copy(sentAtMs = 3_000, changes = listOf(FoldPostureCodec.Change(2_500, "HALF_OPENED → TABLETOP/WINDOW")) + first.changes)
            store.put("hh-f", "android0123456789ab", first)
            store.put("hh-f", "android0123456789ab", second)
            assertEquals(second, store.latest("hh-f"))
            assertNull(store.latest("hh-other"))
        }
    }
}
