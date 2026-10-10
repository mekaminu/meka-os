package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.HeldMessages
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.Replica
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Better transcripts (call assistant polish 8d i, Meka 2026-10-09: his test message came back from Twilio as "Of the
 * origin."): Amazon Transcribe hears the recording kept in MEKA's bucket, its result is read once and deleted with its
 * job, and Twilio's words are only the fallback.
 */
class TranscriptsTest {
    private val token = "twilio-auth-token-for-tests"
    private val sid = "AC" + "0123456789abcdef".repeat(2)
    private val base = "https://meka.example"
    private val ops = InMemoryServerOpStore()
    private val devices = InMemoryDeviceRegistry().also { it.enrol("hh", "fold") }
    private var nowMs = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private var seq = 0
    private val store = InMemoryRecordingStore()
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 1)
    private val urgent = mutableListOf<String>()

    private fun result(text: String) =
        """{"jobName":"j","accountId":"1","status":"COMPLETED","results":{"transcripts":[{"transcript":${kotlinx.serialization.json.JsonPrimitive(text)}}],"items":[]}}"""

    /** A stand-in for Amazon Transcribe: answers [statuses] in turn, then writes [heard] (if any) where it was told. */
    private inner class FakeJobs(var statuses: List<String> = listOf("IN_PROGRESS", "COMPLETED"), var heard: String? = "It's the garage. Your car is ready to collect.") : TranscribeJobs {
        val calls = mutableListOf<String>()
        private var out: String? = null
        private var polls = 0
        override fun start(job: String, mediaUri: String, outputBucket: String, outputKey: String) {
            calls += "start $job $mediaUri -> $outputBucket/$outputKey"
            out = outputKey
        }
        override fun status(job: String): String {
            calls += "status $job"
            val s = statuses[minOf(polls++, statuses.size - 1)]
            if (s == "COMPLETED") heard?.let { store.put(out!!, result(it).encodeToByteArray()) }
            return s
        }
        override fun delete(job: String) { calls += "delete $job" }
    }

    private val jobs = FakeJobs()
    private val sleeps = mutableListOf<Long>()
    private fun transcriber(j: TranscribeJobs = jobs) =
        BatchTranscriber(j, "meka-blobs", store, now = { nowMs }, sleep = { sleeps += it; nowMs += it }, newJobName = { "meka-voice-1" })

    private val twilio = TwilioVoice(
        authToken = { token }, accountSid = { sid },
        delete = { _, _ -> 204 },
        get = { _, _ -> HttpFetched(200, mp3) },
    )

    private fun assistant(t: Transcriber?) = CallAssistant(
        ops, FieldReader.scanning(ops), household = { devices.soleHousehold() }, now = { nowMs },
        onUrgent = { urgent += it }, recordings = store, transcriber = t,
    )

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

    private suspend fun ApplicationTestBuilder.message(call: String, twilioHeard: String? = "Of the origin.") {
        hook(VoiceStep.RECORDED, "CallSid" to call, "From" to "07700 900123", "RecordingDuration" to "12", "RecordingSid" to rec(1))
        hook(
            VoiceStep.TRANSCRIBED, "CallSid" to call, "RecordingSid" to rec(1),
            "TranscriptionStatus" to if (twilioHeard != null) "completed" else "failed", "TranscriptionText" to twilioHeard.orEmpty(),
        )
    }

    private fun fold(): HeldMessages {
        var n = 0
        val replica = Replica("hh", "fold", HlcClock("fold", { nowMs }), InMemoryReplicaStore(), MekaSchema) { "f${n++}" }
        replica.applyRemoteBatch(ops.after("hh", 0, 1000).map { it.op })
        return HeldMessages(replica) { nowMs }
    }

    @Test
    fun transcribesWordsReplaceTwiliosAndTheResultAndJobAreDeleted() = testApplication {
        application { mekaSync(ops, devices, voice = VoiceRoutes(assistant(transcriber()), listOf(twilio), base), background = { it() }) }
        switchOn()
        message("CA1")
        val id = CallAssistantRules.heldId("twilio", "CA1")

        assertEquals(
            listOf(
                "start meka-voice-1 s3://meka-blobs/voice/hh/$id.mp3 -> meka-blobs/voice/hh/$id.transcript.json",
                "status meka-voice-1", "status meka-voice-1", "delete meka-voice-1",
            ),
            jobs.calls,
        )
        assertEquals(listOf(Transcripts.POLL_MS), sleeps)
        // Only the recording is left: the result was read once and deleted.
        assertEquals(setOf("voice/hh/$id.mp3"), store.objects.keys)
        assertEquals("Voice message · “It's the garage. Your car is ready to collect.”", fold().items().single().displayLine)
        // One text, Transcribe's: Twilio's guess was never written.
        assertEquals(1, ops.after("hh", 0, 1000).count { it.op.field == HeldMessageFields.TEXT })
    }

    @Test
    fun theWordsWaitForTranscribeSoTheAppsShowTranscribingNotTwiliosGuess() {
        val a = assistant(transcriber())
        switchOn()
        a.handle("twilio", VoiceEvent.Recorded("CA1", "07700 900123", 12))
        a.handle("twilio", VoiceEvent.Transcribed("CA1", "Of the origin.", rec(1)))
        val item = fold().items().single()
        assertTrue(item.transcribing)
        assertEquals("Voice message · Transcribing…", item.displayLine)
        // Without Transcribe (or without a recording to transcribe) Twilio's words are written straight away, as before.
        val plain = assistant(null)
        plain.handle("twilio", VoiceEvent.Recorded("CA2", "07700 900222", 9))
        plain.handle("twilio", VoiceEvent.Transcribed("CA2", "Call me back.", rec(2)))
        assertTrue(fold().items().any { it.text == "Call me back." })
    }

    @Test
    fun whenTranscribeFailsOrHearsNothingTwiliosWordsStand() = testApplication {
        jobs.statuses = listOf("FAILED")
        application { mekaSync(ops, devices, voice = VoiceRoutes(assistant(transcriber()), listOf(twilio), base), background = { it() }) }
        switchOn()
        message("CA1")
        assertEquals("Voice message · “Of the origin.”", fold().items().single().displayLine)
        assertTrue("delete meka-voice-1" in jobs.calls)
        assertEquals(1, store.objects.size) // just the recording

        // Heard nothing, and Twilio failed too: "no words came through", not "Transcribing…" for ever.
        jobs.statuses = listOf("COMPLETED")
        jobs.heard = "  "
        message("CA2", twilioHeard = null)
        assertTrue(fold().items().single { it.text == null }.noTranscript)
    }

    @Test
    fun aSlowJobStopsWaitingAtTheDeadlineAndIsStillCleanedUp() {
        val slow = FakeJobs(statuses = listOf("IN_PROGRESS"))
        val t = transcriber(slow)
        assertNull(t.transcribe("voice/hh/h1.mp3"))
        assertEquals(Transcripts.DEADLINE_MS / Transcripts.POLL_MS, sleeps.size.toLong())
        assertEquals("delete meka-voice-1", slow.calls.last())

        // A job that couldn't start isn't deleted (there is none), and nothing is left behind.
        val refused = object : TranscribeJobs {
            override fun start(job: String, mediaUri: String, outputBucket: String, outputKey: String) = throw IllegalStateException("AccessDenied")
            override fun status(job: String) = error("not started")
            override fun delete(job: String) = error("not started")
        }
        assertFailsWith<IllegalStateException> { transcriber(refused).transcribe("voice/hh/h1.mp3") }
        assertTrue(store.objects.isEmpty())
    }

    @Test
    fun aMessageDismissedWhileItWasBeingTranscribedGetsNoWords() = testApplication {
        val a = assistant(Transcriber { key ->
            // Done on the Fold while Transcribe was working.
            ops.append(
                Op("d1", "hh", EntityTypes.HELD_MESSAGE, CallAssistantRules.heldId("twilio", "CA1"), HeldMessageFields.CLEARED, FieldValue.Bool(true), Hlc(nowMs + 1, 0, "fold"), emptyList(), "fold"),
            )
            "words for $key"
        })
        application { mekaSync(ops, devices, voice = VoiceRoutes(a, listOf(twilio), base), background = { it() }) }
        switchOn()
        message("CA1")
        assertTrue(ops.after("hh", 0, 1000).none { it.op.field == HeldMessageFields.TEXT || it.op.field == HeldMessageFields.NO_TRANSCRIPT })
    }

    @Test
    fun anUrgentMessageHeardByTranscribeWakesTheDevicesAtOnce() = testApplication {
        jobs.heard = "It's an emergency, please call me back."
        application { mekaSync(ops, devices, voice = VoiceRoutes(assistant(transcriber()), listOf(twilio), base), background = { it() }) }
        switchOn()
        message("CA1", twilioHeard = "Of the origin.")
        assertEquals(listOf("hh"), urgent)
        assertTrue(fold().items().single().isUrgent)
    }

    @Test
    fun resultsAreReadFromTranscribesJsonAndKeptBesideTheRecording() {
        assertEquals("Hello there. Call me.", Transcripts.text(result("Hello   there.\nCall me. ")))
        assertNull(Transcripts.text(result("")))
        assertNull(Transcripts.text("""{"results":{}}"""))
        assertNull(Transcripts.text("not json"))
        assertEquals("voice/hh/h1.transcript.json", Transcripts.outputKey("voice/hh/h1.mp3"))
        assertFailsWith<IllegalArgumentException> { Transcripts.outputKey("other/h1.mp3") }
    }
}
