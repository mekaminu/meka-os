package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recordings with both voices, slice 3: the player shows who's speaking along the words. Invented data only. */
class CallSpeakerTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 11, 14)
    private val now = cal.toEpochMs(day - 7, 11 * 60)
    private val id = "0123456789abcdef0123456789abcdef"

    private fun words(): BookingWords = BookingWords().apply {
        add(true, "Hi, this is Obi, Alex's AI assistant. ", 0)
        add(true, "This call is recorded.", 1_200)
        add(false, "Rosa's Kitchen, how can I help?", 3_400)
        add(true, "A table for four on Saturday at seven, please.", 6_000)
        add(false, "Yes, that's fine.", 9_500)
    }

    @Test
    fun eachLineKeepsWhenItStartedInTheRecording() {
        val w = words()
        assertEquals(4, BookingWords.decode(w.all(), "Rosa's Kitchen").size)
        assertEquals("0,3400,6000,9500", w.times())
        // A line without a start (before Obi's side opened) means no times at all, never misaligned ones.
        val unknown = BookingWords().apply { add(true, "Hello", -1); add(false, "Hi", 500) }
        assertEquals("", unknown.times())
    }

    @Test
    fun theTimesAreReadOnlyWhenTheyFitTheLines() {
        assertEquals(listOf(0L, 3_400L, 6_000L), CallRecordingRules.wordTimes("0,3400,6000", 3))
        assertEquals(emptyList<Long>(), CallRecordingRules.wordTimes("0,3400", 3)) // a line short
        assertEquals(emptyList<Long>(), CallRecordingRules.wordTimes("0,6000,3400", 3)) // out of order
        assertEquals(emptyList<Long>(), CallRecordingRules.wordTimes("0,x,6000", 3))
        assertEquals(emptyList<Long>(), CallRecordingRules.wordTimes("-5,10,20", 3))
        assertEquals(emptyList<Long>(), CallRecordingRules.wordTimes(null, 3))
        assertEquals(emptyList<Long>(), CallRecordingRules.wordTimes("", 0))
    }

    @Test
    fun thePlayerShowsWhoIsSpeakingWhereTheRecordingHasGotTo() {
        val w = words()
        val lines = BookingWords.decode(w.all(), "Rosa's Kitchen")
        val at = CallRecordingRules.wordTimes(w.times(), lines.size)
        val first = CallRecordingRules.speaking(lines, at, 1_000)!!
        assertEquals("Obi", first.who)
        assertTrue(first.obi)
        assertEquals(0, first.index)
        val them = CallRecordingRules.speaking(lines, at, 4_000)!!
        assertEquals("Rosa's Kitchen", them.who)
        assertEquals("Rosa's Kitchen, how can I help?", them.text)
        // A line shows a moment before its start; the last line stays to the end.
        assertEquals(2, CallRecordingRules.speaking(lines, at, 6_000 - CallRecordingRules.LEAD_MS)!!.index)
        assertEquals(3, CallRecordingRules.speaking(lines, at, 60_000)!!.index)
        assertEquals("Obi is speaking: A table for four on Saturday at seven, please.",
            CallRecordingRules.speakingSpoken(CallRecordingRules.speaking(lines, at, 7_000)!!))
        // No times, or times that don't fit: no speaker.
        assertNull(CallRecordingRules.speaking(lines, emptyList(), 4_000))
        assertNull(CallRecordingRules.speaking(lines, listOf(0L), 4_000))
        assertNull(CallRecordingRules.speaking(emptyList(), emptyList(), 0))
    }

    @Test
    fun theResultCardCarriesTheTimesBesideItsWords() {
        val w = words()
        val f = mapOf(
            BookingCallFields.BUSINESS to FieldValue.Text("Rosa's Kitchen"),
            BookingCallFields.DAY to FieldValue.Int64(day),
            BookingCallFields.STATE to FieldValue.Text(BookingCallState.BOOKED.code),
            BookingCallFields.ENDED_AT to FieldValue.Int64(now - 60_000),
            BookingCallFields.AUDIO to FieldValue.Bool(true),
            BookingCallFields.TRANSCRIPT to FieldValue.Text(w.all()),
            BookingCallFields.WORD_TIMES to FieldValue.Text(w.times()),
        )
        val row = BookingCallRules.rows(listOf(BookingCallRules.read(id, f)!!), emptyList(), emptyList(), now, cal).single()
        assertTrue(row.canPlay)
        assertEquals(listOf(0L, 3_400L, 6_000L, 9_500L), row.transcriptAtMs)
        assertEquals("Rosa's Kitchen", CallRecordingRules.speakingInRow(row, 4_000)!!.who)
        // A call from before slice 3 (no times) still plays, with no speaker line.
        val old = BookingCallRules.rows(listOf(BookingCallRules.read(id, f - BookingCallFields.WORD_TIMES)!!), emptyList(), emptyList(), now, cal).single()
        assertTrue(old.canPlay)
        assertEquals(emptyList<Long>(), old.transcriptAtMs)
        assertNull(CallRecordingRules.speakingInRow(old, 4_000))
    }
}
