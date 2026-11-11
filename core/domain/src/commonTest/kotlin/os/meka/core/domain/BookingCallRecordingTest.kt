package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recordings with both voices, slice 2: booking calls are recorded, kept beside the card, and played from the result card. Invented data only. */
class BookingCallRecordingTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 11, 14)
    private val now = cal.toEpochMs(day - 7, 11 * 60)
    private val id = "0123456789abcdef0123456789abcdef"
    private val dayMs = 24 * 60 * 60_000L

    private fun fields(state: BookingCallState, audio: Boolean?, endedAt: Long?): Map<String, os.meka.core.sync.FieldValue> = buildMap {
        put(BookingCallFields.BUSINESS, os.meka.core.sync.FieldValue.Text("Rosa's Kitchen"))
        put(BookingCallFields.DAY, os.meka.core.sync.FieldValue.Int64(day))
        put(BookingCallFields.STATE, os.meka.core.sync.FieldValue.Text(state.code))
        put(BookingCallFields.TRANSCRIPT, os.meka.core.sync.FieldValue.Text("[]"))
        endedAt?.let { put(BookingCallFields.ENDED_AT, os.meka.core.sync.FieldValue.Int64(it)) }
        audio?.let { put(BookingCallFields.AUDIO, os.meka.core.sync.FieldValue.Bool(it)) }
    }

    private fun row(state: BookingCallState, audio: Boolean?, endedAt: Long?, at: Long = now): BookingCallRow =
        BookingCallRules.rows(listOf(BookingCallRules.read(id, fields(state, audio, endedAt))!!), emptyList(), emptyList(), at, cal).single()

    @Test
    fun theResultCardOffersPlayWhileTheRecordingIsKept() {
        assertTrue(row(BookingCallState.BOOKED, true, now - 60_000).canPlay)
        assertTrue(row(BookingCallState.NOT_BOOKED, true, now - 60_000).canPlay)
        // Not kept, not over, or past the 7 days: no Play.
        assertFalse(row(BookingCallState.BOOKED, null, now - 60_000).canPlay)
        assertFalse(row(BookingCallState.BOOKED, false, now - 60_000).canPlay)
        assertFalse(row(BookingCallState.CALLING, true, null).canPlay)
        assertFalse(row(BookingCallState.BOOKED, true, now - 7 * dayMs - 1).canPlay)
    }

    @Test
    fun aRecordingIsPlayableOnlyWithinTheChosenKeep() {
        assertTrue(CallRecordingRules.bookingPlayable(true, now - dayMs, now))
        assertFalse(CallRecordingRules.bookingPlayable(true, now - dayMs, now, keepDays = 0))
        assertFalse(CallRecordingRules.bookingPlayable(true, null, now))
        assertFalse(CallRecordingRules.bookingPlayable(false, now - 1, now))
        assertFalse(CallRecordingRules.bookingPlayable(true, now + 60_000, now)) // a clock ahead never plays
    }

    @Test
    fun theRecordingLivesUnderTheHouseholdsRecordingsSoTheSweepCoversIt() {
        val key = CallRecordingRules.bookingKey("hh-1", id)!!
        assertEquals("voice/hh-1/call-$id.mp3", key)
        assertTrue(key.startsWith(VoiceRecordingRules.householdPrefix("hh-1")!!))
        assertNull(CallRecordingRules.bookingKey("hh-1", "h0123456789abcdef")) // a held message's id is not a card
        assertNull(CallRecordingRules.bookingKey("../x", id))
        assertNull(CallRecordingRules.bookingKey("hh-1", "../../etc"))
    }

    @Test
    fun theWordsAndTheRecordingAreForgottenTogether() {
        val c = BookingCallRules.read(id, fields(BookingCallState.BOOKED, true, now - 8 * dayMs))!!.copy(transcript = null)
        assertTrue(BookingLiveRules.shouldForget(c, now))
        assertFalse(BookingLiveRules.shouldForget(c.copy(audio = false), now))
    }

    @Test
    fun obiSaysTheBookingCallIsRecorded() {
        val c = BookingCallRules.read(id, fields(BookingCallState.APPROVED, null, null))!!
        val recorded = BookingDialRules.instruction(c, "Alex", null, now, cal, recorded = true)
        assertTrue("start by saying exactly: \"Hi, this is Obi, Alex's assistant. This call is recorded.\"" in recorded, recorded)
        val plain = BookingDialRules.instruction(c, "Alex", null, now, cal)
        assertFalse(CallRecordingRules.RECORDED_LINE in plain)
        assertTrue("This call is recorded." in BookingDialRules.instruction(c, null, null, now, cal, recorded = true))
    }
}
