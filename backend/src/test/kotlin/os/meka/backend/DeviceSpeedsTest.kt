package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.DeviceSpeedCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/** Needs Meka #43: each device sends its day's response times; the build report prints one line per device. */
class DeviceSpeedsTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val macSecret = devices.enrol("hh", "mac")
    private val macKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "mac"), it.publicB64) }
    private val keys = ReleasePublisher.generateKeyPair()
    private val privateKey = ReleasePublisher.privateKey(keys.first)
    private val at = java.time.Instant.parse("2026-11-13T08:00:00Z").toEpochMilli()
    private val phone = DeviceSpeedCodec.Report("phone", at, 6, 500, 1_200, 14, 1_700, 3_100)
    private val mac = DeviceSpeedCodec.Report("Mac", at + 60_000, 2, 400, 450, 0, null, null)

    private fun ApplicationTestBuilder.mekaServer() = application {
        mekaSync(
            InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore()), publisher = PublisherKeySource { keys.second },
            deviceSpeeds = DeviceSpeeds(InMemoryDeviceSpeedStore()),
        )
    }

    private fun ApplicationTestBuilder.publisherClient() = ReleasePublisherClient(privateKey, ReleasePublisherClient.Post { path, headers, body ->
        val r = client.post(path) { headers.forEach { (k, v) -> header(k, v) }; setBody(body) }
        r.status.value to r.bodyAsText()
    })

    @Test
    fun eachDeviceSendsAndThePublisherReadsTheNewestPerDevice() = testApplication {
        mekaServer()
        assertEquals(emptyList(), publisherClient().deviceSpeeds())
        val path = DeviceSpeedCodec.REPORT_PATH
        for ((key, secret, r) in listOf(Triple(foldKey, foldSecret, phone.copy(helloCount = 1)), Triple(foldKey, foldSecret, phone), Triple(macKey, macSecret, mac))) {
            val body = DeviceSpeedCodec.encodeReport(r)
            assertEquals(HttpStatusCode.OK, client.post(path) { with(key) { signed(secret, path, body) } }.status)
        }
        val read = publisherClient().deviceSpeeds()!!
        assertEquals(listOf(mac, phone), read)
        assertEquals(
            listOf(
                "::notice title=Obi's speed (devices, last day)::Mac · 2 hellos avg 0.4 s, slowest 0.5 s (aim 0.6 s) · sent 2026-11-13 08:01 UTC",
                "::warning title=Obi's speed (devices, last day)::Phone · 6 hellos avg 0.5 s, slowest 1.2 s (aim 0.6 s) · " +
                    "14 answers avg 1.7 s, slowest 3.1 s (aim 1.5 s), over aim · sent 2026-11-13 08:00 UTC",
            ),
            ReleasePublisherCli.deviceSpeedLines(read),
        )
        assertEquals(listOf("::notice title=Obi's speed (devices)::no device has sent its day's times yet"), ReleasePublisherCli.deviceSpeedLines(emptyList()))
        assertEquals(listOf("::notice title=Obi's speed (devices)::this server doesn't take the devices' times yet"), ReleasePublisherCli.deviceSpeedLines(null))
    }

    @Test
    fun thePublisherCantSendAndUnsignedCallersAreRefused() = testApplication {
        mekaServer()
        val path = DeviceSpeedCodec.REPORT_PATH
        val body = DeviceSpeedCodec.encodeReport(phone)
        val asPublisher = client.post(path) { ReleasePublisher.headers(privateKey, path, body).forEach { (k, v) -> header(k, v) }; setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, asPublisher.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { with(foldKey) { signed(foldSecret, path, "{\"w\":1}") } }.status)
        assertEquals(emptyList(), publisherClient().deviceSpeeds())
    }
}
