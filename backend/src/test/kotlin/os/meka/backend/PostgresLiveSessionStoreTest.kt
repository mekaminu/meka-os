package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ada's Live mode's meter on real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset. */
class PostgresLiveSessionStoreTest {
    private val url = System.getenv("MEKA_TEST_DB_URL")

    private fun ds() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
        password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
    })

    @Test
    fun theMonthsSessionsCostAndMinutes() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE live_session") }
            val store = PostgresLiveSessionStore(ds)
            store.add(LiveSessionRow("ls_a", "hh", "2026-10", 1, 500, reported = false))
            store.add(LiveSessionRow("ls_b", "hh", "2026-10", 2, 500, reported = false))
            store.add(LiveSessionRow("ls_c", "hh", "2026-09", 3, 500, reported = false))
            assertEquals(LiveUse(2, 0), store.monthUse("2026-10"))
            assertFalse(store.report("ls_a", "other", 90, 30, 100))
            assertTrue(store.report("ls_a", "hh", 90, 120, 100))
            assertFalse(store.report("ls_a", "hh", 1, 1, 1))
            // The longer of heard and spoken; the unreported session counts as a talk, not yet as minutes.
            assertEquals(LiveUse(2, 120), store.monthUse("2026-10"))
            assertEquals(600, store.monthMicroUsd("2026-10"))
            assertEquals(LiveUse(1, 0), store.monthUse("2026-09"))
        }
    }
}
