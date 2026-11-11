package os.meka.backend

import os.meka.core.domain.AboutYouFields
import os.meka.core.domain.AboutYouRules
import os.meka.core.domain.BookingCallFields
import os.meka.core.domain.BookingCallState
import os.meka.core.domain.CallRecordingRules
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LocalCalendar
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recordings with both voices, slice 2: Obi's booking calls are recorded from his first word (both sides), he says so,
 * the recording is kept beside the call card for the result card's ▶ Play and forgotten with the call's words.
 * Invented data only.
 */
class BookingCallRecordingTest {
    private val today = CivilDate.toEpochDay(2026, 11, 7)
    private var nowMs = LocalCalendar.UTC.toEpochMs(today, 11 * 60)
    private val ops = InMemoryServerOpStore()
    private var seq = 0
    private val card = "0123456789abcdef0123456789abcdef"
    private val call = "CA" + "b".repeat(32)
    private val rec = "RE" + "2".repeat(32)
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 1)
    private val line = LiveCallLine(FieldReader.scanning(ops), InMemoryLiveCallStore(), configured = { true }, nowMs = { nowMs }, recordsCalls = { true })
    private val store = InMemoryRecordingStore { nowMs }
    private val gets = mutableListOf<String>()
    private val twilio = TwilioVoice(
        authToken = { "t" }, accountSid = { "AC" + "0123456789abcdef".repeat(2) },
        get = { url, _ -> gets += url; HttpFetched(200, mp3) },
    )

    private class Dialer : OutboundDialer {
        val twiml = mutableListOf<String>()
        override fun dialConfigured() = true
        override fun dial(to: String, from: String, twiml: String, statusUrl: String, ringSeconds: Int, limitSeconds: Int): String? {
            this.twiml += twiml
            return "CA" + "b".repeat(32)
        }
    }

    private val dialer = Dialer()
    private fun caller(recordings: RecordingStore? = store) = BookingCaller(
        ops, BankSync.scanning(ops), { "hh" }, line, dialer, twiml = { twilio.bookingTwiml("https://meka.example", it) },
        statusUrl = "https://meka.example/v1/voice/twilio/booking-status", now = { nowMs }, recordings = recordings,
    )

    private fun put(type: String, id: String, field: String, value: FieldValue) {
        ops.append(Op("w${seq++}", "hh", type, id, field, value, Hlc(nowMs + seq, 0, "fold"), emptyList(), "fold"))
    }

    private fun f(field: String) = FieldReader.scanning(ops).latest("hh", EntityTypes.BOOKING_CALL, card, field)

    /** About you and one approved card for next Saturday 19:00–20:00 at a landline. */
    private fun approved() {
        put(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, AboutYouFields.SHOWN_NAME, FieldValue.Text("Alex Example"))
        put(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, AboutYouFields.ASSISTANT_NUMBER, FieldValue.Text("020 7946 0000"))
        mapOf(
            BookingCallFields.BUSINESS to FieldValue.Text("Rosa's Kitchen"), BookingCallFields.WHAT to FieldValue.Text("Table for 4"),
            BookingCallFields.NUMBER to FieldValue.Text("+441234567890"), BookingCallFields.DAY to FieldValue.Int64(today + 7),
            BookingCallFields.FROM to FieldValue.Int64(19 * 60L), BookingCallFields.TO to FieldValue.Int64(20 * 60L),
            BookingCallFields.PEOPLE to FieldValue.Int64(4), BookingCallFields.STATE to FieldValue.Text(BookingCallState.APPROVED.code),
            BookingCallFields.ASKED_AT to FieldValue.Int64(nowMs - 60_000), BookingCallFields.APPROVED_AT to FieldValue.Int64(nowMs - 30_000),
        ).forEach { (k, v) -> put(EntityTypes.BOOKING_CALL, card, k, v) }
    }

    /** The ticket the booking call's stream would claim. */
    private fun ticket(): LiveCallTicket {
        val token = Regex("""name="ticket" value="([0-9a-f]{32})"""").find(dialer.twiml.last())!!.groupValues[1]
        return assertNotNull(line.claim(token, call))
    }

    /** The call is over, as its session's end writes it. */
    private fun booked() {
        put(EntityTypes.BOOKING_CALL, card, BookingCallFields.STATE, FieldValue.Text(BookingCallState.BOOKED.code))
        put(EntityTypes.BOOKING_CALL, card, BookingCallFields.ENDED_AT, FieldValue.Int64(nowMs))
    }

    @Test
    fun aBookingCallIsRecordedAndObiSaysSo() {
        approved()
        val c = caller()
        assertTrue(c.tick())
        val t = ticket()
        assertTrue(t.booking && t.recorded)
        assertTrue("\"Hi, this is Obi, Alex's assistant. ${CallRecordingRules.RECORDED_LINE}\"" in t.instruction, t.instruction)
    }

    @Test
    fun withDontKeepOrNowhereToKeepItNothingIsRecorded() {
        approved()
        put(EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.RECORDING_DAYS, FieldValue.Int64(0))
        assertTrue(caller().tick())
        val t = ticket()
        assertFalse(t.recorded)
        assertFalse(CallRecordingRules.RECORDED_LINE in t.instruction)
    }

    @Test
    fun withNoStoreNothingIsRecorded() {
        approved()
        assertTrue(caller(recordings = null).tick())
        assertFalse(ticket().recorded)
    }

    @Test
    fun thePhoneServicesCallbackIsTheBookingCallersStep() {
        val e = twilio.parse(VoiceStep.BOOKING_RECORDED, mapOf("CallSid" to call, "RecordingSid" to rec, "RecordingStatus" to "completed"))
        assertEquals(VoiceEvent.BookingRecorded(call, rec, "completed"), e)
        assertTrue(e!!.forBooking)
        assertEquals(VoiceReply.Done, caller().handle(e))
    }

    @Test
    fun aFinishedRecordingIsKeptBesideTheCardOnceTheCallIsClosedAndPlays() {
        approved()
        val c = caller()
        c.tick()
        var slept = 0
        // The recording is ready just before the stream's end closes the card: it waits for it.
        assertTrue(c.keepRecording(twilio, VoiceEvent.BookingRecorded(call, rec, "completed")) { slept++; if (slept == 2) booked() })
        assertEquals(2, slept)
        assertContentEquals(mp3, store.objects[CallRecordingRules.bookingKey("hh", card)])
        assertEquals(FieldValue.Bool(true), f(BookingCallFields.AUDIO))
        assertContentEquals(mp3, c.recording("hh", card))
        // Another household, or an id that isn't a card: nothing.
        assertNull(c.recording("other", card))
        assertNull(c.recording("hh", "h0123456789abcdef"))
        // A second callback fetches nothing twice.
        val fetched = gets.size
        assertTrue(c.keepRecording(twilio, VoiceEvent.BookingRecorded(call, rec, "completed")) { })
        assertEquals(fetched, gets.size)
        // Don't keep chosen later: no longer played.
        put(EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.RECORDING_DAYS, FieldValue.Int64(0))
        assertNull(c.recording("hh", card))
    }

    @Test
    fun nothingIsKeptForAFailedRecordingACallItDidntRingOrWithDontKeep() {
        approved()
        val c = caller()
        c.tick()
        booked()
        assertFalse(c.keepRecording(twilio, VoiceEvent.BookingRecorded(call, rec, "absent")) { })
        assertFalse(c.keepRecording(twilio, VoiceEvent.BookingRecorded(call, null, "completed")) { })
        assertFalse(c.keepRecording(twilio, VoiceEvent.BookingRecorded("CA" + "9".repeat(32), rec, "completed")) { error("no wait for a call it didn't ring") })
        put(EntityTypes.CONTEXT_MODE, WorkMode.ENTITY_ID, WorkFields.RECORDING_DAYS, FieldValue.Int64(0))
        assertFalse(c.keepRecording(twilio, VoiceEvent.BookingRecorded(call, rec, "completed")) { })
        assertTrue(store.objects.isEmpty())
        assertNull(f(BookingCallFields.AUDIO))
    }

    @Test
    fun afterSevenDaysTheRecordingGoesWithTheWords() {
        approved()
        val c = caller()
        c.tick()
        booked()
        put(EntityTypes.BOOKING_CALL, card, BookingCallFields.TRANSCRIPT, FieldValue.Text("[]"))
        assertTrue(c.keepRecording(twilio, VoiceEvent.BookingRecorded(call, rec, "completed")) { })
        nowMs += 8 * 24 * 60 * 60_000L
        assertNull(c.recording("hh", card))
        c.tick()
        assertEquals(FieldValue.Bool(false), f(BookingCallFields.AUDIO))
        assertTrue(store.objects.isEmpty())
    }
}
