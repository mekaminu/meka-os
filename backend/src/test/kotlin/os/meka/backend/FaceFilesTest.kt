package os.meka.backend

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.FaceCodec
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Obi's face (slice 1): the seven files from the private bucket to keyed phones and Macs only, and nothing else. */
class FaceFilesTest {
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private val watchSecret = devices.enrol("hh", "watch0123abcd")
    private val watchKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "watch0123abcd"), it.publicB64) }
    private val unkeyedSecret = devices.enrol("hh", "oldmac")
    private val store = InMemoryFaceFileStore()
    private var clock = 1_000_000L

    private fun ApplicationTestBuilder.mekaServer() = application {
        mekaSync(InMemoryServerOpStore(), devices, faceFiles = FaceFiles(store) { clock })
    }

    @Test
    fun noFilesYetIsAnEmptyListSoTheAppsKeepTheOrb() = testApplication {
        mekaServer()
        val m = FaceCodec.MANIFEST_PATH
        val body = FaceCodec.encodeManifestRequest()
        val r = client.post(m) { with(foldKey) { signed(foldSecret, m, body) } }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(emptyList(), FaceCodec.decodeManifest(r.bodyAsText()))
        val f = FaceCodec.FILE_PATH
        val req = FaceCodec.encodeFileRequest("mouth-ah.png")
        assertEquals(HttpStatusCode.NotFound, client.post(f) { with(foldKey) { signed(foldSecret, f, req) } }.status)
    }

    @Test
    fun theFoldGetsTheFilesAndTheManifestOnceUploaded() = testApplication {
        mekaServer()
        val png = ByteArray(2_000) { (it * 7).toByte() }
        FaceCodec.FILES.forEach { store.objects[it] = png }
        store.objects["notes.txt"] = "private".encodeToByteArray() // something else in the folder is never listed
        val m = FaceCodec.MANIFEST_PATH
        val body = FaceCodec.encodeManifestRequest()
        val list = FaceCodec.decodeManifest(client.post(m) { with(foldKey) { signed(foldSecret, m, body) } }.bodyAsText())
        assertTrue(FaceCodec.complete(list))
        assertEquals(7, list.size)

        val f = FaceCodec.FILE_PATH
        val req = FaceCodec.encodeFileRequest("mouth-oh.png")
        val r = client.post(f) { with(foldKey) { signed(foldSecret, f, req) } }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("image/png", r.headers["Content-Type"])
        assertEquals(FaceFiles.CACHE_CONTROL, r.headers["Cache-Control"])
        assertContentEquals(png, r.readRawBytes())
    }

    @Test
    fun watchesUnkeyedCallersAndOtherNamesAreRefusedBeforeTheBucketIsRead() = testApplication {
        mekaServer()
        FaceCodec.FILES.forEach { store.objects[it] = ByteArray(10) }
        val f = FaceCodec.FILE_PATH
        val req = FaceCodec.encodeFileRequest(FaceCodec.LOOP)
        assertEquals(HttpStatusCode.Forbidden, client.post(f) { with(watchKey) { signed(watchSecret, f, req) } }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(f) { with(foldKey) { signed("wrong", f, req) } }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(f) { with(foldKey) { signed(unkeyedSecret, f, req) } }.status)
        for (bad in listOf("../db/secret", "face/x", "notes.txt")) {
            val b = """{"w":${WireCodec.VERSION},"file":"$bad"}"""
            assertEquals(HttpStatusCode.BadRequest, client.post(f) { with(foldKey) { signed(foldSecret, f, b) } }.status)
        }
        assertEquals(0, store.reads)
    }

    @Test
    fun theListingIsKeptAFewMinutes() {
        val face = FaceFiles(store) { clock }
        val who = DeviceIdentity("hh", "fold")
        assertEquals(emptyList(), FaceCodec.decodeManifest(face.manifest(who).body))
        store.objects["mouth-m.png"] = ByteArray(5)
        assertEquals(emptyList(), FaceCodec.decodeManifest(face.manifest(who).body))
        clock += FaceFiles.LIST_CACHE_MS
        assertEquals(listOf("mouth-m.png"), FaceCodec.decodeManifest(face.manifest(who).body).map { it.name })
    }
}
