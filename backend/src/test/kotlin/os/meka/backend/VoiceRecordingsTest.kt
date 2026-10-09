package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.HeldMessages
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.VoiceRecordingRules
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PushRequest
import os.meka.core.sync.Replica
import os.meka.core.wire.VoiceMessageCodec
import os.meka.core.wire.WireCodec
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Callers' recordings (call assistant polish 8c, Meka 2026-10-09: "can I not listen to it?"): kept in MEKA's own
 * bucket once transcribed, fetched before Twilio's copy is deleted, played only by the household's keyed devices, and
 * deleted at once on Done.
 */
class VoiceRecordingsTest {
    private val token = "twilio-auth-token-for-tests"
    private val sid = "AC" + "0123456789abcdef".repeat(2)
    private val base = "https://meka.example"
    private val ops = InMemoryServerOpStore()
    private val devices = InMemoryDeviceRegistry()
    private val foldSecret = devices.enrol("hh", "fold")
    private val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
    private var nowMs = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private var seq = 0
    private val store = InMemoryRecordingStore()
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 1)

    /** What the server asked Twilio for, in order: "GET url (auth?)" and "DELETE url". */
    private val twilioCalls = mutableListOf<String>()
    private var media: () -> HttpFetched = { HttpFetched(200, mp3) }
    private val twilio = TwilioVoice(
        authToken = { token }, accountSid = { sid },
        delete = { url, _ -> twilioCalls += "DELETE $url"; 204 },
        get = { url, headers ->
            twilioCalls += "GET $url" + if (headers.containsKey("Authorization")) " (auth)" else ""
            if (url.startsWith("https://api.twilio.com/")) media() else HttpFetched(200, mp3)
        },
    )
    private val assistant = CallAssistant(
        ops, FieldReader.scanning(ops), household = { devices.soleHousehold() }, now = { nowMs }, recordings = store,
    )
    private val voice = VoiceRoutes(assistant, listOf(twilio), base)

    private fun rec(n: Int) = "RE" + n.toString().padStart(32, '0')

    private fun switchOn() = ops.append(
        Op("w${seq++}", "hh", EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.CALL_ASSISTANT, FieldValue.Bool(true), Hlc(nowMs, seq, "fold"), emptyList(), "fold"),
    )

    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8) }

    private suspend fun ApplicationTestBuilder.hook(step: String, vararg p: Pair<String, String>) =
        client.post("/v1/voice/twilio/$step") {
            contentType(ContentType.Application.FormUrlEncoded)
            header("X-Twilio-Signature", TwilioVoice.signature(token, "$base/v1/voice/twilio/$step", p.toMap()))
            setBody(form(*p))
        }

    /** A message left on [call]: recorded, then transcribed with recording [recording]. */
    private suspend fun ApplicationTestBuilder.message(call: String, recording: String) {
        hook(VoiceStep.RECORDED, "CallSid" to call, "From" to "07700 900123", "RecordingDuration" to "12", "RecordingSid" to recording)
        hook(
            VoiceStep.TRANSCRIBED, "CallSid" to call, "RecordingSid" to recording,
            "TranscriptionStatus" to "completed", "TranscriptionText" to "It's the garage, your car is ready.",
        )
    }

    private suspend fun ApplicationTestBuilder.play(id: String, secret: String = foldSecret, key: TestDeviceKey = foldKey) =
        client.post(VoiceMessageCodec.PATH) { with(key) { signed(secret, VoiceMessageCodec.PATH, VoiceMessageCodec.encodeRequest(id)) } }

    private fun fold(): HeldMessages {
        var n = 0
        val replica = Replica("hh", "fold", HlcClock("fold", { nowMs }), InMemoryReplicaStore(), MekaSchema) { "f${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 1000).map { it.op })
        return HeldMessages(replica) { nowMs }
    }

    @Test
    fun aRecordingIsKeptBeforeTwilioDeletesItsCopyAndOnlyTheHouseholdsKeyedDevicesCanPlayIt() = testApplication {
        // Twilio's media answers with a short-lived signed address: followed once, without the account's credentials.
        media = { HttpFetched(302, ByteArray(0), "https://media.twiliocdn.example/rec.mp3?sig=1") }
        application { mekaSync(ops, devices, voice = voice, background = { it() }) }
        switchOn()
        message("CA1", rec(1))
        val id = CallAssistantRules.heldId("twilio", "CA1")

        assertEquals(
            listOf(
                "GET https://api.twilio.com/2010-04-01/Accounts/$sid/Recordings/${rec(1)}.mp3 (auth)",
                "GET https://media.twiliocdn.example/rec.mp3?sig=1",
                "DELETE https://api.twilio.com/2010-04-01/Accounts/$sid/Recordings/${rec(1)}.json",
            ),
            twilioCalls,
        )
        assertContentEquals(mp3, store.objects["voice/hh/$id.mp3"])
        assertEquals(setOf("voice/hh/$id.mp3"), store.objects.keys)
        val item = fold().items().single()
        assertTrue(item.hasAudio)
        assertEquals("Voice message · “It's the garage, your car is ready.”", item.displayLine)

        // The Fold plays it: raw MP3, not cached anywhere.
        val r = play(id)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(ContentType.parse(VoiceMessageCodec.AUDIO_TYPE), r.contentType()?.withoutParameters())
        assertEquals("no-store", r.headers["Cache-Control"])
        assertContentEquals(mp3, r.readRawBytes())

        // Unsigned, a device without its key, a made-up id, a malformed one: refused or nothing there.
        assertEquals(HttpStatusCode.Unauthorized, client.post(VoiceMessageCodec.PATH) { header("Authorization", "Bearer $foldSecret"); setBody(VoiceMessageCodec.encodeRequest(id)) }.status)
        val macSecret = devices.enrol("hh", "mac")
        assertEquals(HttpStatusCode.Forbidden, client.post(VoiceMessageCodec.PATH) { header("Authorization", "Bearer $macSecret"); setBody(VoiceMessageCodec.encodeRequest(id)) }.status)
        assertEquals(HttpStatusCode.NotFound, play("h0000000000000000").status)
        assertEquals(HttpStatusCode.BadRequest, client.post(VoiceMessageCodec.PATH) { with(foldKey) { signed(foldSecret, VoiceMessageCodec.PATH, """{"w":${WireCodec.VERSION},"id":"../x"}""") } }.status)
        // Another household's device can't reach it, even knowing the id.
        val strangerSecret = devices.enrol("hh2", "intruder")
        val strangerKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh2", "intruder"), it.publicB64) }
        assertEquals(HttpStatusCode.NotFound, play(id, strangerSecret, strangerKey).status)

        // After 30 days it isn't offered (the bucket's lifecycle rule removes it).
        nowMs += VoiceRecordingRules.KEEP_MS
        assertEquals(HttpStatusCode.NotFound, play(id).status)
    }

    @Test
    fun doneDeletesTheRecordingAtOnceAndAMessageDismissedBeforeItsTranscriptKeepsNone() = testApplication {
        application { mekaSync(ops, devices, voice = voice, background = { it() }) }
        switchOn()
        message("CA1", rec(1))
        val id = CallAssistantRules.heldId("twilio", "CA1")
        assertEquals(HttpStatusCode.OK, play(id).status)

        // Done on the Fold: its push carries cleared = true; the recording goes there and then.
        val done = Op("d1", "hh", EntityTypes.HELD_MESSAGE, id, HeldMessageFields.CLEARED, FieldValue.Bool(true), Hlc(nowMs + 1, 0, "fold"), emptyList(), "fold")
        val body = WireCodec.encodePushRequest(PushRequest("hh", "fold", listOf(done)))
        assertEquals(HttpStatusCode.OK, client.post("/v1/sync/push") { with(foldKey) { signed(foldSecret, "/v1/sync/push", body) } }.status)
        assertTrue(store.objects.isEmpty())
        assertEquals(HttpStatusCode.NotFound, play(id).status)

        // A message dismissed before Twilio's transcript came: nothing fetched or kept, Twilio's copy still deleted.
        twilioCalls.clear()
        hook(VoiceStep.RECORDED, "CallSid" to "CA2", "From" to "07700 900222", "RecordingDuration" to "8", "RecordingSid" to rec(2))
        val id2 = CallAssistantRules.heldId("twilio", "CA2")
        val done2 = WireCodec.encodePushRequest(
            PushRequest("hh", "fold", listOf(Op("d2", "hh", EntityTypes.HELD_MESSAGE, id2, HeldMessageFields.CLEARED, FieldValue.Bool(true), Hlc(nowMs + 2, 0, "fold"), emptyList(), "fold"))),
        )
        client.post("/v1/sync/push") { with(foldKey) { signed(foldSecret, "/v1/sync/push", done2) } }
        hook(VoiceStep.TRANSCRIBED, "CallSid" to "CA2", "RecordingSid" to rec(2), "TranscriptionStatus" to "completed", "TranscriptionText" to "hi")
        assertEquals(listOf("DELETE https://api.twilio.com/2010-04-01/Accounts/$sid/Recordings/${rec(2)}.json"), twilioCalls)
        assertTrue(store.objects.isEmpty())
    }

    @Test
    fun whenTwilioWontHandItOverNothingIsKeptAndTheMessageStaysAsText() = testApplication {
        media = { HttpFetched(404, ByteArray(0)) }
        application { mekaSync(ops, devices, voice = voice, background = { it() }) }
        switchOn()
        message("CA1", rec(1))
        assertTrue(store.objects.isEmpty())
        val item = fold().items().single()
        assertFalse(item.hasAudio)
        assertTrue(ops.after("hh", 0, 1000).none { it.op.field == HeldMessageFields.AUDIO })
        // Twilio's copy is deleted all the same: the words live on in the summary.
        assertTrue(twilioCalls.last().startsWith("DELETE "))
        assertEquals(HttpStatusCode.NotFound, play(CallAssistantRules.heldId("twilio", "CA1")).status)
    }

    @Test
    fun fetchingFromTwilioIsBoundedAndNeedsTheAccount() {
        assertContentEquals(mp3, twilio.fetchRecording(rec(1)))
        assertNull(twilio.fetchRecording("RE../../Calls"))
        assertNull(TwilioVoice(authToken = { token }, get = { _, _ -> HttpFetched(200, mp3) }).fetchRecording(rec(1)))
        val tooBig = TwilioVoice(authToken = { token }, accountSid = { sid }, get = { _, _ -> HttpFetched(200, ByteArray(VoiceRecordingRules.MAX_BYTES + 1)) })
        assertNull(tooBig.fetchRecording(rec(1)))
        // A redirect to anything but https is not followed.
        val plain = TwilioVoice(authToken = { token }, accountSid = { sid }, get = { _, _ -> HttpFetched(302, ByteArray(0), "http://media.example/x") })
        assertNull(plain.fetchRecording(rec(1)))
    }
}
