package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.WireCodec
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The release-only publisher (build plan: hands-free phone updates). */
class ReleasePublisherTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val keys = ReleasePublisher.generateKeyPair()
    private val privateKey = ReleasePublisher.privateKey(keys.first)
    private val store = InMemoryReleaseStore()

    private fun ApplicationTestBuilder.mekaServer(publicKey: String? = keys.second, registry: DeviceRegistry = devices) =
        application { mekaSync(InMemoryServerOpStore(), registry, enrolToken = "e".repeat(40), releases = Releases(store), push = null, publisher = PublisherKeySource { publicKey }) }

    private fun ApplicationTestBuilder.publisherClient(key: java.security.PrivateKey = privateKey) =
        ReleasePublisherClient(key, ReleasePublisherClient.Post { path, headers, body ->
            val r = client.post(path) { headers.forEach { (k, v) -> header(k, v) }; setBody(body) }
            r.status.value to r.bodyAsText()
        })

    @Test
    fun gitHubPublishesABuildThatTheFoldFetches() = testApplication {
        mekaServer()
        val publisher = publisherClient()
        assertEquals(null, publisher.latest("android"))
        val bytes = Random(7).nextBytes(WireCodec.RELEASE_CHUNK_BYTES + 4321)
        val r = publisher.publish("android", bytes, 501, "0.1.501")
        assertTrue(r is ReleasePublisherClient.Outcome.Published, r.toString())
        assertEquals(501L, publisher.latest("android")!!.versionCode)
        // Recorded as published by the GitHub build, for Meka's household.
        assertEquals(true, store.find("hh", "android", 501)?.complete)

        // The Fold sees and fetches it as it would a Mac build.
        val latestBody = WireCodec.encodePlatform("android")
        val offered = WireCodec.decodeRelease(
            client.post("/v1/releases/latest") { with(foldKey) { signed(foldSecret, "/v1/releases/latest", latestBody) } }.bodyAsText(),
        )!!
        val fetched = (0 until offered.chunkCount).map { i ->
            val ref = WireCodec.encodeChunkRef(WireCodec.ChunkRef("android", 501, i))
            Base64.getDecoder().decode(WireCodec.decodeChunkData(client.post("/v1/releases/chunk") { with(foldKey) { signed(foldSecret, "/v1/releases/chunk", ref) } }.bodyAsText()))
        }.reduce { a, b -> a + b }
        assertContentEquals(bytes, fetched)

        // The same build again is fine; an older or same-numbered different one is "not newer", not a failure.
        assertTrue(publisher.publish("android", bytes, 501, "0.1.501") is ReleasePublisherClient.Outcome.Published)
        assertTrue(publisher.publish("android", Random(8).nextBytes(500), 501, "0.1.501") is ReleasePublisherClient.Outcome.NotNewer)
        assertTrue(publisher.publish("android", Random(9).nextBytes(500), 400, "0.1.400") is ReleasePublisherClient.Outcome.NotNewer)
    }

    @Test
    fun thePublisherMayCallNothingButLatestAndUpload() = testApplication {
        mekaServer()
        val routes = listOf(
            "/v1/releases/chunk", "/v1/sync/push", "/v1/sync/pull", "/v1/sync/wait", "/v1/devices/key", "/v1/enrol",
        )
        for (path in routes) {
            val body = "{}"
            val r = client.post(path) { ReleasePublisher.headers(privateKey, path, body).forEach { (k, v) -> header(k, v) }; setBody(body) }
            assertEquals(HttpStatusCode.Forbidden, r.status, path)
        }
        // A chunk can't be fetched with the publisher's key, even of a build that exists.
        publisherClient().publish("android", Random(1).nextBytes(100), 10, "0.1.10")
        val ref = WireCodec.encodeChunkRef(WireCodec.ChunkRef("android", 10, 0))
        val chunk = client.post("/v1/releases/chunk") { ReleasePublisher.headers(privateKey, "/v1/releases/chunk", ref).forEach { (k, v) -> header(k, v) }; setBody(ref) }
        assertEquals(HttpStatusCode.Forbidden, chunk.status)
    }

    @Test
    fun anotherKeyAReplayOrNoRegisteredKeyIsRefused() = testApplication {
        mekaServer()
        val stranger = ReleasePublisher.privateKey(ReleasePublisher.generateKeyPair().first)
        val body = WireCodec.encodePlatform("android")
        suspend fun latestWith(h: Map<String, String>) = client.post("/v1/releases/latest") { h.forEach { (k, v) -> header(k, v) }; setBody(body) }.status
        assertEquals(HttpStatusCode.Unauthorized, latestWith(ReleasePublisher.headers(stranger, "/v1/releases/latest", body)))
        val once = ReleasePublisher.headers(privateKey, "/v1/releases/latest", body)
        assertEquals(HttpStatusCode.OK, latestWith(once))
        assertEquals(HttpStatusCode.Unauthorized, latestWith(once)) // the nonce was used
        // Only the publisher's own name is accepted.
        assertEquals(HttpStatusCode.Unauthorized, latestWith(ReleasePublisher.headers(privateKey, "/v1/releases/latest", body) + ("Authorization" to "Publisher mac")))
        // Signed for another route.
        assertEquals(HttpStatusCode.Unauthorized, latestWith(ReleasePublisher.headers(privateKey, "/v1/releases/upload", body)))
        // A device can't enrol under the publisher's name: device ids are [a-z0-9] only, and "github-build" isn't one.
        val enrol = WireCodec.encodeEnrolRequest(WireCodec.EnrolRequest("hh", ReleasePublisher.ID, "x"))
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/enrol") { header("Authorization", "Enrol ${"e".repeat(40)}"); setBody(enrol) }.status)
    }

    @Test
    fun withNoKeySetOrNoSingleHouseholdNothingIsPublished() = testApplication {
        val two = InMemoryDeviceRegistry().apply { enrol("hh", "fold"); enrol("other", "x") }
        mekaServer(publicKey = null)
        val body = WireCodec.encodePlatform("android")
        val r = client.post("/v1/releases/latest") { ReleasePublisher.headers(privateKey, "/v1/releases/latest", body).forEach { (k, v) -> header(k, v) }; setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals(null, two.soleHousehold())
        assertEquals("hh", devices.soleHousehold())
    }

    @Test
    fun aServerWithTwoHouseholdsRefusesThePublisher() = testApplication {
        val two = InMemoryDeviceRegistry().apply { enrol("hh", "fold"); enrol("other", "x") }
        mekaServer(registry = two)
        val body = WireCodec.encodePlatform("android")
        val r = client.post("/v1/releases/latest") { ReleasePublisher.headers(privateKey, "/v1/releases/latest", body).forEach { (k, v) -> header(k, v) }; setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }
}
