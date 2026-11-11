package os.meka.backend

import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.CallRecordingRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.HeldMessageFields
import os.meka.core.domain.LiveCallRules
import os.meka.core.domain.ObiCallRules
import os.meka.core.domain.VoiceRecordingRules
import os.meka.core.domain.WorkFields
import os.meka.core.domain.WorkMode
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recordings with both voices (Meka 2026-11-08 21:08: "I want to hear what the AI says too"): a live call is recorded
 * by the phone service from Obi's first word, both sides on their own channel, kept beside its message like a caller's
 * recording, and stopped before any put-through to his mobile.
 */
class LiveCallRecordingTest {
    private val ops = InMemoryServerOpStore()
    private var nowMs = 1_794_000_000_000L // 2026-11
    private var seq = 0
    private val token = "twilio-auth-token-for-tests"
    private val sid = "AC" + "0123456789abcdef".repeat(2)
    private val call = "CA" + "1".repeat(32)
    private val rec = "RE" + "2".repeat(32)
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 1)
    private var hasStore = true
    private val line = LiveCallLine(FieldReader.scanning(ops), InMemoryLiveCallStore(), configured = { true }, nowMs = { nowMs }, recordsCalls = { hasStore })
    private val store = InMemoryRecordingStore { nowMs }
    private val assistant = CallAssistant(ops, FieldReader.scanning(ops), household = { "hh" }, now = { nowMs }, recordings = store, liveLine = line)

    /** What the server asked Twilio for: "POST url form" and "GET url". */
    private val twilioCalls = mutableListOf<String>()
    private var postStatus = 201
    private val twilio = TwilioVoice(
        authToken = { token }, accountSid = { sid },
        delete = { url, _ -> twilioCalls += "DELETE $url"; 204 },
        get = { url, _ -> twilioCalls += "GET $url"; HttpFetched(200, mp3) },
        post = { url, form, _ -> twilioCalls += "POST $url $form"; HttpFetched(postStatus, "{}".toByteArray()) },
    )

    private fun set(field: String, value: FieldValue) {
        ops.append(Op("w${seq++}", "hh", EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, field, value, Hlc(nowMs + seq, 0, "fold"), emptyList(), "fold"))
    }

    private fun on() { set(WorkFields.CALL_ASSISTANT, FieldValue.Bool(true)); set(WorkFields.CALL_LIVE, FieldValue.Bool(true)) }

    private fun field(id: String, f: String) = FieldReader.scanning(ops).latest("hh", EntityTypes.HELD_MESSAGE, id, f)

    private fun ticket(): LiveCallTicket = line.claim(line.ticket("hh", "twilio", call, "+447700900123", false)!!, call)!!

    /** The live call left a message (as the stream's end writes it). */
    private fun messageLeft(t: LiveCallTicket) {
        val m = LiveCallRules.Message("Sam", "the dentist", "Please call back about Tuesday", urgent = false, scam = false, callback = null)
        val end = LiveCallEnd(live = true, seconds = 60, obiSeconds = 20, message = m, heard = "")
        assistant.liveEnded(t, end, line.record(t, end))
    }

    @Test
    fun obiSaysTheCallIsRecordedOnlyWhenItIs() {
        on()
        val recorded = ticket()
        assertTrue(recorded.recorded)
        assertTrue("\"${ObiCallRules.OPENING} ${CallRecordingRules.RECORDED_LINE}\"" in recorded.instruction, recorded.instruction)
        // Don't keep: nothing is recorded and he doesn't say it is.
        set(WorkFields.RECORDING_DAYS, FieldValue.Int64(0))
        val unkept = ticket()
        assertFalse(unkept.recorded)
        assertFalse(CallRecordingRules.RECORDED_LINE in unkept.instruction)
        assertTrue("\"${ObiCallRules.OPENING}\"" in unkept.instruction)
        // Nowhere to keep it: the same.
        set(WorkFields.RECORDING_DAYS, FieldValue.Int64(7))
        hasStore = false
        assertFalse(ticket().recorded)
    }

    @Test
    fun theRecordingStartsOnTheCallWithBothChannelsAndStopsBeforeAPutThrough() {
        val status = "https://meka.example" + VoiceStep.path(TwilioVoice.ID, VoiceStep.CALL_RECORDED)
        assertTrue(twilio.startCallRecording(call, status))
        val start = twilioCalls.single()
        assertTrue(start.startsWith("POST https://api.twilio.com/2010-04-01/Accounts/$sid/Calls/$call/Recordings.json "), start)
        assertTrue("RecordingChannels=dual" in start && "RecordingTrack=both" in start, start)
        assertTrue("RecordingStatusCallback=$status" in start, start)
        assertTrue(twilio.stopCallRecording(call))
        assertTrue(twilioCalls.last().startsWith("POST https://api.twilio.com/2010-04-01/Accounts/$sid/Calls/$call/Recordings/Twilio.CURRENT.json "), twilioCalls.last())
        assertTrue("Status=stopped" in twilioCalls.last())
        // Already over: nothing to stop is fine.
        postStatus = 404
        assertTrue(twilio.stopCallRecording(call))
        assertFalse(twilio.startCallRecording(call, status))
        // Only a real call id, and only with the account set up.
        assertFalse(twilio.startCallRecording("CA../../Accounts", status))
        assertFalse(TwilioVoice(authToken = { token }).startCallRecording(call, status))
        // The line remembers which calls record until they're stopped.
        line.markRecording(call)
        assertTrue(line.isRecording(call))
        line.stoppedRecording(call)
        assertFalse(line.isRecording(call))
    }

    @Test
    fun thePhoneServicesCallbackIsParsed() {
        assertEquals(
            VoiceEvent.CallRecorded(call, rec, "completed"),
            twilio.parse(VoiceStep.CALL_RECORDED, mapOf("CallSid" to call, "RecordingSid" to rec, "RecordingStatus" to "completed")),
        )
        assertEquals(VoiceReply.Done, assistant.handle("twilio", VoiceEvent.CallRecorded(call, rec, "completed")))
    }

    @Test
    fun aFinishedRecordingIsKeptBesideTheLiveCallsMessageAndPlays() {
        on()
        val t = ticket()
        messageLeft(t)
        val id = CallAssistantRules.heldId("twilio", call)
        assertTrue(assistant.keepCallRecording(twilio, VoiceEvent.CallRecorded(call, rec, "completed")) { error("no wait needed") })
        assertContentEquals(mp3, store.objects[VoiceRecordingRules.key("hh", id)])
        assertEquals(FieldValue.Bool(true), field(id, HeldMessageFields.AUDIO))
        assertContentEquals(mp3, assistant.recording("hh", id))
        // A second callback keeps nothing twice.
        val gets = twilioCalls.count { it.startsWith("GET ") }
        assertTrue(assistant.keepCallRecording(twilio, VoiceEvent.CallRecorded(call, rec, "completed")) { })
        assertEquals(gets, twilioCalls.count { it.startsWith("GET ") })
    }

    @Test
    fun itWaitsForTheMessageWrittenAsTheStreamEnds() {
        on()
        val t = ticket()
        var slept = 0
        val kept = assistant.keepCallRecording(twilio, VoiceEvent.CallRecorded(call, rec, "completed")) {
            slept++
            if (slept == 2) messageLeft(t)
        }
        assertTrue(kept)
        assertEquals(2, slept)
        // A call that never left a message: nothing kept after the wait.
        val other = "CA" + "3".repeat(32)
        var waits = 0
        assertFalse(assistant.keepCallRecording(twilio, VoiceEvent.CallRecorded(other, rec, "completed")) { waits++ })
        assertEquals((CallRecordingRules.WAIT_FOR_MESSAGE_MS / CallRecordingRules.WAIT_STEP_MS).toInt(), waits)
    }

    @Test
    fun nothingIsKeptForAFailedRecordingOrWithDontKeep() {
        on()
        val t = ticket()
        messageLeft(t)
        val id = CallAssistantRules.heldId("twilio", call)
        assertFalse(assistant.keepCallRecording(twilio, VoiceEvent.CallRecorded(call, rec, "absent")) { })
        assertFalse(assistant.keepCallRecording(twilio, VoiceEvent.CallRecorded(call, null, "completed")) { })
        set(WorkFields.RECORDING_DAYS, FieldValue.Int64(0))
        assertFalse(assistant.keepCallRecording(twilio, VoiceEvent.CallRecorded(call, rec, "completed")) { })
        assertTrue(store.objects.isEmpty())
        assertNull(field(id, HeldMessageFields.AUDIO))
    }
}
