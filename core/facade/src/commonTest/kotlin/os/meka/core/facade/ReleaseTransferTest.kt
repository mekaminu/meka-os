package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import os.meka.core.sync.TransportException
import os.meka.core.wire.WireCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseTransferTest {
    /** A server in memory that fails the first [flaky] calls of each chunk with a network error. */
    private class FakeServer(private val flaky: Int = 0) : ReleasesApi {
        val chunks = HashMap<Int, ByteArray>()
        var published: AppRelease? = null
        var refuse: String? = null
        private val failures = HashMap<String, Int>()
        private fun maybeFail(key: String) {
            val n = failures.getOrElse(key) { 0 }
            if (n < flaky) { failures[key] = n + 1; throw TransportException("offline") }
        }
        override suspend fun latestRelease(platform: String) = published
        override suspend fun releaseChunk(platform: String, versionCode: Long, index: Int): ByteArray {
            maybeFail("get$index"); return chunks.getValue(index)
        }
        override suspend fun uploadReleaseChunk(release: AppRelease, index: Int, bytes: ByteArray): Boolean {
            refuse?.let { throw PublishRefusedException(it) }
            maybeFail("put$index")
            chunks[index] = bytes
            val done = chunks.size == release.chunkCount && Sha256.hex((0 until release.chunkCount).map { chunks.getValue(it) }.reduce { a, b -> a + b }) == release.sha256
            if (done) published = release
            return done
        }
    }

    private val apk = Random(7).nextBytes(WireCodec.RELEASE_CHUNK_BYTES * 2 + 500)

    @Test
    fun aBuildIsPublishedInChunksAndComesBackIdentical() = runTest {
        val server = FakeServer(flaky = 2) // two network errors per chunk are ridden out
        val out = ReleaseTransfer.publish(server, "android", apk, 412, "0.1.412")
        val pub = out as PublishOutcome.Published
        assertEquals(3, pub.release.chunkCount)
        assertEquals(Sha256.hex(apk), pub.release.sha256)
        assertTrue(out.message.startsWith("Published build 412 to the Fold."))

        val got = mutableListOf<ByteArray>()
        val progress = mutableListOf<Int>()
        ReleaseTransfer.download(server, server.published!!, { got += it }, { progress += it })
        assertContentEquals(apk, got.reduce { a, b -> a + b })
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test
    fun aRefusalOrALostServerIsReportedPlainly() = runTest {
        val refusing = FakeServer().apply { refuse = "build 412 isn't newer than the published build 412" }
        assertEquals("Not published: build 412 isn't newer than the published build 412.", ReleaseTransfer.publish(refusing, "android", apk, 412, "0.1.412").message)
        val down = FakeServer(flaky = 3)
        assertTrue(ReleaseTransfer.publish(down, "android", apk, 412, "0.1.412") is PublishOutcome.Failed)
        assertTrue(ReleaseTransfer.publish(FakeServer(), "android", ByteArray(0), 1, "x") is PublishOutcome.Refused)
    }

    @Test
    fun aChunkOfTheWrongSizeStopsTheDownload() = runTest {
        val server = FakeServer()
        ReleaseTransfer.publish(server, "android", apk, 412, "0.1.412")
        server.chunks[1] = ByteArray(10)
        var failed = false
        try { ReleaseTransfer.download(server, server.published!!, {}, {}) } catch (e: TransportException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun onlyAMekaApkWithItsGradleMetadataCanBePublished() {
        val meta = """{"applicationId":"os.meka.android","elements":[{"versionCode":412,"versionName":"0.1.412","outputFile":"app-debug.apk"}]}"""
        val zip = "PK\u0003\u0004rest".encodeToByteArray()
        assertEquals(412L, ReleaseTransfer.checkApk(zip, meta).first?.versionCode)
        assertEquals("That file isn't an APK.", ReleaseTransfer.checkApk("hello".encodeToByteArray(), meta).second)
        assertTrue(ReleaseTransfer.checkApk(zip, null).second!!.contains("output-metadata.json"))
        assertTrue(ReleaseTransfer.checkApk(zip, meta.replace("os.meka.android", "com.other")).second!!.startsWith("That APK isn't MEKA"))
    }

    @Test
    fun theTransportMapsServerRefusals() = runTest {
        val client = HttpClient(MockEngine { req ->
            when (req.url.encodedPath) {
                "/v1/releases/upload" -> respond("build 412 isn't newer than the published build 412", HttpStatusCode.Conflict)
                "/v1/releases/latest" -> respond(WireCodec.encodeRelease(null), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val t = HttpSyncTransport(client, "https://meka.example", null) { "s".repeat(64) }
        assertEquals(null, t.latestRelease("android"))
        val out = ReleaseTransfer.publish(t, "android", apk, 412, "0.1.412")
        assertEquals(PublishOutcome.Refused("build 412 isn't newer than the published build 412"), out)
    }
}
