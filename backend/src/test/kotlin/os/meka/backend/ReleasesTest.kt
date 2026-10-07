package os.meka.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.WireCodec
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleasesTest {
    private val devices = InMemoryDeviceRegistry()
    private val macSecret = devices.enrol("hh", "mac")
    private val foldSecret = devices.enrol("hh", "fold")
    private val strangerSecret = devices.enrol("other", "intruder")
    private val macKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "mac"), it.publicB64) }
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val strangerKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("other", "intruder"), it.publicB64) }
    private val mac = DeviceIdentity("hh", "mac")

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun apk(size: Int, seed: Int = 1) = Random(seed).nextBytes(size)
    private fun release(bytes: ByteArray, code: Long) =
        WireCodec.AppRelease("android", code, "0.1.$code", sha(bytes), bytes.size.toLong(), WireCodec.releaseChunkCount(bytes.size.toLong()))
    private fun chunks(bytes: ByteArray, code: Long): List<WireCodec.ReleaseChunk> {
        val r = release(bytes, code)
        return (0 until r.chunkCount).map { i ->
            val from = i * WireCodec.RELEASE_CHUNK_BYTES
            val part = bytes.copyOfRange(from, from + WireCodec.releaseChunkSize(r.sizeBytes, i))
            WireCodec.ReleaseChunk(r, i, Base64.getEncoder().encodeToString(part))
        }
    }
    private fun publish(releases: Releases, bytes: ByteArray, code: Long) = chunks(bytes, code).map { releases.upload(mac, it) }.last()

    @Test
    fun aBuildPublishedFromTheMacIsFetchedChunkByChunkByTheFold() = testApplication {
        application { mekaSync(InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore())) }
        val bytes = apk(WireCodec.RELEASE_CHUNK_BYTES * 2 + 1234)
        val latestBody = WireCodec.encodePlatform("android")
        suspend fun latest() = WireCodec.decodeRelease(
            client.post("/v1/releases/latest") { with(foldKey) { signed(foldSecret, "/v1/releases/latest", latestBody) } }.bodyAsText(),
        )
        assertEquals(null, latest())

        val parts = chunks(bytes, 412)
        // Out of order is fine; nothing is offered until every chunk is in and the whole file matched its hash.
        for (c in listOf(parts[2], parts[0])) {
            val body = WireCodec.encodeReleaseChunk(c)
            val r = client.post("/v1/releases/upload") { with(macKey) { signed(macSecret, "/v1/releases/upload", body) } }
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(false, WireCodec.decodeUploadAck(r.bodyAsText()).complete)
            assertEquals(null, latest())
        }
        val body = WireCodec.encodeReleaseChunk(parts[1])
        val done = client.post("/v1/releases/upload") { with(macKey) { signed(macSecret, "/v1/releases/upload", body) } }
        assertEquals(WireCodec.UploadAck(3, true), WireCodec.decodeUploadAck(done.bodyAsText()))
        val offered = latest()!!
        assertEquals(412L, offered.versionCode)

        val fetched = (0 until offered.chunkCount).map { i ->
            val ref = WireCodec.encodeChunkRef(WireCodec.ChunkRef("android", 412, i))
            val r = client.post("/v1/releases/chunk") { with(foldKey) { signed(foldSecret, "/v1/releases/chunk", ref) } }
            Base64.getDecoder().decode(WireCodec.decodeChunkData(r.bodyAsText()))
        }.reduce { a, b -> a + b }
        assertContentEquals(bytes, fetched)
        assertEquals(sha(bytes), offered.sha256)

        // Another household sees nothing; a missing chunk is a 404; unsigned requests are refused.
        val strangerLatest = client.post("/v1/releases/latest") { with(strangerKey) { signed(strangerSecret, "/v1/releases/latest", latestBody) } }
        assertEquals(null, WireCodec.decodeRelease(strangerLatest.bodyAsText()))
        val ref = WireCodec.encodeChunkRef(WireCodec.ChunkRef("android", 412, 3))
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/releases/chunk") { with(foldKey) { signed(foldSecret, "/v1/releases/chunk", ref) } }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/releases/latest") { header("Authorization", "Bearer $foldSecret"); setBody(latestBody) }.status)
    }

    @Test
    fun devicesWithoutAKeyCannotPublishOrFetch() = testApplication {
        val bare = devices.enrol("hh", "old")
        application { mekaSync(InMemoryServerOpStore(), devices, releases = Releases(InMemoryReleaseStore())) }
        val r = client.post("/v1/releases/latest") { header("Authorization", "Bearer $bare"); setBody(WireCodec.encodePlatform("android")) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun aBuildMustBeNewerAndAWrongHashIsDiscarded() {
        val store = InMemoryReleaseStore()
        val releases = Releases(store)
        val one = apk(5000, 1)
        assertEquals(Releases.Upload.Ack(1, true), publish(releases, one, 10))
        // Publishing the same build again is fine; the same number with different contents, or an older one, is not.
        assertEquals(Releases.Upload.Ack(1, true), publish(releases, one, 10))
        assertTrue(publish(releases, apk(5000, 2), 10) is Releases.Upload.Conflict)
        assertTrue(publish(releases, apk(5000, 3), 9) is Releases.Upload.Conflict)

        // Contents that don't match the hash they were published with are dropped, not offered.
        val two = apk(3000, 4)
        val lying = chunks(two, 11).single().let { c -> c.copy(release = c.release.copy(sha256 = "0".repeat(64))) }
        assertTrue(releases.upload(mac, lying) is Releases.Upload.Rejected)
        assertEquals(null, store.find("hh", "android", 11))
        assertEquals(10L, releases.latest(mac, "android")!!.versionCode)
        // A chunk whose bytes don't fit its place is refused.
        val short = chunks(two, 11).single().copy(dataB64 = Base64.getEncoder().encodeToString(ByteArray(10)))
        assertTrue(releases.upload(mac, short) is Releases.Upload.Rejected)
    }

    @Test
    fun theNewestTwoBuildsAreKept() {
        val store = InMemoryReleaseStore()
        val releases = Releases(store)
        for (code in 1L..4L) publish(releases, apk(100, code.toInt()), code)
        assertEquals(listOf(false, false, true, true), (1L..4L).map { store.find("hh", "android", it) != null })
        assertEquals(4L, releases.latest(mac, "android")!!.versionCode)
    }

    /** The same rules on Postgres (CI's service container); skipped when MEKA_TEST_DB_URL is unset. */
    @Test
    fun postgresStoreKeepsChunksAndPrunes() {
        val url = System.getenv("MEKA_TEST_DB_URL") ?: return
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = System.getenv("MEKA_TEST_DB_USER") ?: "postgres"
            password = System.getenv("MEKA_TEST_DB_PASSWORD") ?: "postgres"; maximumPoolSize = 2
        }).use { ds ->
            Migrations.apply(ds)
            ds.connection.use { c ->
                c.createStatement().execute("DELETE FROM app_release WHERE household_id = 'hh'")
                c.createStatement().execute("INSERT INTO household(id) VALUES ('hh') ON CONFLICT DO NOTHING")
            }
            val store = PostgresReleaseStore(ds)
            val releases = Releases(store)
            val big = apk(WireCodec.RELEASE_CHUNK_BYTES + 77, 9)
            val parts = chunks(big, 20)
            assertEquals(Releases.Upload.Ack(1, false), releases.upload(mac, parts[0]))
            assertEquals(null, releases.latest(mac, "android"))
            assertEquals(Releases.Upload.Ack(2, true), releases.upload(mac, parts[1]))
            assertEquals(release(big, 20), releases.latest(mac, "android"))
            assertContentEquals(big.copyOfRange(WireCodec.RELEASE_CHUNK_BYTES, big.size), releases.chunk(mac, WireCodec.ChunkRef("android", 20, 1)))
            // An abandoned upload of a newer build is kept while it might still finish.
            releases.upload(mac, chunks(apk(WireCodec.RELEASE_CHUNK_BYTES + 5, 10), 25)[0])
            for (code in 21L..22L) publish(releases, apk(100, code.toInt()), code)
            assertEquals(null, store.find("hh", "android", 20))
            assertEquals(listOf(true, true), listOf(21L, 22L).map { store.find("hh", "android", it)?.complete == true })
            assertEquals(false, store.find("hh", "android", 25)?.complete)
            assertTrue(releases.upload(mac, chunks(apk(100, 5), 21).single()) is Releases.Upload.Conflict)
            ds.connection.use { c -> c.createStatement().execute("DELETE FROM app_release WHERE household_id = 'hh'") }
        }
    }
}
