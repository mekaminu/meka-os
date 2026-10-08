package os.meka.core.wire

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PullResponse
import os.meka.core.sync.PushRequest
import os.meka.core.sync.PushResponse
import os.meka.core.sync.SequencedOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFailsWith

class WireCodecTest {
    private fun op(v: FieldValue, base: List<String> = listOf("b1", "b2")) = Op(
        "op1", "hh", "task", "t1", "title", v, Hlc(1_790_000_000_123, 7, "android"), base, "android", 3,
    )

    @Test
    fun opsRoundTripForEveryValueType() {
        listOf(
            FieldValue.Text("Logan football — kit ✓ \"quoted\" \n newline"),
            FieldValue.Int64(Long.MAX_VALUE),
            FieldValue.Int64(Long.MIN_VALUE),
            FieldValue.Bool(true),
            FieldValue.Null,
        ).forEach { v ->
            val o = op(v)
            assertEquals(o, WireCodec.decodeOp(WireCodec.encodeOp(o)))
        }
        assertEquals(op(FieldValue.Null, emptyList()), WireCodec.decodeOp(WireCodec.encodeOp(op(FieldValue.Null, emptyList()))))
    }

    @Test
    fun protocolDocumentsRoundTrip() {
        val push = PushRequest("hh", "android", listOf(op(FieldValue.Text("x"))))
        assertEquals(push, WireCodec.decodePushRequest(WireCodec.encodePushRequest(push)))
        val pushResp = PushResponse(listOf("a", "b"), mapOf("c" to "household mismatch"))
        assertEquals(pushResp, WireCodec.decodePushResponse(WireCodec.encodePushResponse(pushResp)))
        val pull = PullRequest("hh", "mac", 42, 100)
        assertEquals(pull, WireCodec.decodePullRequest(WireCodec.encodePullRequest(pull)))
        val pullResp = PullResponse(listOf(SequencedOp(43, op(FieldValue.Bool(false)))), hasMore = true)
        assertEquals(pullResp, WireCodec.decodePullResponse(WireCodec.encodePullResponse(pullResp)))
    }

    @Test
    fun enrolmentRoundTripsAndValidatesIds() {
        val r = WireCodec.EnrolRequest("hh1", "android3f9a", "Fold 8")
        assertEquals(r, WireCodec.decodeEnrolRequest(WireCodec.encodeEnrolRequest(r)))
        assertEquals("s3cr3t", WireCodec.decodeEnrolResponse(WireCodec.encodeEnrolResponse("s3cr3t")))
        assertFailsWith<WireFormatException> { WireCodec.decodeEnrolRequest(WireCodec.encodeEnrolRequest(r.copy(deviceId = "../etc"))) }
        assertFailsWith<WireFormatException> { WireCodec.decodeEnrolRequest(WireCodec.encodeEnrolRequest(r.copy(householdId = ""))) }
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val s = """{"w":1,"hh":"hh","dev":"mac","after":5,"limit":10,"newFieldFromTheFuture":{"x":1}}"""
        assertEquals(PullRequest("hh", "mac", 5, 10), WireCodec.decodePullRequest(s))
    }

    @Test
    fun malformedAndFutureVersionsAreRejected() {
        assertFailsWith<WireFormatException> { WireCodec.decodePullRequest("""{"w":2,"hh":"h","dev":"d","after":0}""") }
        assertFailsWith<WireFormatException> { WireCodec.decodePullRequest("""{"hh":"h"}""") }
        assertFailsWith<WireFormatException> { WireCodec.decodePushRequest("not json") }
        assertFailsWith<WireFormatException> {
            WireCodec.decodePushRequest("""{"w":1,"hh":"h","dev":"d","ops":[{"id":"x"}]}""")
        }
    }

    @Test
    fun integrationDocumentsRoundTrip() {
        val accounts = listOf(
            WireCodec.IntegrationAccount("google", "me@gmail.com", "ok", 1_790_000_000_000L),
            WireCodec.IntegrationAccount("microsoft", "me@outlook.com", "needs_reconnect", null),
            WireCodec.IntegrationAccount("google", "work@gmail.com", "ok", null, canEdit = true),
        )
        assertEquals(accounts, WireCodec.decodeAccounts(WireCodec.encodeAccounts(accounts)))
        // An older server's list (no "edit") reads as read-only.
        assertEquals(false, WireCodec.decodeAccounts("""{"w":${WireCodec.VERSION},"accounts":[{"provider":"google","email":"a@b.c","status":"ok"}]}""").single().canEdit)
        assertEquals("https://accounts.example/x?y=1", WireCodec.decodeConnectUrl(WireCodec.encodeConnectUrl("https://accounts.example/x?y=1")))
    }

    @Test
    fun connectRequestsAskForEditingOnlyWhenSaidAndEditingChangesRoundTrip() {
        assertEquals(true, WireCodec.decodeConnectRequest(WireCodec.encodeConnectRequest(true)))
        assertEquals(false, WireCodec.decodeConnectRequest(WireCodec.encodeConnectRequest(false)))
        assertEquals(false, WireCodec.decodeConnectRequest("")) // older apps send nothing: read-only
        val off = WireCodec.EditingChange("me@gmail.com", editing = false)
        assertEquals(off, WireCodec.decodeEditingChange(WireCodec.encodeEditingChange(off)))
        assertFailsWith<WireFormatException> { WireCodec.decodeEditingChange("""{"w":${WireCodec.VERSION},"email":"me@gmail.com"}""") }
    }

