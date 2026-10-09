package os.meka.core.facade

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import os.meka.core.sync.PullRequest
import os.meka.core.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpSyncTransportTest {
    /** Records what it was asked to sign, so the test can recompute the canonical string independently. */
    private class RecordingKey : DeviceKey {
        val signed = mutableListOf<String>()
        override val publicKeyDerBase64 = "A".repeat(124)
        override fun signBase64(message: String): String { signed += message; return "sig" + signed.size }
    }

    private val requests = mutableListOf<HttpRequestData>()
    private val json = headersOf("Content-Type", "application/json")
    private var pushStatus = HttpStatusCode.OK
    private val pushReply = WireCodec.encodePushToken(WireCodec.PushToken("fcm", ""))
    private var speakStatus = HttpStatusCode.OK
    /** Set: the server answers a spoken clip as raw MP3 (voice slice 2) with these bytes and headers. */
    private var speakRaw: Pair<ByteArray, io.ktor.http.Headers>? = null
    private val speakReply = os.meka.core.wire.SpeechCodec.encodeResponse(
        os.meka.core.wire.SpeechCodec.Response("spoken", audio = "bXAz", format = "mp3", voice = "Amy", engine = "generative"),
    )
    private var requestStatus = HttpStatusCode.OK
    private var triageStatus = HttpStatusCode.OK
    private val triageReply = os.meka.core.wire.MessageTriageCodec.encodeResponse(
        os.meka.core.wire.MessageTriageCodec.Response("answered", lane = "needs_reply", summary = "Asks about Saturday", draft = "Yes, I'll be there"),
    )
    private var digestStatus = HttpStatusCode.OK
    private val digestReply = os.meka.core.wire.GroupDigestCodec.encodeResponse(
        os.meka.core.wire.GroupDigestCodec.Response("answered", listOf(os.meka.core.wire.GroupDigestCodec.GroupAnswer("Barça lads", "Lineup debate"))),
    )
    private val requestReply = os.meka.core.wire.MessageRequestCodec.encodeResponse(
        os.meka.core.wire.MessageRequestCodec.Response("answered", listOf(os.meka.core.wire.MessageRequestCodec.Proposal("task", "Pick up dry cleaning", words = "tomorrow"))),
    )
    private fun client() = HttpClient(MockEngine { req ->
        requests += req
        when (req.url.encodedPath) {
            "/v1/sync/pull" -> respond(WireCodec.encodePullResponse(os.meka.core.sync.PullResponse(emptyList(), false)), HttpStatusCode.OK, json)
            "/v1/devices/key" -> respond(WireCodec.encodeDeviceKey("A".repeat(124)), HttpStatusCode.OK, json)
            "/v1/integrations/google/connect" -> respond("not configured", HttpStatusCode.Conflict)
            "/v1/push/token" -> respond(pushReply, pushStatus, json)
            "/v1/speech/speak" -> speakRaw?.let { (bytes, h) -> respond(bytes, speakStatus, h) } ?: respond(speakReply, speakStatus, json)
            "/v1/ai/message-request" -> respond(requestReply, requestStatus, json)
            "/v1/ai/message-triage" -> respond(triageReply, triageStatus, json)
            "/v1/ai/group-digest" -> respond(digestReply, digestStatus, json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    @Test
    fun everyRequestIsSignedOverMethodPathTimeNonceAndBodyHash() = runTest {
        val key = RecordingKey()
        val t = HttpSyncTransport(client(), "https://meka.example/", key, nowMs = { 1_790_000_000_000L }) { "s".repeat(64) }
        t.pull(PullRequest("home", "fold", 7))
        val req = requests.single()
        val body = (req.body as TextContent).text
        val nonce = req.headers[RequestSigning.HEADER_NONCE]!!
        assertEquals("1790000000000", req.headers[RequestSigning.HEADER_TIME])
        assertEquals("sig1", req.headers[RequestSigning.HEADER_SIGNATURE])
        assertEquals("Bearer " + "s".repeat(64), req.headers["Authorization"])
        assertEquals(listOf("MEKA1", "POST", "/v1/sync/pull", "1790000000000", nonce, Sha256.hex(body.encodeToByteArray())).joinToString("\n"), key.signed.single())
    }

    @Test
    fun withoutAKeyRequestsCarryOnlyTheBearer() = runTest {
        HttpSyncTransport(client(), "https://meka.example", null) { "s".repeat(64) }.pull(PullRequest("home", "fold", 0))
        assertNull(requests.single().headers[RequestSigning.HEADER_SIGNATURE])
    }

    @Test
    fun theKeyIsRegisteredOnceAndConnectReportsAnUnconfiguredProvider() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        t.prepare(); t.prepare()
        assertEquals(1, requests.count { it.url.encodedPath == "/v1/devices/key" })
        assertEquals(ConnectStart.NotSetUp, t.startConnect("google", editing = false))
        assertTrue(requests.last().url.encodedPath.endsWith("/connect"))
        assertEquals(false, WireCodec.decodeConnectRequest((requests.last().body as TextContent).text))
        // Allow editing asks for it in the (signed) body.
        t.startConnect("google", editing = true)
        assertEquals(true, WireCodec.decodeConnectRequest((requests.last().body as TextContent).text))
        assertTrue(requests.last().headers[RequestSigning.HEADER_SIGNATURE] != null)
    }

    @Test
    fun thePushAddressIsSentSignedAndAServerWithoutPushSaysSo() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        val token = "dQw4w9WgXcQ:APA91b" + "x".repeat(40)
        t.registerPushToken("fcm", token)
        val req = requests.last()
        assertEquals("/v1/push/token", req.url.encodedPath)
        assertEquals(WireCodec.PushToken("fcm", token), WireCodec.decodePushToken((req.body as TextContent).text))
        assertTrue(req.headers[RequestSigning.HEADER_SIGNATURE] != null)
        // The core reports success, and false (try again later) when the server has no push route.
        val core = MekaCore("home", "fold", os.meka.core.sync.InMemoryReplicaStore(), t, kotlin.random.Random(1))
        assertTrue(core.registerPushToken(token))
        pushStatus = HttpStatusCode.NotFound
        assertEquals(false, core.registerPushToken(token))
        assertEquals(false, MekaCore("home", "fold", os.meka.core.sync.InMemoryReplicaStore(), null, kotlin.random.Random(1)).registerPushToken(token))
    }

    @Test
    fun mekasWordsAreSentSignedToBeSpokenAndAServerWithoutAVoiceSaysOff() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        val r = t.speak("Anything else?", "Amy")
        assertEquals("bXAz", r.audio)
        val req = requests.last()
        assertEquals("/v1/speech/speak", req.url.encodedPath)
        assertEquals(os.meka.core.wire.SpeechCodec.Request("Anything else?", "Amy", binary = true), os.meka.core.wire.SpeechCodec.decodeRequest((req.body as TextContent).text))
        assertTrue(req.headers[RequestSigning.HEADER_SIGNATURE] != null)
        speakStatus = HttpStatusCode.NotFound
        assertEquals("off", t.speak("Anything else?", null).state)
        // The voices route isn't on this test server either: off, not an error.
        assertEquals("off", t.speechVoices().state)
    }

    @Test
    fun aClipThatComesBackAsRawMp3ReadsAsTheSameAnswer() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0, 0, 0)
        speakRaw = mp3 to headersOf(
            "Content-Type" to listOf("audio/mpeg"),
            os.meka.core.wire.SpeechCodec.HEADER_VOICE to listOf("Brian"),
            os.meka.core.wire.SpeechCodec.HEADER_ENGINE to listOf("neural"),
        )
        val r = t.speak("One moment…", "Brian")
        assertEquals(os.meka.core.wire.SpeechCodec.Response("spoken", kotlin.io.encoding.Base64.encode(mp3), "mp3", "Brian", "neural"), r)
        // An oversized body is refused, not played.
        speakRaw = ByteArray(os.meka.core.wire.SpeechCodec.MAX_AUDIO_BYTES + 1) to headersOf("Content-Type", "audio/mpeg")
        assertEquals("failed", t.speak("One moment…", "Brian").state)
        // Refusals stay JSON (and so does every answer from an older server).
        speakRaw = null
        assertEquals("bXAz", t.speak("Anything else?", "Amy").audio)
    }

    @Test
    fun oneMessageIsSentSignedForProposalsAndAServerWithoutTheRouteSaysOff() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        val sent = os.meka.core.wire.MessageRequestCodec.Request("Wife", "14:02", "2026-10-09", "Friday 9 October 2026 · 14:03", "", "dry cleaning tomorrow?")
        val r = t.messageRequest(sent)
        assertEquals("Pick up dry cleaning", r.proposals.single().title)
        val req = requests.last()
        assertEquals("/v1/ai/message-request", req.url.encodedPath)
        assertEquals(sent, os.meka.core.wire.MessageRequestCodec.decodeRequest((req.body as TextContent).text))
        assertTrue(req.headers[RequestSigning.HEADER_SIGNATURE] != null)
        requestStatus = HttpStatusCode.NotFound
        assertEquals("off", t.messageRequest(sent).state)
    }

    @Test
    fun oneMessageIsSentSignedForItsTriageAndAServerWithoutTheRouteSaysOff() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        val sent = os.meka.core.wire.MessageTriageCodec.Request("Tunde", "", "14:02", "2026-10-09", "Friday 9 October 2026 · 14:03", "", "are you coming Saturday?")
        val r = t.messageTriage(sent)
        assertEquals("needs_reply", r.lane)
        assertEquals("Yes, I'll be there", r.draft)
        val req = requests.last()
        assertEquals("/v1/ai/message-triage", req.url.encodedPath)
        assertEquals(sent, os.meka.core.wire.MessageTriageCodec.decodeRequest((req.body as TextContent).text))
        assertTrue(req.headers[RequestSigning.HEADER_SIGNATURE] != null)
        triageStatus = HttpStatusCode.NotFound
        assertEquals("off", t.messageTriage(sent).state)
        triageStatus = HttpStatusCode.Forbidden
        assertEquals("failed", t.messageTriage(sent).state)
    }

    @Test
    fun aDigestGoesOutSignedInOneCallAndAServerWithoutTheRouteSaysOff() = runTest {
        val t = HttpSyncTransport(client(), "https://meka.example", RecordingKey()) { "s".repeat(64) }
        val sent = os.meka.core.wire.GroupDigestCodec.Request(
            "2026-10-09", "Friday 9 October 2026 · 12:30",
            listOf(os.meka.core.wire.GroupDigestCodec.Group("Barça lads", listOf(os.meka.core.wire.GroupDigestCodec.Line("Tunde", "12:01", "lineup?")))),
        )
        val r = t.groupDigest(sent)
        assertEquals("Lineup debate", r.groups.single().gist)
        val req = requests.last()
        assertEquals("/v1/ai/group-digest", req.url.encodedPath)
        assertEquals(sent, os.meka.core.wire.GroupDigestCodec.decodeRequest((req.body as TextContent).text))
        assertTrue(req.headers[RequestSigning.HEADER_SIGNATURE] != null)
        digestStatus = HttpStatusCode.NotFound
        assertEquals("off", t.groupDigest(sent).state)
        digestStatus = HttpStatusCode.Forbidden
        assertEquals("failed", t.groupDigest(sent).state)
    }
}
