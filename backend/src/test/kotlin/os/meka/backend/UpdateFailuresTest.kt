package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.UpdateFailureCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phone update failures (build plan: phone updates sometimes fail to install): the Fold reports, a build run reads. */
class UpdateFailuresTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val keys = ReleasePublisher.generateKeyPair()
    private val privateKey = ReleasePublisher.privateKey(keys.first)
    private val failures = InMemoryUpdateFailureStore()
    private val now = System.currentTimeMillis()
    private val report = UpdateFailureCodec.Report(
        "install", 5, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match", 425, 430, now - 60_000, "over 5 GB", "wifi", 1,
    )

    private fun ApplicationTestBuilder.mekaServer() = application {
        mekaSync(
            InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore()), publisher = PublisherKeySource { keys.second },
            updateFailures = UpdateFailures(failures),
        )
    }

    private fun ApplicationTestBuilder.publisherClient() = ReleasePublisherClient(privateKey, ReleasePublisherClient.Post { path, headers, body ->
        val r = client.post(path) { headers.forEach { (k, v) -> header(k, v) }; setBody(body) }
        r.status.value to r.bodyAsText()
    })

    @Test
    fun theFoldReportsAndThePublisherReadsNewestFirst() = testApplication {
        mekaServer()
        val path = UpdateFailureCodec.REPORT_PATH
        for (r in listOf(report.copy(toCode = 429, atMs = now - 120_000), report)) {
            val body = UpdateFailureCodec.encodeReport(r)
            assertEquals(HttpStatusCode.OK, client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.status)
        }
        val read = publisherClient().failures()!!
        assertEquals(listOf(430L, 429L), read.map { it.toCode })
        assertEquals(report, read.first())
        // The Fold can read them too.
        val list = UpdateFailureCodec.LIST_PATH
        val body = UpdateFailureCodec.encodeListRequest()
        assertEquals(2, UpdateFailureCodec.decodeList(client.post(list) { with(foldKey) { signed(foldSecret, list, body) } }.bodyAsText()).size)
        // The job's lines: one warning per recent failure, '%' escaped.
        val lines = ReleasePublisherCli.failureLines(read, now)
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("::warning title=Phone update failed::"), lines[0])
        assertTrue(lines[0].endsWith("build 425 → 430 · install · STATUS_FAILURE_CONFLICT · INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match · storage over 5 GB · wifi · 1 attempt"), lines[0])
    }

    @Test
    fun thePublisherCantReportAndUnsignedCallersAreRefused() = testApplication {
        mekaServer()
        val path = UpdateFailureCodec.REPORT_PATH
        val body = UpdateFailureCodec.encodeReport(report)
        val asPublisher = client.post(path) { ReleasePublisher.headers(privateKey, path, body).forEach { (k, v) -> header(k, v) }; setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, asPublisher.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { with(foldKey) { signed(foldSecret, path, "{\"w\":1}") } }.status)
        assertEquals(emptyList(), publisherClient().failures())
    }

    @Test
    fun oldFailuresAreLeftOutAndTheStoreKeepsTwenty() {
        val old = report.copy(atMs = now - 15 * 86_400_000L)
        assertEquals(listOf("::notice title=Phone update failures::none reported in the last 14 days"), ReleasePublisherCli.failureLines(listOf(old), now))
        assertEquals(listOf("::notice title=Phone update failures::the server doesn't report them yet"), ReleasePublisherCli.failureLines(null, now))
        repeat(25) { failures.add("hh", "fold", report.copy(toCode = 400L + it)) }
        assertEquals(10, failures.recent("hh", 10).size)
        assertEquals(424L, failures.recent("hh", 1).single().toCode)
        assertEquals(20, failures.recent("hh", 100).size)
    }

    @Test
    fun postgresKeepsTheNewestTwentyNewestFirst() {
        val url = System.getenv("MEKA_TEST_DB_URL") ?: return
        com.zaxxer.hikari.HikariDataSource(com.zaxxer.hikari.HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 2
        }).use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c -> c.createStatement().execute("TRUNCATE op_log, device, household CASCADE") }
            PostgresDeviceRegistry(ds).enrol("hh-uf", "fold", "fold")
            val store = PostgresUpdateFailureStore(ds)
            repeat(22) { store.add("hh-uf", "fold", report.copy(toCode = 400L + it, status = if (it == 21) null else 5)) }
            val all = store.recent("hh-uf", 100)
            assertEquals(20, all.size)
            assertEquals(421L, all.first().toCode)
            assertEquals(null, all.first().status)
            assertEquals(report.copy(toCode = 420), all[1])
        }
    }

    @Test
    fun aServerWithoutTheRouteReadsAsNotYet() = testApplication {
        application { mekaSync(InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore()), publisher = PublisherKeySource { keys.second }) }
        assertEquals(null, publisherClient().failures())
    }
}