    @Test
    fun pushTokensRoundTripAndAreValidated() {
        val t = WireCodec.PushToken("fcm", "dQw4w9WgXcQ:APA91bH-abc_DEF.123456789")
        assertEquals(t, WireCodec.decodePushToken(WireCodec.encodePushToken(t)))
        // An empty token removes the device's address.
        assertEquals(t.copy(token = ""), WireCodec.decodePushToken(WireCodec.encodePushToken(t.copy(token = ""))))
        assertFailsWith<WireFormatException> { WireCodec.decodePushToken(WireCodec.encodePushToken(t.copy(service = "FCM!"))) }
        assertFailsWith<WireFormatException> { WireCodec.decodePushToken(WireCodec.encodePushToken(t.copy(token = "short"))) }
        assertFailsWith<WireFormatException> { WireCodec.decodePushToken(WireCodec.encodePushToken(t.copy(token = "a".repeat(30) + "\"/"))) }
        assertFailsWith<WireFormatException> { WireCodec.decodePushToken(WireCodec.encodePushToken(t.copy(token = "a".repeat(4097)))) }
    }

    @Test
    fun newsImageRefsAreOnlyServerKeys() {
        val k = "0123456789abcdef0123456789abcdef"
        assertEquals(k, WireCodec.decodeNewsImageRef(WireCodec.encodeNewsImageRef(k)))
        for (bad in listOf("", k.uppercase(), k + "0", "../" + k.drop(3), "https://x.example/a.jpg")) {
            assertFailsWith<WireFormatException> { WireCodec.decodeNewsImageRef(WireCodec.encodeNewsImageRef(bad)) }
        }
    }

    @Test
    fun releaseDocumentsRoundTripAndAreValidated() {
        val size = WireCodec.RELEASE_CHUNK_BYTES * 2L + 10
        val r = WireCodec.AppRelease("android", 412, "0.1.412", "a".repeat(64), size, 3)
        assertEquals(r, WireCodec.decodeRelease(WireCodec.encodeRelease(r)))
        assertEquals(null, WireCodec.decodeRelease(WireCodec.encodeRelease(null)))
        assertEquals(3, WireCodec.releaseChunkCount(size))
        assertEquals(10, WireCodec.releaseChunkSize(size, 2))
        // The last chunk's base64 must be exactly as long as its bytes need ("AAAAAAAAAAAAAA==" is 10 bytes).
        val last = WireCodec.ReleaseChunk(r, 2, "AAAAAAAAAAAAAA==")
        assertEquals(last, WireCodec.decodeReleaseChunk(WireCodec.encodeReleaseChunk(last)))
        assertFailsWith<WireFormatException> { WireCodec.decodeReleaseChunk(WireCodec.encodeReleaseChunk(last.copy(dataB64 = "AAAA"))) }
        assertFailsWith<WireFormatException> { WireCodec.decodeReleaseChunk(WireCodec.encodeReleaseChunk(last.copy(index = 3))) }
        // A chunk count that doesn't match the size, a bad hash or an absurd size are refused.
        assertFailsWith<WireFormatException> { WireCodec.decodeRelease(WireCodec.encodeRelease(r.copy(chunkCount = 2))) }
        assertFailsWith<WireFormatException> { WireCodec.decodeRelease(WireCodec.encodeRelease(r.copy(sha256 = "Z".repeat(64)))) }
        assertFailsWith<WireFormatException> {
            WireCodec.decodeRelease(WireCodec.encodeRelease(r.copy(sizeBytes = WireCodec.RELEASE_MAX_BYTES + 1, chunkCount = 201)))
        }
        val ref = WireCodec.ChunkRef("android", 412, 1)
        assertEquals(ref, WireCodec.decodeChunkRef(WireCodec.encodeChunkRef(ref)))
        assertEquals("android", WireCodec.decodePlatform(WireCodec.encodePlatform("android")))
        assertFailsWith<WireFormatException> { WireCodec.decodePlatform(WireCodec.encodePlatform("../x")) }
        assertEquals(WireCodec.UploadAck(2, false), WireCodec.decodeUploadAck(WireCodec.encodeUploadAck(WireCodec.UploadAck(2, false))))
    }

    @Test
    fun apkMetadataIsReadFromWhatGradleWrites() {
        val written = """{"version":3,"artifactType":{"type":"APK","kind":"Directory"},"applicationId":"os.meka.android",
            "variantName":"debug","elements":[{"type":"SINGLE","filters":[],"attributes":[],"versionCode":412,
            "versionName":"0.1.412","outputFile":"app-debug.apk"}],"elementType":"File","minSdkVersionForDexing":31}"""
        assertEquals(WireCodec.ApkMetadata("os.meka.android", 412, "0.1.412", "app-debug.apk"), WireCodec.decodeApkMetadata(written))
        assertEquals(null, WireCodec.decodeApkMetadata("not json"))
        assertEquals(null, WireCodec.decodeApkMetadata(written.replace("\"versionCode\":412", "\"versionCode\":0")))
        assertEquals(null, WireCodec.decodeApkMetadata(written.replace("app-debug.apk", "app-debug.aab")))
    }
}
