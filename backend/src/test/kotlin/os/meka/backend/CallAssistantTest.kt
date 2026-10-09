package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.CallAssistantScript
import os.meka.core.domain.CaptureKind
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.HeldMessages
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.MekaVoiceFields
import os.meka.core.domain.MekaVoiceStore
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import os.meka.core.wire.SpeechCodec
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The call assistant's voice side (build plan M1, call assistant slice 2): Twilio webhooks → the after-work summary. */
class CallAssistantTest {
    private val token = "twilio-auth-token-for-tests"
    private val base = "https://meka.example"
    private val ops = InMemoryServerOpStore()
    private val devices = InMemoryDeviceRegistry().also { it.enrol("hh", "fold") }
    private var nowMs = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private val woken = mutableListOf<String>()
    private val urgent = mutableListOf<String>()
    private var seq = 0
    private val assistant = CallAssistant(
        ops, FieldReader.scanning(ops), household = { devices.soleHousehold() }, now = { nowMs },
        onWritten = { woken += it }, onUrgent = { urgent += it },
    )
    private val voice = VoiceRoutes(assistant, listOf(TwilioVoice(authToken = { token })), base)

    private fun switch(on: Boolean) {
        ops.append(
            Op(
                "w${seq++}", "hh", EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.CALL_ASSISTANT, FieldValue.Bool(on),
                Hlc(nowMs + seq, 0, "fold"), emptyList(), "fold",
            ),
        )
    }

    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { (k, v) -> URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8) }

    private suspend fun ApplicationTestBuilder.hook(step: String, vararg p: Pair<String, String>, sign: String = token, signedUrl: String? = null) =
        client.post("/v1/voice/twilio/$step") {
            contentType(ContentType.Application.FormUrlEncoded)
            header("X-Twilio-Signature", TwilioVoice.signature(sign, signedUrl ?: "$base/v1/voice/twilio/$step", p.toMap()))
            setBody(form(*p))
        }

    /** The Fold, after pulling everything the server holds. */
    private fun fold(): HeldMessages {
        var n = 0
        val replica = Replica("hh", "fold", HlcClock("fold", { nowMs }), InMemoryReplicaStore(), MekaSchema) { "f${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 1000).map { it.op })
        return HeldMessages(replica) { nowMs }
    }

    private fun heldOps() = ops.after("hh", 0, 1000).map { it.op }.filter { it.entityType == EntityTypes.HELD_MESSAGE }

    @Test
    fun aCallerLeavesAnUrgentMessageThatReachesTheSummaryAndWakesTheFoldAtOnce() = testApplication {
        application { mekaSync(ops, devices, voice = voice) }
        switch(true)
        val call = arrayOf("CallSid" to "CA100", "From" to "+447700900123", "To" to "+441234567890")

        val greet = hook(VoiceStep.INCOMING, *call)
        assertEquals(HttpStatusCode.OK, greet.status)
        val twiml = greet.bodyAsText()
        assertTrue(twiml.startsWith("<?xml"))
        assertTrue("Hi, you&apos;ve reached Meka&apos;s automated assistant." in twiml, twiml)
        assertTrue("""<Record action="$base/v1/voice/twilio/recorded"""" in twiml, twiml)
        assertTrue("""transcribeCallback="$base/v1/voice/twilio/transcribed"""" in twiml, twiml)
        assertTrue("""maxLength="120"""" in twiml)
        assertTrue(heldOps().isEmpty())

        val asked = hook(VoiceStep.RECORDED, *call, "RecordingDuration" to "14", "RecordingSid" to "RE1").bodyAsText()
        assertTrue("""<Gather input="dtmf speech"""" in asked, asked)
        assertTrue("""action="$base/v1/voice/twilio/answered"""" in asked)
        assertTrue("Is it urgent?" in asked)
        assertEquals(listOf("hh"), woken)

        val bye = hook(VoiceStep.ANSWERED, *call, "Digits" to "1").bodyAsText()
        assertTrue("I&apos;ll let Meka know straight away" in bye, bye)
        assertTrue("<Hangup/>" in bye)
        assertEquals(listOf("hh"), urgent)

        val item = fold().items().single()
        assertEquals(CaptureKind.VOICE_MESSAGE, item.kind)
        assertEquals("+447700900123", item.personName)
        assertTrue(item.urgent)
        assertEquals("Voice message", item.displayLine)
        assertEquals(nowMs, item.atMs)
        assertEquals(CallAssistantRules.heldId("twilio", "CA100"), item.id)
        assertTrue(heldOps().all { it.deviceId == "server" })

        // The transcript arrives later and is shown in the summary on both apps.
        hook(
            VoiceStep.TRANSCRIBED, *call, "RecordingSid" to "RE1", "TranscriptionStatus" to "completed",
            "TranscriptionText" to "Hi Meka, it's the garage, your car is ready.",
        ).also { assertEquals(HttpStatusCode.OK, it.status); assertTrue("<Response></Response>" in it.bodyAsText()) }
        assertEquals("Voice message · “Hi Meka, it's the garage, your car is ready.”", fold().items().single().displayLine)
        assertEquals(listOf("hh", "hh"), woken)
        assertEquals(listOf("hh"), urgent)
    }

    @Test
    fun retriedWebhooksWriteNothingMore() = testApplication {
        application { mekaSync(ops, devices, voice = voice) }
        switch(true)
        val call = arrayOf("CallSid" to "CA200", "From" to "07700 900222")
        hook(VoiceStep.RECORDED, *call, "RecordingDuration" to "5")
        hook(VoiceStep.ANSWERED, *call, "SpeechResult" to "Yes it is.")
        val written = heldOps().size
        hook(VoiceStep.RECORDED, *call, "RecordingDuration" to "5")
        hook(VoiceStep.ANSWERED, *call, "Digits" to "1")
        assertEquals(written, heldOps().size)
        assertEquals(listOf("hh"), woken)
        assertEquals(listOf("hh"), urgent)
        assertEquals(1, fold().items().size)
    }

    @Test
    fun notUrgentNoAnswerAndAnEmergencyInTheWords() = testApplication {
        application { mekaSync(ops, devices, voice = voice) }
        switch(true)
        hook(VoiceStep.RECORDED, "CallSid" to "CA1", "From" to "07700 900111", "RecordingDuration" to "8")
        assertTrue("Meka will get your message after work" in hook(VoiceStep.ANSWERED, "CallSid" to "CA1", "Digits" to "2").bodyAsText())
        hook(VoiceStep.RECORDED, "CallSid" to "CA2", "From" to "07700 900222", "RecordingDuration" to "8")
        hook(VoiceStep.ANSWERED, "CallSid" to "CA2") // said nothing
        assertTrue(urgent.isEmpty())
        assertFalse(fold().items().any { it.urgent })
        // Its own words: the transcript says "emergency", so the Fold is woken at high priority after all.
        hook(VoiceStep.TRANSCRIBED, "CallSid" to "CA2", "TranscriptionStatus" to "completed", "TranscriptionText" to "This is an emergency, call me")
        assertEquals(listOf("hh"), urgent)
        assertTrue(fold().items().first { it.personName == "07700 900222" }.isUrgent)
        // A failed transcription writes nothing.
        val before = heldOps().size
        hook(VoiceStep.TRANSCRIBED, "CallSid" to "CA1", "TranscriptionStatus" to "failed", "TranscriptionText" to "")
        assertEquals(before, heldOps().size)
    }

    @Test
    fun aTranscriptAfterDoneDoesNotBringTheWordsBack() = testApplication {
        application { mekaSync(ops, devices, voice = voice) }
        switch(true)
        hook(VoiceStep.RECORDED, "CallSid" to "CA9", "From" to "07700 900999", "RecordingDuration" to "3")
        val id = CallAssistantRules.heldId("twilio", "CA9")
        ops.append(Op("c1", "hh", EntityTypes.HELD_MESSAGE, id, HeldMessageFields.CLEARED, FieldValue.Bool(true), Hlc(nowMs + 99, 0, "mac"), emptyList(), "mac"))
        hook(VoiceStep.TRANSCRIBED, "CallSid" to "CA9", "TranscriptionStatus" to "completed", "TranscriptionText" to "private words")
        assertNull(heldOps().firstOrNull { it.field == HeldMessageFields.TEXT })
        assertTrue(fold().items().isEmpty())
    }

    @Test
    fun switchedOffNoHouseholdOrNothingSaidTurnsTheCallerAwayAndWritesNothing() = testApplication {
        application { mekaSync(ops, devices, voice = voice) }
        // Never switched on: busy.
        assertEquals("""<?xml version="1.0" encoding="UTF-8"?><Response><Reject reason="busy"/></Response>""", hook(VoiceStep.INCOMING, "CallSid" to "CA1").bodyAsText())
        switch(true)
        assertTrue("<Record" in hook(VoiceStep.INCOMING, "CallSid" to "CA2").bodyAsText())
        switch(false)
        assertTrue("<Reject" in hook(VoiceStep.INCOMING, "CallSid" to "CA3").bodyAsText())
        switch(true)
        // A recording with nothing in it: goodbye, nothing held.
        val empty = hook(VoiceStep.RECORDED, "CallSid" to "CA4", "RecordingDuration" to "0").bodyAsText()
        assertTrue("I didn&apos;t hear a message" in empty, empty)
        assertTrue(heldOps().isEmpty())
        assertTrue(woken.isEmpty())
        // A second household: the server can't tell whose call it is.
        devices.enrol("hh2", "fold")
        assertTrue("<Reject" in hook(VoiceStep.INCOMING, "CallSid" to "CA5").bodyAsText())
    }

    @Test
    fun onlyTheSignedPhoneServiceGetsIn() = testApplication {
        application { mekaSync(ops, devices, voice = voice) }
        switch(true)
        val call = arrayOf("CallSid" to "CA1", "From" to "07700 900123", "RecordingDuration" to "9")
        assertEquals(HttpStatusCode.Forbidden, hook(VoiceStep.RECORDED, *call, sign = "someone-elses-token").status)
        assertEquals(HttpStatusCode.Forbidden, hook(VoiceStep.RECORDED, *call, signedUrl = "$base/v1/voice/twilio/incoming").status)
        // Tampered form: the signature was made over different values.
        val tampered = client.post("/v1/voice/twilio/recorded") {
            contentType(ContentType.Application.FormUrlEncoded)
            header("X-Twilio-Signature", TwilioVoice.signature(token, "$base/v1/voice/twilio/recorded", call.toMap()))
            setBody(form("CallSid" to "CA1", "From" to "07700 900666", "RecordingDuration" to "9"))
        }
        assertEquals(HttpStatusCode.Forbidden, tampered.status)
        val unsigned = client.post("/v1/voice/twilio/recorded") { contentType(ContentType.Application.FormUrlEncoded); setBody(form(*call)) }
        assertEquals(HttpStatusCode.Forbidden, unsigned.status)
        val publisher = client.post("/v1/voice/twilio/recorded") { header("Authorization", "${ReleasePublisher.AUTH_SCHEME} ${ReleasePublisher.ID}"); setBody(form(*call)) }
        assertEquals(HttpStatusCode.Forbidden, publisher.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/voice/acme/recorded") { setBody(form(*call)) }.status)
        assertEquals(HttpStatusCode.NotFound, hook("hangup", *call).status)
        assertTrue(heldOps().isEmpty())
    }

    @Test
    fun withNoAuthTokenYetEveryWebhookIsRefused() = testApplication {
        val unset = VoiceRoutes(assistant, listOf(TwilioVoice(authToken = { null })), base)
        application { mekaSync(ops, devices, voice = unset) }
        switch(true)
        assertEquals(HttpStatusCode.Forbidden, hook(VoiceStep.INCOMING, "CallSid" to "CA1").status)
    }

    @Test
    fun aTranscribedRecordingIsDeletedFromTwilio() {
        val deleted = mutableListOf<Pair<String, Map<String, String>>>()
        val sid = "AC" + "0".repeat(32)
        val rec = "RE" + "a".repeat(32)
        val twilio = TwilioVoice(authToken = { token }, accountSid = { sid }, delete = { url, h -> deleted += url to h; 204 })
        assertEquals(VoiceEvent.Transcribed("CA1", "hello", rec), twilio.parse(VoiceStep.TRANSCRIBED, mapOf("CallSid" to "CA1", "RecordingSid" to rec, "TranscriptionStatus" to "completed", "TranscriptionText" to "hello")))
        assertTrue(twilio.deleteRecording(rec))
        assertEquals("https://api.twilio.com/2010-04-01/Accounts/$sid/Recordings/$rec.json", deleted.single().first)
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("$sid:$token".toByteArray()), deleted.single().second["Authorization"])
        // Nothing odd is ever put into the URL, and nothing is tried without the account's SID.
        assertFalse(twilio.deleteRecording("RE../../Calls"))
        assertFalse(TwilioVoice(authToken = { token }, delete = { _, _ -> 204 }).deleteRecording(rec))
        assertEquals(1, deleted.size)
    }

    @Test
    fun formsSignaturesAndTheLatestField() {
        assertEquals(mapOf("From" to "+44 7700", "SpeechResult" to "yes please", "Digits" to ""), parseForm("From=%2B44+7700&SpeechResult=yes%20please&Digits="))
        val sig = TwilioVoice.signature(token, "$base/v1/voice/twilio/incoming", mapOf("b" to "2", "a" to "1"))
        assertEquals(sig, TwilioVoice.signature(token, "$base/v1/voice/twilio/incoming", linkedMapOf("a" to "1", "b" to "2")))
        assertFalse(sig == TwilioVoice.signature(token, "$base/v1/voice/twilio/incoming", mapOf("a" to "1", "b" to "3")))
        // The latest switch by HLC wins, whatever order the ops arrived in.
        ops.append(Op("x2", "hh", EntityTypes.CONTEXT_MODE, "work", WorkFields.CALL_ASSISTANT, FieldValue.Bool(false), Hlc(20, 0, "mac"), emptyList(), "mac"))
        ops.append(Op("x1", "hh", EntityTypes.CONTEXT_MODE, "work", WorkFields.CALL_ASSISTANT, FieldValue.Bool(true), Hlc(10, 0, "fold"), emptyList(), "fold"))
        assertEquals(FieldValue.Bool(false), FieldReader.scanning(ops).latest("hh", EntityTypes.CONTEXT_MODE, "work", WorkFields.CALL_ASSISTANT))
        assertNull(FieldReader.scanning(ops).latest("hh", EntityTypes.CONTEXT_MODE, "work", WorkFields.SCHEDULE))
        // Urgent wake-ups go at high priority; the content is still only "sync".
        val m = FcmSender.message("tok", urgent = true)["message"]!!.jsonObject
        assertEquals("high", m["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)
        assertEquals("sync", m["data"]!!.jsonObject["t"]!!.jsonPrimitive.content)
        assertEquals("normal", FcmSender.message("tok")["message"]!!.jsonObject["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)
        assertTrue(CallAssistantScript.GREETING.startsWith("Hi, you've reached Meka's automated assistant."))
    }

    @Test
    fun callersHearMekasVoiceTheOneMekaChoseForTalk() = testApplication {
        // MEKA's server offers generative Amy and neural Emma; the Fold chose Emma (context_mode/voice, synced).
        val offered = listOf(SpeechCodec.Voice("Amy", "Female", "generative"), SpeechCodec.Voice("Emma", "Female", "neural"))
        val withVoice = CallAssistant(ops, FieldReader.scanning(ops), household = { devices.soleHousehold() }, now = { nowMs }, speechVoices = { offered })
        application { mekaSync(ops, devices, voice = VoiceRoutes(withVoice, listOf(TwilioVoice(authToken = { token })), base)) }
        switch(true)
        fun choose(name: String?) = ops.append(
            Op(
                "v${seq++}", "hh", EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID, MekaVoiceFields.NAME, name.fv(),
                Hlc(nowMs + 100 + seq, 0, "fold"), emptyList(), "fold",
            ),
        )
        val call = arrayOf("CallSid" to "CA700", "From" to "+447700900123")

        // Nothing chosen: the server's default, on its best engine.
        assertTrue("""<Say voice="Polly.Amy-Generative" language="en-GB">""" in hook(VoiceStep.INCOMING, *call).bodyAsText())
        choose("Emma")
        val greet = hook(VoiceStep.INCOMING, *call).bodyAsText()
        assertTrue("""<Say voice="Polly.Emma-Neural" language="en-GB">""" in greet, greet)
        assertFalse("Polly.Amy" in greet)
        // Every line of the call is in the same voice, through to goodbye.
        assertTrue("Polly.Emma-Neural" in hook(VoiceStep.RECORDED, *call, "RecordingDuration" to "9").bodyAsText())
        assertTrue("Polly.Emma-Neural" in hook(VoiceStep.ANSWERED, *call, "Digits" to "2").bodyAsText())
        // The device's own voice can't reach a caller: the call is in MEKA's default voice.
        choose("device")
        assertTrue("Polly.Amy-Generative" in hook(VoiceStep.INCOMING, *call).bodyAsText())
    }

    @Test
    fun theCallVoiceFallsBackWithoutFailingTheCall() {
        val offered = listOf(SpeechCodec.Voice("Amy", "Female", "generative"), SpeechCodec.Voice("Brian", "Male", "neural"))
        assertEquals(CallVoice("Brian", "neural"), CallVoice.choose("Brian", offered))
        assertEquals(CallVoice("Amy", "generative"), CallVoice.choose(null, offered))
        assertEquals(CallVoice("Amy", "generative"), CallVoice.choose("Zed", offered)) // not offered here: the default
        assertEquals(CallVoice("Amy", "generative"), CallVoice.choose("not a voice!", offered))
        // Polly off or unreachable: the chosen name on the neural engine, else Amy.
        assertEquals(CallVoice("Arthur", "neural"), CallVoice.choose("Arthur", emptyList()))
        assertEquals(CallVoice.DEFAULT, CallVoice.choose("device", emptyList()))
        assertEquals(CallVoice.DEFAULT, CallVoice.choose(null, emptyList()))
        // Twilio's names: generative where Twilio has it, else neural; a voice Twilio lacks speaks as Amy, never fails.
        assertEquals("Polly.Amy-Generative", TwilioVoice.voiceName(CallVoice("Amy", "generative")))
        assertEquals("Polly.Amy-Neural", TwilioVoice.voiceName(CallVoice("Amy", "neural")))
        assertEquals("Polly.Arthur-Neural", TwilioVoice.voiceName(CallVoice("Arthur", "generative")))
        assertEquals("Polly.Amy-Neural", TwilioVoice.voiceName(CallVoice("Zed", "neural")))
        // Webhooks before a household exists still answer (busy), in no voice at all.
        val none = CallAssistant(ops, FieldReader.scanning(ops), household = { null }, speechVoices = { error("not asked") })
        assertEquals(CallVoice.DEFAULT, none.voice())
    }
}
