package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.FoldPostureCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Fold's posture changes (bedside clock, slice 2): the Fold sends, a build run reads the newest report. */
class FoldPosturesTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val keys = ReleasePublisher.generateKeyPair()
    private val privateKey = ReleasePublisher.privateKey(keys.first)
    private val at = java.time.Instant.parse("2026-10-12T18:14:00Z").toEpochMilli()
    private val report = FoldPostureCodec.Report(
        "SM-F966B", at + 60_000,
        listOf(FoldPostureCodec.Change(at, "HALF_OPENED across=false 2176×1812 95° → TABLETOP/WINDOW"), FoldPostureCodec.Change(at - 60_000, "FLAT → FLAT")),
    )

    private fun ApplicationTestBuilder.mekaServer() = application {
        mekaSync(
            InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore()), publisher = PublisherKeySource { keys.second },
            foldPostures = FoldPostures(InMemoryFoldPostureStore()),
        )
    }

    private fun ApplicationTestBuilder.publisherClient() = ReleasePublisherClient(privateKey, ReleasePublisherClient.Post { path, headers, body ->
        val r = client.post(path) { headers.forEach { (k, v) -> header(k, v) }; setBody(body) }
        r.status.value to r.bodyAsText()
    })

    @Test
    fun theFoldSendsAndThePublisherReadsTheNewest() = testApplication {
        mekaServer()
        assertNull(publisherClient().postures())
        val path = FoldPostureCodec.REPORT_PATH
        for (r in listOf(report.copy(sentAtMs = at), report)) {
            val body = FoldPostureCodec.encodeReport(r)
            assertEquals(HttpStatusCode.OK, client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.status)
        }
        val read = publisherClient().postures()
        assertEquals(report, read)
        assertEquals(
            listOf(
                "::notice title=Fold posture (SM-F966B, sent 2026-10-12 18:15 UTC)::2026-10-12 18:14 UTC · HALF_OPENED across=false 2176×1812 95° → TABLETOP/WINDOW",
                "::notice title=Fold posture (SM-F966B, sent 2026-10-12 18:15 UTC)::2026-10-12 18:13 UTC · FLAT → FLAT",
            ),
            ReleasePublisherCli.postureLines(read),
        )
        assertEquals(listOf("::notice title=Fold posture::the Fold hasn't sent its posture changes yet"), ReleasePublisherCli.postureLines(null))
    }

    @Test
    fun thePublisherCantSendAndUnsignedCallersAreRefused() = testApplication {
        mekaServer()
        val path = FoldPostureCodec.REPORT_PATH
        val body = FoldPostureCodec.encodeReport(report)
        val asPublisher = client.post(path) { ReleasePublisher.headers(privateKey, path, body).forEach { (k, v) -> header(k, v) }; setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, asPublisher.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { with(foldKey) { signed(foldSecret, path, "{\"w\":1}") } }.status)
        assertNull(publisherClient().postures())
    }
}
