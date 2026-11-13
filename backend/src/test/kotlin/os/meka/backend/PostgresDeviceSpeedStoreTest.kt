package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import os.meka.core.wire.DeviceSpeedCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/** Each device's day's response times on real Postgres (MEKA_TEST_DB_URL, CI); skipped when unset. */
class PostgresDeviceSpeedStoreTest {
    private val url = System.getenv("MEKA_TEST_DB_URL")

    private fun ds() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
        password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 4
    })

    @Test
    fun theNewestSummaryPerDeviceIsKept() {
        if (url == null) return
        ds().use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household, device_speed_report CASCADE") }
            PostgresDeviceRegistry(ds).enrol("hh-s", "android0123456789ab", "Fold")
            val store = PostgresDeviceSpeedStore(ds)
            assertEquals(emptyList(), store.latest("hh-s"))
            val first = DeviceSpeedCodec.Report("phone", 2_000, 1, 500, 500, 0, null, null)
            val second = first.copy(sentAtMs = 3_000, answerCount = 2, answerAvgMs = 1_400, answerMaxMs = 1_800)
            val mac = DeviceSpeedCodec.Report("Mac", 3_500, 0, null, null, 1, 900, 900)
            store.put("hh-s", "android0123456789ab", first)
            store.put("hh-s", "android0123456789ab", second)
            Thread.sleep(5)
            store.put("hh-s", "mac0123456789abcdef", mac)
            assertEquals(listOf(mac, second), store.latest("hh-s"))
            assertEquals(emptyList(), store.latest("hh-other"))
        }
    }
}
